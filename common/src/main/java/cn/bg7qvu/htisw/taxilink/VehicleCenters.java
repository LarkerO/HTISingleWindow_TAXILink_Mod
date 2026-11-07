package cn.bg7qvu.htisw.taxilink;

import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static cn.bg7qvu.htisw.taxilink.Json.*;

/** One file per world/dimension; all access is confined to its simulator thread. */
final class VehicleCenters {
    private final Path file;
    private JsonObject centers;
    VehicleCenters(Path root) {
        file = root.resolve("taxilink-centers.json");
    }
    void load() {
        try {
            if (Files.notExists(file)) Json.write(file, new JsonObject());
            JsonObject next = Json.object(Files.readString(file));
            for (var e : next.entrySet()) validate(e.getKey(), e.getValue().getAsJsonObject());
            checkSize(next);
            centers = next;
        } catch (Exception e) { throw new Fault("CENTERS_IO_ERROR", "Cannot load " + file); }
    }
    JsonObject list() { if (centers == null) load(); return centers.deepCopy(); }
    JsonObject get(String id) {
        if (centers == null) load();
        if (!centers.has(id)) throw new Fault("CENTER_NOT_FOUND", "Unknown vehicle center: " + id);
        return centers.getAsJsonObject(id).deepCopy();
    }
    void put(Simulator sim, Config config, String id, JsonObject center) {
        validate(id, center);
        for (JsonElement element : center.getAsJsonArray("depotIds")) {
            long depotId = Json.id(data("id", element), "id");
            Depot depot = sim.depotIdMap.get(depotId);
            if (!config.allows(sim.dimension, depotId)) throw new Fault("DEPOT_NOT_ALLOWED", "Authorize center depots first");
            if (depot == null || depot.savedRails.size() != 1) throw new Fault("DEDICATED_DEPOT_REQUIRED", "Each center depot must have exactly one siding");
        }
        for (JsonElement element : center.getAsJsonArray("routes")) {
            JsonObject route = element.getAsJsonObject();
            Platform a = sim.platformIdMap.get(Json.id(route, "pickupPlatformId"));
            Platform b = sim.platformIdMap.get(Json.id(route, "dropoffPlatformId"));
            if (a == null || b == null || a == b) throw new Fault("INVALID_ROUTE", "Center route platforms must exist and differ");
        }
        JsonObject next = list(); next.add(id, center.deepCopy()); write(next);
    }
    void remove(String id) { get(id); JsonObject next = list(); next.remove(id); write(next); }
    private void write(JsonObject next) {
        checkSize(next);
        try { Json.write(file, next); centers = next; }
        catch (IOException e) { throw new Fault("CENTERS_IO_ERROR", "Cannot save " + file); }
    }
    private static void checkSize(JsonObject next) {
        if (next.toString().getBytes(StandardCharsets.UTF_8).length > 32 * 1024)
            throw new Fault("CENTER_LIMIT", "Vehicle center definitions must fit in 32 KiB per dimension");
    }
    String revision(String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(get(id).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void validate(String id, JsonObject c) {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new Fault("INVALID_REQUEST", "Invalid centerId");
        string(c, "name");
        if (!c.has("depotIds") || !c.get("depotIds").isJsonArray() || c.getAsJsonArray("depotIds").isEmpty()
            || c.getAsJsonArray("depotIds").size() > 128) throw new Fault("INVALID_REQUEST", "Center requires 1..128 depots");
        Set<Long> depots = new HashSet<>();
        for (JsonElement e : c.getAsJsonArray("depotIds")) if (!depots.add(Json.id(data("id", e), "id"))) throw new Fault("INVALID_REQUEST", "Duplicate depot");
        if (!c.has("routes") || !c.get("routes").isJsonArray() || c.getAsJsonArray("routes").size() > 128)
            throw new Fault("INVALID_REQUEST", "routes must be an array of up to 128 routes");
        Set<String> routes = new HashSet<>();
        for (JsonElement e : c.getAsJsonArray("routes")) {
            JsonObject r = e.getAsJsonObject();
            if (!routes.add(string(r, "id"))) throw new Fault("INVALID_REQUEST", "Duplicate center route");
            string(r, "name"); Json.id(r, "pickupPlatformId"); Json.id(r, "dropoffPlatformId");
        }
    }
    JsonObject resolve(Simulator sim, JsonObject p, Long selectedDepot) {
        JsonObject c = get(string(p, "centerId"));
        JsonObject result = p.deepCopy();
        if (p.has("centerRouteId")) {
            String routeId = string(p, "centerRouteId");
            JsonObject route = null;
            for (JsonElement e : c.getAsJsonArray("routes")) if (string(e.getAsJsonObject(), "id").equals(routeId)) route = e.getAsJsonObject();
            if (route == null) throw new Fault("CENTER_ROUTE_NOT_FOUND", "Unknown center route");
            result.add("pickupPlatformId", route.get("pickupPlatformId").deepCopy());
            result.add("dropoffPlatformId", route.get("dropoffPlatformId").deepCopy());
            if (!result.has("routeName")) result.add("routeName", route.get("name").deepCopy());
        }
        long depotId = selectedDepot == null ? Json.id(data("id", c.getAsJsonArray("depotIds").get(0)), "id") : selectedDepot;
        Depot depot = sim.depotIdMap.get(depotId);
        if (depot == null || depot.savedRails.size() != 1) throw new Fault("DEDICATED_DEPOT_REQUIRED", "Center depot must have one siding");
        result.addProperty("depotId", Long.toString(depotId));
        result.addProperty("sidingId", Long.toString(depot.savedRails.iterator().next().getId()));
        return result;
    }
}
