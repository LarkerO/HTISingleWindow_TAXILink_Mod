package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;

public class PlayerPositionTest {
    public static class Server implements Executor {
        public List<Player> online = new ArrayList<>();
        public Server getPlayerList() { return this; }
        public List<Player> getPlayers() { return online; }
        public void execute(Runnable task) { task.run(); }
    }
    public static class Profile {
        public UUID getId() { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
        public String getName() { return "PlayerOne"; }
    }
    public static class World {
        public World dimension() { return this; }
        public String location() { return "minecraft:overworld"; }
    }
    public static class Player {
        public Profile getGameProfile() { return new Profile(); }
        public World serverLevel() { return new World(); }
        public double getX() { return 123; }
        public double getY() { return 64; }
        public double getZ() { return -456; }
    }
    @Test void onlineSnapshotExcludesOtherWorldsAndOfflinePlayers() throws Exception {
        try {
            Server server = new Server(); server.online.add(new Player());
            PlayerNames.server = server; PlayerNames.refresh();
            var result = PlayerNames.position("PlayerOne", "minecraft/overworld");
            assertTrue(result.get("online").getAsBoolean());
            assertEquals(123, result.getAsJsonObject("position").get("x").getAsDouble());
            assertFalse(PlayerNames.position("PlayerOne", "minecraft/the_nether").get("online").getAsBoolean());
            assertFalse(PlayerNames.position("OtherPlayer", "minecraft/overworld").get("online").getAsBoolean());
            var refresh = PlayerNames.class.getDeclaredField("lastRefresh"); refresh.setAccessible(true); refresh.setLong(null, 0);
            server.online.clear(); PlayerNames.refresh();
            assertFalse(PlayerNames.position("PlayerOne", "minecraft/overworld").get("online").getAsBoolean());
        } finally { PlayerNames.clear(); }
    }
}
