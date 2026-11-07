package cn.bg7qvu.htisw.taxilink;

import cn.bg7qvu.htisw.taxilink.mtr.MtrAccess;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.bg7qvu.htisw.taxilink.Json.*;

public final class TaxiRuntime implements AutoCloseable {
    private final Config config;
    private final Path state;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "TAXILink-transport"); t.setDaemon(true); return t; });
    private final WebSocketLink link;
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;
    private volatile long lastErrorLog;
    private record PendingQuote(JsonObject request, RoutePreview.QuoteJob job, long deadline) {}
    private static final class Context {
        final Simulator simulator;
        final RideService rides;
        final Config config;
        final AtomicBoolean enqueued = new AtomicBoolean();
        final Queue<JsonObject> commands = new ConcurrentLinkedQueue<>();
        final ArrayDeque<PendingQuote> quotes = new ArrayDeque<>();
        long lastTelemetry;
        Context(Simulator simulator, RideService rides, Config config) { this.simulator = simulator; this.rides = rides; this.config = config; }
    }
    public TaxiRuntime(Config config, Path state) { this.config = config; this.state = state; link = new WebSocketLink(config, this::receive); }
    int reloadDepots() throws IOException {
        int count = 0;
        for (Context c : contexts.values()) count += c.config.depotAllowlist.reload();
        return count;
    }
    public void start() throws ReflectiveOperationException {
        MtrAccess.verify();
        timer.scheduleWithFixedDelay(this::pulse, 0, 250, TimeUnit.MILLISECONDS);
        TaxiLink.LOG.log(System.Logger.Level.INFO, "Started TAXILink for " + config.serverId);
    }
    private void pulse() {
        if (closed) return;
        try {
            PlayerNames.refresh();
            for (Simulator sim : MtrAccess.simulators()) {
                Context context = contexts.computeIfAbsent(sim.dimension, d -> {
                    Path stateRoot = state == null ? MtrAccess.integratedStateRoot(sim) : state;
                    try {
                        Config local = config.forWorld(stateRoot);
                        return new Context(sim, new RideService(local, stateRoot, d, link::send), local);
                    } catch (IOException e) { throw new IllegalStateException("Cannot load world TAXILink data", e); }
                });
                if (context.enqueued.compareAndSet(false, true)) sim.run(() -> process(context));
            }
            link.tick();
        } catch (Exception e) {
            if (System.currentTimeMillis() - lastErrorLog > 30000) { lastErrorLog = System.currentTimeMillis(); TaxiLink.LOG.log(System.Logger.Level.ERROR, "Bridge unavailable", e); }
        }
    }
    private void process(Context c) {
        try {
            if (closed) return;
            c.rides.recover(c.simulator);
            for (int i = 0; i < 16; i++) {
                JsonObject request = c.commands.poll(); if (request == null) break;
                pending.decrementAndGet();
                try {
                    JsonObject payload = request.getAsJsonObject("params");
                    if (string(request, "type").equals("taxi.quote")) {
                        if (c.quotes.size() >= 8) throw new Fault("SERVER_BUSY", "Too many pending quotes");
                        c.quotes.add(new PendingQuote(request, RoutePreview.quote(c.simulator, c.config, c.rides.resolveQuote(c.simulator, payload)), System.nanoTime() + TimeUnit.SECONDS.toNanos(12)));
                        continue;
                    }
                    JsonObject result = c.rides.command(c.simulator, string(request, "type"), payload);
                    link.send(data("type", "response", "requestId", request.get("requestId").getAsString(), "ok", true, "data", result));
                } catch (Exception e) { reject(request, e instanceof Fault f ? f.code : "INTERNAL_ERROR", e instanceof Fault ? e.getMessage() : "MTR operation failed; inspect server log"); }
            }
            if (!c.quotes.isEmpty()) {
                PendingQuote quote = c.quotes.removeFirst();
                try {
                    if (System.nanoTime() > quote.deadline()) throw new Fault("QUOTE_TIMEOUT", "Route preview timed out");
                    JsonObject result = quote.job().tick();
                    if (result == null) c.quotes.addLast(quote);
                    else link.send(data("type", "response", "requestId", string(quote.request(), "requestId"), "ok", true, "data", result));
                } catch (Exception e) {
                    reject(quote.request(), e instanceof Fault f ? f.code : "INTERNAL_ERROR", e.getMessage());
                }
            }
            boolean telemetry = System.currentTimeMillis() - c.lastTelemetry >= config.telemetrySeconds * 1000L;
            if (telemetry) c.lastTelemetry = System.currentTimeMillis();
            c.rides.tick(c.simulator, telemetry);
        } catch (Exception e) {
            JsonObject request; while ((request = c.commands.poll()) != null) { pending.decrementAndGet(); reject(request, "RECOVERY_FAILED", "Journal recovery needs operator attention; inspect server log"); }
            if (System.currentTimeMillis() - lastErrorLog > 30000) { lastErrorLog = System.currentTimeMillis(); TaxiLink.LOG.log(System.Logger.Level.ERROR, "Simulator processing failed", e); }
        } finally { c.enqueued.set(false); }
    }
    private void receive(JsonObject request) {
        if (closed) return;
        try {
            String type = string(request, "type");
            if (type.equals("subscribed") || type.equals("pong")) return;
            string(request, "requestId");
            if (!request.has("v") || request.get("v").getAsInt() != 1) throw new Fault("UNSUPPORTED_VERSION", "v must be 1");
            if (!config.serverId.equals(string(request, "serverId"))) throw new Fault("WRONG_SERVER", "serverId mismatch");
            if (type.equals("mtr.dimensions")) {
                List<String> dimensions = contexts.keySet().stream().sorted().toList();
                link.send(data("type", "response", "requestId", request.get("requestId").getAsString(), "ok", true, "data", data("dimensions", dimensions)));
                return;
            }
            if (!Set.of("taxi.centers.list", "taxi.centers.put", "taxi.centers.remove", "taxi.centers.reload", "taxi.logs", "taxi.control", "taxi.quote", "taxi.geometry", "taxi.create", "taxi.status", "taxi.list", "taxi.doors", "taxi.depart", "taxi.destroy", "taxi.passengers").contains(type)
                && !MtrCatalog.COMMANDS.contains(type) && !RideService.DEPOT_COMMANDS.contains(type)) throw new Fault("UNKNOWN_COMMAND", type);
            String dimension = string(request, "dimension");
            Context context = contexts.get(dimension);
            if (context == null) throw new Fault("DIMENSION_NOT_READY", "Unknown or uninitialized MTR dimension");
            if (!request.has("params") || !request.get("params").isJsonObject()) throw new Fault("INVALID_REQUEST", "params must be an object");
            if (pending.incrementAndGet() > config.maxPendingCommands) { pending.decrementAndGet(); throw new Fault("SERVER_BUSY", "Pending command limit reached"); }
            context.commands.add(request);
        } catch (Exception e) { reject(request, e instanceof Fault f ? f.code : "INVALID_REQUEST", e instanceof Fault ? e.getMessage() : "Malformed command envelope"); }
    }
    private void reject(JsonObject request, String code, String message) {
        Object requestId = request.has("requestId") && request.get("requestId").isJsonPrimitive() ? request.get("requestId").getAsString() : null;
        link.send(data("type", "response", "requestId", requestId, "ok", false, "error", data("code", code, "message", message)));
    }
    @Override public void close() {
        closed = true; timer.shutdownNow(); link.close();
        // Keep write-ahead journals. Next start reconciles before accepting any new command.
    }
}
