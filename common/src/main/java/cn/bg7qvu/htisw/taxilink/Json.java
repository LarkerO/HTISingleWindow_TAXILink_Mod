package cn.bg7qvu.htisw.taxilink;

import org.mtr.libraries.com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Json() {}
    public static JsonObject object(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    public static String string(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString())
            throw new Fault("INVALID_REQUEST", "Missing string: " + key);
        String value = o.get(key).getAsString();
        if (value.isBlank() || value.length() > 256) throw new Fault("INVALID_REQUEST", "Invalid string: " + key);
        return value;
    }
    public static long id(JsonObject o, String key) {
        String value = string(o, key);
        if (!value.matches("-?[1-9][0-9]{0,18}")) throw new Fault("INVALID_REQUEST", key + " must be a nonzero signed decimal long ID string");
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new Fault("INVALID_REQUEST", "ID overflow: " + key); }
    }
    public static double number(JsonObject o, String key, double min, double max) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isNumber()) throw new Fault("INVALID_REQUEST", "Missing number: " + key);
        double n = o.get(key).getAsDouble();
        if (!Double.isFinite(n) || n < min || n > max) throw new Fault("INVALID_REQUEST", "Out of range: " + key);
        return n;
    }
    public static JsonObject data(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return o;
    }
    public static void write(Path path, JsonObject data) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    public static final class Fault extends RuntimeException {
        public final String code;
        public Fault(String code, String message) { super(message); this.code = code; }
    }
}
