package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.file.Path;
import java.util.*;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsTest {
    @TempDir Path temp;
    RideServiceTest f;
    @BeforeEach void setup() throws Exception { f = new RideServiceTest(); f.temp = temp; f.setup(); }
    @AfterEach void cleanup() { VehicleControl.clear(); PlayerNames.clear(); }
    JsonObject query(String order) { return data("orderId", order); }
    JsonObject call(String type, JsonObject p) { return f.service.command(f.sim, type, p); }
    JsonObject status(String order) { return call("taxi.status", query(order)); }
    void center() {
        call("taxi.centers.put", data("centerId", "city", "center", data("name", "城区中心",
            "depotIds", List.of(Long.toString(f.depot.getId())), "routes", List.of(data("id", "airport", "name", "机场接送",
                "pickupPlatformId", Long.toString(f.pickup.getId()), "dropoffPlatformId", Long.toString(f.dropoff.getId()))))));
    }
    JsonObject order(String id) {
        JsonObject p = f.request(); p.addProperty("orderId", id); p.addProperty("centerId", "city"); p.addProperty("centerRouteId", "airport");
        p.remove("depotId"); p.remove("sidingId"); p.remove("pickupPlatformId"); p.remove("dropoffPlatformId"); return p;
    }
    void tick() throws Exception {
        var tick = Simulator.class.getDeclaredMethod("tick", long.class); tick.setAccessible(true);
        tick.invoke(f.sim, 100L); f.service.tick(f.sim, false);
    }
    Vehicle active() throws Exception {
        for (int i = 0; i < 1000; i++) {
            tick(); JsonObject s = status("test-order");
            if (s.get("phase").getAsString().equals("ACTIVE")) {
                Vehicle v = f.siding.getVehicleById(Long.parseLong(s.get("vehicleId").getAsString()));
                if (v != null && v.isMoving()) return v;
            }
        }
        throw new AssertionError("Vehicle never dispatched: " + status("test-order"));
    }
    @Test void centerQueuesInArrivalOrderAndRetriesAreIdempotent() {
        center(); f.config.maxActiveRides = 1;
        assertEquals("GENERATING", call("taxi.create", order("first")).get("phase").getAsString());
        assertEquals("机场接送", status("first").get("routeName").getAsString());
        JsonObject second = order("z-second"), third = order("a-third");
        assertEquals("QUEUED", call("taxi.create", second).get("phase").getAsString());
        assertEquals("QUEUED", call("taxi.create", third).get("phase").getAsString());
        assertEquals(1, status("z-second").get("queuePosition").getAsInt());
        assertEquals(2, status("a-third").get("queuePosition").getAsInt());
        assertEquals(1, call("taxi.create", second).get("queuePosition").getAsInt());
        assertEquals(3, call("taxi.list", data()).getAsJsonArray("rides").size());
        call("taxi.destroy", query("first")); f.service.tick(f.sim, false);
        assertEquals("GENERATING", status("z-second").get("phase").getAsString());
        assertEquals("QUEUED", status("a-third").get("phase").getAsString());
        assertEquals("GENERATING", call("taxi.create", second).get("phase").getAsString());
        call("taxi.destroy", query("z-second")); f.service.tick(f.sim, false);
        assertEquals("GENERATING", status("a-third").get("phase").getAsString());
        assertEquals(1, f.sim.routes.size());
    }
    @Test void queuedOrdersExpireEvenWhenTheActiveLimitIsFull() throws Exception {
        center(); f.config.maxActiveRides = 1; f.config.queueTimeoutSeconds = 1;
        call("taxi.create", order("first")); call("taxi.create", order("waiting"));
        Thread.sleep(1050); f.service.tick(f.sim, false);
        JsonObject s = status("waiting");
        assertEquals("CANCELLED", s.get("phase").getAsString()); assertEquals("QUEUE_TIMEOUT", s.get("error").getAsString());
        assertEquals(1, f.sim.routes.size()); assertEquals("GENERATING", status("first").get("phase").getAsString());
    }
    @Test void cancellingQueuedOrderNeverRestoresOrClearsTheBusyDepot() {
        center(); call("taxi.create", order("first")); call("taxi.create", order("waiting"));
        String routeId = status("first").get("routeId").getAsString();
        call("taxi.destroy", query("waiting"));
        assertEquals("DESTROYED", status("waiting").get("phase").getAsString());
        assertTrue(f.depot.getRouteIds().contains(Long.parseLong(routeId))); assertEquals(1, f.sim.routes.size());
        assertEquals("CENTER_BUSY", assertThrows(Fault.class, () -> call("taxi.centers.remove", data("centerId", "city"))).code);
    }
    @Test void centerStateAndAllowlistAreIsolatedAcrossWorlds() throws Exception {
        Config a = f.config.forWorld(temp.resolve("world-a")), b = f.config.forWorld(temp.resolve("world-b"));
        a.depotAllowlist.set(f.sim.dimension, f.depot.getId(), true);
        assertTrue(a.allows(f.sim.dimension, f.depot.getId())); assertFalse(b.allows(f.sim.dimension, f.depot.getId()));
        center(); RideService other = new RideService(b, temp.resolve("world-b"), f.sim.dimension, e -> {});
        assertTrue(other.command(f.sim, "taxi.centers.list", data()).getAsJsonObject("centers").isEmpty());
        assertFalse(call("taxi.centers.list", data()).getAsJsonObject("centers").isEmpty());
    }
    @Test void centerChoosesAnotherIdleDepotAndRejectsAnInvalidConsistBeforeQueueing() {
        f.rail(-20, -10, false, true); f.rail(-10, 0, false, false);
        Siding otherSiding = new Siding(f.pos(-20), f.pos(-10), 10, TransportMode.TRAIN, f.sim);
        Depot other = new Depot(TransportMode.TRAIN, f.sim);
        other.setCorners(new Position(-21, 63, -1), new Position(-9, 65, 1));
        f.sim.depots.add(other); f.sim.sidings.add(otherSiding); f.sim.sync(); otherSiding.init();
        f.config.allowedDepots.put(f.sim.dimension, List.of(Long.toString(f.depot.getId()), Long.toString(other.getId())));
        center(); JsonObject c = call("taxi.centers.list", data()).getAsJsonObject("centers").getAsJsonObject("city");
        c.getAsJsonArray("depotIds").add(Long.toString(other.getId()));
        call("taxi.centers.put", data("centerId", "city", "center", c));
        call("taxi.create", order("first"));
        JsonObject second = call("taxi.create", order("second"));
        assertEquals(Long.toString(other.getId()), second.get("depotId").getAsString()); assertEquals(2, f.sim.routes.size());
        JsonObject bad = order("bad"); bad.getAsJsonArray("vehicleCars").get(0).getAsJsonObject().addProperty("capacity", 0);
        assertEquals("INVALID_REQUEST", assertThrows(Fault.class, () -> call("taxi.create", bad)).code);
        assertEquals(2, call("taxi.list", data()).getAsJsonArray("rides").size());
    }
    @Test void speedControlEmergencyAndResumePreserveOrderAndPathLimits() throws Exception {
        call("taxi.create", f.request()); Vehicle v = active();
        long route = Long.parseLong(status("test-order").get("routeId").getAsString()); assertTrue(VehicleControl.silent(route));
        call("taxi.control", data("orderId", "test-order", "mode", "speed", "speedKmh", 10));
        assertTrue(v.vehicleExtraData.immutablePath.stream().allMatch(p -> p.getSpeedLimitMetersPerMillisecond() <= 10/3600D));
        for (int i=0; i<15; i++) tick();
        assertTrue(status("test-order").get("speedKmh").getAsDouble() <= 10.0001);
        double progress = ((Number)MtrAccess.read(v, "railProgress")).doubleValue();
        call("taxi.control", data("orderId", "test-order", "mode", "emergency"));
        assertEquals(0, ((Number)MtrAccess.read(v, "speed")).doubleValue()); assertTrue(VehicleControl.block(v));
        assertEquals(progress, ((Number)MtrAccess.read(v, "railProgress")).doubleValue());
        assertEquals("EMERGENCY_STOPPED", assertThrows(Fault.class, () -> call("taxi.control", data("orderId", "test-order", "mode", "speed", "speedKmh", 5))).code);
        call("taxi.control", data("orderId", "test-order", "mode", "resume")); assertFalse(VehicleControl.block(v));
        for (int i=0; i<5; i++) tick(); assertTrue(v.isMoving());
        assertTrue(v.vehicleExtraData.immutablePath.stream().allMatch(p -> p.getSpeedLimitMetersPerMillisecond() <= 30/3600D));
        call("taxi.destroy", query("test-order")); assertFalse(VehicleControl.silent(route));
    }
    @Test void passengerVisitsAndTimelinePersistAfterCompletion() throws Exception {
        call("taxi.create", f.request()); Vehicle v = active(); UUID player = UUID.randomUUID();
        f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(f.riding(player, 0))));
        JsonObject riders = call("taxi.passengers", query("test-order"));
        assertEquals(1, riders.getAsJsonArray("passengerHistory").size());
        long boarded = riders.getAsJsonArray("passengerHistory").get(0).getAsJsonObject().get("boardedAt").getAsLong();
        f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(f.riding(player, -1))));
        riders = call("taxi.passengers", query("test-order"));
        assertTrue(riders.getAsJsonArray("passengerHistory").get(0).getAsJsonObject().get("alightedAt").getAsLong() >= boarded);
        f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(f.riding(player, 0))));
        assertEquals(2, call("taxi.passengers", query("test-order")).getAsJsonArray("passengerHistory").size());
        f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(f.riding(player, -1)))); call("taxi.passengers", query("test-order"));
        call("taxi.destroy", query("test-order"));
        RideService restarted = new RideService(f.config, temp.resolve("journal"), f.sim.dimension, e -> {}); restarted.recover(f.sim);
        assertEquals(2, restarted.command(f.sim, "taxi.passengers", query("test-order")).getAsJsonArray("passengerHistory").size());
        JsonArray logs = restarted.command(f.sim, "taxi.logs", query("test-order")).getAsJsonArray("items");
        assertEquals(2, logs.asList().stream().filter(e -> e.getAsJsonObject().get("type").getAsString().equals("passenger.boarded")).count());
        for (int i=0;i<logs.size();i++) assertEquals(i+1,logs.get(i).getAsJsonObject().get("sequence").getAsInt());
    }
    @Test void fullTripHasChineseStagesAndHistoricalThreeLegGeometry() throws Exception {
        f.config.pickupWaitSeconds = 1; f.config.closedDoorDepartureDelaySeconds = 0;
        call("taxi.create", f.request()); Set<String> stages = new HashSet<>();
        for (int i=0;i<1500;i++) { tick(); JsonObject s=status("test-order"); stages.add(s.get("stage").getAsString()); if(s.get("phase").getAsString().equals("COMPLETED"))break; }
        assertEquals("COMPLETED",status("test-order").get("phase").getAsString());
        assertTrue(stages.containsAll(List.of("TO_PICKUP","WAITING_PICKUP","ON_TRIP","WAITING_DROPOFF","RETURNING")), stages.toString());
        JsonObject g=call("taxi.geometry",query("test-order"));
        assertEquals(3,g.getAsJsonArray("legs").size()); assertEquals(g.get("totalMeters"),g.get("progressMeters"));
        assertEquals("已完成",status("test-order").get("stageLabel").getAsString());
    }
    @Test void returnToDepotAutomaticallyHonorsNextQueuedOrder() throws Exception {
        f.config.pickupWaitSeconds = 1; f.config.closedDoorDepartureDelaySeconds = 0;
        center(); call("taxi.create", order("first")); call("taxi.create", order("second"));
        for (int i=0;i<3000;i++) {
            tick();
            if (status("second").get("phase").getAsString().equals("COMPLETED")) break;
        }
        assertEquals("COMPLETED",status("first").get("phase").getAsString());
        assertEquals("COMPLETED",status("second").get("phase").getAsString());
        assertTrue(f.sim.routes.isEmpty());
    }
    @Test void changingCenterAfterQuoteInvalidatesItsSnapshot() {
        center(); JsonObject request = order("quoted");
        JsonObject resolved = f.service.resolveQuote(f.sim, request);
        request.add("centerRevision", resolved.get("centerRevision"));
        JsonObject changed = call("taxi.centers.list", data()).getAsJsonObject("centers").getAsJsonObject("city");
        changed.getAsJsonArray("routes").get(0).getAsJsonObject().addProperty("name", "修改后的路线");
        call("taxi.centers.put", data("centerId", "city", "center", changed));
        assertEquals("QUOTE_STALE", assertThrows(Fault.class, () -> call("taxi.create", request)).code);
        assertTrue(f.sim.routes.isEmpty());
    }
    @Test void orderHistoryCanBeEnumeratedWithPagination() {
        for (int i=0;i<8;i++) {
            JsonObject p=f.request(); p.addProperty("orderId","order-"+i);
            call("taxi.create",p); call("taxi.destroy",query("order-"+i));
        }
        Set<String> ids=new HashSet<>(); int offset=0;
        while(true) {
            JsonObject page=call("taxi.list",data("offset",offset,"limit",3));
            assertTrue(page.getAsJsonArray("rides").size()<=3);
            for(JsonElement e:page.getAsJsonArray("rides")) assertTrue(ids.add(e.getAsJsonObject().get("orderId").getAsString()));
            if(!page.has("nextOffset") || page.get("nextOffset").isJsonNull())break;
            offset=page.get("nextOffset").getAsInt();
        }
        assertEquals(8,ids.size());
    }
}
