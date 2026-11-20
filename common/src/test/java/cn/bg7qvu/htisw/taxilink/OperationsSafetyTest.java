package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.file.Path;
import java.util.*;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsSafetyTest {
    @TempDir Path temp;
    OperationsTest o;
    @BeforeEach void setup() throws Exception { o = new OperationsTest(); o.temp = temp; o.setup(); }
    @AfterEach void cleanup() { o.cleanup(); }
    @Test void dropoffWaitsForRidersAndDebouncesEmptyBeforeReturning() throws Exception {
        o.f.config.pickupWaitSeconds = 1; o.f.config.fullBoardingCloseDelaySeconds = 0;
        o.f.config.closedDoorDepartureDelaySeconds = 0; o.f.config.dropoffMinWaitSeconds = 1; o.f.config.dropoffEmptyConfirmSeconds = 2;
        o.call("taxi.create", o.f.request()); Vehicle v = o.active(); UUID rider = UUID.randomUUID();
        o.f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(o.f.riding(rider, 0))));
        for (int i=0; i<2000 && !o.status("test-order").get("stage").getAsString().equals("WAITING_DROPOFF"); i++) o.tick();
        assertEquals("WAITING_DROPOFF", o.status("test-order").get("stage").getAsString());
        double progress = ((Number) MtrAccess.read(v, "railProgress")).doubleValue();
        for (int i=0; i<400; i++) o.tick();
        assertEquals(progress, ((Number) MtrAccess.read(v, "railProgress")).doubleValue());
        assertFalse(o.status("test-order").get("dropoffReleased").getAsBoolean());
        assertEquals("PASSENGERS_DISEMBARKING", assertThrows(Fault.class, () -> o.call("taxi.doors", data("orderId","test-order","state","closed"))).code);
        assertEquals("VEHICLE_OCCUPIED", assertThrows(Fault.class, () -> o.call("taxi.destroy", o.query("test-order"))).code);
        o.f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(o.f.riding(rider, -1))));
        for (int i=0;i<10;i++) o.tick();
        o.f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(o.f.riding(rider, 0))));
        o.tick(); assertFalse(o.status("test-order").get("dropoffReleased").getAsBoolean());
        o.f.siding.updateVehicleRidingEntities(v.getId(), new ObjectArrayList<>(List.of(o.f.riding(rider, -1))));
        for (int i=0;i<300 && !o.status("test-order").get("dropoffReleased").getAsBoolean();i++) o.tick();
        assertTrue(o.status("test-order").get("dropoffReleased").getAsBoolean());
        for (int i=0;i<1500 && !o.status("test-order").get("phase").getAsString().equals("COMPLETED");i++) o.tick();
        assertEquals("COMPLETED",o.status("test-order").get("phase").getAsString());
        assertTrue(o.f.sim.routes.isEmpty());
    }
    @Test void realObstructionSampleEscalatesButOrdinaryZeroSpeedDoesNot() throws Exception {
        o.f.config.obstructionWaitSeconds = 5;
        o.call("taxi.create", o.f.request()); Vehicle v = o.active();
        MtrAccess.field(Vehicle.class,"speed").setDouble(v,0);
        long now=o.f.sim.getCurrentMillis();
        for (int i=0;i<8;i++) { MtrAccess.field(Data.class,"currentMillis").setLong(o.f.sim,now+i*1000); o.f.service.tick(o.f.sim,false); }
        assertFalse(o.status("test-order").get("assistanceRequired").getAsBoolean());
        for (int i=0;i<8;i++) { MtrAccess.field(Data.class,"currentMillis").setLong(o.f.sim,now+10000+i*1000); ObstructionTracker.record(v,2); o.f.service.tick(o.f.sim,false); }
        assertTrue(o.status("test-order").get("assistanceRequired").getAsBoolean());
        assertEquals("BLOCKER_CHANGED", assertThrows(Fault.class, () -> o.call("taxi.clear_returning",data("orderId","test-order","targetOrderId","unknown"))).code);
        ObstructionTracker.remove(v); o.f.service.tick(o.f.sim,false);
        assertFalse(o.status("test-order").get("assistanceRequired").getAsBoolean());
    }
    @Test void repeatedCompleteTripsRestoreDepotForReuse() throws Exception {
        o.f.config.pickupWaitSeconds=1; o.f.config.closedDoorDepartureDelaySeconds=0; o.f.config.dropoffMinWaitSeconds=1; o.f.config.dropoffEmptyConfirmSeconds=1;
        for(int cycle=0;cycle<4;cycle++) {
            String id="reuse-"+cycle; JsonObject p=o.f.request();p.addProperty("orderId",id);
            o.call("taxi.create",p);
            for(int i=0;i<2000 && !o.status(id).get("phase").getAsString().equals("COMPLETED");i++)o.tick();
            assertEquals("COMPLETED",o.status(id).get("phase").getAsString());
            assertTrue(o.f.sim.routes.isEmpty());assertTrue(o.f.depot.getRouteIds().isEmpty());assertTrue(o.f.siding.getVehicleCars().isEmpty());
        }
    }
    @Test void clearingMatchedReturningVehicleRechecksRidersSpeedAndLeavesSourceDepotAlone() throws Exception {
        o.f.rail(-20,-10,false,true);o.f.rail(-10,0,false,false);
        Siding siding=new Siding(o.f.pos(-20),o.f.pos(-10),10,TransportMode.TRAIN,o.f.sim);
        Depot depot=new Depot(TransportMode.TRAIN,o.f.sim);depot.setCorners(new Position(-21,63,-1),new Position(-9,65,1));
        o.f.sim.depots.add(depot);o.f.sim.sidings.add(siding);o.f.sim.sync();siding.init();
        o.f.config.allowedDepots.put(o.f.sim.dimension,List.of(Long.toString(o.f.depot.getId()),Long.toString(depot.getId())));
        o.call("taxi.create",o.f.request());
        JsonObject p=o.f.request();p.addProperty("orderId","returning");p.addProperty("depotId",Long.toString(depot.getId()));p.addProperty("sidingId",Long.toString(siding.getId()));
        o.call("taxi.create",p);
        for(int i=0;i<1000&&(!o.status("returning").get("phase").getAsString().equals("ACTIVE")||!o.status("test-order").get("phase").getAsString().equals("ACTIVE"));i++)o.tick();
        Vehicle source=o.f.siding.getVehicleById(Long.parseLong(o.status("test-order").get("vehicleId").getAsString()));
        Vehicle target=siding.getVehicleById(Long.parseLong(o.status("returning").get("vehicleId").getAsString()));
        double afterDropoff=target.vehicleExtraData.immutablePath.stream().filter(path->path.getSavedRailBaseId()==o.f.dropoff.getId()).findFirst().orElseThrow().getEndDistance()+1;
        MtrAccess.field(Vehicle.class,"railProgress").setDouble(target,afterDropoff);
        MtrAccess.field(Vehicle.class,"speed").setDouble(source,0);
        @SuppressWarnings("unchecked") List<Object> frames=(List<Object>)((List<?>)MtrAccess.read(o.f.sim,"vehiclePositions")).get(source.getTransportMode().ordinal());
        double progress=((Number)MtrAccess.read(source,"railProgress")).doubleValue();
        PathData path=source.vehicleExtraData.immutablePath.stream().filter(rail->rail.getEndDistance()>progress+1).findFirst().orElseThrow();
        VehiclePosition occupancy=new VehiclePosition();occupancy.addSegment(0,path.getEndDistance()-path.getStartDistance(),target.getId());
        Map<Position,Map<Position,VehiclePosition>> map=new HashMap<>();map.put(path.getOrderedPosition1(),Map.of(path.getOrderedPosition2(),occupancy));frames.set(0,map);
        ObstructionTracker.record(source,100);
        JsonObject request=data("orderId","test-order","targetOrderId","returning");
        MtrAccess.field(Vehicle.class,"speed").setDouble(target,.001);
        assertEquals("VEHICLE_MOVING",assertThrows(Fault.class,()->o.call("taxi.clear_returning",request)).code);
        MtrAccess.field(Vehicle.class,"speed").setDouble(target,0);UUID rider=UUID.randomUUID();
        siding.updateVehicleRidingEntities(target.getId(),new ObjectArrayList<>(List.of(o.f.riding(rider,0))));
        assertEquals("VEHICLE_OCCUPIED",assertThrows(Fault.class,()->o.call("taxi.clear_returning",request)).code);
        siding.updateVehicleRidingEntities(target.getId(),new ObjectArrayList<>(List.of(o.f.riding(rider,-1))));
        assertEquals("DESTROYED",o.call("taxi.clear_returning",request).get("phase").getAsString());
        assertEquals("ACTIVE",o.status("test-order").get("phase").getAsString());assertNotNull(o.f.siding.getVehicleById(source.getId()));
        assertTrue(depot.getRouteIds().isEmpty());assertFalse(o.f.depot.getRouteIds().isEmpty());
    }
    @Test void clientBoardingOverrideIsLimitedToStoppedOpenTaxiDoors() throws Exception {
        o.call("taxi.create",o.f.request()); Vehicle v=o.active();
        MtrAccess.field(v.vehicleExtraData.getClass(),"thisRouteNumber").set(v.vehicleExtraData,"TX-"+"A".repeat(32));
        MtrAccess.invoke(v.vehicleExtraData,"openDoors"); assertFalse(ClientBoarding.allows(v));
        MtrAccess.field(Vehicle.class,"speed").setDouble(v,0); assertTrue(ClientBoarding.allows(v));
        MtrAccess.invoke(v.vehicleExtraData,"closeDoors"); assertFalse(ClientBoarding.allows(v));
        MtrAccess.invoke(v.vehicleExtraData,"openDoors");
        MtrAccess.field(v.vehicleExtraData.getClass(),"thisRouteNumber").set(v.vehicleExtraData,"Regular train"); assertFalse(ClientBoarding.allows(v));
    }
    @Test void obstructionReadsMtrSegmentsAndExcludesTheWaitingVehicle() throws Exception {
        o.call("taxi.create",o.f.request()); Vehicle v=o.active();
        double progress=((Number)MtrAccess.read(v,"railProgress")).doubleValue();
        PathData path=v.vehicleExtraData.immutablePath.stream().filter(p->p.getEndDistance()>progress+1).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<Object> frames=(List<Object>)((List<?>)MtrAccess.read(o.f.sim,"vehiclePositions")).get(v.getTransportMode().ordinal());
        VehiclePosition occupancy=new VehiclePosition();
        double length=path.getEndDistance()-path.getStartDistance();
        occupancy.addSegment(0,length,v.getId()); occupancy.addSegment(0,length,9999);
        Map<Position,Map<Position,VehiclePosition>> map=new HashMap<>();map.put(path.getOrderedPosition1(),Map.of(path.getOrderedPosition2(),occupancy));
        frames.set(0,map);
        JsonArray matches=MtrAccess.blockingVehicles(o.f.sim,v,100,id->data("orderId","returning-test","returning",true));
        assertEquals(1,matches.size()); assertEquals("9999",matches.get(0).getAsJsonObject().get("vehicleId").getAsString());
        assertTrue(matches.get(0).getAsJsonObject().get("returning").getAsBoolean());
        assertEquals("mtr_occupied_segment",matches.get(0).getAsJsonObject().get("source").getAsString());
    }
}
