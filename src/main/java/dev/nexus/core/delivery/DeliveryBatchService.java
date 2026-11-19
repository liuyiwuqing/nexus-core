package dev.nexus.core.delivery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies cancel/retry to every delivery of one message, and cancel to one device's waiting queue.
 *
 * <p>The predicate is literally {@link DeliveryService#CAN_CANCEL}/{@link DeliveryService#CAN_RETRY},
 * so a batch can never reach a row the single-delivery endpoints refuse. Skip reasons are written as
 * the negations of that predicate's conjuncts in the same order, which makes "reason is null" and
 * "eligible" agree by construction instead of by a second, drifting copy of the rules.
 */
@Service
public class DeliveryBatchService {
    /** A message fans out to a bounded device set; a larger batch would hide a runaway fan-out. */
    public static final int MAX_BATCH = 500;
    /** The console selects at most one page of messages; a larger span is accidental, not a plan. */
    public static final int MAX_MESSAGES = 20;
    private static final String CANCEL_REASON = """
        CASE WHEN d.status<>'pending' THEN 'not_pending'
             WHEN d.attempts<>0 THEN 'attempted'
             WHEN d.cancel_blocked THEN 'cancel_blocked'
             WHEN m.expires_at<=now() THEN 'message_expired'
             WHEN m.read_at IS NOT NULL THEN 'message_read'
             WHEN NOT v.enabled THEN 'device_disabled' END""";
    private static final String RETRY_REASON = """
        CASE WHEN d.status<>'failed' THEN 'not_failed'
             WHEN m.expires_at<=now() THEN 'message_expired'
             WHEN m.read_at IS NOT NULL THEN 'message_read'
             WHEN NOT v.enabled THEN 'device_disabled' END""";
    private static final String CANCEL_UPDATE = """
        UPDATE deliveries d SET status='cancelled',last_error='cancelled_by_admin'
        FROM inbox_messages m,devices v WHERE d.message_id=m.message_id AND d.device_id=v.device_id AND d.message_id=?"""
        + " AND " + DeliveryService.CAN_CANCEL;
    private static final String RETRY_UPDATE = """
        UPDATE deliveries d SET status='pending',attempts=0,next_attempt_at=now(),last_error=NULL
        FROM inbox_messages m,devices v WHERE d.message_id=m.message_id AND d.device_id=v.device_id AND d.message_id=?"""
        + " AND " + DeliveryService.CAN_RETRY;
    private static final String CANCEL_DEVICE_UPDATE = """
        UPDATE deliveries d SET status='cancelled',last_error='cancelled_by_admin'
        FROM inbox_messages m,devices v WHERE d.message_id=m.message_id AND d.device_id=v.device_id AND d.device_id=?"""
        + " AND d.status IN ('pending','sent') AND " + DeliveryService.CAN_CANCEL;

    private final JdbcTemplate db;

    public DeliveryBatchService(JdbcTemplate db) { this.db = db; }

    record Row(UUID eventId, String deviceId, String status, int attempts, String reason) {}

    @Transactional
    public Map<String,Object> run(UUID messageId, boolean cancel, boolean dryRun) {
        if (db.queryForList("SELECT 1 FROM inbox_messages WHERE message_id=?", Integer.class, messageId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "message_not_found");
        int deliveries = db.queryForObject("SELECT count(*) FROM deliveries WHERE message_id=?", Integer.class, messageId);
        if (deliveries > MAX_BATCH) throw new ResponseStatusException(HttpStatus.CONFLICT, "batch_too_large");
        var before = classify(messageId, cancel);
        // One statement, not a loop: rows claimed between the read and the write simply stop matching,
        // and RETURNING tells us exactly which ones changed instead of trusting the candidate list.
        var applied = dryRun ? List.<UUID>of() : db.queryForList(cancel ? CANCEL_UPDATE + " RETURNING d.event_id"
            : RETRY_UPDATE + " RETURNING d.event_id", UUID.class, messageId);
        Set<UUID> appliedIds = new HashSet<>(applied);
        var after = dryRun ? before : classify(messageId, cancel);
        var candidates = new ArrayList<Map<String,Object>>();
        for (var row : before) if (row.reason() == null) candidates.add(view(row, false));
        var skipped = new ArrayList<Map<String,Object>>();
        for (var row : after) if (row.reason() != null && !appliedIds.contains(row.eventId())) skipped.add(view(row, true));
        int remaining = 0;
        for (var row : after) if (row.reason() == null) remaining++;
        var result = new LinkedHashMap<String,Object>();
        result.put("messageId", messageId);
        result.put("action", cancel ? "cancel" : "retry");
        result.put("dryRun", dryRun);
        result.put("applied", applied.size());
        result.put("counts", Map.of("deliveries", deliveries, "candidates", candidates.size(),
            "skipped", skipped.size(), "remaining", remaining));
        result.put("candidates", candidates);
        result.put("skipped", skipped);
        result.put("asOf", Instant.now());
        return result;
    }

    /**
     * Cancels every delivery that is still waiting for one device and never started sending.
     *
     * <p>The scope is the same pending+sent set the device card counts, so the plan explains the
     * whole visible queue: sent rows are reported as {@code not_pending} and stay untouched. A
     * device queue is bounded like a message fan-out; a larger set is refused, not truncated.
     */
    @Transactional
    public Map<String,Object> cancelDeviceQueue(String deviceId, boolean dryRun) {
        if (db.queryForList("SELECT 1 FROM devices WHERE device_id=?", Integer.class, deviceId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device_not_found");
        int waiting = db.queryForObject("SELECT count(*) FROM deliveries WHERE device_id=? AND status IN ('pending','sent')",
            Integer.class, deviceId);
        if (waiting > MAX_BATCH) throw new ResponseStatusException(HttpStatus.CONFLICT, "batch_too_large");
        var before = classifyDeviceQueue(deviceId);
        var applied = dryRun ? List.<UUID>of() : db.queryForList(CANCEL_DEVICE_UPDATE + " RETURNING d.event_id",
            UUID.class, deviceId);
        Set<UUID> appliedIds = new HashSet<>(applied);
        var after = dryRun ? before : classifyDeviceQueue(deviceId);
        var candidates = new ArrayList<Map<String,Object>>();
        for (var row : before) if (row.reason() == null) candidates.add(view(row, false));
        var skipped = new ArrayList<Map<String,Object>>();
        for (var row : after) if (row.reason() != null && !appliedIds.contains(row.eventId())) skipped.add(view(row, true));
        int remaining = 0;
        for (var row : after) if (row.reason() == null) remaining++;
        var result = new LinkedHashMap<String,Object>();
        result.put("deviceId", deviceId);
        result.put("action", "cancel");
        result.put("dryRun", dryRun);
        result.put("applied", applied.size());
        // waiting 是请求开始时的快照；写入后它仍等于 applied + skipped + remaining（除非并发改动了状态）。
        result.put("counts", Map.of("waiting", waiting, "candidates", candidates.size(),
            "skipped", skipped.size(), "remaining", remaining));
        result.put("candidates", candidates);
        result.put("skipped", skipped);
        result.put("asOf", Instant.now());
        return result;
    }

    /**
     * Applies the same per-message batch to a small set of messages in one transaction.
     *
     * <p>Every selected message must exist: a selection that lost a message is refused as a whole
     * instead of applying a partial plan that no longer matches what the operator reviewed. The
     * aggregate fan-out limit is checked before the first write, so the per-message limit can never
     * abort midway.
     */
    @Transactional
    public Map<String,Object> runMany(List<UUID> messageIds, boolean cancel, boolean dryRun) {
        if (messageIds == null || messageIds.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        var ids = new ArrayList<UUID>();
        for (var id : messageIds) if (id != null && !ids.contains(id)) ids.add(id);
        if (ids.size() > MAX_MESSAGES) throw new ResponseStatusException(HttpStatus.CONFLICT, "batch_too_large");
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] keys = ids.toArray();
        int found = db.queryForObject("SELECT count(*) FROM inbox_messages WHERE message_id IN (" + placeholders + ")",
            Integer.class, keys);
        if (found != ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "message_not_found");
        int deliveries = db.queryForObject("SELECT count(*) FROM deliveries WHERE message_id IN (" + placeholders + ")",
            Integer.class, keys);
        if (deliveries > MAX_BATCH) throw new ResponseStatusException(HttpStatus.CONFLICT, "batch_too_large");
        var messages = new ArrayList<Map<String,Object>>();
        int applied = 0, candidates = 0, skipped = 0, remaining = 0;
        for (var id : ids) {
            // Self-invocation joins the transaction started here, so any later failure rolls the
            // earlier messages back as well.
            var one = run(id, cancel, dryRun);
            messages.add(one);
            applied += (Integer) one.get("applied");
            var counts = (Map<?,?>) one.get("counts");
            candidates += (Integer) counts.get("candidates");
            skipped += (Integer) counts.get("skipped");
            remaining += (Integer) counts.get("remaining");
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("action", cancel ? "cancel" : "retry");
        result.put("dryRun", dryRun);
        result.put("applied", applied);
        result.put("counts", Map.of("messages", ids.size(), "deliveries", deliveries,
            "candidates", candidates, "skipped", skipped, "remaining", remaining));
        result.put("messages", messages);
        result.put("asOf", Instant.now());
        return result;
    }

    private List<Row> classify(UUID messageId, boolean cancel) {
        return db.query("""
            SELECT d.event_id,d.device_id,d.status,d.attempts,%s AS reason
            FROM deliveries d JOIN inbox_messages m USING(message_id) JOIN devices v USING(device_id)
            WHERE d.message_id=? ORDER BY d.device_id,d.event_id
            """.formatted(cancel ? CANCEL_REASON : RETRY_REASON),
            (rs, index) -> new Row(rs.getObject("event_id", UUID.class), rs.getString("device_id"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("reason")), messageId);
    }

    private List<Row> classifyDeviceQueue(String deviceId) {
        return db.query("""
            SELECT d.event_id,d.device_id,d.status,d.attempts,%s AS reason
            FROM deliveries d JOIN inbox_messages m USING(message_id) JOIN devices v USING(device_id)
            WHERE d.device_id=? AND d.status IN ('pending','sent') ORDER BY d.event_id
            """.formatted(CANCEL_REASON),
            (rs, index) -> new Row(rs.getObject("event_id", UUID.class), rs.getString("device_id"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("reason")), deviceId);
    }

    private static Map<String,Object> view(Row row, boolean withReason) {
        var item = new LinkedHashMap<String,Object>();
        item.put("eventId", row.eventId());
        item.put("deviceId", row.deviceId());
        item.put("status", row.status());
        item.put("attempts", row.attempts());
        if (withReason) item.put("reason", row.reason());
        return item;
    }
}
