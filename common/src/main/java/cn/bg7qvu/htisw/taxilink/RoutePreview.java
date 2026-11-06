package cn.bg7qvu.htisw.taxilink;

import org.mtr.core.data.*;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static cn.bg7qvu.htisw.taxilink.Json.*;

final class RoutePreview {
    private RoutePreview() {}

    record QuoteJob(List<BooleanSupplier> steps, Supplier<JsonObject> result) {
        JsonObject tick() {
            return steps.stream().allMatch(BooleanSupplier::getAsBoolean) ? result.get() : null;
        }
    }

    static QuoteJob quote(Simulator sim, Config config, JsonObject p) {
        long depotId = id(p, "depotId");
        Depot depot = sim.depotIdMap.get(depotId);
        Siding siding = sim.sidingIdMap.get(id(p, "sidingId"));
        Platform pickup = sim.platformIdMap.get(id(p, "pickupPlatformId"));
        Platform dropoff = sim.platformIdMap.get(id(p, "dropoffPlatformId"));
        double cap = number(p, "maxSpeedKmh", 1, 160);
        double acceleration = number(p, "accelerationMps2", 0.1, 4);
        double deceleration = number(p, "decelerationMps2", 0.1, 4);
        double lowSpeed = number(p, "lowSpeedKmh", 0, 160);
        if (!config.allows(sim.dimension, depotId)) throw new Fault("DEPOT_NOT_ALLOWED", "Select an authorized depot");
        if (depot == null || siding == null || pickup == null || dropoff == null)
            throw new Fault("MTR_ID_NOT_FOUND", "Unknown depot, siding or platform");
        if (siding.area != depot || depot.savedRails.size() != 1 || pickup == dropoff || !pickup.isTransportMode(siding)
            || !dropoff.isTransportMode(siding) || pickup.getDwellTime() <= 0 || dropoff.getDwellTime() <= 0
            || siding.getTransportMode().continuousMovement)
            throw new Fault("INVALID_ROUTE", "Invalid depot or platforms");
        long altitude = depot.getCruisingAltitude();
        var outbound = new ObjectArrayList<PathData>();
        var trip = new ObjectArrayList<PathData>();
        var inbound = new ObjectArrayList<PathData>();
        return new QuoteJob(List.of(leg(sim, siding, pickup, -1, altitude, outbound),
            leg(sim, pickup, dropoff, 0, altitude, trip), leg(sim, dropoff, siding, 2, altitude, inbound)),
            () -> estimate(sim, siding, outbound, trip, cap, acceleration, deceleration, lowSpeed));
    }

    private static JsonObject estimate(Simulator sim, Siding siding, ObjectArrayList<PathData> outbound,
                                       ObjectArrayList<PathData> trip, double cap, double acceleration,
                                       double deceleration, double lowSpeed) {
        // Use the same overlap rule as MTR's depot path generation.
        if (SidingPathFinder.overlappingPaths(outbound, trip)) trip.removeFirst();
        SidingPathFinder.generatePathDataDistances(trip, 0);
        PathData.writePathCache(trip, sim, siding.getTransportMode());
        double distance = trip.isEmpty() ? 0 : trip.getLast().getEndDistance();
        if (distance <= 0) throw new Fault("ROUTE_GENERATION_FAILED", "Empty passenger route");
        double seconds = 0;
        double lowSpeedSeconds = 0;
        for (PathData path : trip) {
            double speed = Math.min(cap, path.getSpeedLimitKilometersPerHour()) / 3.6;
            double length = path.getEndDistance() - path.getStartDistance();
            seconds += length / speed;
            if (speed * 3.6 < lowSpeed) lowSpeedSeconds += length / speed;
        }
        // Approximate the start and stop from rest; signals and boarding are outside the estimate.
        double peak = Math.min(cap / 3.6, Math.sqrt(2 * distance / (1 / acceleration + 1 / deceleration)));
        seconds += peak / (2 * acceleration) + peak / (2 * deceleration);
        lowSpeedSeconds += Math.min(peak, lowSpeed / 3.6) * (1 / acceleration + 1 / deceleration);
        return data("dimension", sim.dimension, "distanceMeters", distance, "estimatedSeconds", (long) Math.ceil(seconds),
            "estimatedLowSpeedSeconds", (long) Math.ceil(Math.min(seconds, lowSpeedSeconds)),
            "estimateModel", "rail_limits_and_terminal_acceleration", "geometry", geometry(sim, trip, 0));
    }

    private static <A extends AreaBase<A,B>, B extends SavedRailBase<B,A>, C extends AreaBase<C,D>, D extends SavedRailBase<D,C>>
    BooleanSupplier leg(Simulator sim, B start, D end, int stop, long altitude, ObjectArrayList<PathData> path) {
        var jobs = new ObjectArrayList<SidingPathFinder<A,B,C,D>>();
        jobs.add(new SidingPathFinder<>(sim, start, end, stop));
        return () -> {
            SidingPathFinder.findPathTick(path, jobs, altitude, () -> {}, (a,b) -> {});
            if (!jobs.isEmpty()) return false;
            if (path.isEmpty()) throw new Fault("ROUTE_GENERATION_FAILED", "No path between " + start.getId() + " and " + end.getId());
            return true;
        };
    }

    static JsonObject geometry(Simulator sim, List<PathData> path, double progress) {
        double total = path.getLast().getEndDistance();
        int samples = Math.min(600, Math.max(1, (int) Math.ceil(total / 3)));
        JsonArray points = new JsonArray();
        int index = 0;
        for (int i = 0; i <= samples; i++) {
            double distance = total * i / samples;
            while (index + 1 < path.size() && distance > path.get(index).getEndDistance()) index++;
            PathData segment = path.get(index);
            var point = segment.getPositionAndTiltAngle(sim, distance - segment.getStartDistance()).position();
            JsonArray row = new JsonArray();
            row.add(round(distance)); row.add(round(point.x())); row.add(round(point.y())); row.add(round(point.z()));
            var radii = segment.getRail().railMath.getHorizontalRadii();
            row.add(radius(radii.leftDouble())); row.add(radius(radii.rightDouble()));
            points.add(row);
        }
        return data("progressMeters", Math.max(0, Math.min(progress, total)), "totalMeters", total, "points", points);
    }

    private static Number radius(double radius) { return Double.isFinite(radius) ? round(radius) : null; }
    private static double round(double number) { return Math.round(number * 100.0) / 100.0; }
}
