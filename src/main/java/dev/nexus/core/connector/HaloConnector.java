package dev.nexus.core.connector;

import dev.nexus.core.delivery.DeviceText;
import dev.nexus.core.inbox.InboxService;
import dev.nexus.core.inbox.MessageInput;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class HaloConnector implements InboxConnector {
    private final InboxService inbox;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final URI base;
    private final String token;
    private final String connectorId;
    private final Instant configuredSince;
    private final boolean retentionEnabled;
    private final ReentrantLock polling = new ReentrantLock();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public HaloConnector(InboxService inbox, JdbcTemplate db, ObjectMapper json,
        @Value("${nexus.halo.enabled}") boolean enabled,
        @Value("${nexus.halo.url}") String url,
        @Value("${nexus.halo.token}") String token,
        @Value("${nexus.halo.connector-id}") String connectorId,
        @Value("${nexus.halo.since}") String since,
        @Value("${nexus.halo.allow-insecure:false}") boolean allowInsecure,
        @Value("${nexus.retention.enabled:false}") boolean retentionEnabled) {
        this.inbox = inbox; this.db = db; this.json = json; this.enabled = enabled; this.token = token;
        this.connectorId = connectorId;
        this.retentionEnabled=retentionEnabled;
        this.configuredSince = since.isBlank() ? null : Instant.parse(since);
        this.base = url.isBlank() ? null : URI.create(url.replaceAll("/+$", "") + "/");
        if (!connectorId.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid_halo_connector_id");
        if (base != null && ((!("https".equals(base.getScheme()) || (allowInsecure && "http".equals(base.getScheme()))))
            || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null))
            throw new IllegalArgumentException("Halo requires an HTTPS base URL without credentials, query or fragment");
    }

    @Override public String id() { return connectorId; }
    @Override public String source() { return "halo"; }
    @Override public Set<String> eventTypes() { return Set.of("comment.created"); }
    private boolean configured() { return enabled && base != null && !token.isBlank(); }

    @Override public Map<String,Object> health() {
        var state = db.queryForList("SELECT last_success_at AS \"lastSuccessAt\",last_error AS \"lastError\",cursor_value AS \"since\" FROM connector_state WHERE connector_id=?", connectorId);
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("id", connectorId); result.put("source", source()); result.put("eventTypes", eventTypes());
        result.put("enabled", enabled); result.put("configured", configured());
        result.put("state", !configured() ? "not_configured" : state.isEmpty() ? "not_checked" : state.getFirst().get("lastError") != null ? "error" : state.getFirst().get("lastSuccessAt") == null ? "not_checked" : "ready");
        if (!state.isEmpty()) result.putAll(dev.nexus.core.web.ApiRows.normalize(state).getFirst());
        return result;
    }

    @Scheduled(fixedDelayString="${nexus.halo.poll-ms}", initialDelayString="${nexus.halo.poll-ms}")
    void scheduledPoll() {
        if (!configured()) return;
        try { poll(); } catch (ResponseStatusException ignored) { /* health() exposes the failure without a body/token log. */ }
    }

    public int poll() {
        if (!configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "halo_not_configured");
        if (!polling.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "halo_poll_in_progress");
        try {
            Instant initial = configuredSince == null ? Instant.now() : configuredSince;
            db.update("INSERT INTO connector_state(connector_id,cursor_value) VALUES(?,?) ON CONFLICT DO NOTHING", connectorId, initial.toString());
            Instant since = Instant.parse(db.queryForObject("SELECT cursor_value FROM connector_state WHERE connector_id=?", String.class, connectorId));
            // Deleted deduplication metadata must not cause every historical comment to reappear.
            Instant retentionSince=Instant.now().minus(Duration.ofDays(30));
            if(retentionEnabled&&since.isBefore(retentionSince))since=retentionSince;
            int received = 0;
            for (int page = 1; page <= 50; ++page) {
                URI uri = base.resolve("apis/api.console.halo.run/v1alpha1/comments?page=" + page + "&size=100&sort=spec.creationTime,desc");
                JsonNode body = fetchList(uri);
                for (var item : body.path("items")) {
                    MessageInput message = normalize(item, since);
                    if (message != null && inbox.ingestPublicComment(message).created()) received++;
                }
                boolean hasNext = body.has("hasNext") ? body.path("hasNext").asBoolean() : (long) page * 100 < body.path("total").asLong();
                if (!hasNext) {
                    db.update("UPDATE connector_state SET last_success_at=now(),last_error=NULL WHERE connector_id=?", connectorId);
                    return received;
                }
            }
            // Never advance the boundary or report success when a scan is incomplete.
            throw failure("scan_limit_exceeded");
        } finally { polling.unlock(); }
    }

    /** Recheck the source immediately before AI/memory export; stored source labels are not consent. */
    public boolean canRevalidate(String fromConnector,String event) {
        return configured()&&connectorId.equals(fromConnector)&&event!=null&&event.matches("comment:[a-zA-Z0-9_.-]{1,120}");
    }
    public Optional<MessageInput> revalidatePublic(String fromConnector, String event) {
        return revalidatePublic(fromConnector,event,Duration.ofSeconds(12));
    }
    public Optional<MessageInput> revalidatePublic(String fromConnector,String event,Duration timeout) {
        if (!canRevalidate(fromConnector,event)) return Optional.empty();
        String name = event.substring("comment:".length());
        // Single '=' is verified against the configured Halo 2.27.2 instance.
        String selector = URLEncoder.encode("metadata.name=" + name, StandardCharsets.UTF_8);
        var body = fetchList(base.resolve("apis/api.console.halo.run/v1alpha1/comments?page=1&size=100&fieldSelector=" + selector),timeout);
        for (var item : body.path("items")) {
            if (name.equals(item.path("comment").path("metadata").path("name").asText()))
                return Optional.ofNullable(normalize(item, Instant.EPOCH));
        }
        return Optional.empty();
    }

    /** Explicit backfill of one comment; applies the same current-public checks as normal polling. */
    public InboxService.IngestResult importComment(String name) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "halo_not_configured");
        if (!name.matches("[a-zA-Z0-9_.-]{1,120}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_comment_name");
        var comment = revalidatePublic(connectorId, "comment:" + name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "comment_not_public_or_missing"));
        return inbox.ingestPublicComment(comment);
    }

    private JsonNode fetchList(URI uri) {
        return fetchList(uri,Duration.ofSeconds(12));
    }
    private JsonNode fetchList(URI uri,Duration timeout) {
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        java.net.http.HttpResponse<byte[]> response;
        try { response = http.send(request, HttpBodies.limited(2 * 1024 * 1024)); }
        catch (IOException e) { throw failure("network_error"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure("interrupted"); }
        if (response.statusCode() == 401 || response.statusCode() == 403) throw failure("authorization_required");
        if (response.statusCode() >= 300 && response.statusCode() < 400) throw failure("authentication_redirect");
        if (response.statusCode() != 200) throw failure("http_" + response.statusCode());
        if (!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) throw failure("invalid_content_type");
        JsonNode body;
        try { body = json.readTree(response.body()); } catch (RuntimeException e) { throw failure("invalid_json"); }
        if (!body.path("items").isArray() || !body.path("total").isIntegralNumber()) throw failure("invalid_response");
        return body;
    }

    private MessageInput normalize(JsonNode item, Instant since) {
        JsonNode comment = item.path("comment"), spec = comment.path("spec"), subject = item.path("subject");
        if (!"Comment".equals(comment.path("kind").asText()) || !"content.halo.run/v1alpha1".equals(comment.path("apiVersion").asText())) return null;
        if (!spec.path("approved").isBoolean() || !spec.path("approved").asBoolean() || !spec.path("hidden").isBoolean() || spec.path("hidden").asBoolean()) return null;
        if (!comment.path("metadata").path("deletionTimestamp").isMissingNode() && !comment.path("metadata").path("deletionTimestamp").isNull()) return null;
        // Approved comments on private/member/unpublished subjects are not public data.
        if (!List.of("Post", "SinglePage").contains(subject.path("kind").asText())
            || !"content.halo.run/v1alpha1".equals(subject.path("apiVersion").asText())
            || !subject.path("spec").path("publish").isBoolean() || !subject.path("spec").path("publish").asBoolean()
            || !subject.path("spec").path("deleted").isBoolean() || subject.path("spec").path("deleted").asBoolean()
            || subject.path("metadata").hasNonNull("deletionTimestamp")
            || !"PUBLIC".equals(subject.path("spec").path("visible").asText())
            || !"PUBLISHED".equals(subject.path("status").path("phase").asText())) return null;
        String name = comment.path("metadata").path("name").asText();
        if (!name.matches("[a-zA-Z0-9_.-]{1,120}")) return null;
        Instant time;
        try {
            String value = spec.path("approvedTime").asText("");
            if (value.isBlank()) value = comment.path("metadata").path("creationTimestamp").asText();
            time = Instant.parse(value);
        } catch (RuntimeException e) { throw failure("invalid_comment_time"); }
        if (time.isBefore(since) || time.isAfter(Instant.now().plusSeconds(300))) return null;
        String content = DeviceText.fit(Jsoup.parse(spec.path("content").asText("")).text(), 8192);
        if (content.isBlank()) return null;
        String sender = spec.path("owner").path("displayName").asText("");
        if (sender.isBlank()) sender = "访客";
        sender = DeviceText.fit(sender, 160);
        String permalink = "";
        try {
            URI link = base.resolve(item.path("permalink").asText(""));
            if (base.getHost().equalsIgnoreCase(link.getHost()) && base.getScheme().equals(link.getScheme()) && base.getPort() == link.getPort() && link.getUserInfo() == null)
                permalink = link.toString();
        } catch (IllegalArgumentException ignored) {}
        return new MessageInput(connectorId, "halo", "comment:" + name, "comment.created", "博客新评论", content,
            DeviceText.fit(content, 180), sender, permalink, "normal", time, null, Map.of("commentName", name), null);
    }

    private ResponseStatusException failure(String code) {
        db.update("UPDATE connector_state SET last_error=? WHERE connector_id=?", code, connectorId);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "halo_" + code);
    }
}
