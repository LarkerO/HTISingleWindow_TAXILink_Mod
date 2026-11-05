<?php
declare(strict_types=1);

namespace app\process;

use Workerman\Connection\TcpConnection;
use Workerman\Protocols\Http\Request;
use Workerman\Protocols\Http\Response;
use Workerman\Timer;
use Workerman\Worker;

/** Single-process debug bridge. The same class runs in webman or start.php. */
final class TaxilinkGateway
{
    private ?TcpConnection $mod = null;
    private ?Worker $http = null;
    private array $authenticated = [];
    private array $handshakes = [];
    private array $pending = [];
    private array $events = [];
    private int $cursor = 0;
    private int $eventBytes = 0;

    public function __construct(
        private readonly string $serverId,
        private readonly string $modToken,
        private readonly string $debugToken,
        private readonly string $httpListen = 'http://127.0.0.1:8789'
    ) {
        if ($modToken === '' || $debugToken === '' || hash_equals($modToken, $debugToken)) {
            throw new \InvalidArgumentException('Set distinct nonempty TAXILINK_TOKEN and TAXILINK_DEBUG_TOKEN');
        }
    }

    public function onWorkerStart(Worker $worker): void
    {
        $this->http = new Worker($this->httpListen);
        $this->http->onMessage = [$this, 'onHttp'];
        $this->http->onClose = function (TcpConnection $connection): void {
            foreach ($this->pending as $id => $entry) {
                if ($entry['connection'] === $connection) {
                    Timer::del($entry['timer']); unset($this->pending[$id]);
                }
            }
        };
        // Workerman::listen adds an HTTP listener in the existing WebSocket process.
        $this->http->listen();
    }

    public function onConnect(TcpConnection $connection): void
    {
        $connection->maxPackageSize = 1048576;
        $this->handshakes[$connection->id] = Timer::add(5, function () use ($connection): void {
            unset($this->handshakes[$connection->id]);
            if (!isset($this->authenticated[$connection->id])) $connection->close();
        }, [], false);
    }

    public function onWebSocketConnect(TcpConnection $connection, Request $request): void
    {
        if ($request->path() !== '/taxilink' || !\TaxilinkProtocol::authenticate(
            (string) $request->header('authorization'), (string) $request->header('x-taxilink-server'), $this->serverId, $this->modToken
        )) {
            $connection->close(); return;
        }
        $this->authenticated[$connection->id] = true;
        if (isset($this->handshakes[$connection->id])) {
            Timer::del($this->handshakes[$connection->id]); unset($this->handshakes[$connection->id]);
        }
        // An authenticated connection must also subscribe within five seconds.
        $this->handshakes[$connection->id] = Timer::add(5, function () use ($connection): void {
            unset($this->handshakes[$connection->id]);
            if ($this->mod !== $connection) $connection->close();
        }, [], false);
    }

    public function onMessage(TcpConnection $connection, string $text): void
    {
        if (!isset($this->authenticated[$connection->id])) { $connection->close(); return; }
        try {
            $message = (new \TaxilinkProtocol($this->serverId))->decode($text);
            if (($message['type'] ?? '') === 'subscribe') {
                if (($message['topics'] ?? []) !== ['taxilink.commands.' . $this->serverId]) {
                    $connection->close(); return;
                }
                if ($this->mod !== null && $this->mod !== $connection) {
                    $old = $this->mod; $this->mod = null; $this->failPending('MOD_RECONNECTED'); $old->close();
                }
                $this->mod = $connection;
                if (isset($this->handshakes[$connection->id])) {
                    Timer::del($this->handshakes[$connection->id]); unset($this->handshakes[$connection->id]);
                }
                $connection->send(json_encode(['type' => 'subscribed'], JSON_THROW_ON_ERROR));
            } elseif ($this->mod !== $connection) { $connection->close(); return; }
            $this->remember($message);
            $id = $message['requestId'] ?? null;
            if (($message['type'] ?? '') === 'response' && is_string($id) && isset($this->pending[$id])) {
                $entry = $this->pending[$id]; unset($this->pending[$id]); Timer::del($entry['timer']);
                $this->reply($entry['connection'], 200, $message);
            }
        } catch (\JsonException | \UnexpectedValueException $e) { $connection->close(); }
    }

