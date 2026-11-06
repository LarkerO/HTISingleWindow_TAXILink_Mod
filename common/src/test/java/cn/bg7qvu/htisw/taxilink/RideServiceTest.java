package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.file.Path;
import java.util.*;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class RideServiceTest {
    @TempDir Path temp;
    Simulator sim;
    Depot depot;
    Siding siding;
    Platform pickup, dropoff;
    Config config;
    List<JsonObject> events;
    RideService service;
    @BeforeEach void setup() throws Exception {
        sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, temp.resolve("mtr"), false);
        MtrAccess.field(Data.class, "currentMillis").setLong(sim, System.currentTimeMillis());
        rail(0, 10, false, true); rail(10, 30, false, false); rail(30, 40, true, false);
        rail(40, 60, false, false); rail(60, 70, true, false);
        siding = new Siding(pos(0), pos(10), 10, TransportMode.TRAIN, sim);
        pickup = new Platform(pos(30), pos(40), TransportMode.TRAIN, sim);
        dropoff = new Platform(pos(60), pos(70), TransportMode.TRAIN, sim);
        depot = new Depot(TransportMode.TRAIN, sim); depot.setName("Dedicated test depot");
        depot.setCorners(new Position(-1, 63, -1), new Position(11, 65, 1));
        sim.depots.add(depot); sim.sidings.add(siding); sim.platforms.add(pickup); sim.platforms.add(dropoff); sim.sync();
        siding.init();
        config = new Config(); config.allowedDepots.put(sim.dimension, List.of(Long.toString(depot.getId())));
        events = new ArrayList<>(); service = new RideService(config, temp.resolve("journal"), sim.dimension, events::add);
        service.recover(sim);
    }
    Position pos(long x) { return new Position(x, 64, 0); }
    void rail(long from, long to, boolean platform, boolean siding) {
        sim.rails.add(Rail.newRail(pos(from), Angle.E, pos(to), Angle.W, Rail.Shape.QUADRATIC,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new ObjectArrayList<>(), 80, 80, platform, siding, true, false, false, TransportMode.TRAIN));
    }
    JsonObject request() {
        JsonArray cars = new JsonArray();
        cars.add(data("vehicleId", "test_car", "length", 4, "width", 2, "capacity", 4,
            "bogie1Position", -1, "bogie2Position", 1, "couplingPadding1", 0, "couplingPadding2", 0));
        return data("orderId", "test-order", "depotId", Long.toString(depot.getId()), "sidingId", Long.toString(siding.getId()),
            "pickupPlatformId", Long.toString(pickup.getId()), "dropoffPlatformId", Long.toString(dropoff.getId()),
            "maxSpeedKmh", 30, "accelerationMps2", 1, "decelerationMps2", 1, "vehicleCars", cars);
    }
    @Test void acceptsOnceAndRejectsConflictingRetries() {
        JsonObject p = request();
        JsonObject first = service.command(sim, "taxi.create", p);
        assertEquals("GENERATING", first.get("phase").getAsString());
        assertEquals(first, service.command(sim, "taxi.create", p.deepCopy()));
        assertEquals(1, sim.routes.size());
        JsonObject changed = p.deepCopy(); changed.addProperty("maxSpeedKmh", 50);
        assertEquals("ORDER_CONFLICT", assertThrows(Fault.class, () -> service.command(sim, "taxi.create", changed)).code);
        JsonObject other = p.deepCopy(); other.addProperty("orderId", "another");
        assertEquals("DEPOT_BUSY", assertThrows(Fault.class, () -> service.command(sim, "taxi.create", other)).code);
    }
    @Test void destructionRestoresDepotAndSidingAndIsIdempotent() {
        double acceleration = siding.getAcceleration();
        service.command(sim, "taxi.create", request());
        JsonObject p = data("orderId", "test-order");
        assertEquals("DESTROYED", service.command(sim, "taxi.destroy", p).get("phase").getAsString());
        assertTrue(depot.getRouteIds().isEmpty()); assertTrue(sim.routes.isEmpty());
        assertEquals(acceleration, siding.getAcceleration()); assertTrue(siding.getVehicleCars().isEmpty());
        assertEquals("DESTROYED", service.command(sim, "taxi.destroy", p).get("phase").getAsString());
    }
    @Test void restartRollsBackAndDoesNotRedispatchOrder() throws Exception {
        service.command(sim, "taxi.create", request());
        RideService restarted = new RideService(config, temp.resolve("journal"), sim.dimension, events::add);
        restarted.recover(sim);
        assertTrue(sim.routes.isEmpty());
        assertEquals("RECOVERED", restarted.command(sim, "taxi.create", request()).get("phase").getAsString());
    }
    @Test void terminalJournalAlsoReconcilesAnOlderMtrWorldAutosave() throws Exception {
        service.command(sim, "taxi.create", request());
        var oldDepot = org.mtr.core.tool.Utilities.getJsonObjectFromData(depot);
        var oldSiding = org.mtr.core.tool.Utilities.getJsonObjectFromData(siding);
        Route oldRoute = sim.routes.iterator().next();
        service.command(sim, "taxi.destroy", data("orderId", "test-order"));
        // Simulate loading a world snapshot saved before the completed journal transaction.
        depot.updateData(new org.mtr.core.serializer.JsonReader(oldDepot));
        siding.updateData(new org.mtr.core.serializer.JsonReader(oldSiding));
        sim.routes.add(oldRoute); sim.sync();
        RideService restarted = new RideService(config, temp.resolve("journal"), sim.dimension, events::add);
        restarted.recover(sim);
        assertTrue(sim.routes.isEmpty()); assertTrue(depot.getRouteIds().isEmpty());
        assertEquals("DESTROYED", restarted.command(sim, "taxi.status", data("orderId", "test-order")).get("phase").getAsString());
    }
    @Test void disconnectedRouteReturnsFailureAndRestoresInfrastructure() {
        sim.rails.remove(sim.rails.stream().skip(3).findFirst().orElseThrow());
        sim.sync();
        service.command(sim, "taxi.create", request());
        // Remove the physical connection between pickup and dropoff. Drive real path finders.
        for (int i = 0; i < 1000; i++) {
            depot.tick(); siding.tick(); service.tick(sim, false);
            if (events.stream().anyMatch(e -> e.get("type").getAsString().equals("ride.failed"))) break;
        }
        JsonObject status = service.command(sim, "taxi.status", data("orderId", "test-order"));
        assertEquals("FAILED", status.get("phase").getAsString(), status.toString());
        assertEquals("ROUTE_GENERATION_FAILED", status.get("error").getAsString());
        assertTrue(sim.routes.isEmpty());
    }
    @Test void generatesDispatchesRespectsCapAndReturnsDespiteTimeSynchronization() throws Exception {
        long originalPickupDwell = pickup.getDwellTime();
        service.command(sim, "taxi.create", request());
        var tick = Simulator.class.getDeclaredMethod("tick", long.class); tick.setAccessible(true);
        boolean observedActive = false;
        long firstDoorOpen = -1, firstDoorClose = -1;
        JsonObject status = null;
        for (int i = 0; i < 6500; i++) {
            tick.invoke(sim, 100L); service.tick(sim, false);
            status = service.command(sim, "taxi.status", data("orderId", "test-order"));
            if (status.get("phase").getAsString().equals("ACTIVE")) {
                observedActive = true;
                assertFalse(status.get("position").isJsonNull());
                assertTrue(status.get("speedKmh").getAsDouble() <= 30.0001, status.toString());
                if (status.get("doorsOpen").getAsBoolean() && firstDoorOpen < 0) firstDoorOpen = i * 100L;
                if (!status.get("doorsOpen").getAsBoolean() && firstDoorOpen >= 0 && firstDoorClose < 0) firstDoorClose = i * 100L;
            }
            if (i % 50 == 0) sim.setGameTime(6000, 1200000, true);
            if (status.get("phase").getAsString().equals("COMPLETED")) break;
        }
        assertTrue(observedActive, String.valueOf(status));
        assertTrue(firstDoorOpen >= 0 && firstDoorClose - firstDoorOpen >= 303_200,
            "Pickup doors must open automatically and stay open through the animation and five-minute boarding window");
        assertEquals(originalPickupDwell, pickup.getDwellTime());
        assertEquals("COMPLETED", status.get("phase").getAsString(), status.toString());
        assertTrue(sim.routes.isEmpty()); assertTrue(depot.getRouteIds().isEmpty());
    }
    @Test void readsRealRidingRecordsRejectsOccupiedDestructionAndControlsStoppedDoors() throws Exception {
        JsonObject group = request(); group.addProperty("passengerCount", 3);
        JsonObject accepted = service.command(sim, "taxi.create", group);
        assertEquals(3, accepted.get("requestedPassengerCount").getAsInt());
        assertEquals(4, accepted.get("capacity").getAsInt());
        var tick = Simulator.class.getDeclaredMethod("tick", long.class); tick.setAccessible(true);
        JsonObject query = data("orderId", "test-order");
        Vehicle vehicle = null;
        for (int i = 0; i < 1000; i++) {
            tick.invoke(sim, 100L); service.tick(sim, false);
            JsonObject status = service.command(sim, "taxi.status", query);
            if (status.get("phase").getAsString().equals("ACTIVE")) {
                vehicle = siding.getVehicleById(Long.parseLong(status.get("vehicleId").getAsString()));
                if (vehicle != null && !vehicle.isMoving()) break;
            }
        }
        assertNotNull(vehicle); assertFalse(vehicle.isMoving());
        UUID passenger = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(riding(passenger, 0), riding(second, 0), riding(third, 0))));
        JsonArray riders = service.command(sim, "taxi.passengers", query).getAsJsonArray("passengers");
        assertEquals(3, riders.size());
        assertEquals(Set.of(passenger.toString(), second.toString(), third.toString()), riders.asList().stream().map(e -> e.getAsJsonObject().get("uuid").getAsString()).collect(java.util.stream.Collectors.toSet()));
        JsonObject onboard = service.command(sim, "taxi.status", query);
        assertEquals(3, onboard.get("onboardCount").getAsInt()); assertEquals(1, onboard.get("remainingCapacity").getAsInt());
        assertEquals("VEHICLE_OCCUPIED", assertThrows(Fault.class, () -> service.command(sim, "taxi.destroy", query)).code);
        assertFalse(sim.routes.isEmpty());
        service.command(sim, "taxi.doors", data("orderId", "test-order", "state", "open"));
        assertEquals(1, vehicle.vehicleExtraData.getDoorMultiplier());
        assertTrue(DoorControl.blocks(vehicle.vehicleExtraData, false));
        service.command(sim, "taxi.doors", data("orderId", "test-order", "state", "closed"));
        assertEquals(-1, vehicle.vehicleExtraData.getDoorMultiplier());
        siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(riding(passenger, -1), riding(second, -1))));
        assertEquals(1, service.command(sim, "taxi.passengers", query).get("onboardCount").getAsInt());
        assertEquals("VEHICLE_OCCUPIED", assertThrows(Fault.class, () -> service.command(sim, "taxi.destroy", query)).code);
        siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(riding(third, -1))));
        assertTrue(service.command(sim, "taxi.passengers", query).getAsJsonArray("passengers").isEmpty());
        assertEquals("DESTROYED", service.command(sim, "taxi.destroy", query).get("phase").getAsString());
    }

    @Test void fullBoardingTriggersBothCountdownsAndReleasesPickup() throws Exception {
        exercisePickupDeparture(false);
    }

    @Test void manualDepartureSkipsBoardingCountdown() throws Exception {
        exercisePickupDeparture(true);
    }

    private void exercisePickupDeparture(boolean manual) throws Exception {
        service.command(sim, "taxi.create", request());
        var tick = Simulator.class.getDeclaredMethod("tick", long.class);
        tick.setAccessible(true);
        JsonObject query = data("orderId", "test-order");
        Vehicle vehicle = null;
        for (int i = 0; i < 1000; i++) {
            tick.invoke(sim, 100L);
            service.tick(sim, false);
            JsonObject status = service.command(sim, "taxi.status", query);
            if (status.has("doorsOpen") && status.get("doorsOpen").getAsBoolean()) {
                vehicle = siding.getVehicleById(Long.parseLong(status.get("vehicleId").getAsString()));
                break;
            }
        }
        assertNotNull(vehicle);
        if (manual) service.command(sim, "taxi.depart", query);
        else {
            siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(riding(UUID.randomUUID(), 0))));
            service.tick(sim, false);
        }
        for (int i = 0; i < 49; i++) {
            tick.invoke(sim, 100L);
            applyClosedDoorOverride(vehicle);
            service.tick(sim, false);
            assertFalse(vehicle.isMoving());
            if (!manual) assertEquals(1, vehicle.vehicleExtraData.getDoorMultiplier());
        }
        tick.invoke(sim, 100L);
        applyClosedDoorOverride(vehicle);
        service.tick(sim, false);
        assertEquals(-1, vehicle.vehicleExtraData.getDoorMultiplier());
        if (!manual) {
            for (int i = 0; i < 49; i++) {
                tick.invoke(sim, 100L);
                applyClosedDoorOverride(vehicle);
                service.tick(sim, false);
                assertFalse(vehicle.isMoving());
            }
        }
        for (int i = 0; i < 10 && !vehicle.isMoving(); i++) {
            tick.invoke(sim, 100L);
            applyClosedDoorOverride(vehicle);
            service.tick(sim, false);
        }
        assertTrue(vehicle.isMoving());
    }

    // The standalone Core test does not load the door mixin.
    private void applyClosedDoorOverride(Vehicle vehicle) {
        if (DoorControl.blocks(vehicle.vehicleExtraData, true)) MtrAccess.invoke(vehicle.vehicleExtraData, "closeDoors");
    }
    VehicleRidingEntity riding(UUID uuid, int car) { return new VehicleRidingEntity(uuid, car, 0, 0, 0, false, false, false, false, false, false, false); }
    @Test void groupSizeMustFitAndRouteNameIsReturnedAndPersisted() throws Exception {
        JsonObject invalid = request(); invalid.addProperty("passengerCount", 5);
        assertEquals("INSUFFICIENT_CAPACITY", assertThrows(Fault.class, () -> service.command(sim, "taxi.create", invalid)).code);
        invalid.addProperty("passengerCount", 1.5);
        assertEquals("INVALID_REQUEST", assertThrows(Fault.class, () -> service.command(sim, "taxi.create", invalid)).code);
        assertTrue(sim.routes.isEmpty());
        JsonObject group = request(); group.addProperty("passengerCount", 4); group.addProperty("routeName", "网约车 · 四人同行");
        JsonObject created = service.command(sim, "taxi.create", group);
        Route route = sim.routes.iterator().next();
        assertEquals("网约车 · 四人同行", route.getName()); assertEquals(created.get("plate").getAsString(), route.getRouteNumber());
        assertEquals(route.getName(), created.get("routeName").getAsString()); assertTrue(route.getHidden());
        RideService restarted = new RideService(config, temp.resolve("journal"), sim.dimension, events::add);
        restarted.recover(sim);
        assertEquals("网约车 · 四人同行", restarted.command(sim, "taxi.status", data("orderId", "test-order")).get("routeName").getAsString());
    }
    @Test void catalogReturnsExistingInfrastructureOutsideTaxiWhitelist() {
        Depot other = new Depot(TransportMode.TRAIN, sim); other.setName("普通运营车厂");
        other.setCorners(new Position(100, 63, 0), new Position(110, 65, 5)); sim.depots.add(other); sim.sync();
        JsonObject depots = service.command(sim, "mtr.depots", data());
        assertEquals(2, depots.get("total").getAsInt());
        JsonObject ordinary = depots.getAsJsonArray("items").asList().stream().map(JsonElement::getAsJsonObject).filter(d -> d.get("id").getAsString().equals(Long.toString(other.getId()))).findFirst().orElseThrow();
        assertFalse(ordinary.get("allowedForTaxi").getAsBoolean());
        JsonObject sidings = service.command(sim, "mtr.sidings", data("depotId", Long.toString(depot.getId())));
        assertEquals(1, sidings.get("total").getAsInt());
        assertTrue(sidings.getAsJsonArray("items").get(0).getAsJsonObject().get("vehicleCars").isJsonArray());
    }
    @Test void stationCatalogIncludesStationPlatformMappingAndOrphanPlatforms() {
        Station station = new Station(sim); station.setName("上车站");
        station.setCorners(new Position(29, 63, -1), new Position(41, 65, 1)); sim.stations.add(station); sim.sync();
        JsonObject stations = service.command(sim, "mtr.stations", data());
        assertEquals(1, stations.get("total").getAsInt());
        assertEquals(1, stations.getAsJsonArray("items").get(0).getAsJsonObject().get("platformCount").getAsInt());
        JsonObject platforms = service.command(sim, "mtr.platforms", data("stationId", Long.toString(station.getId())));
        assertEquals(1, platforms.get("total").getAsInt());
        assertEquals(Long.toString(pickup.getId()), platforms.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(2, service.command(sim, "mtr.platforms", data()).get("total").getAsInt());
    }
    @Test void routeCatalogPreservesStopOrderAndIdentifiesOwnRoutes() {
        Route ordinary = new Route(TransportMode.TRAIN, sim); ordinary.setName("TAXILink prefix is not ownership");
        ordinary.getRoutePlatforms().add(new RoutePlatformData(dropoff.getId())); ordinary.getRoutePlatforms().add(new RoutePlatformData(pickup.getId()));
        sim.routes.add(ordinary); sim.sync();
        service.command(sim, "taxi.create", request());
        JsonObject result = service.command(sim, "mtr.routes", data());
        assertEquals(2, result.get("total").getAsInt());
        for (JsonElement entry : result.getAsJsonArray("items")) {
            JsonObject route = entry.getAsJsonObject();
            if (route.get("id").getAsString().equals(Long.toString(ordinary.getId()))) {
                assertFalse(route.get("taxilinkManaged").getAsBoolean());
                assertEquals(Long.toString(dropoff.getId()), route.getAsJsonArray("platformIds").get(0).getAsString());
            } else { assertTrue(route.get("taxilinkManaged").getAsBoolean()); assertEquals("test-order", route.get("orderId").getAsString()); }
        }
    }
    @Test void catalogPaginatesWithoutDuplicatesAndDetectsChanges() {
        for (int i = 0; i < 4; i++) {
            Depot d = new Depot(TransportMode.TRAIN, sim); d.setName("Depot " + i); sim.depots.add(d);
        }
        Set<String> seen = new HashSet<>();
        JsonObject first = service.command(sim, "mtr.depots", data("limit", 2));
        JsonObject page = first;
        while (true) {
            for (JsonElement e : page.getAsJsonArray("items")) assertTrue(seen.add(e.getAsJsonObject().get("id").getAsString()));
            if (page.get("nextOffset").isJsonNull()) break;
            page = service.command(sim, "mtr.depots", data("offset", page.get("nextOffset").getAsInt(), "limit", 2, "revision", first.get("revision").getAsString()));
        }
        assertEquals(5, seen.size());
        depot.setName("Edited");
        assertEquals("CATALOG_CHANGED", assertThrows(Fault.class, () -> service.command(sim, "mtr.depots", data("offset", 2, "revision", first.get("revision").getAsString()))).code);
        assertEquals("INVALID_REQUEST", assertThrows(Fault.class, () -> service.command(sim, "mtr.depots", data("offset", 0.5))).code);
    }
    @Test void catalogByteBudgetStillAllowsCompleteEnumeration() {
        for (int i = 0; i < 120; i++) {
            Depot d = new Depot(TransportMode.TRAIN, sim); d.setName("车厂".repeat(100) + i); sim.depots.add(d);
        }
        JsonObject first = service.command(sim, "mtr.depots", data("limit", 500));
        assertFalse(first.get("nextOffset").isJsonNull());
        assertTrue(first.getAsJsonArray("items").size() < 121);
        JsonObject page = first;
        int count = 0;
        while (true) {
            assertTrue(page.getAsJsonArray("items").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 48 * 1024 + 2);
            count += page.getAsJsonArray("items").size();
            if (page.get("nextOffset").isJsonNull()) break;
            page = service.command(sim, "mtr.depots", data("offset", page.get("nextOffset").getAsInt(), "limit", 500, "revision", first.get("revision").getAsString()));
        }
        assertEquals(121, count);
    }
    @Test void authorizationAndDimensionsAreValidatedBeforeMutation() {
        config.allowedDepots.clear();
        assertEquals("DEPOT_NOT_ALLOWED", assertThrows(Fault.class, () -> service.command(sim, "taxi.create", request())).code);
        assertTrue(sim.routes.isEmpty());
    }
    @Test void capsOnlyOwnedPathCopies() {
        PathData path = new PathData(sim.rails.iterator().next(), 0, 0, 0, pos(0), pos(10));
        PathData.writePathCache(new ObjectArrayList<>(List.of(path)), sim, TransportMode.TRAIN);
        @SuppressWarnings("unchecked") List<PathData> owned = (List<PathData>) MtrAccess.read(siding, "pathSidingToMainRoute");
        owned.add(path); MtrAccess.configureRidePaths(siding, 25, pickup.getId(), 315000);
        assertTrue(owned.getFirst().getSpeedLimitMetersPerMillisecond() <= 25D / 3600);
        assertEquals(80, path.getSpeedLimitKilometersPerHour());
    }
    @Test void apiBoundaryMatchesActualBetaJar() throws Exception { MtrAccess.verify(); }

    @Test void depotAllowlistMigratesAndSupportsLiveWebEditsAndReload() throws Exception {
        var configPath = temp.resolve("config/taxilink.json");
        var listPath = configPath.resolveSibling("taxilink-depots.json");
        String depotId = Long.toString(depot.getId());
        Json.write(configPath, data("allowedDepots", Map.of(sim.dimension, List.of(depotId)), "serverId", "test-server"));
        Config migrated = Config.load(configPath);
        assertFalse(Json.object(java.nio.file.Files.readString(configPath)).has("allowedDepots"));
        assertTrue(migrated.allows(sim.dimension, depot.getId()));
        RideService web = new RideService(migrated, temp.resolve("allowlist-test"), sim.dimension, ignored -> {});
        web.command(sim, "taxi.depots.remove", data("depotId", depotId));
        assertFalse(migrated.allows(sim.dimension, depot.getId()));
        assertFalse(Config.load(configPath).allows(sim.dimension, depot.getId()));
        web.command(sim, "taxi.depots.add", data("depotId", depotId));
        assertTrue(Config.load(configPath).allows(sim.dimension, depot.getId()));
        java.nio.file.Files.writeString(listPath, "{ invalid json");
        assertEquals("ALLOWLIST_IO_ERROR", assertThrows(Fault.class, () -> web.command(sim, "taxi.depots.reload", data())).code);
        assertTrue(migrated.allows(sim.dimension, depot.getId()));
        Json.write(listPath, data(sim.dimension, List.of()));
        assertTrue(web.command(sim, "taxi.depots.reload", data()).getAsJsonArray("depotIds").isEmpty());
        assertFalse(migrated.allows(sim.dimension, depot.getId()));
    }

    @Test void integratedWorldsWithTheSameDimensionUseSeparateJournals() {
        Simulator other = new Simulator(sim.dimension, new String[]{sim.dimension}, temp.resolve("other-world/mtr"), false);
        assertEquals(temp.resolve("mtr/minecraft/overworld/taxilink-state"), MtrAccess.integratedStateRoot(sim));
        assertNotEquals(MtrAccess.integratedStateRoot(sim), MtrAccess.integratedStateRoot(other));
    }
}
