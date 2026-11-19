package dev.nexus.core.inbox;

import dev.nexus.core.web.ApiRows;
import dev.nexus.core.web.ConsoleListQuery;
import dev.nexus.core.web.MetadataCsv;
import dev.nexus.core.events.EventJournal;
import dev.nexus.core.routing.MessageRouting;
import dev.nexus.core.routing.MessageTemplates;
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
    private final MessageRouting routing;
    private final MessageTemplates templates;

    public InboxService(JdbcTemplate db, ObjectMapper json, EventJournal events, MessageRouting routing,MessageTemplates templates) {
        this.db = db;
        this.json = json;
        this.events = events;
        this.routing = routing;
        this.templates = templates;
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
        return ingest(in,publicComment,null);
    }
    @Transactional
    public IngestResult ingestScoped(MessageInput in,List<String> permitted) {
        return ingest(in,false,routing.targets(in,Instant.now()).stream().filter(t->permitted.contains(t.deviceId())).toList());
    }
    /**
     * 管理台测试消息：省略设备和模板时保持既有路由/广播；显式设备只投递到这些启用设备；模板渲染成逐设备投递快照。
     * 同一 eventId 的重放必须保持相同设备集合和模板，避免未知结果后的重试悄悄改发给另一批设备或换模板。
     */
    @Transactional
    public IngestResult consoleMessage(MessageInput in) {
        if (in.devices() == null && in.templateId() == null) return ingest(in, false);
        templates.require(in.templateId());
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "console-message:" + in.eventId());
        List<String> devices = null;
        if (in.devices() != null) {
            devices = in.devices().stream().distinct().sorted().toList();
            if (devices.isEmpty() || devices.size() > 32) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_route");
            for (String device : devices)
                if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=? AND enabled)", Boolean.class, device)))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_route");
        }
        var existing = db.queryForList("SELECT message_id FROM inbox_messages WHERE connector_id=? AND upstream_event_id=?", in.connectorId(), in.eventId());
        if (!existing.isEmpty()) {
            UUID id = (UUID) existing.getFirst().get("message_id");
            if (devices != null) {
                var original = db.queryForList("SELECT device_id FROM deliveries WHERE message_id=? ORDER BY device_id", String.class, id);
                if (!original.equals(devices)) throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency_conflict");
            }
            if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM deliveries WHERE message_id=? AND template_id IS DISTINCT FROM ?::uuid)", Boolean.class, id, in.templateId())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency_conflict");
            return new IngestResult(id, false);
        }
        List<MessageRouting.Target> targets = devices != null
            ? devices.stream().map(d -> new MessageRouting.Target(d, null, in.templateId())).toList()
            : routing.targets(in, Instant.now()).stream().map(t -> new MessageRouting.Target(t.deviceId(), t.priority(), in.templateId())).toList();
        return ingest(in, false, targets);
    }

    @Transactional
    public IngestResult targetedTest(MessageInput in, List<String> devices,UUID templateId) {
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "targeted-test:"+in.eventId());
        templates.require(templateId);
        if(devices==null || devices.isEmpty() || devices.size()>32)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_route");
        for(String device:devices)if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=? AND enabled)",Boolean.class,device)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_route");
        var existing=db.queryForList("SELECT message_id,title,content,priority FROM inbox_messages WHERE connector_id='targeted-test' AND upstream_event_id=?",in.eventId());
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            var original=db.queryForList("SELECT device_id FROM deliveries WHERE message_id=? ORDER BY device_id",String.class,row.get("message_id"));
            if(!in.title().equals(row.get("title"))||!in.content().equals(row.get("content"))||!in.priority().equals(row.get("priority"))||!original.equals(devices.stream().distinct().sorted().toList()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM deliveries WHERE message_id=? AND template_id IS DISTINCT FROM ?::uuid)",Boolean.class,row.get("message_id"),templateId)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
        }
        return ingest(in,false,devices.stream().distinct().map(d->new MessageRouting.Target(d,null,templateId)).toList());
    }
    private IngestResult ingest(MessageInput in, boolean publicComment, List<MessageRouting.Target> targets) {
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
        for (var target : targets==null?routing.targets(in,now):targets) {
            var rendered=target.templateId()==null?null:templates.render(target.templateId(),in);
            db.update("INSERT INTO deliveries(event_id,message_id,device_id,priority_override,title_override,summary_override,event_type_override,template_id,template_version,sound,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), id, target.deviceId(),target.priority(),rendered==null?null:rendered.title(),rendered==null?null:rendered.summary(),rendered==null?null:rendered.eventType(),target.templateId(),rendered==null?null:rendered.version(),rendered==null?null:rendered.sound(),rendered==null?null:rendered.durationMs());
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
    @Transactional(readOnly=true)
    public List<Map<String,Object>> exportContent(String readStatus,String source,String from,String before){
        var filter=ConsoleListQuery.messages(readStatus,source,from,before);
        var rows=db.queryForList("""
            SELECT m.message_id AS "messageId",m.source,m.event_type AS "eventType",m.title,m.content,m.summary,m.sender,
                m.original_url AS "originalUrl",m.priority,m.received_at AS "receivedAt",m.created_at AS "createdAt"
            FROM inbox_messages m
            """+filter.where()+" AND m.received_at>now()-interval '7 days' AND m.content_purged_at IS NULL ORDER BY m.received_at DESC,m.message_id LIMIT 1001",filter.parameters());
        if(rows.size()>1000)throw new ResponseStatusException(HttpStatus.CONFLICT,"content_export_too_large");
        return rows;
    }

    @Transactional
    public void markRead(UUID id) {
        if (db.update("UPDATE inbox_messages SET read_at=COALESCE(read_at,now()) WHERE message_id=?", id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("UPDATE deliveries SET status='expired',last_error='read_elsewhere' WHERE message_id=? AND status IN ('pending','sent')", id);
    }
}
