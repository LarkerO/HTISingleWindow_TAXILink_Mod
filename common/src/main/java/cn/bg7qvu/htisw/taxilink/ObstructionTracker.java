package cn.bg7qvu.htisw.taxilink;

import java.util.*;

/** Only real MTR overlap results are recorded; zero speed alone is never an obstruction. */
public final class ObstructionTracker {
    public record Sample(long at, double distance) {}
    private static final Map<Object, Sample> BLOCKED = Collections.synchronizedMap(new WeakHashMap<>());
    public static void record(Object vehicle, double distance) {
        if (distance >= 0) BLOCKED.put(vehicle, new Sample(System.currentTimeMillis(), distance));
        else BLOCKED.remove(vehicle);
    }
    public static Sample sample(Object vehicle) { return BLOCKED.get(vehicle); }
    public static void remove(Object vehicle) { BLOCKED.remove(vehicle); }
}
