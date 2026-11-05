package cn.bg7qvu.htisw.taxilink;

import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.LongFunction;
import static cn.bg7qvu.htisw.taxilink.Json.*;

/** Read-only map discovery, always called on the owning simulator thread. */
public final class MtrCatalog {
    public static final Set<String> COMMANDS = Set.of("mtr.depots", "mtr.sidings", "mtr.routes", "mtr.stations", "mtr.platforms");
    private MtrCatalog() {}
    public static JsonObject query(Simulator sim, String type, JsonObject params, Config config, LongFunction<String> owner) {
        List<JsonObject> items = new ArrayList<>();
        switch (type) {
            case "mtr.depots" -> sim.depots.forEach(d -> {
                JsonObject item = named(d);
                item.add("position", position(d.getCenter()));
                item.addProperty("sidingCount", d.savedRails.size());
                item.addProperty("allowedForTaxi", config.allows(sim.dimension, d.getId()));
                item.add("routeIds", ids(d.getRouteIds()));
                items.add(item);
            });
            case "mtr.sidings" -> {
                Long depotId = params.has("depotId") ? id(params, "depotId") : null;
                sim.sidings.forEach(s -> {
                    if (depotId != null && (s.area == null || s.area.getId() != depotId)) return;
                    JsonObject item = named(s);
                    item.addProperty("depotId", s.area == null ? null : Long.toString(s.area.getId()));
                    item.add("position", position(s.getMidPosition()));
                    item.addProperty("railLength", s.getRailLength());
                    item.addProperty("accelerationMps2", s.getAcceleration() * 1_000_000D);
                    item.addProperty("decelerationMps2", s.getDeceleration() * 1_000_000D);
                    item.add("vehicleCars", org.mtr.core.tool.Utilities.getJsonObjectFromData(s).get("vehicleCars"));
                    items.add(item);
                });
            }
            case "mtr.routes" -> sim.routes.forEach(r -> {
                JsonObject item = named(r);
                item.addProperty("routeNumber", r.getRouteNumber()); item.addProperty("hidden", r.getHidden());
                item.addProperty("routeType", r.getRouteTypeKey());
                JsonArray platforms = new JsonArray();
                r.getRoutePlatforms().forEach(p -> {
                    // Preserve route order and unresolved IDs, rather than silently dropping a stop.
                    JsonObject stored = org.mtr.core.tool.Utilities.getJsonObjectFromData(p);
                    platforms.add(Long.toString(stored.get("platformId").getAsLong()));
                });
                item.add("platformIds", platforms);
                String orderId = owner.apply(r.getId());
                item.addProperty("taxilinkManaged", orderId != null); item.addProperty("orderId", orderId);
                items.add(item);
            });
            case "mtr.stations" -> sim.stations.forEach(s -> {
                JsonObject item = named(s);
                item.add("position", position(s.getCenter())); item.addProperty("platformCount", s.savedRails.size());
                items.add(item);
            });
            case "mtr.platforms" -> {
                Long stationId = params.has("stationId") ? id(params, "stationId") : null;
                sim.platforms.forEach(p -> {
                    if (stationId != null && (p.area == null || p.area.getId() != stationId)) return;
                    JsonObject item = named(p);
                    item.addProperty("stationId", p.area == null ? null : Long.toString(p.area.getId()));
                    item.addProperty("stationName", p.area == null ? null : p.area.getName());
                    item.add("position", position(p.getMidPosition())); item.addProperty("dwellTimeMillis", p.getDwellTime());
                    items.add(item);
                });
            }
            default -> throw new Fault("UNKNOWN_COMMAND", type);
        }
        // Stable order, including negative MTR IDs, makes complete pagination deterministic.
        items.sort(Comparator.comparingLong(o -> Long.parseLong(o.get("id").getAsString())));
        String revision = revision(items);
        if (params.has("revision") && !revision.equals(string(params, "revision"))) throw new Fault("CATALOG_CHANGED", "Map catalog changed; restart at offset 0");
        int offset = integer(params, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = integer(params, "limit", 100, 1, 500);
        if (offset > items.size()) throw new Fault("INVALID_REQUEST", "offset exceeds catalog total");
        JsonArray page = new JsonArray();
        int next = offset;
        // Avoid unbounded replies even on large maps. The PHP gateway accepts up to 1 MiB.
        int bytes = 0;
        while (next < items.size() && page.size() < limit) {
            JsonObject item = items.get(next);
            int size = item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (size > 48 * 1024) throw new Fault("CATALOG_ITEM_TOO_LARGE", "One catalog entry exceeds 48 KiB");
            if (bytes + size > 48 * 1024) break;
            page.add(item); bytes += size; next++;
        }
        return data("dimension", sim.dimension, "kind", type.substring(4), "revision", revision,
            "offset", offset, "limit", limit, "total", items.size(), "nextOffset", next < items.size() ? next : null,
            "sampledAt", System.currentTimeMillis(), "items", page);
    }
    public static int integer(JsonObject params, String key, int fallback, int min, int max) {
        if (!params.has(key)) return fallback;
        double n = number(params, key, min, max);
        if (n != Math.rint(n)) throw new Fault("INVALID_REQUEST", key + " must be an integer");
        return (int) n;
    }
    private static JsonObject named(NameColorDataBase value) {
        return data("id", Long.toString(value.getId()), "name", value.getName(), "color", value.getColorHex(), "transportMode", value.getTransportMode() == null ? null : value.getTransportMode().name());
    }
    private static JsonObject position(Position p) { return data("x", p.getX(), "y", p.getY(), "z", p.getZ()); }
    private static JsonArray ids(Iterable<Long> values) { JsonArray ids = new JsonArray(); values.forEach(id -> ids.add(Long.toString(id))); return ids; }
    private static String revision(List<JsonObject> items) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (JsonObject item : items) { hash.update(item.toString().getBytes(StandardCharsets.UTF_8)); hash.update((byte) '\n'); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
