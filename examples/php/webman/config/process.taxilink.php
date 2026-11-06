<?php
declare(strict_types=1);

// In an existing webman project, copy the protocol helper to its project root.
if (!class_exists(\TaxilinkProtocol::class) && function_exists('base_path')) {
    require_once base_path('TaxilinkProtocol.php');
}

$tokenDirectory = function_exists('base_path') ? base_path() : dirname(__DIR__, 2);
$readToken = static function (string $variable, string $file) use ($tokenDirectory): string {
    $value = getenv($variable);
    if ($value !== false && trim($value) !== '') {
        return $value;
    }
    $path = $tokenDirectory . DIRECTORY_SEPARATOR . $file;
    $saved = is_file($path) ? @file_get_contents($path) : false;
    if ($saved === false || trim($saved) === '') {
        throw new \RuntimeException("Missing TAXILink token. Run init.bat or fill $path.");
    }
    return trim($saved);
};

$legacy = getenv('TAXILINK_ALLOW_LEGACY_TRANSPORT') === '1';
$sm2 = $legacy ? null : new app\process\Sm2Wire(
    getenv('TAXILINK_OPENSSL') ?: 'openssl',
    getenv('TAXILINK_GATEWAY_PRIVATE_KEY') ?: $tokenDirectory . '/taxilink-gateway-private.pem',
    getenv('TAXILINK_MOD_PUBLIC_KEY') ?: $tokenDirectory . '/taxilink-mod-public.pem'
);
$businessKey = getenv('TAXILINK_BUSINESS_PUBLIC_KEY');
$business = !$businessKey ? null : new app\process\Sm2Wire(
    getenv('TAXILINK_OPENSSL') ?: 'openssl',
    getenv('TAXILINK_GATEWAY_PRIVATE_KEY') ?: $tokenDirectory . '/taxilink-gateway-private.pem',
    $businessKey
);

return [
    'taxilink' => [
        'handler' => app\process\TaxilinkGateway::class,
        'listen' => getenv('TAXILINK_WS_LISTEN') ?: 'websocket://127.0.0.1:8788',
        // HTTP and WebSocket connections share this one process. Do not increase count.
        'count' => 1,
        'constructor' => [
            'serverId' => getenv('TAXILINK_SERVER_ID') ?: 'taxilink-01',
            'modToken' => $legacy ? $readToken('TAXILINK_TOKEN', '.env.taxilink-mod') : '',
            'debugToken' => $readToken('TAXILINK_DEBUG_TOKEN', '.env.taxilink-debug'),
            'httpListen' => getenv('TAXILINK_HTTP_LISTEN') ?: 'http://127.0.0.1:8789',
            'sm2' => $sm2,
            'business' => $business,
        ],
    ],
];