    public function onClose(TcpConnection $connection): void
    {
        unset($this->authenticated[$connection->id]);
        if (isset($this->handshakes[$connection->id])) {
            Timer::del($this->handshakes[$connection->id]); unset($this->handshakes[$connection->id]);
        }
        if ($this->mod === $connection) { $this->mod = null; $this->failPending('MOD_DISCONNECTED'); }
    }

    public function onHttp(TcpConnection $connection, Request $request): void
    {
        if (!hash_equals('Bearer ' . $this->debugToken, (string) $request->header('authorization'))) {
            $this->reply($connection, 401, ['error' => 'UNAUTHORIZED']); return;
        }
        if ($request->method() === 'GET' && $request->path() === '/health') {
            $this->reply($connection, 200, ['serverId' => $this->serverId, 'connected' => $this->mod !== null, 'pending' => count($this->pending)]); return;
        }
        if ($request->method() === 'GET' && $request->path() === '/events') {
            $after = max(0, (int) $request->get('afterCursor', 0));
            $this->reply($connection, 200, ['cursor' => $this->cursor, 'events' => array_values(array_filter($this->events, fn ($e) => $e['cursor'] > $after))]); return;
        }
        if ($request->method() !== 'POST' || $request->path() !== '/command') {
            $this->reply($connection, 404, ['error' => 'NOT_FOUND']); return;
        }
        if ($this->mod === null) { $this->reply($connection, 503, ['error' => 'MOD_OFFLINE']); return; }
        if (count($this->pending) >= 128) { $this->reply($connection, 429, ['error' => 'TOO_MANY_PENDING_REQUESTS']); return; }
        try {
            if (strlen($request->rawBody()) > 65536) throw new \InvalidArgumentException('Request too large');
            $input = json_decode($request->rawBody(), true, 64, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
            if (!is_array($input) || !is_string($input['type'] ?? null) || !is_array($input['params'] ?? [])) throw new \InvalidArgumentException('Expected type and params object');
            $dimension = $input['dimension'] ?? 'minecraft/overworld';
            $id = $input['requestId'] ?? bin2hex(random_bytes(16));
            if (!is_string($dimension) || !is_string($id) || trim($id) === '' || strlen($id) > 256) throw new \InvalidArgumentException('Invalid dimension/requestId');
            if (isset($this->pending[$id])) { $this->reply($connection, 409, ['error' => 'REQUEST_ID_PENDING']); return; }
            $wire = (new \TaxilinkProtocol($this->serverId, $dimension))->command($input['type'], $input['params'] ?? [], $id);
            $timer = Timer::add(15, function () use ($id): void {
                if (!isset($this->pending[$id])) return;
                $entry = $this->pending[$id]; unset($this->pending[$id]);
                $this->reply($entry['connection'], 504, ['error' => 'MOD_RESPONSE_TIMEOUT', 'requestId' => $id]);
            }, [], false);
            $this->pending[$id] = ['connection' => $connection, 'timer' => $timer];
            $connection->pauseRecv();
            if ($this->mod->send($wire) === false) {
                unset($this->pending[$id]); Timer::del($timer);
                $this->reply($connection, 502, ['error' => 'MOD_SEND_FAILED', 'requestId' => $id]);
            }
        } catch (\JsonException | \InvalidArgumentException $e) {
            $this->reply($connection, 400, ['error' => 'INVALID_REQUEST', 'message' => $e->getMessage()]);
        }
    }

    private function remember(array $message): void
    {
        $event = ['cursor' => ++$this->cursor, 'message' => $message];
        $this->events[] = $event; $this->eventBytes += strlen(json_encode($event, JSON_THROW_ON_ERROR));
        while (count($this->events) > 128 || $this->eventBytes > 2097152) {
            $removed = array_shift($this->events); $this->eventBytes -= strlen(json_encode($removed, JSON_THROW_ON_ERROR));
        }
    }
    private function failPending(string $code): void
    {
        $pending = $this->pending; $this->pending = [];
        foreach ($pending as $id => $entry) {
            Timer::del($entry['timer']); $this->reply($entry['connection'], 503, ['error' => $code, 'requestId' => $id]);
        }
    }
    private function reply(TcpConnection $connection, int $status, array $body): void
    {
        $connection->close(new Response($status, ['Content-Type' => 'application/json; charset=utf-8', 'Cache-Control' => 'no-store', 'Connection' => 'close'], json_encode($body, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE)));
    }
}
