package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.mtr.core.data.*;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static cn.bg7qvu.htisw.taxilink.Json.*;

/** All methods, including journal recovery, execute exclusively on the owning MTR simulator. */
public final class RideService {
    static final Set<String> DEPOT_COMMANDS = Set.of("taxi.depots.list", "taxi.depots.add", "taxi.depots.remove", "taxi.depots.reload");
    private final Config config;
    private final Path directory;
    private final Consumer<JsonObject> events;
    private final Map<String, Ride> rides = new LinkedHashMap<>();
    private boolean recovered;
    public RideService(Config config, Path root, String dimension, Consumer<JsonObject> events) {
        this.config = config;
        this.directory = root.resolve(UUID.nameUUIDFromBytes(dimension.getBytes(StandardCharsets.UTF_8)).toString());
        this.events = events;
    }
    public static final class Ride {
        public String orderId, plate, dimension, phase = "PREPARED", error;
        public long depotId, sidingId, routeId, vehicleId, createdAt, deadline;
        public boolean departed;
        public JsonObject params, depotBackup, sidingBackup;
        private transient long pickupSince = -1, pickupDoorsOpenedAt = -1, fullSince = -1, departAt = -1;
        private transient boolean pickupReleased, pickupPassed;
        boolean terminal() { return Set.of("COMPLETED", "DESTROYED", "FAILED", "RECOVERED").contains(phase); }
    }
    private Path file(Ride ride) { return directory.resolve(UUID.nameUUIDFromBytes(ride.orderId.getBytes(StandardCharsets.UTF_8)) + ".json"); }
    private void save(Ride ride) {
        try { Json.write(file(ride), GSON.toJsonTree(ride).getAsJsonObject()); }
        catch (Exception e) { throw new Fault("STORAGE_ERROR", "Cannot persist ride journal: " + e.getClass().getSimpleName()); }
    }
    public void recover(Simulator sim) throws Exception {
        if (recovered) return;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Ride ride = GSON.fromJson(Files.readString(path), Ride.class);
                rides.put(ride.orderId, ride);
            }
            for (Ride ride : rides.values()) {
                if (!ride.terminal()) {
                    // Never duplicate dispatch after a restart; restore dedicated infrastructure.
                    if (!passengers(sim, ride).isEmpty()) throw new IllegalStateException("Occupied ride blocks recovery: " + ride.orderId);
                    restore(sim, ride);
                    ride.phase = "RECOVERED";
                    ride.error = "SERVER_RESTART";
                    save(ride);
                    emit(ride, "ride.recovered");
                }
            }
            // The journal may be newer than MTR's last world autosave. Reconcile terminal
            // orders too, otherwise a crash after completion can resurrect a ghost taxi.
            for (Ride ride : rides.values()) {
                Depot depot = sim.depotIdMap.get(ride.depotId);
                if (depot != null && depot.getRouteIds().contains(ride.routeId)) {
                    Siding siding = sim.sidingIdMap.get(ride.sidingId);
                    boolean[] occupied = {false};
                    if (siding != null) siding.iterateVehicles(v -> v.vehicleExtraData.iterateRidingEntities(e -> occupied[0] = true));
                    if (occupied[0]) throw new IllegalStateException("Stale saved taxi is occupied: " + ride.orderId);
                    restore(sim, ride);
                } else if (sim.routes.removeIf(r -> r.getId() == ride.routeId)) sim.sync();
            }
        }
        recovered = true;
    }
    public JsonObject command(Simulator sim, String type, JsonObject p) {
        if (DEPOT_COMMANDS.contains(type)) return manageDepots(sim, type, p);
        if (MtrCatalog.COMMANDS.contains(type)) return MtrCatalog.query(sim, type, p, config,
            routeId -> rides.values().stream().filter(r -> r.routeId == routeId).map(r -> r.orderId).findFirst().orElse(null));
        if (type.equals("taxi.create")) return create(sim, p);
        if (type.equals("taxi.list")) {
            JsonArray result = new JsonArray();
            rides.values().forEach(r -> result.add(summary(r)));
            return data("rides", result);
        }
        Ride ride = rides.get(string(p, "orderId"));
        if (ride == null) throw new Fault("ORDER_NOT_FOUND", "Unknown orderId");
        return switch (type) {
            case "taxi.status" -> status(sim, ride);
            case "taxi.geometry" -> {
                Vehicle v = vehicle(sim, ride);
                if (v == null || ride.terminal() || v.vehicleExtraData.immutablePath.isEmpty())
                    throw new Fault("VEHICLE_NOT_READY", "Vehicle path is not available");
                yield RoutePreview.geometry(sim, v.vehicleExtraData.immutablePath,
                    ((Number) MtrAccess.read(v, "railProgress")).doubleValue());
            }
            case "taxi.passengers" -> passengerStatus(sim, ride);
            case "taxi.depart" -> {
                Vehicle v = vehicle(sim, ride);
                if (!ride.phase.equals("ACTIVE") || pickupStop(ride, v) == null || ride.pickupReleased)
                    throw new Fault("NOT_AT_PICKUP", "Departure requires a vehicle stopped at the pickup platform");
                scheduleDeparture(ride, v, sim.getCurrentMillis());
                yield data("orderId", ride.orderId, "departureDelaySeconds", config.closedDoorDepartureDelaySeconds);
            }
            case "taxi.destroy" -> {
                if (!ride.terminal()) {
                    if (!passengers(sim, ride).isEmpty()) throw new Fault("VEHICLE_OCCUPIED", "Passengers must disembark before destruction");
                    restore(sim, ride); ride.phase = "DESTROYED"; save(ride); emit(ride, "ride.destroyed");
                }
                yield summary(ride);
            }
            case "taxi.doors" -> {
                String state = string(p, "state");
                if (!Set.of("open", "closed").contains(state)) throw new Fault("INVALID_REQUEST", "state must be open or closed");
                Vehicle vehicle = vehicle(sim, ride);
                if (vehicle == null || ride.terminal()) throw new Fault("VEHICLE_NOT_READY", "Vehicle is not active");
                if (vehicle.isMoving()) throw new Fault("VEHICLE_MOVING", "Door commands require a stopped vehicle");
                if (state.equals("open") && pickupStop(ride, vehicle) != null) {
                    if (ride.pickupReleased) throw new Fault("DEPARTURE_IN_PROGRESS", "Pickup departure has already been released");
                    ride.departAt = ride.fullSince = -1;
                }
                DoorControl.set(vehicle.vehicleExtraData, state.equals("open"));
                MtrAccess.invoke(vehicle.vehicleExtraData, state.equals("open") ? "openDoors" : "closeDoors");
                yield data("orderId", ride.orderId, "doorTarget", state, "control", "held_until_next_command");
            }
            default -> throw new Fault("UNKNOWN_COMMAND", type);
        };
    }
    private PathData pickupStop(Ride ride, Vehicle v) {
        if (v == null || ride.pickupPassed || !v.getIsOnRoute() || v.isMoving()) return null;
        long pickupId = id(ride.params, "pickupPlatformId");
        double progress = ((Number) MtrAccess.read(v, "railProgress")).doubleValue();
        for (PathData path : v.vehicleExtraData.immutablePath) {
            if (path.getSavedRailBaseId() == pickupId && path.getDwellTime() > 0)
                return Math.abs(progress - path.getEndDistance()) < 0.000001 ? path : null;
        }
        return null;
    }

    private void scheduleDeparture(Ride ride, Vehicle v, long now) {
        if (ride.departAt >= 0) return;
        DoorControl.set(v.vehicleExtraData, false);
        MtrAccess.invoke(v.vehicleExtraData, "closeDoors");
        ride.departAt = now + config.closedDoorDepartureDelaySeconds * 1000L;
    }

    private void tickPickup(Simulator sim, Ride ride, Vehicle v) {
        if (ride.pickupPassed) return;
        PathData stop = pickupStop(ride, v);
        if (stop == null) {
            if (ride.pickupSince >= 0 || ride.departAt >= 0) {
                ride.pickupPassed = true;
                DoorControl.remove(v.vehicleExtraData);
            }
            return;
        }
        long now = sim.getCurrentMillis();
        if (ride.pickupSince < 0) ride.pickupSince = now;
        if (ride.pickupDoorsOpenedAt < 0 && v.vehicleExtraData.getDoorMultiplier() > 0) ride.pickupDoorsOpenedAt = now;
        if (ride.departAt < 0) {
            int target = ride.params.has("passengerCount") ? ride.params.get("passengerCount").getAsInt() : 1;
            long onboard = passengers(sim, ride).stream().filter(p -> !p.get("driver").getAsBoolean()).count();
            if (onboard < target) ride.fullSince = -1;
            else if (ride.fullSince < 0) ride.fullSince = now;
            boolean full = ride.fullSince >= 0 && now - ride.fullSince >= config.fullBoardingCloseDelaySeconds * 1000L;
            long opening = ride.pickupDoorsOpenedAt >= 0 ? ride.pickupDoorsOpenedAt : ride.pickupSince + 1_000L;
            boolean timedOut = now - opening >= config.pickupWaitSeconds * 1000L + Vehicle.DOOR_MOVE_TIME;
            if (full || timedOut) scheduleDeparture(ride, v, now);
        }
        if (ride.departAt >= 0 && now >= ride.departAt && !ride.pickupReleased) {
            MtrAccess.releasePickup(v, stop);
            ride.pickupReleased = true;
        }
    }
    private JsonObject manageDepots(Simulator sim, String type, JsonObject params) {
        DepotAllowlist allowlist = config.depotAllowlist;
        if (allowlist == null) throw new Fault("ALLOWLIST_UNAVAILABLE", "Depot allowlist was not loaded");
        try {
            if (type.equals("taxi.depots.reload")) {
                allowlist.reload();
            } else if (!type.equals("taxi.depots.list")) {
                long depotId = id(params, "depotId");
                boolean add = type.equals("taxi.depots.add");
                if (add) {
                    Depot depot = sim.depotIdMap.get(depotId);
                    if (depot == null) throw new Fault("MTR_ID_NOT_FOUND", "Depot does not exist in this dimension");
                    if (depot.savedRails.size() != 1 || depot.savedRails.iterator().next().area != depot) {
                        throw new Fault("DEDICATED_DEPOT_REQUIRED", "Use a dedicated depot with exactly one siding");
                    }
                }
                allowlist.set(sim.dimension, depotId, add);
            }
        } catch (IOException e) {
            TaxiLink.LOG.log(System.Logger.Level.ERROR, "Cannot update TAXILink depot allowlist", e);
            throw new Fault("ALLOWLIST_IO_ERROR", "Check config/taxilink-depots.json and the server log");
        }
        return data("dimension", sim.dimension, "depotIds", allowlist.ids(sim.dimension));
    }
    private JsonObject create(Simulator sim, JsonObject p) {
        String orderId = string(p, "orderId");
        Ride existing = rides.get(orderId);
        if (existing != null) {
            if (!existing.params.equals(p)) throw new Fault("ORDER_CONFLICT", "orderId already used with different parameters");
            return summary(existing);
        }
        long depotId = id(p, "depotId"), sidingId = id(p, "sidingId");
        long pickupId = id(p, "pickupPlatformId"), dropoffId = id(p, "dropoffPlatformId");
        double speed = number(p, "maxSpeedKmh", 1, 160);
        double acceleration = number(p, "accelerationMps2", 0.1, 4);
        double deceleration = number(p, "decelerationMps2", 0.1, 4);
        if (!config.allows(sim.dimension, depotId)) throw new Fault("DEPOT_NOT_ALLOWED", "Add the dedicated depot to the TAXILink allowlist first");
        if (rides.values().stream().filter(r -> !r.terminal()).count() >= config.maxActiveRides) throw new Fault("CAPACITY_EXCEEDED", "Dimension active ride limit reached");
        if (rides.values().stream().anyMatch(r -> !r.terminal() && r.depotId == depotId)) throw new Fault("DEPOT_BUSY", "Depot is assigned to another order");
        Depot depot = sim.depotIdMap.get(depotId);
        Siding siding = sim.sidingIdMap.get(sidingId);
        Platform pickup = sim.platformIdMap.get(pickupId), dropoff = sim.platformIdMap.get(dropoffId);
        if (depot == null || siding == null || pickup == null || dropoff == null) throw new Fault("MTR_ID_NOT_FOUND", "Unknown depot, siding, or platform");
        if (depot.savedRails.size() != 1 || !depot.savedRails.contains(siding) || siding.area != depot) throw new Fault("DEDICATED_DEPOT_REQUIRED", "Use a depot containing exactly the specified one siding");
        if (pickup == dropoff || !depot.isTransportMode(siding) || !depot.isTransportMode(pickup) || !depot.isTransportMode(dropoff) || depot.getTransportMode().continuousMovement) throw new Fault("INVALID_ROUTE", "Platforms must differ and use the same non-continuous transport mode");
        if (pickup.getDwellTime() <= 0 || dropoff.getDwellTime() <= 0) throw new Fault("INVALID_ROUTE", "Pickup and dropoff must have positive dwell times");
        boolean[] busy = {false};
        siding.iterateVehicles(v -> { if (v.getIsOnRoute()) busy[0] = true; v.vehicleExtraData.iterateRidingEntities(e -> busy[0] = true); });
        if (busy[0]) throw new Fault("DEPOT_BUSY", "Siding contains a running or occupied vehicle");
        if (!p.has("vehicleCars") || !p.get("vehicleCars").isJsonArray() || p.getAsJsonArray("vehicleCars").isEmpty() || p.getAsJsonArray("vehicleCars").size() > 16) throw new Fault("INVALID_REQUEST", "vehicleCars requires 1..16 cars");
        ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
        for (JsonElement element : p.getAsJsonArray("vehicleCars")) {
            JsonObject c = element.getAsJsonObject();
            double capacity = number(c, "capacity", 1, 1000);
            if (capacity != Math.rint(capacity)) throw new Fault("INVALID_REQUEST", "capacity must be an integer");
            double length = number(c, "length", 0.5, 100);
            cars.add(new VehicleCar(string(c, "vehicleId"), length, number(c, "width", 0.1, 20), (long) capacity, number(c, "bogie1Position", -length / 2, length / 2), number(c, "bogie2Position", -length / 2, length / 2), number(c, "couplingPadding1", 0, 10), number(c, "couplingPadding2", 0, 10)));
        }
        if (Siding.getTotalVehicleLength(cars) > siding.getRailLength()) throw new Fault("VEHICLE_TOO_LONG", "Cars do not fit the siding");
        int capacity = Math.toIntExact(cars.stream().mapToLong(VehicleCar::getCapacity).sum());
        int passengerCount = MtrCatalog.integer(p, "passengerCount", 1, 1, 16000);
        if (passengerCount > capacity) throw new Fault("INSUFFICIENT_CAPACITY", "Requested passengers exceed total vehicle capacity");
        String customRouteName = p.has("routeName") ? string(p, "routeName") : null;
        Ride ride = new Ride();
        ride.orderId = orderId; ride.dimension = sim.dimension;
        ride.plate = "TX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        ride.depotId = depotId; ride.sidingId = sidingId; ride.params = p.deepCopy();
        ride.createdAt = System.currentTimeMillis(); ride.deadline = ride.createdAt + config.generationTimeoutSeconds * 1000L;
        ride.depotBackup = Utilities.getJsonObjectFromData(depot); ride.sidingBackup = Utilities.getJsonObjectFromData(siding);
        Route route = new Route(depot.getTransportMode(), sim);
        route.setName(customRouteName == null ? "TAXILink " + ride.plate : customRouteName); route.setRouteNumber(ride.plate); route.setHidden(true);
        route.getRoutePlatforms().add(new RoutePlatformData(pickupId)); route.getRoutePlatforms().add(new RoutePlatformData(dropoffId));
        ride.routeId = route.getId();
        save(ride); // Write-ahead journal precedes the first MTR mutation.
        rides.put(orderId, ride);
        try {
            siding.clearVehicles(); siding.setIsManual(false); siding.setMaxVehicles(1);
            siding.setVehicleCars(cars);
            if (siding.getVehicleCars().size() != cars.size()) throw new Fault("VEHICLE_TOO_LONG", "MTR rejected part of the consist");
            siding.setMaxManualSpeed(speed / 3600D);
            siding.setAcceleration(acceleration / 1_000_000D); siding.setDeceleration(deceleration / 1_000_000D);
            siding.setDelayedVehicleSpeedIncreasePercentage(0);
            siding.setDelayedVehicleReduceDwellTimePercentage(0);
            depot.setRepeatInfinitely(false); depot.setUseRealTime(true);
            depot.getRealTimeDepartures().clear(); for (int hour = 0; hour < 24; hour++) depot.setFrequency(hour, 0);
            depot.getRouteIds().clear(); depot.getRouteIds().add(route.getId()); sim.routes.add(route); sim.sync();
            ride.phase = "GENERATING"; save(ride);
            depot.updateGenerationStatus(sim.getCurrentMillis(), Depot.GeneratedStatus.NONE, 0, 0);
            Depot.generateDepots(sim, new ObjectArrayList<>(List.of(depot)));
            emit(ride, "ride.accepted");
            return summary(ride);
        } catch (Exception e) {
            restore(sim, ride); ride.phase = "FAILED"; ride.error = "CREATE_FAILED"; save(ride); throw e;
        }
    }
    public void tick(Simulator sim, boolean telemetry) {
        for (Ride ride : rides.values()) {
            if (ride.terminal()) continue;
            try {
                Depot depot = sim.depotIdMap.get(ride.depotId);
                Siding siding = sim.sidingIdMap.get(ride.sidingId);
                if (depot == null || siding == null) throw new Fault("INFRASTRUCTURE_REMOVED", "Depot or siding was removed");
                if (ride.phase.equals("GENERATING")) {
                    Depot.GeneratedStatus generation = depot.getLastGeneratedStatus();
                    if (generation != Depot.GeneratedStatus.NONE && generation != Depot.GeneratedStatus.SUCCESSFUL)
                        throw new Fault("ROUTE_GENERATION_FAILED", generation.name());
                    if (generation == Depot.GeneratedStatus.SUCCESSFUL) {
                        siding.clearVehicles();
                        long dwell = (config.pickupWaitSeconds + config.closedDoorDepartureDelaySeconds + 10L) * 1000;
                        MtrAccess.configureRidePaths(siding, ride.params.get("maxSpeedKmh").getAsDouble(), id(ride.params, "pickupPlatformId"), dwell);
                        siding.startGeneratingDepartures();
                        long departure = sim.getCurrentMillis() + 1000;
                        depot.getRealTimeDepartures().add(departure);
                        if (!siding.addDeparture(departure)) throw new Fault("DISPATCH_FAILED", "MTR rejected departure");
                        ride.phase = "DISPATCHING"; save(ride); emit(ride, "ride.route_ready");
                    }
                }
                if (ride.phase.equals("DISPATCHING")) {
                    siding.iterateVehicles(v -> { if (v.getIsOnRoute()) { ride.vehicleId = v.getId(); ride.departed = true; } });
                    if (ride.departed) { ride.phase = "ACTIVE"; save(ride); emit(ride, "ride.dispatched"); }
                }
                if ((ride.phase.equals("GENERATING") || ride.phase.equals("DISPATCHING")) && System.currentTimeMillis() > ride.deadline)
                    throw new Fault("GENERATION_TIMEOUT", "Path generation or dispatch timed out");
                if (ride.phase.equals("ACTIVE")) {
                    Vehicle v = vehicle(sim, ride);
                    if (v == null || !v.getIsOnRoute()) {
                        if (!passengers(sim, ride).isEmpty()) { ride.phase = "AWAITING_DISEMBARK"; save(ride); emit(ride, "ride.awaiting_disembark"); }
                        else complete(sim, ride);
                    } else tickPickup(sim, ride, v);
                }
                if (ride.phase.equals("AWAITING_DISEMBARK") && passengers(sim, ride).isEmpty()) complete(sim, ride);
                if (telemetry && !ride.terminal()) events.accept(data("type", "taxi.telemetry", "data", status(sim, ride)));
            } catch (Exception e) {
                // Never delete an occupied vehicle during an error path.
                if (!passengers(sim, ride).isEmpty()) {
                    if (!"RECOVERY_REQUIRES_DISEMBARK".equals(ride.error)) { ride.error = "RECOVERY_REQUIRES_DISEMBARK"; save(ride); emit(ride, "ride.error"); }
                    continue;
                }
                restore(sim, ride); ride.phase = "FAILED";
                ride.error = e instanceof Fault f ? f.code : "MTR_ERROR";
                save(ride); emit(ride, "ride.failed");
                TaxiLink.LOG.log(System.Logger.Level.ERROR, "Ride failed: " + ride.orderId, e);
            }
        }
    }
    private void complete(Simulator sim, Ride ride) { restore(sim, ride); ride.phase = "COMPLETED"; save(ride); emit(ride, "ride.completed"); }
    private void restore(Simulator sim, Ride ride) {
        Siding siding = sim.sidingIdMap.get(ride.sidingId); Depot depot = sim.depotIdMap.get(ride.depotId);
        if (siding != null) { siding.iterateVehicles(v -> DoorControl.remove(v.vehicleExtraData)); siding.clearVehicles(); siding.updateData(new JsonReader(ride.sidingBackup)); }
        if (depot != null) depot.updateData(new JsonReader(ride.depotBackup));
        sim.routes.removeIf(r -> r.getId() == ride.routeId);
        sim.sync();
        if (depot != null) Depot.generateDepots(sim, new ObjectArrayList<>(List.of(depot)));
    }
    private Vehicle vehicle(Simulator sim, Ride ride) {
        Siding siding = sim.sidingIdMap.get(ride.sidingId);
        return siding == null || ride.vehicleId == 0 ? null : siding.getVehicleById(ride.vehicleId);
    }
    private List<JsonObject> passengers(Simulator sim, Ride ride) {
        List<JsonObject> result = new ArrayList<>();
        // Also includes a parked vehicle while creation/recovery is in progress.
        Siding siding = sim.sidingIdMap.get(ride.sidingId);
        if (siding != null && !ride.terminal()) siding.iterateVehicles(v -> v.vehicleExtraData.iterateRidingEntities(e -> {
            if (e.isOnVehicle()) result.add(data("uuid", e.uuid.toString(), "car", e.getRidingCar(), "driver", e.isDriver()));
        }));
        return result;
    }
    private JsonObject summary(Ride ride) {
        return data("orderId", ride.orderId, "plate", ride.plate, "dimension", ride.dimension, "phase", ride.phase,
            "depotId", Long.toString(ride.depotId), "sidingId", Long.toString(ride.sidingId), "routeId", Long.toString(ride.routeId),
            "routeName", ride.params.has("routeName") ? ride.params.get("routeName").getAsString() : "TAXILink " + ride.plate,
            "routeNumber", ride.plate, "routeHidden", true, "capacity", capacity(ride),
            "requestedPassengerCount", ride.params.has("passengerCount") ? ride.params.get("passengerCount").getAsInt() : 1,
            "vehicleId", ride.vehicleId == 0 ? null : Long.toString(ride.vehicleId), "error", ride.error);
    }
    private int capacity(Ride ride) {
        int result = 0;
        for (JsonElement car : ride.params.getAsJsonArray("vehicleCars")) result += car.getAsJsonObject().get("capacity").getAsInt();
        return result;
    }
    private JsonObject passengerStatus(Simulator sim, Ride ride) {
        List<JsonObject> players = passengers(sim, ride);
        return data("orderId", ride.orderId, "capacity", capacity(ride), "onboardCount", players.size(),
            "remainingCapacity", Math.max(0, capacity(ride) - players.size()), "overCapacity", players.size() > capacity(ride), "passengers", players);
    }
    private JsonObject status(Simulator sim, Ride ride) {
        JsonObject result = summary(ride); Vehicle v = vehicle(sim, ride);
        passengerStatus(sim, ride).entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
        result.addProperty("sampledAt", System.currentTimeMillis());
        result.add("position", JsonNull.INSTANCE); result.add("etaPickupSeconds", JsonNull.INSTANCE); result.add("etaDropoffSeconds", JsonNull.INSTANCE);
        if (v != null && !ride.terminal()) {
            var head = v.getHeadPositionAndTiltAngle();
            if (head != null && head.position() != null) result.add("position", data("x", head.position().x(), "y", head.position().y(), "z", head.position().z()));
            result.addProperty("speedKmh", ((Number) MtrAccess.read(v, "speed")).doubleValue() * 3600D);
            result.addProperty("doorsOpen", v.vehicleExtraData.getDoorMultiplier() > 0);
            result.addProperty("etaModel", "free_flow_distance_and_dwell");
            result.add("etaPickupSeconds", eta(v, id(ride.params, "pickupPlatformId")));
            result.add("etaDropoffSeconds", eta(v, id(ride.params, "dropoffPlatformId")));
        }
        return result;
    }
    private JsonElement eta(Vehicle vehicle, long target) {
        double progress = ((Number) MtrAccess.read(vehicle, "railProgress")).doubleValue();
        double seconds = 0;
        for (PathData path : vehicle.vehicleExtraData.immutablePath) {
            if (path.getEndDistance() < progress) continue;
            double distance = Math.max(0, path.getEndDistance() - Math.max(progress, path.getStartDistance()));
            double speed = path.getSpeedLimitMetersPerMillisecond() * 1000;
            if (speed <= 0 && distance > 0) return JsonNull.INSTANCE;
            seconds += distance / Math.max(speed, 0.001);
            if (path.getSavedRailBaseId() == target) return GSON.toJsonTree((long) Math.ceil(seconds));
            seconds += path.getDwellTime() / 1000D;
        }
        return JsonNull.INSTANCE;
    }
    private void emit(Ride ride, String type) { events.accept(data("type", type, "data", summary(ride))); }
}
