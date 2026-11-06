package cn.bg7qvu.htisw.taxilink;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.mtr.libraries.com.google.gson.JsonObject;

public final class Config {
    public boolean enabled = false;
    public String endpoint = "wss://example.com/taxilink";
    public String tokenEnvironment = "TAXILINK_TOKEN";
    public String tokenFile;
    public String serverId = "taxilink-01";
    public boolean allowInsecureLocalhost = false;
    public int telemetrySeconds = 2;
    public int generationTimeoutSeconds = 60;
    public int maxActiveRides = 64;
    public int maxMessageBytes = 65536;
    public int maxPendingCommands = 128;
    /** dimension (MTR namespace/path) -> explicitly reserved depot IDs */
    public Map<String, List<String>> allowedDepots = new LinkedHashMap<>();
    private transient Path configDirectory;
    transient DepotAllowlist depotAllowlist;

    public String token() {
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path path = Path.of(tokenFile);
            if (!path.isAbsolute()) {
                if (configDirectory == null) throw new IllegalStateException("Load TAXILink config before using a relative tokenFile");
                path = configDirectory.resolve(path).normalize();
            }
            try {
                String value = Files.readString(path).strip();
                if (!value.matches("[!-~]+")) {
                    throw new IllegalArgumentException("TAXILink tokenFile must contain one nonempty ASCII token: " + path);
                }
                return value;
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read TAXILink tokenFile: " + path, e);
            }
        }
        String value = System.getenv(tokenEnvironment);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set environment variable " + tokenEnvironment);
        return value;
    }
    List<String> allowedDepotIds(String dimension) {
        return depotAllowlist == null ? allowedDepots.getOrDefault(dimension, List.of()) : depotAllowlist.ids(dimension);
    }

    public boolean allows(String dimension, long depot) {
        return allowedDepotIds(dimension).contains(Long.toString(depot));
    }
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
        boolean fresh = Files.notExists(path);
        JsonObject root = fresh ? Json.GSON.toJsonTree(new Config()).getAsJsonObject() : Json.object(Files.readString(path));
        Config config = Json.GSON.fromJson(root, Config.class);
        config.configDirectory = path.toAbsolutePath().getParent();
        config.depotAllowlist = new DepotAllowlist(config.configDirectory.resolve(DepotAllowlist.FILE_NAME), config.allowedDepots);
        if (root.remove("allowedDepots") != null || fresh) Json.write(path, root);
        if (config.enabled) config.validate();
        return config;
    }
}
