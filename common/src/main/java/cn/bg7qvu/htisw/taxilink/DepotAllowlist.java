package cn.bg7qvu.htisw.taxilink;

import org.mtr.libraries.com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Instance-wide allowlist with atomic snapshots for all MTR dimensions. */
final class DepotAllowlist {
    static final String FILE_NAME = "taxilink-depots.json";
    private final Path path;
    private volatile Map<String, List<String>> snapshot;

    DepotAllowlist(Path path, Map<String, List<String>> legacy) throws IOException {
        this.path = path;
        if (Files.notExists(path)) {
            JsonObject initial = legacy == null ? new JsonObject() : Json.GSON.toJsonTree(legacy).getAsJsonObject();
            Map<String, List<String>> validated = parse(initial);
            Json.write(path, initial);
            snapshot = validated;
        } else {
            reload();
        }
    }

    List<String> ids(String dimension) {
        return snapshot.getOrDefault(dimension, List.of());
    }

    synchronized int reload() throws IOException {
        Map<String, List<String>> loaded = read();
        snapshot = loaded;
        return loaded.values().stream().mapToInt(List::size).sum();
    }

    synchronized void set(String dimension, long depotId, boolean allowed) throws IOException {
        Map<String, List<String>> next = new LinkedHashMap<>(read());
        Set<String> ids = new LinkedHashSet<>(next.getOrDefault(dimension, List.of()));
        if (allowed) ids.add(Long.toString(depotId));
        else ids.remove(Long.toString(depotId));
        next.put(dimension, List.copyOf(ids));
        Json.write(path, Json.GSON.toJsonTree(next).getAsJsonObject());
        snapshot = Map.copyOf(next);
    }

    private Map<String, List<String>> read() throws IOException {
        try {
            return parse(Json.object(Files.readString(path)));
        } catch (RuntimeException e) {
            throw new IOException("Invalid TAXILink depot allowlist: " + path, e);
        }
    }

    private static Map<String, List<String>> parse(JsonObject root) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+/[a-z0-9_./-]+") || !entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("Expected an MTR dimension mapped to an array of depot IDs");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement value : entry.getValue().getAsJsonArray()) {
                JsonObject id = new JsonObject();
                id.add("depotId", value);
                ids.add(Long.toString(Json.id(id, "depotId")));
            }
            result.put(entry.getKey(), List.copyOf(ids));
        }
        return Map.copyOf(result);
    }
}
