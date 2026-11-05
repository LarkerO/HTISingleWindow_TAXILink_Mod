package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketLinkTest {
    @Test void authenticatesSubscribesAndReassemblesFragmentedCommands() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Config config = new Config(); config.serverId = "test-server";
            config.endpoint = "ws://localhost:" + server.getLocalPort() + "/taxilink";
            config.allowInsecureLocalhost = true;
            config.tokenEnvironment = System.getenv().entrySet().stream().filter(e -> !e.getValue().isBlank() && !e.getValue().contains("\n") && !e.getValue().contains("\r")).findFirst().orElseThrow().getKey();
            CompletableFuture<String> received = new CompletableFuture<>();
            Future<?> remote = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                    ByteArrayOutputStream headers = new ByteArrayOutputStream();
                    while (!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) { int b = in.read(); if (b < 0) throw new EOFException(); headers.write(b); }
                    String text = headers.toString(StandardCharsets.US_ASCII);
                    Map<String, String> fields = new HashMap<>();
                    for (String line : text.split("\r\n")) { int colon = line.indexOf(':'); if (colon > 0) fields.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()); }
                    assertTrue(text.startsWith("GET /taxilink HTTP/1.1"));
                    assertEquals("Bearer " + config.token(), fields.get("authorization"));
                    assertEquals("test-server", fields.get("x-taxilink-server"));
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((fields.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                    var subscription = Json.object(readClientText(in));
                    assertEquals("subscribe", subscription.get("type").getAsString());
                    assertEquals("taxilink.commands.test-server", subscription.getAsJsonArray("topics").get(0).getAsString());
                    send(out, 0x01, "{\"type\":\"taxi.status\",");
                    send(out, 0x80, "\"requestId\":\"fragmented\"}");
                    received.get(10, TimeUnit.SECONDS);
                } catch (Exception e) { throw new CompletionException(e); }
            });
            WebSocketLink link = new WebSocketLink(config, object -> received.complete(object.get("requestId").getAsString()));
            try { config.validate(); link.tick(); assertEquals("fragmented", received.get(15, TimeUnit.SECONDS)); remote.get(15, TimeUnit.SECONDS); }
            finally { link.close(); }
        }
    }
    private static String readClientText(InputStream in) throws IOException {
        assertEquals(0x81, in.read()); int sizeByte = in.read(); assertTrue((sizeByte & 0x80) != 0);
        int size = sizeByte & 0x7f;
        if (size == 126) size = (in.read() << 8) | in.read();
        if (size == 127) throw new IOException("Unexpected large test frame");
        byte[] mask = in.readNBytes(4), body = in.readNBytes(size);
        for (int i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
        return new String(body, StandardCharsets.UTF_8);
    }
    private static void send(OutputStream out, int opcode, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); out.write(opcode); out.write(bytes.length); out.write(bytes); out.flush();
    }
}
