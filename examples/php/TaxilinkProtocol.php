<?php
declare(strict_types=1);

/** PHP 8.1+. Feed these JSON strings into your authenticated WebSocket connection. */
final class TaxilinkProtocol
{
    public function __construct(
        private readonly string $serverId,
        private readonly string $dimension = 'minecraft/overworld'
    ) {}

    public function command(string $type, array $params = [], ?string $requestId = null): string
    {
        $allowed = ['taxi.create', 'taxi.status', 'taxi.list', 'taxi.passengers', 'taxi.doors', 'taxi.destroy'];
        if (!in_array($type, $allowed, true)) {
            throw new InvalidArgumentException('Unknown TAXILink command');
        }
        foreach (['depotId', 'sidingId', 'pickupPlatformId', 'dropoffPlatformId'] as $key) {
            if (array_key_exists($key, $params) &&
                (!is_string($params[$key]) || !preg_match('/^-?[1-9][0-9]{0,18}$/D', $params[$key]))) {
                throw new InvalidArgumentException($key . ' must be a decimal string');
            }
        }
        return json_encode([
            'v' => 1,
            'type' => $type,
            'serverId' => $this->serverId,
            'requestId' => $requestId ?? bin2hex(random_bytes(16)),
            'dimension' => $this->dimension,
            'params' => (object) $params,
        ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    }

    public function status(string $orderId): string
    {
        return $this->command('taxi.status', ['orderId' => $orderId]);
    }

    public function doors(string $orderId, bool $open): string
    {
        return $this->command('taxi.doors', ['orderId' => $orderId, 'state' => $open ? 'open' : 'closed']);
    }

    public function destroy(string $orderId): string
    {
        return $this->command('taxi.destroy', ['orderId' => $orderId]);
    }

    public function decode(string $text): array
    {
        $message = json_decode($text, true, 64, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
        if (!is_array($message) || ($message['v'] ?? null) !== 1 || ($message['serverId'] ?? null) !== $this->serverId) {
            throw new UnexpectedValueException('Wrong TAXILink protocol or server');
        }
        return $message;
    }

    /** Call during your framework's HTTP upgrade/authentication hook. Use headers, never URL query tokens. */
    public static function authenticate(string $authorization, string $receivedServerId, string $expectedServerId, string $secret): bool
    {
        return $secret !== '' && hash_equals($expectedServerId, $receivedServerId)
            && hash_equals('Bearer ' . $secret, $authorization);
    }
}
