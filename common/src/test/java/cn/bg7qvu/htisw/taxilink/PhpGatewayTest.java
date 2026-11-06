package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real PHP/Workerman integration; composer install in examples/php first. */
@EnabledIfEnvironmentVariable(named = "TAXILINK_TEST_PHP", matches = ".+")
class PhpGatewayTest {
    @TempDir Path temp;
    @Test void httpCommandReachesAuthenticatedModAndReturnsRealMtrCatalog() throws Exception {
        String php = System.getenv("TAXILINK_TEST_PHP");
        String token = System.getenv("TAXILINK_TEST_MOD_TOKEN");
        assertNotNull(token, "Set TAXILINK_TEST_MOD_TOKEN to a throwaway test token");
        int wsPort = port(), httpPort = port();
        Path root = Path.of("..").toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder(php, root.resolve("examples/php/start.php").toString(), "start");
        builder.environment().put("TAXILINK_SERVER_ID", "gateway-test");
        builder.environment().put("TAXILINK_TOKEN", token);
        builder.environment().put("TAXILINK_DEBUG_TOKEN", "local-debug-integration-only");
        builder.environment().put("TAXILINK_WS_LISTEN", "websocket://127.0.0.1:" + wsPort);
        builder.environment().put("TAXILINK_HTTP_LISTEN", "http://127.0.0.1:" + httpPort);
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("gateway.log").toFile());
        Process server = builder.start();
        Path configPath = temp.resolve("config/taxilink.json");
        Json.write(configPath, data("enabled", true, "serverId", "gateway-test",
            "endpoint", "ws://127.0.0.1:" + wsPort + "/taxilink", "allowInsecureLocalhost", true,
            "tokenEnvironment", "TAXILINK_TEST_UNUSED_ENV", "tokenFile", "taxilink.token"));
        Files.writeString(configPath.resolveSibling("taxilink.token"), token + System.lineSeparator());
        Config cfg = Config.load(configPath);
        AtomicReference<WebSocketLink> reference = new AtomicReference<>();
        Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, temp.resolve("mtr"), false);
        Depot depot = new Depot(TransportMode.TRAIN, sim); depot.setName("PHP联调车厂"); sim.depots.add(depot);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             ExecutorService simulation = Executors.newSingleThreadExecutor()) {
            WebSocketLink link = new WebSocketLink(cfg, request -> {
                if (request.get("type").getAsString().equals("subscribed")) return;
                simulation.submit(() -> reference.get().send(data("type", "response", "requestId", request.get("requestId").getAsString(), "ok", true,
                    "data", MtrCatalog.query(sim, request.get("type").getAsString(), request.getAsJsonObject("params"), cfg, id -> null))));
            });
            reference.set(link);
            URI base = URI.create("http://127.0.0.1:" + httpPort);
            try {
                awaitHealth(http, base, false, server);
                var page = http.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode());
                assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
                assertTrue(page.body().contains("TAXILink 调试台"));
                assertFalse(page.body().contains("local-debug-integration-only"));
                assertEquals(401, http.send(HttpRequest.newBuilder(base.resolve("/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(503, post(http, base, "{\"type\":\"mtr.depots\",\"params\":{}}").statusCode());
                link.tick(); awaitHealth(http, base, true, server);
                var response = post(http, base, "{\"type\":\"mtr.depots\",\"params\":{\"limit\":2}}");
                assertEquals(200, response.statusCode(), response.body());
                JsonObject message = object(response.body());
                assertTrue(message.get("ok").getAsBoolean());
                assertEquals("PHP联调车厂", message.getAsJsonObject("data").getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString());
                assertEquals(400, post(http, base, "{\"type\":\"not-a-command\"}").statusCode());
                var events = http.send(auth(base.resolve("/events")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(object(events.body()).getAsJsonArray("events").size() >= 2);
                link.close(); awaitHealth(http, base, false, server);
            } finally { link.close(); }
        } finally {
            server.descendants().forEach(ProcessHandle::destroy); server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
    }
    private static int port() throws Exception { try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static HttpRequest.Builder auth(URI uri) { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer local-debug-integration-only"); }
    private static HttpResponse<String> post(HttpClient http, URI base, String text) throws Exception {
        return http.send(auth(base.resolve("/command")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(text)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void awaitHealth(HttpClient http, URI base, boolean connected, Process server) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            assertTrue(server.isAlive(), "PHP gateway exited; inspect test gateway.log");
            try {
                var response = http.send(auth(base.resolve("/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && object(response.body()).get("connected").getAsBoolean() == connected) return;
            } catch (java.io.IOException ignored) {}
            Thread.sleep(50);
        }
        fail("Gateway health did not become connected=" + connected);
    }
}
