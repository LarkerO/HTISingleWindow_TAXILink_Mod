<?php
declare(strict_types=1);

// Standalone runner for the same handler used by the webman custom process.
chdir(__DIR__);
require __DIR__ . '/vendor/autoload.php';
if (!is_dir(__DIR__ . '/runtime')) mkdir(__DIR__ . '/runtime', 0770, true);
Workerman\Worker::$logFile = __DIR__ . '/runtime/workerman.log';
Workerman\Worker::$pidFile = __DIR__ . '/runtime/workerman.pid';
$entry = (require __DIR__ . '/webman/config/process.taxilink.php')['taxilink'];
$handler = new $entry['handler'](...array_values($entry['constructor']));
$worker = new Workerman\Worker($entry['listen']);
$worker->count = 1;
$worker->name = 'taxilink-debug';
foreach (['onWorkerStart', 'onConnect', 'onWebSocketConnect', 'onMessage', 'onClose'] as $event) {
    $worker->$event = [$handler, $event];
}
Workerman\Worker::runAll();
