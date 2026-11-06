<?php
declare(strict_types=1);

// Standalone runner for the same handler used by the webman custom process.
chdir(__DIR__);
$autoload = __DIR__ . '/vendor/autoload.php';
if (!is_file($autoload)) {
    fwrite(STDERR, "TAXILink PHP dependencies are missing. Run composer install in " . __DIR__ . " and try again.\n");
    exit(1);
}
require $autoload;
if (!is_dir(__DIR__ . '/runtime')) mkdir(__DIR__ . '/runtime', 0770, true);
Workerman\Worker::$logFile = __DIR__ . '/runtime/workerman.log';
Workerman\Worker::$pidFile = __DIR__ . '/runtime/workerman.pid';
try {
    $entry = (require __DIR__ . '/webman/config/process.taxilink.php')['taxilink'];
    $handler = new $entry['handler'](...array_values($entry['constructor']));
} catch (RuntimeException | InvalidArgumentException $e) {
    fwrite(STDERR, $e->getMessage() . PHP_EOL);
    exit(1);
}
$worker = new Workerman\Worker($entry['listen']);
$worker->count = 1;
$worker->name = 'taxilink-debug';
foreach (['onWorkerStart', 'onConnect', 'onWebSocketConnect', 'onMessage', 'onClose'] as $event) {
    $worker->$event = [$handler, $event];
}
Workerman\Worker::runAll();
