<?php
declare(strict_types=1);

require __DIR__ . '/../webman/app/process/TaxiFare.php';

use app\process\TaxiFare;

$rules = TaxiFare::rules();
$route = ['distanceMeters' => 16000, 'estimatedLowSpeedSeconds' => 480];
$fare = TaxiFare::estimate($route, $rules, new DateTimeImmutable('2026-11-07T12:00:00+08:00'));
$expected = ['base' => 700, 'distance' => 2160, 'lowSpeed' => 35, 'longDistance' => 920];
if ($fare['componentsMinor'] !== $expected || $fare['amountMinor'] !== array_sum($expected)) {
    throw new RuntimeException('Noon fare or distance tier calculation failed');
}
$short = ['distanceMeters' => 2500, 'estimatedLowSpeedSeconds' => 420];
foreach (['04:59:59' => 700, '05:00:00' => 600, '14:00:00' => 600, '23:00:00' => 700] as $clock => $base) {
    if (TaxiFare::estimate($short, $rules, new DateTimeImmutable("2026-11-07T$clock+08:00"))['amountMinor'] !== $base)
        throw new RuntimeException("Incorrect fare at $clock");
}
echo "Fare boundaries and distance surcharges passed\n";
