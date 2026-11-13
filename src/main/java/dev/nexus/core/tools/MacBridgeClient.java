package dev.nexus.core.tools;

import dev.nexus.core.ai.CommentModel;
import dev.nexus.core.connector.HttpBodies;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MacBridgeClient {
    private final boolean enabled;
    private final URI base;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MacBridgeClient(ObjectMapper json, @Value("${nexus.bridge.enabled:false}") boolean enabled,
        @Value("${nexus.bridge.url:}") String url, @Value("${nexus.bridge.token:}") String token) {
        this.json = json; this.enabled = enabled; this.token = token;
        base = url.isBlank() ? null : URI.create(url.replaceAll("/+$", "") + "/");
        if (base != null && (!CommentModel.safeEndpoint(base) || base.getQuery() != null || base.getFragment() != null))
            throw new IllegalArgumentException("invalid_bridge_endpoint");
        if (!token.isBlank() && (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("invalid_bridge_token");
    }
    public boolean configured() { return enabled && base != null && token.length() >= 32; }
    public JsonNode catalog() { return send("tools", null); }
    public JsonNode lookup(UUID id) { return send("executions/" + id, null); }
    public JsonNode execute(UUID id, String actor, String tool, String arguments, String hash, Instant expires, boolean confirmed) {
        Map<String,Object> body = new LinkedHashMap<>(Map.of("requestId", id, "toolId", tool, "arguments", json.readTree(arguments)));
        if (confirmed) {
            long epoch = expires.getEpochSecond();
            String proof = id + "\n" + actor + "\n" + tool + "\n" + hash + "\n" + epoch;
            try {
                var hmac = Mac.getInstance("HmacSHA256");
                hmac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                body.put("approval", Map.of("actor", actor, "expiresAt", epoch, "signature", HexFormat.of().formatHex(hmac.doFinal(proof.getBytes(StandardCharsets.UTF_8)))));
            } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
        }
        try { return send("execute", body); }
        catch (BridgeUnavailable failure) {
            // Querying an existing execution never repeats the action.
            try { return lookup(id); } catch (BridgeUnavailable ignored) { throw failure; }
        }
    }
    private JsonNode send(String path, Map<String,Object> body) {
        if (!configured()) throw new BridgeUnavailable();
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(body == null ? 3 : 12))
            .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        try {
            var result = http.send(builder.build(), HttpBodies.limited(32 * 1024));
            if (result.statusCode() != 200) throw new BridgeUnavailable();
            var value = json.readTree(result.body());
            if (!value.isObject()) throw new BridgeUnavailable();
            return value;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BridgeUnavailable(); }
        catch (Exception e) { throw new BridgeUnavailable(); }
    }
    static final class BridgeUnavailable extends RuntimeException {}
}
