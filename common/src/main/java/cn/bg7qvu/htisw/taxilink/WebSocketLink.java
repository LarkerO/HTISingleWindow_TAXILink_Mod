package cn.bg7qvu.htisw.taxilink;

import org.mtr.libraries.com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static cn.bg7qvu.htisw.taxilink.Json.data;

/** Outgoing authenticated WebSocket client. No listening port and no Minecraft networking channels. */
public final class WebSocketLink implements AutoCloseable {
    private final Config config;
    private final Consumer<JsonObject> incoming;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile long nextAttempt, lastPong, lastPing;
    private int failures;
    private CompletableFuture<Void> sends = CompletableFuture.completedFuture(null);
    public WebSocketLink(Config config, Consumer<JsonObject> incoming) { this.config = config; this.incoming = incoming; }
    public void tick() {
        if (closed) return;
        long now = System.currentTimeMillis();
        WebSocket active = socket;
        if (active != null) {
            if (now - lastPong > 60000) { disconnected(active); active.abort(); }
            else if (now - lastPing > 20000) {
                lastPing = now;
                active.sendPing(ByteBuffer.wrap(new byte[]{1})).exceptionally(e -> { disconnected(active); active.abort(); return null; });
            }
            return;
        }
        if (now < nextAttempt || !connecting.compareAndSet(false, true)) return;
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + config.token())
            .header("X-TAXILink-Server", config.serverId)
            .buildAsync(URI.create(config.endpoint), new Listener())
            .whenComplete((ws, error) -> {
                connecting.set(false);
                if (error != null) retry();
                else if (closed) ws.abort();
            });
    }
    private synchronized void retry() {
        nextAttempt = System.currentTimeMillis() + Math.min(60000, 1000L << Math.min(failures++, 6)) + ThreadLocalRandom.current().nextInt(500);
    }
    private void disconnected(WebSocket ws) {
        if (socket == ws) { socket = null; retry(); }
    }
    /** Bounded best-effort events. After reconnect the backend must reconcile using taxi.list/status. */
    public synchronized void send(JsonObject object) {
        WebSocket active = socket;
        if (active == null || closed) return;
        object.addProperty("v", 1); object.addProperty("serverId", config.serverId); object.addProperty("timestamp", System.currentTimeMillis());
        if (queued.incrementAndGet() > 256) { queued.decrementAndGet(); disconnected(active); active.abort(); return; }
        String text = object.toString();
        sends = sends.handle((ignored, error) -> null).thenCompose(ignored -> {
            if (socket != active || closed) return CompletableFuture.completedFuture(null);
            return active.sendText(text, true).orTimeout(10, TimeUnit.SECONDS).thenApply(ws -> (Void) null);
        }).whenComplete((ignored, error) -> {
            queued.decrementAndGet();
            if (error != null) { disconnected(active); active.abort(); }
        });
    }
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private int bytes;
        @Override public void onOpen(WebSocket ws) {
            if (closed) { ws.abort(); return; }
            socket = ws; failures = 0; lastPong = lastPing = System.currentTimeMillis();
            send(data("type", "subscribe", "topics", new String[]{"taxilink.commands." + config.serverId},
                "capabilities", new String[]{"taxi.create", "taxi.status", "taxi.list", "taxi.doors", "taxi.destroy", "taxi.passengers", "mtr.dimensions", "mtr.depots", "mtr.sidings", "mtr.routes", "mtr.stations", "mtr.platforms"}));
            ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence fragment, boolean last) {
            bytes += fragment.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > config.maxMessageBytes) { ws.sendClose(1009, "Message too large"); disconnected(ws); return null; }
            text.append(fragment);
            if (last) {
                try { incoming.accept(Json.object(text.toString())); }
                catch (Exception e) { send(data("type", "protocol.error", "error", data("code", "INVALID_JSON", "message", "Expected a JSON object"))); }
                text.setLength(0); bytes = 0;
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) { ws.request(1); return ws.sendPong(message); }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) { lastPong = System.currentTimeMillis(); ws.request(1); return null; }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer message, boolean last) { ws.sendClose(1003, "JSON text only"); disconnected(ws); return null; }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { disconnected(ws); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { disconnected(ws); ws.abort(); }
    }
    @Override public void close() {
        closed = true; WebSocket active = socket; socket = null;
        if (active != null) active.abort();
        client.close();
    }
}
