package cn.bg7qvu.htisw.taxilink;

import org.mtr.core.data.Vehicle;

/** Optional TAXILink client extension; the MTR jar and ordinary trains remain unchanged. */
public final class ClientBoarding {
    public static boolean allows(Object candidate) {
        if (Boolean.getBoolean("taxilink.disablePlatformlessBoarding") || !(candidate instanceof Vehicle vehicle)) return false;
        String number = vehicle.vehicleExtraData.getThisRouteNumber();
        return vehicle.getIsOnRoute() && !vehicle.isMoving() && vehicle.vehicleExtraData.getDoorMultiplier() > 0
            && number != null && number.matches("TX-[A-F0-9]{32}");
    }
}
