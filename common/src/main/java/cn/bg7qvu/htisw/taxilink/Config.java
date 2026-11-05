package cn.bg7qvu.htisw.taxilink;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

public final class Config {
    public boolean enabled = false;
    public String endpoint = "wss://example.com/taxilink";
    public String tokenEnvironment = "TAXILINK_TOKEN";
    public String serverId = "taxilink-01";
    public boolean allowInsecureLocalhost = false;
    public int telemetrySeconds = 2;
    public int generationTimeoutSeconds = 60;
    public int maxActiveRides = 64;
    public int maxMessageBytes = 65536;
    public int maxPendingCommands = 128;
    /** dimension (MTR namespace/path) -> explicitly reserved depot IDs */
    public Map<String, List<String>> allowedDepots = new LinkedHashMap<>();
    public String token() {
        String value = System.getenv(tokenEnvironment);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set environment variable " + tokenEnvironment);
        return value;
    }
    public boolean allows(String dimension, long depot) { return allowedDepots.getOrDefault(dimension, List.of()).contains(Long.toString(depot)); }
    public void validate() {
        URI uri = URI.create(endpoint);
        boolean local = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
        if (!("wss".equals(uri.getScheme()) || (allowInsecureLocalhost && local && "ws".equals(uri.getScheme()))) || uri.getUserInfo() != null || uri.getHost() == null)
            throw new IllegalArgumentException("Use wss://, or explicitly enable ws:// on localhost");
        if (!serverId.matches("[A-Za-z0-9_-]{1,64}") || telemetrySeconds < 1 || generationTimeoutSeconds < 5 || generationTimeoutSeconds > 600 || maxActiveRides < 1 || maxActiveRides > 1024 || maxMessageBytes < 1024 || maxMessageBytes > 1048576 || maxPendingCommands < 1 || maxPendingCommands > 4096)
            throw new IllegalArgumentException("Invalid TAXILink limits/serverId");
        token();
    }
    public static Config load(Path path) throws Exception {
        if (!Files.exists(path)) {
            Config config = new Config();
            config.allowedDepots.put("minecraft/overworld", new ArrayList<>());
            Json.write(path, Json.GSON.toJsonTree(config).getAsJsonObject());
            return config;
        }
        Config config = Json.GSON.fromJson(Files.readString(path), Config.class);
        if (config == null || config.allowedDepots == null) throw new IllegalArgumentException("Invalid config");
        if (config.enabled) config.validate();
        return config;
    }
}
