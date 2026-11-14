package dev.nexus.core.delivery;

import dev.nexus.core.web.ApiRows;
import dev.nexus.core.web.ConsoleListQuery;
import dev.nexus.core.web.MetadataCsv;
import dev.nexus.core.inbox.InboxService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class DeliveryService {
    private final JdbcTemplate db;
    private final int maxAttempts;
    private final int retrySeconds;
    private final InboxService inbox;
    private static final List<String> ACK_ORDER = List.of("pending", "sent", "received", "displayed", "read");
    private static final String CAN_CANCEL = "d.status='pending' AND d.attempts=0 AND NOT d.cancel_blocked AND m.expires_at>now() AND m.read_at IS NULL AND v.enabled";

    public DeliveryService(JdbcTemplate db, @Value("${nexus.delivery.max-attempts}") int maxAttempts,
                           @Value("${nexus.delivery.retry-seconds}") int retrySeconds, InboxService inbox) {
        this.db = db;
        this.maxAttempts = maxAttempts;
        this.retrySeconds = retrySeconds;
        this.inbox = inbox;
        if (maxAttempts < 1 || maxAttempts > 100 || retrySeconds < 1) throw new IllegalArgumentException("invalid_delivery_policy");
    }

    @Transactional
    public List<Map<String,Object>> claim() {
        var rows = db.queryForList("""
            SELECT d.event_id,d.device_id,d.message_id,d.attempts,m.title,m.summary,m.source,m.event_type,m.priority,m.expires_at
            FROM deliveries d JOIN inbox_messages m USING(message_id) JOIN devices v USING(device_id)
            WHERE d.status IN ('pending','sent') AND d.next_attempt_at<=now() AND m.expires_at>now()
              AND m.read_at IS NULL AND v.enabled AND d.attempts<?
            ORDER BY d.next_attempt_at LIMIT 8 FOR UPDATE OF d SKIP LOCKED
            """, maxAttempts);
        for (var row : rows) {
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            long delay = Math.min(300L, retrySeconds * (1L << Math.min(attempts - 1, 8)));
            db.update("UPDATE deliveries SET attempts=?,next_attempt_at=? WHERE event_id=?", attempts, Timestamp.from(Instant.now().plusSeconds(delay)), row.get("event_id"));
        }
        return rows;
    }

    public void expire() {
        db.update("""
            UPDATE deliveries d SET status='expired',last_error='expired'
            FROM inbox_messages m WHERE d.message_id=m.message_id AND d.status IN ('pending','sent','received','displayed') AND m.expires_at<=now()
            """);
        db.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE status IN ('pending','sent') AND attempts>=? AND next_attempt_at<=now()", maxAttempts);
    }

    public void published(UUID event) {
        db.update("UPDATE deliveries SET status='sent',last_error=NULL WHERE event_id=? AND status IN ('pending','sent')", event);
    }

    public void publishFailed(UUID event) {
        db.update("UPDATE deliveries SET last_error='broker_unavailable' WHERE event_id=? AND status IN ('pending','sent')", event);
    }

    @Transactional
    public void acknowledge(String device, JsonNode ack) {
        if (!validVersion(ack) || !device.equals(ack.path("deviceId").asText())) return;
        UUID event, message;
        try {
            event = UUID.fromString(ack.path("eventId").asText());
            message = UUID.fromString(ack.path("messageId").asText());
        } catch (IllegalArgumentException e) { return; }
        var rows = db.queryForList("""
            SELECT d.status,d.attempts,m.expires_at FROM deliveries d JOIN inbox_messages m USING(message_id)
            JOIN devices v USING(device_id) WHERE d.event_id=? AND d.message_id=? AND d.device_id=? AND v.enabled FOR UPDATE OF d
            """, event, message, device);
        if (rows.isEmpty() || ((Number) rows.getFirst().get("attempts")).intValue() == 0) return;
        String current = (String) rows.getFirst().get("status");
        String next = ack.path("status").asText();
        db.update("UPDATE devices SET last_seen=now(),online=true WHERE device_id=?", device);
        if (next.equals("rejected")) {
            if (!List.of("pending", "sent").contains(current)) return;
            String reason = ack.path("reason").asText();
            if (List.of("busy", "storage_error", "time_unavailable").contains(reason))
                db.update("UPDATE deliveries SET last_error=? WHERE event_id=?", reason, event);
            else if (List.of("expired", "invalid").contains(reason))
                db.update("UPDATE deliveries SET status=?,last_error=? WHERE event_id=?", reason.equals("expired") ? "expired" : "failed", reason, event);
            return;
        }
        if (!List.of("received", "displayed", "read").contains(next)) return;
        // Late valid ACKs can resolve an exhausted delivery, but cannot revive expired messages.
        if (List.of("expired","cancelled").contains(current) || !((Timestamp) rows.getFirst().get("expires_at")).toInstant().isAfter(Instant.now())) return;
        if (ACK_ORDER.indexOf(next) <= ACK_ORDER.indexOf(current)) return;
        db.update("""
            UPDATE deliveries SET status=?,last_error=NULL,received_at=COALESCE(received_at,now()),
               displayed_at=CASE WHEN ? IN ('displayed','read') THEN COALESCE(displayed_at,now()) ELSE displayed_at END,
               read_at=CASE WHEN ?='read' THEN COALESCE(read_at,now()) ELSE read_at END WHERE event_id=?
            """, next, next, next, event);
        if (next.equals("read")) inbox.markRead(message);
    }

    public void state(String device, JsonNode state) {
        if (!validVersion(state) || !device.equals(state.path("deviceId").asText())) return;
        if (!state.path("online").isBoolean()) return;
        if (!state.path("online").asBoolean()) {
            db.update("UPDATE devices SET online=false WHERE device_id=?", device);
        } else if (Math.abs(Instant.now().getEpochSecond() - state.path("observedAt").asLong(0)) < 120) {
            db.update("UPDATE devices SET online=true,last_seen=now() WHERE device_id=? AND enabled", device);
        }
    }

    public Map<String,Object> list(int page, int size, String status, String deviceId, String from, String before) {
        var filter = ConsoleListQuery.deliveries(status, deviceId, from, before);
        return Map.of("items", ApiRows.normalize(db.queryForList("""
            SELECT d.event_id AS "eventId",d.message_id AS "messageId",d.device_id AS "deviceId",d.status,d.attempts,
                   d.last_error AS "lastError",d.received_at AS "receivedAt",d.displayed_at AS "displayedAt",d.read_at AS "readAt",
                   d.next_attempt_at AS "nextAttemptAt",m.title,m.expires_at AS "expiresAt",
            """+"("+CAN_CANCEL+") AS \"canCancel\" FROM deliveries d JOIN inbox_messages m USING(message_id) JOIN devices v USING(device_id)"+
            filter.where()+" ORDER BY m.received_at DESC,d.event_id LIMIT ? OFFSET ?", filter.parameters(size, (page - 1) * size))),
            "total", db.queryForObject("SELECT count(*) FROM deliveries d JOIN inbox_messages m USING(message_id)"+filter.where(), Long.class, filter.parameters()), "page", page, "size", size);
    }

    public List<Map<String,Object>> export(String status, String deviceId, String from, String before) {
        var filter = ConsoleListQuery.deliveries(status, deviceId, from, before);
        return db.queryForList("""
            SELECT d.event_id AS "eventId",d.message_id AS "messageId",d.device_id AS "deviceId",d.status,d.attempts,
                m.received_at AS "messageReceivedAt",d.received_at AS "receivedAt",d.displayed_at AS "displayedAt",
                d.read_at AS "readAt",d.next_attempt_at AS "nextAttemptAt"
            FROM deliveries d JOIN inbox_messages m USING(message_id)
            """+filter.where()+" ORDER BY m.received_at DESC,d.event_id LIMIT ?", filter.parameters(MetadataCsv.ROW_LIMIT + 1));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(UUID event,int page,int size) {
        var current=db.queryForList("SELECT d.event_id AS \"eventId\",d.status,d.attempts,("+CAN_CANCEL+
            ") AS \"canCancel\" FROM deliveries d JOIN inbox_messages m USING(message_id) JOIN devices v USING(device_id) WHERE d.event_id=?",event);
        if(current.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"delivery_not_found");
        var first=db.queryForList("SELECT kind FROM delivery_history WHERE event_id=? ORDER BY sequence LIMIT 1",String.class,event);
        return Map.of("items",ApiRows.normalize(db.queryForList("""
            SELECT sequence,recorded_at AS "recordedAt",kind,previous_status AS "previousStatus",status,attempts,
                last_error AS "lastError",next_attempt_at AS "nextAttemptAt",received_at AS "receivedAt",
                displayed_at AS "displayedAt",read_at AS "readAt"
            FROM delivery_history WHERE event_id=? ORDER BY sequence LIMIT ? OFFSET ?
            """,event,size,(page-1)*size)),
            "delivery",current.getFirst(),"includesCreation",!first.isEmpty()&&first.getFirst().equals("created"),
            "total",db.queryForObject("SELECT count(*) FROM delivery_history WHERE event_id=?",Long.class,event),"page",page,"size",size);
    }

    @Transactional
    public void cancel(UUID event) {
        // The conditional UPDATE and claim() serialize on the delivery row. A
        // reservation permanently blocks cancellation before network publication.
        if(db.update("""
            UPDATE deliveries d SET status='cancelled',last_error='cancelled_by_admin'
            FROM inbox_messages m,devices v WHERE d.message_id=m.message_id AND d.device_id=v.device_id AND d.event_id=? AND
            """+CAN_CANCEL,event)>0)return;
        var rows=db.queryForList("SELECT status FROM deliveries WHERE event_id=?",String.class,event);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"delivery_not_found");
        if(!rows.getFirst().equals("cancelled"))throw new ResponseStatusException(HttpStatus.CONFLICT,"delivery_not_cancellable");
    }

    private boolean validVersion(JsonNode packet) {
        return packet.isObject() && packet.path("schemaVersion").isNumber() && packet.path("schemaVersion").asDouble() == 1.0;
    }

    @Transactional
    public void retry(UUID event) {
        int count = db.update("""
            UPDATE deliveries d SET status='pending',attempts=0,next_attempt_at=now(),last_error=NULL
            FROM inbox_messages m,devices v WHERE d.message_id=m.message_id AND d.device_id=v.device_id AND d.event_id=?
              AND d.status='failed' AND m.expires_at>now() AND m.read_at IS NULL AND v.enabled
            """, event);
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "delivery_not_retryable");
    }
}
