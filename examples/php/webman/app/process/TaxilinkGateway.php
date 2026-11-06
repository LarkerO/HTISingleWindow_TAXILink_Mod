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
    private array $quotes = [];
    private array $signedHttp = [];
    private array $events = [];
    private int $cursor = 0;
    private int $eventBytes = 0;

    public function __construct(
        private readonly string $serverId,
        private readonly string $modToken,
        private readonly string $debugToken,
        private readonly string $httpListen = 'http://127.0.0.1:8789',
        private readonly ?Sm2Wire $sm2 = null,
        private readonly ?Sm2Wire $business = null
    ) {
        if ($debugToken === '' || ($sm2 === null && ($modToken === '' || hash_equals($modToken, $debugToken)))) {
            throw new \InvalidArgumentException('Set distinct nonempty TAXILINK_TOKEN and TAXILINK_DEBUG_TOKEN');
        }
    }

    public function onWorkerStart(Worker $worker): void
    {
        $this->http = new Worker($this->httpListen);
        $this->http->onMessage = [$this, 'onHttp'];
        $this->http->onClose = function (TcpConnection $connection): void {
            unset($this->signedHttp[$connection->id]);
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
        try {
            $authorized = $this->sm2 === null ? \TaxilinkProtocol::authenticate(
                (string) $request->header('authorization'), (string) $request->header('x-taxilink-server'), $this->serverId, $this->modToken
            ) : $this->serverId === (json_decode($this->sm2->verify('handshake',
                (string) $request->header('x-taxilink-proof')), true, 8, JSON_THROW_ON_ERROR)['serverId'] ?? null);
        } catch (\Throwable $e) { $authorized = false; }
        if ($request->path() !== '/taxilink' || !$authorized) {
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
            $message = (new \TaxilinkProtocol($this->serverId))->decode($this->sm2?->verify('mod', $text) ?? $text);
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
                $ack = json_encode(['type' => 'subscribed'], JSON_THROW_ON_ERROR);
                $connection->send($this->sm2?->sign('gateway', $ack) ?? $ack);
            } elseif ($this->mod !== $connection) { $connection->close(); return; }
            $this->remember($message);
            $id = $message['requestId'] ?? null;
            if (($message['type'] ?? '') === 'response' && is_string($id) && isset($this->pending[$id])) {
                $entry = $this->pending[$id]; unset($this->pending[$id]); Timer::del($entry['timer']);
                if ($entry['fareRules'] !== null && ($message['ok'] ?? false)) {
                    try {
                        $message['data']['fare'] = TaxiFare::estimate($message['data'], $entry['fareRules'], $entry['quotedAt']);
                        $quoteId = bin2hex(random_bytes(16));
                        $this->quotes[$quoteId] = ['fingerprint' => $entry['fingerprint'], 'expires' => time() + 300];
                        if (count($this->quotes) > 512) array_shift($this->quotes);
                        $message['data']['quoteId'] = $quoteId;
                        $message['data']['quoteExpiresAt'] = $this->quotes[$quoteId]['expires'] * 1000;
                    } catch (\Throwable $e) {
                        $this->reply($entry['connection'], 500, ['error' => 'FARE_ESTIMATE_FAILED']); return;
                    }
                }
                $this->reply($entry['connection'], 200, $message);
            }
        } catch (\Throwable $e) { $connection->close(); }
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
        if ($request->method() === 'GET' && $request->path() === '/') {
            $html = @file_get_contents(__DIR__ . '/taxilink-debug.html');
            if ($html === false) {
                $this->reply($connection, 503, ['error' => 'DEBUG_PAGE_MISSING']);
                return;
            }
            $connection->close(new Response(200, [
                'Content-Type' => 'text/html; charset=utf-8',
                'Cache-Control' => 'no-store',
                'Content-Security-Policy' => "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
                'Connection' => 'close',
            ], $html));
            return;
        }
        $signed = $request->method() === 'POST' && $request->path() === '/signed-command';
        $rawBody = $request->rawBody();
        if ($signed) {
            try {
                if ($this->business === null || strlen($rawBody) > 90000) throw new \UnexpectedValueException();
                $rawBody = $this->business->verify('frontend', $rawBody);
                $this->signedHttp[$connection->id] = true;
            } catch (\Throwable $e) {
                $this->reply($connection, 401, ['error' => 'INVALID_BUSINESS_SIGNATURE']); return;
            }
        } elseif (!hash_equals('Bearer ' . $this->debugToken, (string) $request->header('authorization'))) {
            $this->reply($connection, 401, ['error' => 'UNAUTHORIZED']); return;
        }
        if ($request->method() === 'GET' && $request->path() === '/health') {
            $this->reply($connection, 200, ['serverId' => $this->serverId, 'connected' => $this->mod !== null, 'pending' => count($this->pending)]); return;
        }
        if ($request->method() === 'GET' && $request->path() === '/events') {
            $after = max(0, (int) $request->get('afterCursor', 0));
            $this->reply($connection, 200, ['cursor' => $this->cursor, 'events' => array_values(array_filter($this->events, fn ($e) => $e['cursor'] > $after))]); return;
        }
        if (!$signed && ($request->method() !== 'POST' || $request->path() !== '/command')) {
            $this->reply($connection, 404, ['error' => 'NOT_FOUND']); return;
        }
        if ($this->mod === null) { $this->reply($connection, 503, ['error' => 'MOD_OFFLINE']); return; }
        if (count($this->pending) >= 128) { $this->reply($connection, 429, ['error' => 'TOO_MANY_PENDING_REQUESTS']); return; }
        try {
            if (strlen($rawBody) > 65536) throw new \InvalidArgumentException('Request too large');
            $input = json_decode($rawBody, true, 64, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
            if (!is_array($input) || !is_string($input['type'] ?? null) || !is_array($input['params'] ?? [])) throw new \InvalidArgumentException('Expected type and params object');
            $dimension = $input['dimension'] ?? 'minecraft/overworld';
            $id = $input['requestId'] ?? bin2hex(random_bytes(16));
            if (!is_string($dimension) || !is_string($id) || trim($id) === '' || strlen($id) > 256) throw new \InvalidArgumentException('Invalid dimension/requestId');
            if (isset($this->pending[$id])) { $this->reply($connection, 409, ['error' => 'REQUEST_ID_PENDING']); return; }
            $params = $input['params'] ?? [];
            if ($signed && !in_array($input['type'], ['taxi.quote', 'taxi.create', 'taxi.status', 'taxi.geometry'], true)) {
                $this->reply($connection, 403, ['error' => 'BUSINESS_COMMAND_NOT_ALLOWED']); return;
            }
            $quoteId = $params['quoteId'] ?? null;
            unset($params['quoteId']);
            $fingerprint = self::fingerprint($dimension, $params);
            if ($input['type'] === 'taxi.create' && ($signed || $this->sm2 !== null)) {
                $quote = is_string($quoteId) ? ($this->quotes[$quoteId] ?? null) : null;
                if ($quote === null || $quote['expires'] <= time() || !hash_equals($quote['fingerprint'], $fingerprint)) {
                    $this->reply($connection, 409, ['error' => 'QUOTE_REQUIRED']); return;
                }
            }
            $fareRules = null;
            $quotedAt = null;
            if ($input['type'] === 'taxi.quote') {
                try {
                    $fareRules = TaxiFare::rules();
                    $quotedAt = new \DateTimeImmutable();
                    $params['lowSpeedKmh'] = $fareRules['lowSpeedKmh'];
                } catch (\Throwable $e) {
                    $this->reply($connection, 500, ['error' => 'FARE_CONFIG_ERROR']); return;
                }
            }
            $wire = (new \TaxilinkProtocol($this->serverId, $dimension))->command($input['type'], $params, $id);
            try { $wire = $this->sm2?->sign('gateway', $wire) ?? $wire; }
            catch (\Throwable $e) { $this->reply($connection, 500, ['error' => 'SM2_SIGN_FAILED']); return; }
            $timer = Timer::add(15, function () use ($id): void {
                if (!isset($this->pending[$id])) return;
                $entry = $this->pending[$id]; unset($this->pending[$id]);
                $this->reply($entry['connection'], 504, ['error' => 'MOD_RESPONSE_TIMEOUT', 'requestId' => $id]);
            }, [], false);
            $this->pending[$id] = ['connection' => $connection, 'timer' => $timer, 'fareRules' => $fareRules, 'quotedAt' => $quotedAt, 'fingerprint' => $fingerprint];
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
        $json = json_encode($body, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
        if (isset($this->signedHttp[$connection->id])) {
            try { $json = $this->business->sign('backend', $json); }
            catch (\Throwable $e) { $status = 500; $json = '{"error":"SM2_SIGN_FAILED"}'; }
        }
        $connection->close(new Response($status, ['Content-Type' => 'application/json; charset=utf-8', 'Cache-Control' => 'no-store', 'Connection' => 'close'], $json));
    }

    private static function fingerprint(string $dimension, array $params): string
    {
        $normalize = static function (array $value) use (&$normalize): array {
            if (!array_is_list($value)) ksort($value, SORT_STRING);
            foreach ($value as &$item) if (is_array($item)) $item = $normalize($item);
            return $value;
        };
        return hash('sha256', json_encode([$dimension, $normalize($params)], JSON_THROW_ON_ERROR));
    }
}
