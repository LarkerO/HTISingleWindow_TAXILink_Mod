package cn.bg7qvu.htisw.taxilink;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Snapshot online profiles on the Minecraft thread, never from an MTR simulation thread. */
final class PlayerNames {
    static volatile Object server;
    private static final Map<UUID, String> names = new ConcurrentHashMap<>();
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static volatile long lastRefresh;
    private static volatile Map<String, org.mtr.libraries.com.google.gson.JsonObject> positions = Map.of();
    static org.mtr.libraries.com.google.gson.JsonObject position(String name, String dimension) {
        var p = positions.get(name);
        if (p == null || System.currentTimeMillis() - lastRefresh > 5000 || !p.get("dimension").getAsString().equals(dimension.replace(':', '/')))
            return Json.data("online", false, "position", null);
        return Json.data("online", true, "position", p.deepCopy(), "sampledAt", lastRefresh);
    }
    static String get(UUID uuid) { return names.get(uuid); }
    static void clear() { server = null; names.clear(); positions = Map.of(); pending.set(false); lastRefresh = 0; }
    static void refresh() {
        Object current = server;
        if (!(current instanceof Executor executor) || System.currentTimeMillis() - lastRefresh < 1000 || !pending.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                if (current != server) return;
                Object list = call(current, "getPlayerList", "method_3760");
                Map<String, org.mtr.libraries.com.google.gson.JsonObject> snapshot = new HashMap<>();
                for (Object player : (List<?>) call(list, "getPlayers", "method_14571")) {
                    Object profile = call(player, "getGameProfile", "method_7334");
                    names.put((UUID) profile.getClass().getMethod("getId").invoke(profile),
                        (String) profile.getClass().getMethod("getName").invoke(profile));
                    try {
                        Object world = callAny(player, "serverLevel", "getServerWorld", "method_51469");
                        Object key = callAny(world, "dimension", "getRegistryKey", "method_27983");
                        String dimension = callAny(key, "location", "getValue", "method_29177").toString().replace(':', '/');
                        snapshot.put((String) profile.getClass().getMethod("getName").invoke(profile), Json.data(
                            "x", call(player, "getX", "method_23317"), "y", call(player, "getY", "method_23318"),
                            "z", call(player, "getZ", "method_23321"), "dimension", dimension));
                    } catch (ReflectiveOperationException ignored) { /* Never expose a coordinate in an unknown world. */ }
                }
                positions = Map.copyOf(snapshot);
            } catch (ReflectiveOperationException e) {
                positions = Map.of();
                TaxiLink.LOG.warn("Cannot resolve player profiles", e);
            } finally { lastRefresh = System.currentTimeMillis(); pending.set(false); }
        });
    }
    private static Object callAny(Object target, String... methods) throws ReflectiveOperationException {
        for (String name : methods) try { return target.getClass().getMethod(name).invoke(target); } catch (NoSuchMethodException ignored) {}
        throw new NoSuchMethodException(Arrays.toString(methods));
    }
    private static Object call(Object target, String named, String intermediary) throws ReflectiveOperationException {
        Method method;
        try { method = target.getClass().getMethod(named); }
        catch (NoSuchMethodException e) { method = target.getClass().getMethod(intermediary); }
        return method.invoke(target);
    }
}
