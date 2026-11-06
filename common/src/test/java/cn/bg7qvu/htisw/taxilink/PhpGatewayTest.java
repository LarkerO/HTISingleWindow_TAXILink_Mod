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
        String openssl = System.getenv().getOrDefault("TAXILINK_TEST_OPENSSL", "openssl");
        Path mod = temp.resolve("mod-private.pem"), modPublic = temp.resolve("mod-public.pem");
        Path gateway = temp.resolve("gateway-private.pem"), gatewayPublic = temp.resolve("gateway-public.pem");
        keyPair(openssl, mod, modPublic);
        keyPair(openssl, gateway, gatewayPublic);
        int wsPort = port(), httpPort = port();
        Path root = Path.of("..").toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder(php, root.resolve("examples/php/start.php").toString(), "start");
        builder.environment().put("TAXILINK_SERVER_ID", "gateway-test");
        builder.environment().put("TAXILINK_ALLOW_LEGACY_TRANSPORT", "0");
        builder.environment().put("TAXILINK_OPENSSL", openssl);
        builder.environment().put("TAXILINK_GATEWAY_PRIVATE_KEY", gateway.toString());
        builder.environment().put("TAXILINK_MOD_PUBLIC_KEY", modPublic.toString());
        builder.environment().put("TAXILINK_BUSINESS_PUBLIC_KEY", modPublic.toString());
        builder.environment().put("TAXILINK_DEBUG_TOKEN", "local-debug-integration-only");
        builder.environment().put("TAXILINK_WS_LISTEN", "websocket://127.0.0.1:" + wsPort);
        builder.environment().put("TAXILINK_HTTP_LISTEN", "http://127.0.0.1:" + httpPort);
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("gateway.log").toFile());
        Process server = builder.start();
        Path configPath = temp.resolve("config/taxilink.json");
        Json.write(configPath, data("enabled", true, "serverId", "gateway-test",
            "endpoint", "ws://127.0.0.1:" + wsPort + "/taxilink", "allowInsecureLocalhost", true,
            "opensslExecutable", openssl, "sm2PrivateKeyFile", mod.toString(), "sm2GatewayPublicKeyFile", gatewayPublic.toString()));
        Config cfg = Config.load(configPath);
        Sm2Wire sender = cfg.sm2Wire();
        Sm2Wire receiver = new Sm2Wire(openssl, gateway, modPublic);
        String proof = sender.sign("mod", "{}");
        assertThrows(IllegalArgumentException.class, () -> receiver.verify("gateway", proof));
        assertEquals("{}", receiver.verify("mod", proof));
        assertThrows(IllegalArgumentException.class, () -> receiver.verify("mod", proof));
        AtomicReference<WebSocketLink> reference = new AtomicReference<>();
        Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, temp.resolve("mtr"), false);
        Depot depot = new Depot(TransportMode.TRAIN, sim); depot.setName("PHP联调车厂"); sim.depots.add(depot);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             ExecutorService simulation = Executors.newSingleThreadExecutor()) {
            WebSocketLink link = new WebSocketLink(cfg, request -> {
                String type = request.get("type").getAsString();
                if (type.equals("subscribed")) return;
                JsonObject result = switch (type) {
                    case "taxi.quote" -> data("distanceMeters", 2000, "estimatedLowSpeedSeconds", 120, "estimatedSeconds", 250);
                    case "taxi.create" -> data("phase", "GENERATING");
                    default -> MtrCatalog.query(sim, type, request.getAsJsonObject("params"), cfg, id -> null);
                };
                simulation.submit(() -> reference.get().send(data("type", "response", "requestId", request.get("requestId").getAsString(), "ok", true,
                    "data", result)));
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
                assertTrue(message.getAsJsonObject("data").get("nextOffset").isJsonNull(),
                    "The final page must preserve nextOffset:null over WebSocket and HTTP");
                assertEquals("PHP联调车厂", message.getAsJsonObject("data").getAsJsonArray("items").get(0).getAsJsonObject().get("name").getAsString());
                assertEquals(400, post(http, base, "{\"type\":\"not-a-command\"}").statusCode());
                var quote = post(http, base, "{\"type\":\"taxi.quote\",\"params\":{\"orderId\":\"test\"}}");
                assertEquals(200, quote.statusCode(), quote.body());
                JsonObject quoteData = object(quote.body()).getAsJsonObject("data");
                assertTrue(quoteData.has("fare"));
                String quoteId = quoteData.get("quoteId").getAsString();
                assertEquals(409, post(http, base, data("type", "taxi.create", "params", data("orderId", "changed", "quoteId", quoteId)).toString()).statusCode());
                String signed = sender.sign("frontend", data("type", "taxi.create", "requestId", "business-confirm",
                    "params", data("orderId", "test", "quoteId", quoteId)).toString());
                var businessRequest = HttpRequest.newBuilder(base.resolve("/signed-command")).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(signed)).build();
                var confirmed = http.send(businessRequest, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, confirmed.statusCode(), confirmed.body());
                assertEquals("business-confirm", object(sender.verify("backend", confirmed.body())).get("requestId").getAsString());
                assertEquals(401, http.send(businessRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
                var events = http.send(auth(base.resolve("/events")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(object(events.body()).getAsJsonArray("events").size() >= 2);
                link.close(); awaitHealth(http, base, false, server);
            } finally { link.close(); }
        } finally {
            server.descendants().forEach(ProcessHandle::destroy); server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
    }
    private static void keyPair(String openssl, Path privateKey, Path publicKey) throws Exception {
        assertEquals(0, new ProcessBuilder(openssl, "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:SM2",
            "-out", privateKey.toString()).start().waitFor());
        assertEquals(0, new ProcessBuilder(openssl, "pkey", "-in", privateKey.toString(), "-pubout",
            "-out", publicKey.toString()).start().waitFor());
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
