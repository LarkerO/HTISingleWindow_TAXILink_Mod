package cn.bg7qvu.htisw.taxilink;

import org.mtr.libraries.com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static cn.bg7qvu.htisw.taxilink.Json.*;

final class Sm2Wire {
    private static final String ID = "TAXILink-v2";
    private final String openssl;
    private final Path privateKey, publicKey;
    private final Map<String, Long> nonces = new HashMap<>();

    Sm2Wire(String openssl, Path privateKey, Path publicKey) {
        this.openssl = openssl;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        if (!Files.isRegularFile(privateKey) || !Files.isRegularFile(publicKey))
            throw new IllegalArgumentException("Missing local SM2 private key or pinned gateway public key");
        sign("mod", "{}");
    }

    String sign(String role, String json) {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String body = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        byte[] signature = crypt(canonical(role, timestamp, nonce, body), null);
        return data("ts", timestamp, "nonce", nonce, "body", body, "sig", Base64.getEncoder().encodeToString(signature)).toString();
    }

    synchronized String verify(String role, String wire) {
        JsonObject envelope = object(wire);
        long timestamp = envelope.get("ts").getAsLong();
        long now = System.currentTimeMillis();
        String nonce = envelope.get("nonce").getAsString();
        String body = envelope.get("body").getAsString();
        byte[] signature = Base64.getDecoder().decode(envelope.get("sig").getAsString());
        nonces.values().removeIf(expiry -> expiry < now);
        if (timestamp < now - 30_000 || timestamp > now + 30_000 || !nonce.matches("[0-9a-f]{32}")
            || signature.length > 80 || nonces.containsKey(nonce) || nonces.size() >= 10_000)
            throw new IllegalArgumentException("Invalid or replayed SM2 envelope");
        crypt(canonical(role, timestamp, nonce, body), signature);
        nonces.put(nonce, timestamp + 30_000);
        return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
    }

    private static String canonical(String role, long ts, String nonce, String body) {
        return ID + "\n" + role + "\n" + ts + "\n" + nonce + "\n" + body;
    }

    private byte[] crypt(String message, byte[] signature) {
        Path input = null, sig = null;
        Process process = null;
        try {
            input = Files.createTempFile("taxilink-sm2-", ".msg");
            sig = Files.createTempFile("taxilink-sm2-", ".sig");
            Files.writeString(input, message, StandardCharsets.US_ASCII);
            List<String> args = new ArrayList<>(List.of(openssl, "dgst", "-sm3", "-sigopt", "distid:" + ID));
            if (signature == null) {
                args.addAll(List.of("-sign", privateKey.toString(), "-passin", "pass:", "-out", sig.toString()));
            } else {
                Files.write(sig, signature);
                args.addAll(List.of("-verify", publicKey.toString(), "-signature", sig.toString()));
            }
            args.add(input.toString());
            process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalArgumentException("SM2 signing or verification failed");
            if (signature != null) return new byte[0];
            byte[] signed = Files.readAllBytes(sig);
            if (signed.length == 0 || signed.length > 80) throw new IllegalArgumentException("Invalid SM2 signature size");
            return signed;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run OpenSSL for SM2", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SM2 operation interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            cleanup(input); cleanup(sig);
        }
    }

    private static void cleanup(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
