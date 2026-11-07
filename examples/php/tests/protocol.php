<?php
declare(strict_types=1);
require __DIR__ . '/../TaxilinkProtocol.php';

function check(bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
}
$protocol = new TaxilinkProtocol('test-server');
foreach (['mtr.dimensions', 'mtr.depots', 'mtr.sidings', 'mtr.routes', 'mtr.stations', 'mtr.platforms',
    'taxi.centers.list', 'taxi.centers.put', 'taxi.centers.remove', 'taxi.centers.reload', 'taxi.logs', 'taxi.control',
    'taxi.depots.list', 'taxi.depots.add', 'taxi.depots.remove', 'taxi.depots.reload'] as $type) {
    $message = json_decode($protocol->command($type), true, 64, JSON_THROW_ON_ERROR);
    check($message['type'] === $type && $message['v'] === 1, 'Command encoding');
}
$wire = $protocol->command('taxi.create', ['depotId' => '-9223372036854775808', 'passengerCount' => 3, 'routeName' => '三人同行']);
check(str_contains($wire, '三人同行'), 'Unicode name preservation');
check(str_contains($wire, '"-9223372036854775808"'), 'Signed ID string preservation');
try { $protocol->command('mtr.platforms', ['stationId' => 123]); throw new RuntimeException('Numeric ID was accepted'); }
catch (InvalidArgumentException $expected) {}
check(TaxilinkProtocol::authenticate('Bearer test-token', 's', 's', 'test-token'), 'Valid auth');
check(!TaxilinkProtocol::authenticate('Bearer bad', 's', 's', 'test-token'), 'Invalid auth');
foreach ([true => 'open', false => 'closed'] as $open => $state) {
    $doors = json_decode($protocol->doors('order-test', (bool) $open), true, 64, JSON_THROW_ON_ERROR);
    check($doors['type'] === 'taxi.doors' && $doors['params']['state'] === $state, 'Door helper arguments');
}
echo "PHP protocol checks passed\n";
