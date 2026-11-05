package cn.bg7qvu.htisw.taxilink;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** No Minecraft members are referenced: identical common bytecode on Mojmap NeoForge and Fabric intermediary. */
public final class TaxiLink {
    public static final System.Logger LOG = System.getLogger("TAXILink");
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static TaxiRuntime runtime;
    private TaxiLink() {}
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        try {
            lifecycle("SERVER_STARTED", () -> {
                try {
                    Config config = Config.load(Path.of("config", "taxilink.json"));
                    if (!config.enabled) { LOG.log(System.Logger.Level.INFO, "Disabled; configure config/taxilink.json to enable"); return; }
                    runtime = new TaxiRuntime(config, Path.of("taxilink-state"));
                    runtime.start();
                } catch (Exception e) { LOG.log(System.Logger.Level.ERROR, "TAXILink startup failed", e); }
            });
            lifecycle("SERVER_STOPPING", () -> { if (runtime != null) { runtime.close(); runtime = null; } });
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Architectury 13.0.8 lifecycle API required", e); }
    }
    @SuppressWarnings("unchecked")
    private static void lifecycle(String name, Runnable action) throws ReflectiveOperationException {
        Class<?> events = Class.forName("dev.architectury.event.events.common.LifecycleEvent");
        Field field = events.getField(name);
        Class<?> listener = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> "TAXILink " + name;
            };
            action.run(); return null;
        });
        Class.forName("dev.architectury.event.Event").getMethod("register", Object.class).invoke(field.get(null), proxy);
    }
}
