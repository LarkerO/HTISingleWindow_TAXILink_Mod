package cn.bg7qvu.htisw.taxilink;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

final class DepotReloadCommand {
    private DepotReloadCommand() {}

    @SuppressWarnings("unchecked")
    static void register(Object dispatcher, Supplier<TaxiRuntime> runtime) {
        ((CommandDispatcher<Object>) dispatcher).register(LiteralArgumentBuilder.<Object>literal("taxilink")
            .requires(DepotReloadCommand::hasPermission)
            .then(LiteralArgumentBuilder.<Object>literal("reload-depots").executes(context -> {
                TaxiRuntime active = runtime.get();
                if (active == null) throw failure("TAXILink is not enabled.");
                try {
                    int count = active.reloadDepots();
                    feedback(context.getSource(), "TAXILink reloaded " + count + " depot whitelist entries.");
                    return 1;
                } catch (IOException e) {
                    TaxiLink.LOG.log(System.Logger.Level.ERROR, "Cannot reload TAXILink depot allowlist", e);
                    throw failure("Cannot reload world TAXILink depot files. Previous whitelist retained.");
                } catch (ReflectiveOperationException e) {
                    TaxiLink.LOG.log(System.Logger.Level.ERROR, "Cannot send TAXILink command feedback", e);
                    return 1;
                }
            })));
    }

    private static com.mojang.brigadier.exceptions.CommandSyntaxException failure(String message) {
        return new SimpleCommandExceptionType(new LiteralMessage(message)).create();
    }

    private static boolean hasPermission(Object source) {
        try {
            return (boolean) method(source, "hasPermission", "method_9259", int.class).invoke(source, 2);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void feedback(Object source, String text) throws ReflectiveOperationException {
        Method send = method(source, "sendSuccess", "method_9226", Supplier.class, boolean.class);
        boolean intermediary = send.getName().equals("method_9226");
        Class<?> component = Class.forName(intermediary ? "net.minecraft.class_2561" : "net.minecraft.network.chat.Component");
        Object message = component.getMethod(intermediary ? "method_43470" : "literal", String.class).invoke(null, text);
        send.invoke(source, (Supplier<Object>) () -> message, false);
    }

    private static Method method(Object source, String named, String intermediary, Class<?>... parameters) throws NoSuchMethodException {
        try {
            return source.getClass().getMethod(named, parameters);
        } catch (NoSuchMethodException e) {
            return source.getClass().getMethod(intermediary, parameters);
        }
    }
}
