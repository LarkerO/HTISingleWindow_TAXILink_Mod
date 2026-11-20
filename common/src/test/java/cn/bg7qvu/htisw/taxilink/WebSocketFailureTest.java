package cn.bg7qvu.htisw.taxilink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class WebSocketFailureTest {
    @TempDir Path temp;
    @Test void silentServerDoesNotBlockTicksOrShutdown() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Config config = new Config(); config.allowLegacyTransport = true;
            Path token = temp.resolve("token"); Files.writeString(token,"local-test-only"); config.tokenFile = token.toString();
            config.endpoint = "ws://127.0.0.1:" + server.getLocalPort();
            WebSocketLink link = new WebSocketLink(config, ignored -> {});
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> { for(int i=0;i<100;i++) link.tick(); });
                assertTimeoutPreemptively(Duration.ofSeconds(2), link::close);
            } finally { link.close(); }
        }
    }
    @Test void malformedEndpointDoesNotLatchConnecting() throws Exception {
        Config config = new Config(); config.allowLegacyTransport=true;
        Path token=temp.resolve("token"); Files.writeString(token,"local-test-only"); config.tokenFile=token.toString();
        config.endpoint="invalid URI";
        try(WebSocketLink link=new WebSocketLink(config, ignored->{})) {
            assertDoesNotThrow(link::tick);
            var field=WebSocketLink.class.getDeclaredField("connecting");field.setAccessible(true);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)field.get(link)).get());
        }
    }
}
