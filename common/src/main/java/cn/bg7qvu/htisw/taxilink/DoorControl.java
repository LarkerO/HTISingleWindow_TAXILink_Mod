package cn.bg7qvu.htisw.taxilink;

import java.util.*;

/** Weak keys avoid retaining destroyed MTR vehicles. Accessed by the simulator and its mixins. */
public final class DoorControl {
    private static final Map<Object, Boolean> TARGETS = Collections.synchronizedMap(new WeakHashMap<>());
    private DoorControl() {}
    public static void set(Object extraData, boolean open) { TARGETS.put(extraData, open); }
    public static void remove(Object extraData) { TARGETS.remove(extraData); }
    public static boolean blocks(Object extraData, boolean requestedOpen) {
        Boolean desired = TARGETS.get(extraData);
        return desired != null && desired != requestedOpen;
    }
}
