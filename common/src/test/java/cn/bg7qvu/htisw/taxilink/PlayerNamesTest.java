package cn.bg7qvu.htisw.taxilink;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;

class PlayerNamesTest {
    public record Profile(UUID id, String name) {
        public UUID getId() { return id; }
        public String getName() { return name; }
    }
    public record NamedPlayer(Profile profile) { public Profile getGameProfile() { return profile; } }
    public record FabricPlayer(Profile profile) { public Profile method_7334() { return profile; } }
    public record NamedList(List<NamedPlayer> players) { public List<NamedPlayer> getPlayers() { return players; } }
    public record FabricList(List<FabricPlayer> players) { public List<FabricPlayer> method_14571() { return players; } }
    public record NamedServer(NamedList players) implements Executor {
        public NamedList getPlayerList() { return players; }
        public void execute(Runnable r) { r.run(); }
    }
    public record FabricServer(FabricList players) implements Executor {
        public FabricList method_3760() { return players; }
        public void execute(Runnable r) { r.run(); }
    }
    @Test void bothLoaderMappingsResolveTheSameProfile() {
        UUID uuid = UUID.randomUUID(); Profile profile = new Profile(uuid, "BG7QVU");
        try {
            PlayerNames.clear(); PlayerNames.server = new NamedServer(new NamedList(List.of(new NamedPlayer(profile))));
            PlayerNames.refresh(); assertEquals("BG7QVU", PlayerNames.get(uuid));
            PlayerNames.clear(); PlayerNames.server = new FabricServer(new FabricList(List.of(new FabricPlayer(profile))));
            PlayerNames.refresh(); assertEquals("BG7QVU", PlayerNames.get(uuid));
        } finally { PlayerNames.clear(); }
        assertNull(PlayerNames.get(uuid));
    }
}
