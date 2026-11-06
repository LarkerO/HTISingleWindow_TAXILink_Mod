package cn.bg7qvu.htisw.taxilink.mtr;

import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;

/** Version-locked private API boundary for MTR 4.1.0-beta.2. Never bundle MTR. */
public final class MtrAccess {
    private MtrAccess() {}
    public static Object read(Object target, String field) {
        try { return field(target.getClass(), field).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("MTR API mismatch: " + field, e); }
    }
    public static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    public static void invoke(Object target, String name) {
        try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("MTR API mismatch: " + name, e); }
    }
    @SuppressWarnings("unchecked")
    public static List<Simulator> simulators() throws ReflectiveOperationException {
        Field mainField = Class.forName("org.mtr.MTR").getDeclaredField("main");
        mainField.setAccessible(true);
        Object main = mainField.get(null);
        return main == null ? List.of() : new ArrayList<>((Collection<Simulator>) read(main, "simulators"));
    }
    public static void verify() throws ReflectiveOperationException {
        field(Siding.class, "pathSidingToMainRoute"); field(Siding.class, "pathMainRoute"); field(Siding.class, "pathMainRouteToSiding");
        field(Vehicle.class, "railProgress"); field(Vehicle.class, "speed");
        Siding.class.getDeclaredMethod("generatePathDistancesAndTimeSegments");
        VehicleExtraData.class.getDeclaredMethod("openDoors"); VehicleExtraData.class.getDeclaredMethod("closeDoors");
    }
    /** Keep integrated worlds separate even if their dimension and MTR IDs coincide. */
    public static Path integratedStateRoot(Simulator simulator) {
        Path depotsPath = (Path) read(read(simulator, "fileLoaderDepots"), "path");
        return depotsPath.toAbsolutePath().getParent().resolve("taxilink-state");
    }
    /** Per-order copies cap automatic speed and braking lookahead without modifying shared physical rails. */
    public static void capPaths(Siding siding, double speedKmh) {
        double cap = speedKmh / 3600D;
        for (String name : List.of("pathSidingToMainRoute", "pathMainRoute", "pathMainRouteToSiding")) {
            @SuppressWarnings("unchecked") List<PathData> paths = (List<PathData>) read(siding, name);
            paths.replaceAll(p -> new LimitedPath(p, cap));
        }
    }
    private static final class LimitedPath extends PathData {
        private final double cap;
        LimitedPath(PathData p, double cap) { super(p, p.getStartDistance(), p.getEndDistance()); this.cap = cap; }
        @Override public double getSpeedLimitMetersPerMillisecond() { return Math.min(super.getSpeedLimitMetersPerMillisecond(), cap); }
        @Override public long getSpeedLimitKilometersPerHour() { return Math.min(super.getSpeedLimitKilometersPerHour(), (long) (cap * 3600)); }
    }
}
