<?php
declare(strict_types=1);

namespace app\process;

final class TaxiFare
{
    public static function rules(): array
    {
        $path = getenv('TAXILINK_FARE_FILE') ?: dirname(__DIR__, 2) . '/config/taxilink-fares.json';
        $json = @file_get_contents($path);
        if ($json === false) throw new \RuntimeException('Cannot read TAXILink fare configuration');
        return json_decode($json, true, 32, JSON_THROW_ON_ERROR);
    }

    public static function estimate(array $route, array $rules, \DateTimeImmutable $at): array
    {
        $at = $at->setTimezone(new \DateTimeZone($rules['timezone']));
        $clock = $at->format('H:i');
        $rate = $rules['default'];
        foreach ($rules['bands'] as $band) {
            if ($clock >= $band['from'] && $clock < $band['until']) {
                $rate = $band;
                break;
            }
        }
        $km = $route['distanceMeters'] * $rules['metersPerBlock'] / 1000;
        $minutes = $route['estimatedLowSpeedSeconds'] / 60;
        $distance = max(0, $km - $rules['includedKilometers']) * $rate['perKilometer'];
        $time = max(0, $minutes - $rules['includedLowSpeedMinutes']) * $rate['perLowSpeedMinute'];
        $surcharge = 0;
        foreach ($rules['distanceSurcharges'] as $tier) {
            $surcharge += max(0, min($km, $tier['untilKilometers'] ?? $km) - $tier['fromKilometers']) * $tier['perKilometer'];
        }
        $components = array_map(static fn ($value) => (int) round($value * 100), [
            'base' => $rate['base'], 'distance' => $distance, 'lowSpeed' => $time, 'longDistance' => $surcharge,
        ]);
        return ['currency' => $rules['currency'], 'amountMinor' => array_sum($components),
            'componentsMinor' => $components, 'quotedAt' => $at->format(DATE_ATOM), 'billableKilometers' => $km];
    }
}
