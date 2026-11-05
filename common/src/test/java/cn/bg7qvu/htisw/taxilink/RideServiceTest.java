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
        service.command(sim, "taxi.create", request());
        var tick = Simulator.class.getDeclaredMethod("tick", long.class); tick.setAccessible(true);
        boolean observedActive = false;
        JsonObject status = null;
        for (int i = 0; i < 5000; i++) {
            tick.invoke(sim, 100L); service.tick(sim, false);
            status = service.command(sim, "taxi.status", data("orderId", "test-order"));
            if (status.get("phase").getAsString().equals("ACTIVE")) {
                observedActive = true;
                assertFalse(status.get("position").isJsonNull());
                assertTrue(status.get("speedKmh").getAsDouble() <= 30.0001, status.toString());
            }
            if (i % 50 == 0) sim.setGameTime(6000, 1200000, true);
            if (status.get("phase").getAsString().equals("COMPLETED")) break;
        }
        assertTrue(observedActive, String.valueOf(status));
        assertEquals("COMPLETED", status.get("phase").getAsString(), status.toString());
        assertTrue(sim.routes.isEmpty()); assertTrue(depot.getRouteIds().isEmpty());
    }
    @Test void readsRealRidingRecordsRejectsOccupiedDestructionAndControlsStoppedDoors() throws Exception {
        service.command(sim, "taxi.create", request());
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
        UUID passenger = UUID.randomUUID();
        siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(new VehicleRidingEntity(passenger, 0, 0, 0, 0, false, false, false, false, false, false, false))));
        JsonArray riders = service.command(sim, "taxi.passengers", query).getAsJsonArray("passengers");
        assertEquals(1, riders.size()); assertEquals(passenger.toString(), riders.get(0).getAsJsonObject().get("uuid").getAsString());
        assertEquals("VEHICLE_OCCUPIED", assertThrows(Fault.class, () -> service.command(sim, "taxi.destroy", query)).code);
        assertFalse(sim.routes.isEmpty());
        service.command(sim, "taxi.doors", data("orderId", "test-order", "state", "open"));
        assertEquals(1, vehicle.vehicleExtraData.getDoorMultiplier());
        assertTrue(DoorControl.blocks(vehicle.vehicleExtraData, false));
        service.command(sim, "taxi.doors", data("orderId", "test-order", "state", "closed"));
        assertEquals(-1, vehicle.vehicleExtraData.getDoorMultiplier());
        siding.updateVehicleRidingEntities(vehicle.getId(), new ObjectArrayList<>(List.of(new VehicleRidingEntity(passenger, -1, 0, 0, 0, false, false, false, false, false, false, false))));
        assertTrue(service.command(sim, "taxi.passengers", query).getAsJsonArray("passengers").isEmpty());
        assertEquals("DESTROYED", service.command(sim, "taxi.destroy", query).get("phase").getAsString());
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
        owned.add(path); MtrAccess.capPaths(siding, 25);
        assertTrue(owned.getFirst().getSpeedLimitMetersPerMillisecond() <= 25D / 3600);
        assertEquals(80, path.getSpeedLimitKilometersPerHour());
    }
    @Test void apiBoundaryMatchesActualBetaJar() throws Exception { MtrAccess.verify(); }
}
