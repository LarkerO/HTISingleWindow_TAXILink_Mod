package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.mtr.libraries.com.google.gson.JsonObject;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional contract check against the separate platform repository's actual gateway. */
@EnabledIfEnvironmentVariable(named="TAXILINK_PLATFORM_ROOT", matches=".+")
class PlatformGatewayTest {
    @TempDir Path temp;
    private int rpcPort;
    @Test void signedPlatformRpcPreservesQuoteDispatchAndCancellation() throws Exception {
        String openssl = System.getenv().getOrDefault("TAXILINK_TEST_OPENSSL", "openssl");
        String php = System.getenv().getOrDefault("TAXILINK_TEST_PHP", "php");
        Path mod = temp.resolve("mod.pem"), modPublic = temp.resolve("mod.pub");
        Path gateway = temp.resolve("gateway.pem"), gatewayPublic = temp.resolve("gateway.pub");
        for (Path[] pair : new Path[][]{{mod, modPublic}, {gateway, gatewayPublic}}) {
            run(openssl, "genpkey", "-algorithm", "SM2", "-out", pair[0].toString());
            run(openssl, "pkey", "-in", pair[0].toString(), "-pubout", "-out", pair[1].toString());
        }
        int port;
        try (var server = new java.net.ServerSocket(0); var rpc = new java.net.ServerSocket(0)) {
            port = server.getLocalPort(); rpcPort = rpc.getLocalPort();
        }
        Path root = Path.of(System.getenv("TAXILINK_PLATFORM_ROOT"));
        String token = "isolated-platform-contract-test-token";
        var builder = new ProcessBuilder(php, root.resolve("backend/tests/mod-gateway-server.php").toString(), "start");
        builder.environment().putAll(java.util.Map.of(
            "APP_ENV", "test", "DEMO_MODE", "0", "TAXILINK_GATEWAY_TEST", "1",
            "DB_DSN", "sqlite:" + temp.resolve("test.sqlite"), "GATEWAY_TOKEN", token,
            "MOD_LISTEN", "websocket://127.0.0.1:" + port,
            "TAXILINK_SERVER_ID", "contract-test", "TAXILINK_DIMENSION", "minecraft/overworld",
            "TAXILINK_TEST_LOG", temp.resolve("worker.log").toString()));
        builder.environment().put("TAXILINK_OPENSSL", openssl);
        builder.environment().put("GATEWAY_RPC_PORT", Integer.toString(rpcPort));
        builder.environment().put("TAXILINK_GATEWAY_PRIVATE_KEY", gateway.toString());
        builder.environment().put("TAXILINK_MOD_PUBLIC_KEY", modPublic.toString());
        Path log = temp.resolve("gateway.log");
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Path configFile = temp.resolve("config.json");
        Json.write(configFile, data("enabled",true,"serverId","contract-test", "endpoint","ws://127.0.0.1:"+port+"/taxilink",
            "allowInsecureLocalhost",true,"opensslExecutable",openssl,"sm2PrivateKeyFile",mod.toString(),"sm2GatewayPublicKeyFile",gatewayPublic.toString()));
        var fixture = new RideServiceTest(); fixture.temp = temp.resolve("world"); fixture.setup();
        AtomicReference<WebSocketLink> reference = new AtomicReference<>();
        try (HttpClient http = HttpClient.newHttpClient(); ExecutorService simulator = Executors.newSingleThreadExecutor()) {
            WebSocketLink link = new WebSocketLink(Config.load(configFile), request -> {
                if (string(request,"type").equals("subscribed")) return;
                simulator.submit(() -> {
                    try {
                        String type = string(request,"type"); JsonObject params = request.getAsJsonObject("params");
                        JsonObject result;
                        if (type.equals("taxi.quote")) {
                            var job = RoutePreview.quote(fixture.sim, fixture.config, params);
                            result = null;
                            for (int i=0; i<100 && result==null; i++) result = job.tick();
                        } else result = fixture.service.command(fixture.sim,type,params);
                        reference.get().send(data("type","response","requestId",string(request,"requestId"),"ok",true,"data",result));
                    } catch (Exception e) {
                        reference.get().send(data("type","response","requestId",string(request,"requestId"),"ok",false,
                            "error",data("code",e instanceof Fault f ? f.code : "TEST_ERROR","message",e.toString())));
                    }
                });
            });
            reference.set(link);
            try {
                JsonObject params = fixture.request(); params.addProperty("lowSpeedKmh",10);
                HttpResponse<String> response = null;
                for (int i=0; i<80; i++) {
                    assertTrue(process.isAlive(), () -> read(log));
                    link.tick();
                    try { response = rpc(http, token, "taxi.quote", params); if (response.statusCode()==200) break; }
                    catch (java.io.IOException ignored) {}
                    Thread.sleep(100);
                }
                assertNotNull(response, () -> read(log));
                assertEquals(200,response.statusCode(),response.body()+read(log));
                JsonObject quote = object(response.body()).getAsJsonObject("data");
                assertEquals(30,quote.get("distanceMeters").getAsDouble(),.01);
                assertTrue(quote.get("pickupDistanceMeters").getAsDouble()>0);
                assertEquals(401,rpc(http,"wrong-token","taxi.status",data("orderId","test-order")).statusCode());
                JsonObject created = object(rpc(http,token,"taxi.create",fixture.request()).body());
                assertTrue(created.get("ok").getAsBoolean(),created.toString());
                assertEquals(Long.toString(fixture.depot.getId()),created.getAsJsonObject("data").get("depotId").getAsString());
                JsonObject cancelled = object(rpc(http,token,"taxi.destroy",data("orderId","test-order")).body());
                assertEquals("DESTROYED",cancelled.getAsJsonObject("data").get("phase").getAsString());
            } finally { link.close(); }
        } finally {
            var children = process.descendants().toList();
            // Stop the supervisor first so Windows Workerman cannot respawn a killed child.
            process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS);
            children.forEach(ProcessHandle::destroyForcibly);
            for (var child : children) child.onExit().get(5,TimeUnit.SECONDS);
        }
    }
    private HttpResponse<String> rpc(HttpClient client,String token,String type,JsonObject params) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+rpcPort+"/command"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(data("type",type,"dimension","minecraft/overworld","params",params).toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void run(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(p.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,p.exitValue());
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return e.toString(); } }
}
