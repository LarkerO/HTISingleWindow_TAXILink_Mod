package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import java.nio.file.Path;
import java.util.*;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSafetyTest {
    @TempDir Path temp;
    OperationsTest o;
    @BeforeEach void setup() throws Exception { o = new OperationsTest(); o.temp = temp; o.setup(); }
    @AfterEach void cleanup() { o.cleanup(); }
    @Test void lostVehicleFailsWithoutClaimingFullDistance() throws Exception {
        o.call("taxi.create", o.f.request());
        o.active();
        assertEquals("TO_PICKUP", o.status("test-order").get("stage").getAsString());
        o.f.siding.clearVehicles();
        o.f.service.tick(o.f.sim, false);
        assertEquals("FAILED", o.status("test-order").get("phase").getAsString());
        assertEquals("VEHICLE_LOST", o.status("test-order").get("error").getAsString());
        var geometry = o.call("taxi.geometry", o.query("test-order"));
        assertTrue(geometry.get("progressMeters").getAsDouble() < geometry.get("totalMeters").getAsDouble());
    }
    @Test void missingFirstDepotFallsBackToHealthySecondDepot() {
        var f = o.f;
        f.rail(-20, -10, false, true); f.rail(-10, 0, false, false);
        Siding otherSiding = new Siding(f.pos(-20), f.pos(-10), 10, TransportMode.TRAIN, f.sim);
        Depot other = new Depot(TransportMode.TRAIN, f.sim);
        other.setCorners(new Position(-21, 63, -1), new Position(-9, 65, 1));
        f.sim.depots.add(other); f.sim.sidings.add(otherSiding); f.sim.sync(); otherSiding.init();
        f.config.allowedDepots.put(f.sim.dimension, List.of(Long.toString(f.depot.getId()), Long.toString(other.getId())));
        o.center();
        var c = o.call("taxi.centers.list", data()).getAsJsonObject("centers").getAsJsonObject("city");
        c.getAsJsonArray("depotIds").add(Long.toString(other.getId()));
        o.call("taxi.centers.put", data("centerId", "city", "center", c));
        f.sim.depots.remove(f.depot); f.sim.sync();
        assertEquals(Long.toString(other.getId()), o.call("taxi.create", o.order("center-order")).get("depotId").getAsString());
        o.call("taxi.destroy", o.query("center-order"));
        var direct = f.request(); direct.addProperty("depotId", Long.toString(other.getId())); direct.addProperty("sidingId", Long.toString(otherSiding.getId()));
        assertEquals("GENERATING", o.call("taxi.create", direct).get("phase").getAsString());
    }
    @Test void generationWaitsForParkedPassengersBeforeClearing() throws Exception {
        var f = o.f;
        o.call("taxi.create", f.request());
        var tick = Simulator.class.getDeclaredMethod("tick", long.class); tick.setAccessible(true);
        for (int i=0; i<1000 && f.depot.getLastGeneratedStatus()!=Depot.GeneratedStatus.SUCCESSFUL; i++) tick.invoke(f.sim,100L);
        assertEquals(Depot.GeneratedStatus.SUCCESSFUL, f.depot.getLastGeneratedStatus());
        tick.invoke(f.sim,100L);
        List<Vehicle> parked = new ArrayList<>(); f.siding.iterateVehicles(parked::add);
        assertFalse(parked.isEmpty());
        var vehicle = parked.getFirst();
        f.siding.updateVehicleRidingEntities(vehicle.getId(), new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<>(List.of(f.riding(UUID.randomUUID(),0))));
        assertEquals(1,o.status("test-order").get("onboardCount").getAsInt());
        f.service.tick(f.sim,false);
        assertNotNull(f.siding.getVehicleById(vehicle.getId()));
        assertEquals(1,o.status("test-order").get("onboardCount").getAsInt());
        assertEquals("GENERATING", o.status("test-order").get("phase").getAsString());
        f.siding.updateVehicleRidingEntities(vehicle.getId(), new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<>());
        // Explicitly mark the existing rider as alighted (MTR applies deltas).
        List<UUID> riders = new ArrayList<>();
        vehicle.vehicleExtraData.iterateRidingEntities(e -> riders.add(e.uuid));
        for (UUID rider : riders) f.siding.updateVehicleRidingEntities(vehicle.getId(), new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<>(List.of(f.riding(rider, -1))));
        f.service.tick(f.sim, false);
        assertEquals("DISPATCHING", o.status("test-order").get("phase").getAsString());
        assertTrue(o.status("test-order").get("error").isJsonNull());
    }
}
