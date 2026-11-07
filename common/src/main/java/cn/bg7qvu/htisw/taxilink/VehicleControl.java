package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.mtr.core.data.Vehicle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side controls shared by both loaders; never enables station-skipping manual ATO. */
public final class VehicleControl {
    private static final Set<Object> HELD = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Set<Long> SILENT_ROUTES = ConcurrentHashMap.newKeySet();
    private VehicleControl() {}
    public static void silent(long route, boolean silent) { if (silent) SILENT_ROUTES.add(route); else SILENT_ROUTES.remove(route); }
    public static boolean silent(long route) { return SILENT_ROUTES.contains(route); }
    public static void hold(Vehicle vehicle, boolean hold) { if (hold) HELD.add(vehicle); else HELD.remove(vehicle); }
    public static boolean block(Object target) {
        if (!HELD.contains(target)) return false;
        Vehicle vehicle = (Vehicle) target;
        try {
            MtrAccess.field(Vehicle.class, "speed").setDouble(vehicle, 0);
            MtrAccess.field(vehicle.vehicleExtraData.getClass(), "speedTarget").setDouble(vehicle.vehicleExtraData, 0);
            MtrAccess.field(Vehicle.class, "lastMovementMillis").setLong(vehicle, System.currentTimeMillis());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("MTR emergency stop API mismatch", e); }
        return true;
    }
    public static void clear() { HELD.clear(); SILENT_ROUTES.clear(); }
}
