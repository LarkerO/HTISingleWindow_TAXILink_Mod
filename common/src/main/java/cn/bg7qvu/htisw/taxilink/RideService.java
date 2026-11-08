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
    private final VehicleCenters centers;
    public RideService(Config config, Path root, String dimension, Consumer<JsonObject> events) {
        this.config = config;
        this.centers = new VehicleCenters(root);
        this.directory = root.resolve(UUID.nameUUIDFromBytes(dimension.getBytes(StandardCharsets.UTF_8)).toString());
        this.events = events;
    }
    public static final class Ride {
        public String orderId, plate, dimension, phase = "PREPARED", error;
        public long depotId, sidingId, routeId, vehicleId, createdAt, deadline;
        public boolean departed;
        public JsonObject params, clientParams, depotBackup, sidingBackup, geometry;
        public String centerId, stage = "PREPARING", controlMode = "automatic";
        public long queuedUntil, queueTicket;
        public double commandedSpeedKmh;
        public List<JsonObject> history = new ArrayList<>(), passengerHistory = new ArrayList<>();
        private transient long pickupSince = -1, pickupDoorsOpenedAt = -1, fullSince = -1, departAt = -1;
        private transient boolean pickupReleased, pickupPassed;
        private transient int observedDoors = -1;
        boolean terminal() { return Set.of("COMPLETED", "DESTROYED", "FAILED", "RECOVERED", "CANCELLED").contains(phase); }
    }
    private Path file(Ride ride) { return directory.resolve(UUID.nameUUIDFromBytes(ride.orderId.getBytes(StandardCharsets.UTF_8)) + ".json"); }
    private void save(Ride ride) {
        try {
            for (int i = 0; i < ride.history.size(); i++) ride.history.get(i).addProperty("sequence", i + 1);
            Json.write(file(ride), GSON.toJsonTree(ride).getAsJsonObject());
        }
        catch (Exception e) { throw new Fault("STORAGE_ERROR", "Cannot persist ride journal: " + e.getClass().getSimpleName()); }
    }
    public void recover(Simulator sim) throws Exception {
        if (recovered) return;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Ride ride = GSON.fromJson(Files.readString(path), Ride.class);
                if (ride.history == null) ride.history = new ArrayList<>();
                if (ride.passengerHistory == null) ride.passengerHistory = new ArrayList<>();
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
        if (type.startsWith("taxi.centers.")) return manageCenters(sim, type, p);
        if (DEPOT_COMMANDS.contains(type)) return manageDepots(sim, type, p);
        if (MtrCatalog.COMMANDS.contains(type)) return MtrCatalog.query(sim, type, p, config,
            routeId -> rides.values().stream().filter(r -> r.routeId == routeId).map(r -> r.orderId).findFirst().orElse(null));
        if (type.equals("taxi.create")) return create(sim, p);
        if (type.equals("taxi.list")) {
            JsonArray result = new JsonArray();
            List<Ride> ordered = rides.values().stream().sorted(Comparator.comparingLong((Ride r) -> r.createdAt).thenComparing(r -> r.orderId)).toList();
            int offset = MtrCatalog.integer(p, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = MtrCatalog.integer(p, "limit", 100, 1, 100);
            int next = offset, bytes = 0;
            while (next < ordered.size() && result.size() < limit) {
                JsonObject item = summary(ordered.get(next));
                int size = item.toString().getBytes(StandardCharsets.UTF_8).length;
                if (!result.isEmpty() && bytes + size > 40 * 1024) break;
                result.add(item); bytes += size; next++;
            }
            return data("rides", result, "total", ordered.size(), "nextOffset", next < ordered.size() ? next : null);
        }
        Ride ride = rides.get(string(p, "orderId"));
        if (ride == null) throw new Fault("ORDER_NOT_FOUND", "Unknown orderId");
        return switch (type) {
            case "taxi.status" -> status(sim, ride);
            case "taxi.geometry" -> {
                Vehicle v = vehicle(sim, ride);
                if (ride.geometry != null && (v == null || ride.terminal())) yield ride.geometry.deepCopy();
                if (v == null || ride.terminal() || v.vehicleExtraData.immutablePath.isEmpty())
                    throw new Fault("VEHICLE_NOT_READY", "Vehicle path is not available");
                yield rideGeometry(sim, ride, v);
            }
            case "taxi.passengers" -> {
                observePassengers(sim, ride);
                JsonObject result = passengerStatus(sim, ride, MtrCatalog.integer(p, "passengerOffset", 0, 0, Integer.MAX_VALUE));
                int offset = MtrCatalog.integer(p, "offset", 0, 0, Integer.MAX_VALUE);
                int limit = MtrCatalog.integer(p, "limit", 100, 1, 100);
                int end = Math.min(ride.passengerHistory.size(), offset + Math.min(limit, Integer.MAX_VALUE - offset));
                result.add("passengerHistory", GSON.toJsonTree(offset >= end ? List.of() : ride.passengerHistory.subList(offset, end)));
                result.addProperty("historyTotal", ride.passengerHistory.size());
                if (end < ride.passengerHistory.size()) result.addProperty("nextOffset", end);
                yield result;
            }
            case "taxi.logs" -> {
                int offset = MtrCatalog.integer(p, "offset", 0, 0, Integer.MAX_VALUE);
                int end = Math.min(ride.history.size(), offset + Math.min(100, Integer.MAX_VALUE - offset));
                yield data("orderId", ride.orderId, "total", ride.history.size(), "items", offset >= end ? List.of() : ride.history.subList(offset, end),
                    "nextOffset", end < ride.history.size() ? end : null);
            }
            case "taxi.control" -> control(sim, ride, p);
            case "taxi.depart" -> {
                Vehicle v = vehicle(sim, ride);
                if ("emergency".equals(ride.controlMode)) throw new Fault("EMERGENCY_STOPPED", "Resume before departure");
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
                emit(ride, state.equals("open") ? "ride.doors_open_command" : "ride.doors_closed_command");
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
        emit(ride, "ride.doors_closed_departure_scheduled");
    }

    private void tickPickup(Simulator sim, Ride ride, Vehicle v) {
        if (ride.pickupPassed || "emergency".equals(ride.controlMode)) return;
        PathData stop = pickupStop(ride, v);
        if (stop == null) {
            if (ride.pickupSince >= 0 || ride.departAt >= 0) {
                ride.pickupPassed = true;
                DoorControl.remove(v.vehicleExtraData);
            }
            return;
        }
        long now = sim.getCurrentMillis();
        if (ride.pickupSince < 0) { ride.pickupSince = now; emit(ride, "ride.waiting_pickup"); }
        if (ride.pickupDoorsOpenedAt < 0 && v.vehicleExtraData.getDoorMultiplier() > 0) ride.pickupDoorsOpenedAt = now;
        if (ride.departAt < 0) {
            int target = ride.params.has("passengerCount") ? ride.params.get("passengerCount").getAsInt() : 1;
            long onboard = passengers(sim, ride).stream().filter(p -> !p.get("driver").getAsBoolean()).count();
            if (onboard < target) ride.fullSince = -1;
            else if (ride.fullSince < 0) ride.fullSince = now;
            boolean full = ride.fullSince >= 0 && now - ride.fullSince >= config.fullBoardingCloseDelaySeconds * 1000L;
            long opening = ride.pickupDoorsOpenedAt >= 0 ? ride.pickupDoorsOpenedAt : ride.pickupSince + 1_000L;
            boolean timedOut = now - opening >= config.pickupWaitSeconds * 1000L + Vehicle.DOOR_MOVE_TIME;
            if (full || timedOut) { emit(ride, full ? "ride.full_boarding" : "ride.pickup_wait_expired"); scheduleDeparture(ride, v, now); }
        }
        if (ride.departAt >= 0 && now >= ride.departAt && !ride.pickupReleased) {
            MtrAccess.releasePickup(v, stop);
            ride.pickupReleased = true;
            emit(ride, "ride.pickup_departure_released");
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
            throw new Fault("ALLOWLIST_IO_ERROR", "Check the current world taxilink-depots.json and server log");
        }
        return data("dimension", sim.dimension, "depotIds", allowlist.ids(sim.dimension));
    }
    private JsonObject createDirect(Simulator sim, JsonObject p) { return createDirect(sim, p, null); }
    private JsonObject createDirect(Simulator sim, JsonObject p, Ride queued) {
        String orderId = string(p, "orderId");
        Ride existing = rides.get(orderId);
        if (existing != null) {
            if (!(existing.clientParams == null ? existing.params : existing.clientParams).equals(p)) throw new Fault("ORDER_CONFLICT", "orderId already used with different parameters");
            return summary(existing);
        }
        long depotId = id(p, "depotId"), sidingId = id(p, "sidingId");
        long pickupId = id(p, "pickupPlatformId"), dropoffId = id(p, "dropoffPlatformId");
        double speed = number(p, "maxSpeedKmh", 1, 160);
        double acceleration = number(p, "accelerationMps2", 0.1, 4);
        double deceleration = number(p, "decelerationMps2", 0.1, 4);
        if (!config.allows(sim.dimension, depotId)) throw new Fault("DEPOT_NOT_ALLOWED", "Add the dedicated depot to the TAXILink allowlist first");
        if (rides.values().stream().filter(r -> !r.terminal() && !r.phase.equals("QUEUED")).count() >= config.maxActiveRides) throw new Fault("CAPACITY_EXCEEDED", "Dimension active ride limit reached");
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
        ride.createdAt = queued == null ? System.currentTimeMillis() : queued.createdAt;
        ride.deadline = System.currentTimeMillis() + config.generationTimeoutSeconds * 1000L;
        if (queued != null) {
            ride.clientParams = queued.clientParams; ride.history = new ArrayList<>(queued.history);
            ride.queuedUntil = queued.queuedUntil; ride.queueTicket = queued.queueTicket;
        }
        ride.depotBackup = Utilities.getJsonObjectFromData(depot); ride.sidingBackup = Utilities.getJsonObjectFromData(siding);
        Route route = new Route(depot.getTransportMode(), sim);
        route.setName(customRouteName == null ? "TAXILink " + ride.plate : customRouteName); route.setRouteNumber(ride.plate); route.setHidden(true);
        route.getRoutePlatforms().add(new RoutePlatformData(pickupId)); route.getRoutePlatforms().add(new RoutePlatformData(dropoffId));
        ride.routeId = route.getId();
        ride.centerId = p.has("centerId") ? string(p, "centerId") : null;
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
            VehicleControl.silent(route.getId(), true);
            depot.getRouteIds().clear(); depot.getRouteIds().add(route.getId()); sim.routes.add(route); sim.sync();
            ride.phase = "GENERATING"; save(ride);
            depot.updateGenerationStatus(sim.getCurrentMillis(), Depot.GeneratedStatus.NONE, 0, 0);
            Depot.generateDepots(sim, new ObjectArrayList<>(List.of(depot)));
            emit(ride, "ride.accepted");
            return summary(ride);
        } catch (Exception e) {
            restore(sim, ride); ride.phase = "FAILED"; ride.error = e instanceof Fault f ? f.code : "CREATE_FAILED";
            emit(ride, "ride.failed"); throw e;
        }
    }
    public void tick(Simulator sim, boolean telemetry) {
        for (Ride ride : rides.values()) {
            if (ride.terminal() || ride.phase.equals("QUEUED")) continue;
            try {
                Depot depot = sim.depotIdMap.get(ride.depotId);
                Siding siding = sim.sidingIdMap.get(ride.sidingId);
                if (depot == null || siding == null) throw new Fault("INFRASTRUCTURE_REMOVED", "Depot or siding was removed");
                if (ride.phase.equals("GENERATING")) {
                    Depot.GeneratedStatus generation = depot.getLastGeneratedStatus();
                    if (generation != Depot.GeneratedStatus.NONE && generation != Depot.GeneratedStatus.SUCCESSFUL)
                        throw new Fault("ROUTE_GENERATION_FAILED", generation.name());
                    if (generation == Depot.GeneratedStatus.SUCCESSFUL) {
                        if (!passengers(sim, ride).isEmpty())
                            throw new Fault("VEHICLE_OCCUPIED", "Passengers must disembark before preparing departure");
                        siding.clearVehicles();
                        ride.error = null;
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
                    if (ride.departed) { ride.phase = "ACTIVE"; ride.stage = "TO_PICKUP"; ride.geometry = rideGeometry(sim, ride, vehicle(sim, ride)); save(ride); emit(ride, "ride.dispatched"); }
                }
                if ((ride.phase.equals("GENERATING") || ride.phase.equals("DISPATCHING")) && System.currentTimeMillis() > ride.deadline)
                    throw new Fault("GENERATION_TIMEOUT", "Path generation or dispatch timed out");
                observePassengers(sim, ride);
                if (ride.phase.equals("ACTIVE")) {
                    Vehicle v = vehicle(sim, ride);
                    if (v == null) throw new Fault("VEHICLE_LOST", "Dispatched vehicle disappeared before confirmed return");
                    if (!v.getIsOnRoute()) {
                        if (!passengers(sim, ride).isEmpty()) { ride.phase = "AWAITING_DISEMBARK"; save(ride); emit(ride, "ride.awaiting_disembark"); }
                        else complete(sim, ride);
                    } else {
                        tickPickup(sim, ride, v); updateStage(ride, v);
                        int open = v.vehicleExtraData.getDoorMultiplier() > 0 ? 1 : 0;
                        if (ride.observedDoors != open && (ride.observedDoors >= 0 || open == 1)) emit(ride, open == 1 ? "ride.doors_opened" : "ride.doors_closed");
                        ride.observedDoors = open;
                    }
                }
                observePassengers(sim, ride);
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
        drainQueue(sim);
    }
    private void complete(Simulator sim, Ride ride) {
        if (ride.geometry != null) ride.geometry.add("progressMeters", ride.geometry.get("totalMeters"));
        restore(sim, ride); ride.phase = "COMPLETED"; save(ride); emit(ride, "ride.completed"); }
    private void restore(Simulator sim, Ride ride) {
        if (ride.depotBackup == null || ride.sidingBackup == null) return;
        Vehicle active = vehicle(sim, ride);
        if (active != null && !active.vehicleExtraData.immutablePath.isEmpty() && !ride.phase.equals("COMPLETED")) {
            try {
                if (ride.geometry == null || !ride.geometry.get("progressMeters").equals(ride.geometry.get("totalMeters")))
                    ride.geometry = rideGeometry(sim, ride, active);
            } catch (Exception e) {
                // A corrupt/incomplete path must never prevent safe infrastructure restoration.
                TaxiLink.LOG.log(System.Logger.Level.WARNING, "Cannot cache final geometry: " + ride.orderId, e);
            }
        }
        VehicleControl.silent(ride.routeId, false);
        Siding siding = sim.sidingIdMap.get(ride.sidingId); Depot depot = sim.depotIdMap.get(ride.depotId);
        if (siding != null) { siding.iterateVehicles(v -> { DoorControl.remove(v.vehicleExtraData); VehicleControl.hold(v, false); }); siding.clearVehicles(); siding.updateData(new JsonReader(ride.sidingBackup)); }
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
            if (e.isOnVehicle()) result.add(data("uuid", e.uuid.toString(), "playerName", PlayerNames.get(e.uuid), "car", e.getRidingCar(), "driver", e.isDriver()));
        }));
        return result;
    }
    private JsonObject summary(Ride ride) {
        return data("orderId", ride.orderId, "plate", ride.plate, "dimension", ride.dimension, "phase", ride.phase, "stage", stage(ride), "stageLabel", stageLabel(stage(ride)),
            "centerId", ride.centerId, "queuedUntil", ride.queuedUntil, "queuePosition", queuePosition(ride),
            "controlMode", ride.controlMode, "commandedSpeedKmh", ride.commandedSpeedKmh,
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
        return passengerStatus(sim, ride, 0);
    }
    private JsonObject passengerStatus(Simulator sim, Ride ride, int offset) {
        List<JsonObject> players = passengers(sim, ride);
        for (JsonObject player : players) {
            for (int i = ride.passengerHistory.size() - 1; i >= 0; i--) {
                JsonObject visit = ride.passengerHistory.get(i);
                if (!visit.has("alightedAt") && visit.get("uuid").equals(player.get("uuid"))) {
                    player.add("boardedAt", visit.get("boardedAt")); break;
                }
            }
        }
        int end = Math.min(players.size(), offset + Math.min(100, Integer.MAX_VALUE - offset));
        return data("orderId", ride.orderId, "capacity", capacity(ride), "onboardCount", players.size(),
            "remainingCapacity", Math.max(0, capacity(ride) - players.size()), "overCapacity", players.size() > capacity(ride),
            "passengers", offset >= end ? List.of() : players.subList(offset, end), "nextPassengerOffset", end < players.size() ? end : null);
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
    JsonObject resolveQuote(Simulator sim, JsonObject p) {
        if (!p.has("centerId")) return p;
        JsonObject resolved = centers.resolve(sim, config, p, null);
        resolved.addProperty("centerRevision", centers.revision(string(p, "centerId")));
        return resolved;
    }

    private JsonObject manageCenters(Simulator sim, String type, JsonObject p) {
        if (type.equals("taxi.centers.reload")) {
            if (rides.values().stream().anyMatch(r -> !r.terminal() && r.centerId != null))
                throw new Fault("CENTER_BUSY", "Finish or cancel center orders before reloading center configuration");
            centers.load();
        }
        else if (type.equals("taxi.centers.put") || type.equals("taxi.centers.remove")) {
            String center = string(p, "centerId");
            if (rides.values().stream().anyMatch(r -> !r.terminal() && center.equals(r.centerId)))
                throw new Fault("CENTER_BUSY", "Finish or cancel center orders before editing its configuration");
            if (type.equals("taxi.centers.put")) {
                if (!p.has("center") || !p.get("center").isJsonObject()) throw new Fault("INVALID_REQUEST", "center must be an object");
                centers.put(sim, config, center, p.getAsJsonObject("center"));
            }
            else centers.remove(center);
        }
        return data("dimension", sim.dimension, "centers", centers.list());
    }

    private JsonObject create(Simulator sim, JsonObject p) {
        String order = string(p, "orderId");
        Ride existing = rides.get(order);
        if (existing != null) {
            if (!(existing.clientParams == null ? existing.params : existing.clientParams).equals(p))
                throw new Fault("ORDER_CONFLICT", "orderId already used with different parameters");
            return summary(existing);
        }
        if (!p.has("centerId")) return createDirect(sim, p);
        if (p.has("centerRevision") && !centers.revision(string(p, "centerId")).equals(string(p, "centerRevision")))
            throw new Fault("QUOTE_STALE", "Vehicle center changed; request a new quote");
        JsonObject resolved = centers.resolve(sim, config, p, null);
        // Validate the request before admitting it to a queue. Path generation stays asynchronous.
        number(resolved, "maxSpeedKmh", 1, 160); number(resolved, "accelerationMps2", .1, 4); number(resolved, "decelerationMps2", .1, 4);
        if (!resolved.has("vehicleCars") || !resolved.get("vehicleCars").isJsonArray()
            || resolved.getAsJsonArray("vehicleCars").isEmpty() || resolved.getAsJsonArray("vehicleCars").size() > 16)
            throw new Fault("INVALID_REQUEST", "vehicleCars requires 1..16 cars");
        int totalCapacity = 0;
        for (JsonElement element : resolved.getAsJsonArray("vehicleCars")) {
            JsonObject car = element.getAsJsonObject();
            string(car, "vehicleId"); double capacity = number(car, "capacity", 1, 1000);
            if (capacity != Math.rint(capacity)) throw new Fault("INVALID_REQUEST", "capacity must be an integer");
            totalCapacity += (int) capacity;
            double length = number(car, "length", .5, 100); number(car, "width", .1, 20);
            number(car, "bogie1Position", -length/2, length/2); number(car, "bogie2Position", -length/2, length/2);
            number(car, "couplingPadding1", 0, 10); number(car, "couplingPadding2", 0, 10);
        }
        if (MtrCatalog.integer(p, "passengerCount", 1, 1, 16000) > totalCapacity)
            throw new Fault("INSUFFICIENT_CAPACITY", "Requested passengers exceed vehicle capacity");
        if (rides.values().stream().filter(r -> r.phase.equals("QUEUED")).count() >= config.maxQueuedRides)
            throw new Fault("QUEUE_FULL", "Waiting queue is full");
        Ride ride = new Ride(); ride.orderId = order; ride.dimension = sim.dimension;
        ride.centerId = string(p, "centerId"); ride.params = resolved; ride.clientParams = p.deepCopy();
        ride.phase = "QUEUED"; ride.createdAt = System.currentTimeMillis();
        ride.queuedUntil = ride.createdAt + config.queueTimeoutSeconds * 1000L;
        ride.queueTicket = rides.values().stream().mapToLong(r -> r.queueTicket).max().orElse(0) + 1;
        save(ride); rides.put(order, ride); emit(ride, "ride.queued");
        drainQueue(sim);
        return summary(rides.get(order));
    }

    private void drainQueue(Simulator sim) {
        Set<String> blocked = new HashSet<>();
        List<Ride> waiting = rides.values().stream().filter(r -> r.phase.equals("QUEUED"))
            .sorted(Comparator.comparingLong((Ride r) -> r.queueTicket)).toList();
        for (Ride ride : waiting) {
            if (System.currentTimeMillis() >= ride.queuedUntil) {
                ride.phase = "CANCELLED"; ride.error = "QUEUE_TIMEOUT"; emit(ride, "ride.queue_expired"); continue;
            }
            if (blocked.contains(ride.centerId)) continue;
            if (rides.values().stream().filter(r -> !r.terminal() && !r.phase.equals("QUEUED")).count() >= config.maxActiveRides) continue;
            try {
                JsonObject center = centers.get(ride.centerId);
                Long free = null;
                for (JsonElement element : center.getAsJsonArray("depotIds")) {
                    long depotId = id(data("id", element), "id");
                    Depot depot = sim.depotIdMap.get(depotId);
                    if (!config.allows(sim.dimension, depotId) || depot == null || depot.savedRails.size() != 1) continue;
                    if (rides.values().stream().anyMatch(r -> !r.terminal() && !r.phase.equals("QUEUED") && r.depotId == depotId)) continue;
                    boolean[] busy = {false};
                    Siding siding = depot.savedRails.iterator().next();
                    siding.iterateVehicles(v -> { if (v.getIsOnRoute()) busy[0] = true; v.vehicleExtraData.iterateRidingEntities(e -> busy[0] = true); });
                    if (!busy[0]) { free = depotId; break; }
                }
                if (free == null) { blocked.add(ride.centerId); continue; }
                JsonObject params = centers.resolve(sim, config, ride.clientParams, free);
                rides.remove(ride.orderId);
                try {
                    createDirect(sim, params, ride);
                    Ride dispatched = rides.get(ride.orderId);
                    emit(dispatched, "ride.queue_dispatched");
                } catch (Exception e) {
                    Ride failed = rides.get(ride.orderId);
                    if (failed != null) {
                        failed.clientParams = ride.clientParams; save(failed);
                    } else rides.put(ride.orderId, ride);
                    throw e;
                }
            } catch (Exception e) {
                Ride current = rides.get(ride.orderId);
                current.phase = "FAILED"; current.error = e instanceof Fault f ? f.code : "DISPATCH_FAILED";
                emit(current, "ride.queue_failed");
            }
        }
    }

    private int queuePosition(Ride ride) {
        if (!ride.phase.equals("QUEUED")) return 0;
        return (int) rides.values().stream().filter(r -> r.phase.equals("QUEUED") && Objects.equals(r.centerId, ride.centerId)
            && (r.queueTicket <= ride.queueTicket)).count();
    }

    private JsonObject control(Simulator sim, Ride ride, JsonObject p) {
        Vehicle v = vehicle(sim, ride);
        if (v == null || !ride.phase.equals("ACTIVE")) throw new Fault("VEHICLE_NOT_READY", "Control requires a running order");
        String mode = string(p, "mode");
        switch (mode) {
            case "emergency" -> { VehicleControl.hold(v, true); VehicleControl.block(v); }
            case "speed" -> {
                if ("emergency".equals(ride.controlMode)) throw new Fault("EMERGENCY_STOPPED", "Resume explicitly before changing speed");
                double speed = number(p, "speedKmh", 1, ride.params.get("maxSpeedKmh").getAsDouble());
                MtrAccess.controlSpeed(v, speed); ride.commandedSpeedKmh = speed;
            }
            case "automatic", "resume" -> {
                MtrAccess.controlSpeed(v, ride.params.get("maxSpeedKmh").getAsDouble());
                VehicleControl.hold(v, false); ride.commandedSpeedKmh = ride.params.get("maxSpeedKmh").getAsDouble(); mode = "automatic";
            }
            default -> throw new Fault("INVALID_REQUEST", "mode must be speed, emergency, resume or automatic");
        }
        ride.controlMode = mode; emit(ride, "ride.control_" + mode);
        return status(sim, ride);
    }

    private JsonObject rideGeometry(Simulator sim, Ride ride, Vehicle v) {
        JsonObject geometry = ride.geometry == null ? RoutePreview.geometry(sim, v.vehicleExtraData.immutablePath, 0) : ride.geometry.deepCopy();
        double pickup = stopDistance(v, id(ride.params, "pickupPlatformId"));
        double dropoff = stopDistance(v, id(ride.params, "dropoffPlatformId"));
        geometry.add("legs", GSON.toJsonTree(List.of(
            data("stage", "TO_PICKUP", "label", "前往上车地", "startMeters", 0, "endMeters", pickup),
            data("stage", "ON_TRIP", "label", "行程中", "startMeters", pickup, "endMeters", dropoff),
            data("stage", "RETURNING", "label", "回库中", "startMeters", dropoff, "endMeters", geometry.get("totalMeters")))));
        geometry.addProperty("progressMeters", Math.max(0, Math.min(geometry.get("totalMeters").getAsDouble(), ((Number) MtrAccess.read(v, "railProgress")).doubleValue())));
        return geometry;
    }

    private double stopDistance(Vehicle v, long platform) {
        for (PathData path : v.vehicleExtraData.immutablePath) if (path.getSavedRailBaseId() == platform && path.getDwellTime() > 0) return path.getEndDistance();
        throw new Fault("INVALID_ROUTE", "Platform absent from vehicle path");
    }

    private void updateStage(Ride ride, Vehicle v) {
        double progress = ((Number) MtrAccess.read(v, "railProgress")).doubleValue();
        double pickup = stopDistance(v, id(ride.params, "pickupPlatformId"));
        double dropoff = stopDistance(v, id(ride.params, "dropoffPlatformId"));
        String next = progress < pickup - .001 ? "TO_PICKUP" : progress <= pickup + .001 ? "WAITING_PICKUP"
            : progress < dropoff - .001 ? "ON_TRIP" : progress <= dropoff + .001 ? "WAITING_DROPOFF" : "RETURNING";
        if (!next.equals(ride.stage)) { ride.stage = next; emit(ride, "ride.stage_" + next.toLowerCase(Locale.ROOT)); }
    }
    private String stage(Ride ride) { return ride.phase.equals("ACTIVE") ? ride.stage : ride.phase; }
    private String stageLabel(String stage) {
        return switch (stage) {
            case "QUEUED" -> "候补排队"; case "PREPARED", "PREPARING", "GENERATING" -> "生成路线";
            case "DISPATCHING" -> "等待出库"; case "TO_PICKUP" -> "前往上车地";
            case "WAITING_PICKUP" -> "等待上客"; case "ON_TRIP" -> "行程中";
            case "WAITING_DROPOFF" -> "等待下客"; case "RETURNING" -> "回库中";
            case "AWAITING_DISEMBARK" -> "等待乘客离车"; case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消"; case "DESTROYED" -> "已销毁";
            case "FAILED" -> "失败"; case "RECOVERED" -> "重启后已回收"; default -> stage;
        };
    }

    private void observePassengers(Simulator sim, Ride ride) {
        if (ride.terminal()) return;
        List<JsonObject> current = passengers(sim, ride);
        Map<String, JsonObject> onboard = new HashMap<>();
        for (JsonObject p : current) onboard.put(string(p, "uuid"), p);
        long now = System.currentTimeMillis(); boolean changed = false;
        for (JsonObject visit : ride.passengerHistory) {
            if (visit.has("alightedAt")) continue;
            JsonObject p = onboard.remove(string(visit, "uuid"));
            if (p == null) {
                visit.addProperty("alightedAt", now); log(ride, "passenger.alighted", visit.deepCopy()); changed = true;
            } else {
                if (p.has("playerName") && !p.get("playerName").isJsonNull() && !Objects.equals(visit.get("playerName"), p.get("playerName"))) {
                    visit.add("playerName", p.get("playerName")); changed = true;
                }
                visit.add("car", p.get("car"));
            }
        }
        for (JsonObject p : onboard.values()) {
            JsonObject visit = p.deepCopy(); visit.addProperty("boardedAt", now);
            ride.passengerHistory.add(visit); log(ride, "passenger.boarded", visit.deepCopy()); changed = true;
        }
        if (changed) save(ride);
    }

    private void log(Ride ride, String type, JsonObject detail) {
        ride.history.add(data("sequence", ride.history.size() + 1, "timestamp", System.currentTimeMillis(), "type", type,
            "stage", stage(ride), "detail", detail));
        save(ride);
    }
    private void emit(Ride ride, String type) {
        log(ride, type, data());
        events.accept(data("type", type, "data", summary(ride)));
    }
}
