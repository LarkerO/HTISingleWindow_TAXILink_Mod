<?php
declare(strict_types=1);

// In an existing webman project, copy the protocol helper to its project root.
if (!class_exists(\TaxilinkProtocol::class) && function_exists('base_path')) {
    require_once base_path('TaxilinkProtocol.php');
}

return [
    'taxilink' => [
        'handler' => app\process\TaxilinkGateway::class,
        'listen' => getenv('TAXILINK_WS_LISTEN') ?: 'websocket://127.0.0.1:8788',
        // HTTP and WebSocket connections share this one process. Do not increase count.
        'count' => 1,
        'constructor' => [
            'serverId' => getenv('TAXILINK_SERVER_ID') ?: 'taxilink-01',
            'modToken' => getenv('TAXILINK_TOKEN') ?: '',
            'debugToken' => getenv('TAXILINK_DEBUG_TOKEN') ?: '',
            'httpListen' => getenv('TAXILINK_HTTP_LISTEN') ?: 'http://127.0.0.1:8789',
        ],
    ],
];
