package dev.nexus.core.inbox;

import dev.nexus.core.web.ApiRows;
import dev.nexus.core.web.ConsoleListQuery;
import dev.nexus.core.web.MetadataCsv;
import dev.nexus.core.events.EventJournal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InboxService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final EventJournal events;

    public InboxService(JdbcTemplate db, ObjectMapper json, EventJournal events) {
        this.db = db;
        this.json = json;
        this.events = events;
    }

    public record IngestResult(UUID messageId, boolean created) {}

    @Transactional
    public IngestResult ingest(MessageInput in) {
        return ingest(in, false);
    }

    @Transactional
    public IngestResult ingestPublicComment(MessageInput in) {
        return ingest(in, true);
    }

    private IngestResult ingest(MessageInput in, boolean publicComment) {
        var existing = db.queryForList("SELECT message_id FROM inbox_messages WHERE connector_id=? AND upstream_event_id=?", in.connectorId(), in.eventId());
        if (!existing.isEmpty()) return new IngestResult((UUID) existing.getFirst().get("message_id"), false);
        Instant now = Instant.now();
        Instant expires = in.expiresAt() == null ? now.plus(1, ChronoUnit.DAYS) : in.expiresAt();
        Instant created = in.createdAt() == null ? now : in.createdAt();
        if (!expires.isAfter(now) || expires.isAfter(now.plus(7, ChronoUnit.DAYS)) || created.isAfter(now.plusSeconds(300)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_message_time");
        String url = in.originalUrl() == null ? "" : in.originalUrl();
        if (!url.isEmpty()) {
            try {
                URI u = URI.create(url);
                if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || u.getHost() == null || u.getUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_original_url");
            }
        }
        UUID id = UUID.randomUUID();
        String summary = in.summary() == null || in.summary().isBlank() ? in.content() : in.summary();
        var rows = db.queryForList("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at,metadata,data_class)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (connector_id,upstream_event_id) DO NOTHING RETURNING message_id
            """, id, in.connectorId(), in.source(), in.eventId(), in.eventType(), in.title(), in.content(), summary,
            in.sender() == null ? "" : in.sender(), url, in.priority() == null ? "normal" : in.priority(),
            Timestamp.from(created), Timestamp.from(expires), json.writeValueAsString(in.metadata() == null ? Map.of() : in.metadata()), publicComment ? "public_comment" : "internal");
        if (rows.isEmpty()) {
            UUID previous = db.queryForObject("SELECT message_id FROM inbox_messages WHERE connector_id=? AND upstream_event_id=?", UUID.class, in.connectorId(), in.eventId());
            return new IngestResult(previous, false);
        }
        // Message and outbox are committed together, including during a process restart.
        for (String device : db.queryForList("SELECT device_id FROM devices WHERE enabled=true", String.class)) {
            db.update("INSERT INTO deliveries(event_id,message_id,device_id) VALUES(?,?,?)", UUID.randomUUID(), id, device);
        }
        events.messageReceived(id, in.source(), publicComment ? "public_comment" : "internal");
        return new IngestResult(id, true);
    }

    public Map<String,Object> list(int page, int size, String readStatus, String source, String from, String before) {
        var filter = ConsoleListQuery.messages(readStatus, source, from, before);
        List<Map<String,Object>> items = db.queryForList("""
            SELECT message_id AS "messageId",connector_id AS "connectorId",source,upstream_event_id AS "eventId",
                   event_type AS "eventType",title,content,summary,sender,original_url AS "originalUrl",priority,
                   created_at AS "createdAt",received_at AS "receivedAt",expires_at AS "expiresAt",read_at AS "readAt",
                   action_status AS "actionStatus",data_class AS "dataClass",metadata::text AS metadata,content_purged_at AS "contentPurgedAt"
            FROM inbox_messages m
            """+filter.where()+" ORDER BY received_at DESC,message_id LIMIT ? OFFSET ?", filter.parameters(size, (page - 1) * size));
        items.forEach(row -> {
            row.put("readStatus", row.get("readAt") == null ? "unread" : "read");
            row.put("metadata", json.readTree((String) row.get("metadata")));
        });
        return Map.of("items", ApiRows.normalize(items), "total", db.queryForObject("SELECT count(*) FROM inbox_messages m"+filter.where(), Long.class, filter.parameters()), "page", page, "size", size);
    }

    public List<Map<String,Object>> export(String readStatus, String source, String from, String before) {
        var filter = ConsoleListQuery.messages(readStatus, source, from, before);
        return db.queryForList("""
            SELECT message_id AS "messageId",connector_id AS "connectorId",source,event_type AS "eventType",priority,
                received_at AS "receivedAt",created_at AS "createdAt",expires_at AS "expiresAt",read_at AS "readAt",
                CASE WHEN read_at IS NULL THEN 'unread' ELSE 'read' END AS "readStatus",content_purged_at AS "contentPurgedAt"
            FROM inbox_messages m
            """+filter.where()+" ORDER BY received_at DESC,message_id LIMIT ?", filter.parameters(MetadataCsv.ROW_LIMIT + 1));
    }

    @Transactional
    public void markRead(UUID id) {
        if (db.update("UPDATE inbox_messages SET read_at=COALESCE(read_at,now()) WHERE message_id=?", id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("UPDATE deliveries SET status='expired',last_error='read_elsewhere' WHERE message_id=? AND status IN ('pending','sent')", id);
    }
}
