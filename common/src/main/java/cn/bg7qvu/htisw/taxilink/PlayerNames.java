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
    static String get(UUID uuid) { return names.get(uuid); }
    static void clear() { server = null; names.clear(); pending.set(false); lastRefresh = 0; }
    static void refresh() {
        Object current = server;
        if (!(current instanceof Executor executor) || System.currentTimeMillis() - lastRefresh < 1000 || !pending.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                if (current != server) return;
                Object list = call(current, "getPlayerList", "method_3760");
                for (Object player : (List<?>) call(list, "getPlayers", "method_14571")) {
                    Object profile = call(player, "getGameProfile", "method_7334");
                    names.put((UUID) profile.getClass().getMethod("getId").invoke(profile),
                        (String) profile.getClass().getMethod("getName").invoke(profile));
                }
            } catch (ReflectiveOperationException e) {
                TaxiLink.LOG.log(System.Logger.Level.WARNING, "Cannot resolve player profiles", e);
            } finally { lastRefresh = System.currentTimeMillis(); pending.set(false); }
        });
    }
    private static Object call(Object target, String named, String intermediary) throws ReflectiveOperationException {
        Method method;
        try { method = target.getClass().getMethod(named); }
        catch (NoSuchMethodException e) { method = target.getClass().getMethod(intermediary); }
        return method.invoke(target);
    }
}
