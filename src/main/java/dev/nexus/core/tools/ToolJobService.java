package dev.nexus.core.tools;

import dev.nexus.core.web.ApiRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ToolJobService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final MacBridgeClient bridge;
    private final ObjectMapper json;
    private final String actor;
    public ToolJobService(JdbcTemplate db, TransactionTemplate transaction, MacBridgeClient bridge, ObjectMapper json,
                          @Value("${nexus.admin-token}") String token) {
        this.db = db; this.transaction = transaction; this.bridge = bridge; this.json = json; actor = ToolCatalog.hash(token);
    }
    public Map<String,Object> catalog() {
        if (!bridge.configured()) return Map.of("configured", false, "state", "off", "tools", ToolCatalog.TOOLS);
        try { return Map.of("configured", true, "state", "ready", "tools", ToolCatalog.TOOLS, "bridge", bridge.catalog()); }
        catch (MacBridgeClient.BridgeUnavailable e) { return Map.of("configured", true, "state", "unavailable", "tools", ToolCatalog.TOOLS); }
    }
    public record Prepared(UUID jobId, boolean created) {}
    @Transactional
    public Prepared prepare(UUID requestKey, String toolId, Map<String,Object> arguments) {
        var tool = ToolCatalog.definition(toolId);
        String encoded = ToolCatalog.arguments(tool, arguments, json), hash = ToolCatalog.hash(encoded);
        if (!bridge.configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "bridge_not_configured");
        UUID id = UUID.randomUUID();
        var inserted = db.queryForList("""
            INSERT INTO tool_jobs(job_id,actor,request_key,tool_id,risk,arguments,arguments_hash,state,expires_at)
            VALUES(?,?,?,?,?,?::jsonb,?,?,now()+interval '5 minutes')
            ON CONFLICT(actor,request_key) DO NOTHING RETURNING job_id
            """, id, actor, requestKey, toolId, tool.risk(), encoded, hash, tool.risk().equals("confirmation") ? "awaiting_confirmation" : "queued");
        if (inserted.isEmpty()) {
            var old = db.queryForMap("SELECT job_id,tool_id,arguments_hash FROM tool_jobs WHERE actor=? AND request_key=?", actor, requestKey);
            if (!toolId.equals(old.get("tool_id")) || !hash.equals(old.get("arguments_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency_conflict");
            return new Prepared((UUID) old.get("job_id"), false);
        }
        audit(id, "created", "");
        return new Prepared(id, true);
    }
    @Transactional
    public void confirm(UUID id, String argumentsHash) {
        var row = owned(id, true);
        if (!row.get("arguments_hash").equals(argumentsHash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation_mismatch");
        if (!((Timestamp) row.get("expires_at")).toInstant().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation_expired");
        if (!row.get("state").equals("awaiting_confirmation")) throw new ResponseStatusException(HttpStatus.CONFLICT, "job_not_confirmable");
        db.update("UPDATE tool_jobs SET state='queued' WHERE job_id=?", id);
        audit(id, "confirmed", "");
    }
    @Transactional
    public void cancel(UUID id) {
        var row = owned(id, true);
        if (!List.of("awaiting_confirmation", "queued").contains(row.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "job_already_started");
        db.update("UPDATE tool_jobs SET state='cancelled',completed_at=now() WHERE job_id=?", id);
        audit(id, "cancelled", "");
    }
    private Map<String,Object> owned(UUID id, boolean lock) {
        var rows = db.queryForList("SELECT * FROM tool_jobs WHERE job_id=? AND actor=?" + (lock ? " FOR UPDATE" : ""), id, actor);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public Map<String,Object> get(UUID id) {
        owned(id, false);
        var row = db.queryForMap("""
            SELECT job_id AS "jobId",tool_id AS "toolId",risk,state,arguments::text AS arguments,arguments_hash AS "argumentsHash",
                result::text AS result,last_error AS "lastError",created_at AS "createdAt",expires_at AS "expiresAt",completed_at AS "completedAt"
            FROM tool_jobs WHERE job_id=?
            """, id);
        row.put("arguments", json.readTree((String) row.get("arguments")));
        row.put("result", row.get("result") == null ? null : json.readTree((String) row.get("result")));
        return ApiRows.normalize(List.of(row)).getFirst();
    }
    public List<Map<String,Object>> list() {
        return db.queryForList("SELECT job_id FROM tool_jobs WHERE actor=? ORDER BY created_at DESC LIMIT 50", actor).stream()
            .map(row -> get((UUID) row.get("job_id"))).toList();
    }
    public List<Map<String,Object>> history(UUID id) {
        owned(id, false);
        return ApiRows.normalize(db.queryForList("SELECT sequence,event,detail,created_at AS \"createdAt\" FROM tool_audit WHERE job_id=? ORDER BY sequence LIMIT 100", id));
    }
    private void audit(UUID id, String event, String detail) { db.update("INSERT INTO tool_audit(job_id,event,detail) VALUES(?,?,?)", id, event, detail); }

    @Scheduled(fixedDelayString="${nexus.tools.tick-ms:1000}", initialDelay=500)
    void process() {
        var work = transaction.execute(status -> {
            var expired = db.queryForList("UPDATE tool_jobs SET state='expired',completed_at=now() WHERE actor=? AND state IN ('queued','awaiting_confirmation') AND expires_at<=now() RETURNING job_id", actor);
            expired.forEach(row -> audit((UUID) row.get("job_id"), "expired", ""));
            var interrupted = db.queryForList("UPDATE tool_jobs SET state='unknown',last_error='execution_interrupted',completed_at=now() WHERE actor=? AND state='executing' AND lease_until<now() RETURNING job_id", actor);
            interrupted.forEach(row -> audit((UUID) row.get("job_id"), "unknown", "execution_interrupted"));
            if (!bridge.configured()) return Map.<String,Object>of();
            var rows = db.queryForList("SELECT job_id,tool_id,risk,arguments::text AS arguments,arguments_hash,expires_at FROM tool_jobs WHERE actor=? AND state='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", actor);
            if (rows.isEmpty()) return Map.<String,Object>of();
            var row = rows.getFirst();
            db.update("UPDATE tool_jobs SET state='executing',lease_until=now()+interval '30 seconds' WHERE job_id=?", row.get("job_id"));
            audit((UUID) row.get("job_id"), "executing", "");
            return row;
        });
        if (work == null || work.isEmpty()) return;
        UUID id = (UUID) work.get("job_id");
        try {
            JsonNode response = bridge.execute(id, actor, (String) work.get("tool_id"), (String) work.get("arguments"),
                (String) work.get("arguments_hash"), ((Timestamp) work.get("expires_at")).toInstant(), work.get("risk").equals("confirmation"));
            recordResult(id, response, "executing");
        } catch (Exception failure) { recordUnknown(id, "bridge_result_unknown"); }
    }
    public void reconcile(UUID id) {
        if (!owned(id, false).get("state").equals("unknown")) throw new ResponseStatusException(HttpStatus.CONFLICT, "job_result_known");
        try { recordResult(id, bridge.lookup(id), "unknown"); }
        catch (MacBridgeClient.BridgeUnavailable failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, "bridge_unavailable"); }
    }
    private void recordUnknown(UUID id, String reason) {
        transaction.executeWithoutResult(status -> {
            if (db.update("UPDATE tool_jobs SET state='unknown',last_error=?,completed_at=now(),lease_until=NULL WHERE job_id=? AND state='executing'", reason, id) == 1) audit(id, "unknown", reason);
        });
    }
    private void recordResult(UUID id, JsonNode response, String previous) {
        String state = response.path("state").asText();
        if (!response.path("requestId").asText().equals(id.toString()) || !List.of("succeeded", "failed").contains(state)) {
            if (previous.equals("executing")) recordUnknown(id, "bridge_result_unknown");
            return;
        }
        String code = response.path("error").asText("");
        if (!code.matches("[a-z0-9_]{0,64}")) code = "tool_failed";
        String reason = code;
        transaction.executeWithoutResult(status -> {
            if (db.update("UPDATE tool_jobs SET state=?,result=?::jsonb,last_error=?,completed_at=now(),lease_until=NULL WHERE job_id=? AND state=?", state,
                json.writeValueAsString(response.path("result")), reason, id, previous) == 1) audit(id, state, reason);
        });
    }
}
