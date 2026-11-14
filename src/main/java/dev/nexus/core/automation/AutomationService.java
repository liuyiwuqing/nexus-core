package dev.nexus.core.automation;

import dev.nexus.core.events.EventJournal;
import dev.nexus.core.ai.CommentModel;
import dev.nexus.core.web.ApiRows;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AutomationService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final EventJournal events;
    private final ObjectMapper json;
    private final Map<String,AutomationAction> actions;
    private final CommentModel model;
    public AutomationService(JdbcTemplate db, TransactionTemplate transaction, EventJournal events, ObjectMapper json, List<AutomationAction> actions, CommentModel model) {
        this.db = db; this.transaction = transaction; this.events = events; this.json = json;
        this.model = model;
        this.actions = actions.stream().collect(Collectors.toUnmodifiableMap(AutomationAction::id, Function.identity()));
    }
    public record Created(UUID ruleId,boolean created) {}
    @Transactional
    public Created create(String name, String trigger, String action, String time, String zone, String title, String content, UUID requestKey) {
        var arguments = arguments(trigger, action, time, zone, title, content);
        UUID id = UUID.randomUUID();
        String fingerprint=requestKey==null?null:creationHash(name,trigger,action,arguments);
        var inserted=db.queryForList("""
            INSERT INTO automation_rules(rule_id,name,trigger_type,action_id,arguments,create_request_key,create_request_hash)
            VALUES(?,?,?,?,?::jsonb,?,?) ON CONFLICT(create_request_key) DO NOTHING RETURNING rule_id
            """,id,name,trigger,action,json.writeValueAsString(arguments),requestKey,fingerprint);
        if(!inserted.isEmpty())return new Created(id,true);
        var original=db.queryForMap("SELECT rule_id,create_request_hash FROM automation_rules WHERE create_request_key=?",requestKey);
        if(!fingerprint.equals(original.get("create_request_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
        return new Created((UUID)original.get("rule_id"),false);
    }
    private Map<String,String> arguments(String trigger, String action, String time, String zone, String title, String content) {
        Map<String,String> arguments;
        if (trigger.equals("message.received") && action.equals("analyze_public_comment")) arguments = Map.of();
        else if (trigger.equals("schedule.daily") && action.equals("notify_device")) {
            if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || zone == null || zone.isBlank() || title == null || title.isBlank() || title.length() > 160 || content == null || content.isBlank() || content.length() > 8192)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_automation_rule");
            try { LocalTime.parse(time); ZoneId.of(zone); }
            catch (java.time.DateTimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_automation_rule"); }
            arguments = Map.of("localTime", time, "zone", zone, "title", title, "content", content);
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_automation_rule");
        return arguments;
    }
    private String creationHash(String name,String trigger,String action,Map<String,String> arguments) {
        String canonical=json.writeValueAsString(new TreeMap<>(Map.of("name",name,"trigger",trigger,"action",action,"arguments",new TreeMap<>(arguments))));
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static final String RULE_VIEW = """
        SELECT r.rule_id AS "ruleId",r.name,r.trigger_type AS trigger,r.action_id AS action,r.enabled,r.version,
            r.arguments::text AS arguments,r.created_at AS "createdAt",(r.deleted_at IS NOT NULL) AS deleted,
            CASE WHEN r.deleted_at IS NOT NULL THEN 'rule_deleted' WHEN r.enabled THEN 'rule_enabled'
                WHEN EXISTS(SELECT 1 FROM automation_runs x WHERE x.rule_id=r.rule_id AND x.state='executing') THEN 'rule_execution_in_progress'
                ELSE '' END AS "editBlock"
        FROM automation_rules r
        """;
    private List<Map<String,Object>> ruleRows(String suffix, Object... parameters) {
        var rows = db.queryForList(RULE_VIEW + suffix, parameters);
        rows.forEach(row -> row.put("arguments", json.readTree((String) row.get("arguments"))));
        rows.forEach(row -> row.put("canEdit", "".equals(row.get("editBlock"))));
        return ApiRows.normalize(rows);
    }
    public List<Map<String,Object>> rules() { return ruleRows(" WHERE r.deleted_at IS NULL ORDER BY r.created_at DESC LIMIT 100"); }
    public Map<String,Object> rule(UUID id) {
        var rows = ruleRows(" WHERE r.rule_id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> edit(UUID id, int expectedVersion, String name, String time, String zone, String title, String content) {
        var rows = db.queryForList("SELECT name,trigger_type,action_id,enabled,version,deleted_at,arguments::text AS arguments FROM automation_rules WHERE rule_id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var current = rows.getFirst();
        if (current.get("deleted_at") != null) throw new ResponseStatusException(HttpStatus.GONE, "rule_deleted");
        var arguments = arguments((String)current.get("trigger_type"), (String)current.get("action_id"), time, zone, title, content);
        boolean same = name.equals(current.get("name")) && json.valueToTree(arguments).equals(json.readTree((String)current.get("arguments")));
        int version = (Integer)current.get("version");
        if (version != expectedVersion && !(version == (long)expectedVersion + 1 && same && Boolean.FALSE.equals(current.get("enabled"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "automation_version_conflict");
        if (Boolean.TRUE.equals(current.get("enabled"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "rule_enabled");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_runs WHERE rule_id=? AND state='executing')", Boolean.class, id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rule_execution_in_progress");
        if (!same) {
            // Only a stopped rule without a claimed run can change its shared execution arguments.
            db.update("UPDATE automation_rules SET name=?,arguments=?::jsonb,version=version+1 WHERE rule_id=?", name, json.writeValueAsString(arguments), id);
            db.update("UPDATE automation_runs SET state='cancelled',last_error='rule_edited',completed_at=now() WHERE rule_id=? AND state='pending'", id);
        }
        return rule(id);
    }
    @Transactional
    public void enable(UUID rule, boolean enabled, Integer expectedVersion) {
        var rows = db.queryForList("SELECT enabled,version,deleted_at FROM automation_rules WHERE rule_id=? FOR UPDATE", rule);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (rows.getFirst().get("deleted_at") != null) throw new ResponseStatusException(HttpStatus.GONE, "rule_deleted");
        if (expectedVersion != null && !expectedVersion.equals(rows.getFirst().get("version")) &&
            !((Integer)rows.getFirst().get("version") == (long)expectedVersion + 1 && rows.getFirst().get("enabled").equals(enabled)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "automation_version_conflict");
        if (rows.getFirst().get("enabled").equals(enabled)) return;
        db.update("UPDATE automation_rules SET enabled=?,version=version+1,enabled_at=CASE WHEN ? THEN now() ELSE enabled_at END WHERE rule_id=?", enabled, enabled, rule);
        if (!enabled) db.update("UPDATE automation_runs SET state='cancelled',last_error='rule_disabled',completed_at=now() WHERE rule_id=? AND state='pending'", rule);
    }
    @Transactional
    public void delete(UUID id, int expectedVersion) {
        if (expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        var rows = db.queryForList("SELECT enabled,version,deleted_at FROM automation_rules WHERE rule_id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row = rows.getFirst();
        int version = (Integer) row.get("version");
        if (row.get("deleted_at") != null && version == (long) expectedVersion + 1) return;
        if (row.get("deleted_at") != null) throw new ResponseStatusException(HttpStatus.GONE, "rule_deleted");
        if (version != expectedVersion) throw new ResponseStatusException(HttpStatus.CONFLICT, "automation_version_conflict");
        if (Boolean.TRUE.equals(row.get("enabled"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "rule_enabled");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM automation_runs WHERE rule_id=? AND state='executing')", Boolean.class, id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rule_execution_in_progress");
        db.update("UPDATE automation_rules SET deleted_at=now(),version=version+1 WHERE rule_id=?", id);
        db.update("UPDATE automation_runs SET state='cancelled',last_error='rule_deleted',completed_at=now() WHERE rule_id=? AND state='pending'", id);
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(UUID id, int expectedVersion) {
        var current = rule(id);
        if (Boolean.TRUE.equals(current.get("deleted"))) throw new ResponseStatusException(HttpStatus.GONE, "rule_deleted");
        if (!Integer.valueOf(expectedVersion).equals(current.get("version")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "automation_version_conflict");
        boolean notice = "notify_device".equals(current.get("action"));
        int candidates = notice ? db.queryForObject("SELECT count(*) FROM devices WHERE enabled", Integer.class) : 0;
        String block = notice ? (candidates == 0 ? "no_enabled_devices" : "") : (model.configured() ? "" : "ai_not_configured");
        return Map.of("dryRun", true, "rule", current, "candidateDevices", candidates, "block", block,
                      "asOf", Instant.now().toString());
    }
    public List<Map<String,Object>> runs(UUID rule) {
        var rows = db.queryForList("""
            SELECT x.run_id AS "runId",x.rule_id AS "ruleId",r.name,x.rule_version AS "ruleVersion",x.state,x.attempts,
                x.last_error AS "lastError",x.result::text AS result,x.created_at AS "createdAt",x.completed_at AS "completedAt"
            FROM automation_runs x JOIN automation_rules r USING(rule_id)
            """ + (rule == null ? "" : " WHERE x.rule_id=?") + " ORDER BY x.created_at DESC LIMIT 100", rule == null ? new Object[]{} : new Object[]{rule});
        rows.forEach(row -> row.put("result", row.get("result") == null ? null : json.readTree((String) row.get("result"))));
        return ApiRows.normalize(rows);
    }
    public void retry(UUID id) {
        if (db.update("""
            UPDATE automation_runs x SET state='pending',attempts=0,next_attempt_at=now(),last_error=NULL
            FROM automation_rules r WHERE x.run_id=? AND x.rule_id=r.rule_id AND r.enabled AND x.rule_version=r.version AND x.state='failed'
            """, id) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "automation_not_retryable");
    }
    @Scheduled(fixedDelayString="${nexus.automation.tick-ms:3000}", initialDelay=500)
    void process() {
        generateDaily(); dispatch();
        var work = transaction.execute(status -> {
            db.update("UPDATE automation_runs SET state='failed',last_error='execution_interrupted',completed_at=now() WHERE state='executing' AND lease_until<now() AND attempts>=3");
            var rows = db.queryForList("""
                SELECT x.run_id,r.action_id,r.arguments::text AS arguments,e.payload::text AS payload
                FROM automation_runs x JOIN automation_rules r USING(rule_id) JOIN core_events e ON e.event_id=x.event_id
                WHERE x.attempts<3 AND ((x.state='pending' AND r.enabled AND x.rule_version=r.version AND x.next_attempt_at<=now())
                   OR (x.state='executing' AND x.lease_until<now()))
                ORDER BY x.next_attempt_at LIMIT 1 FOR UPDATE OF x SKIP LOCKED
                """);
            if (rows.isEmpty()) return Map.<String,Object>of();
            var row = rows.getFirst(); UUID lease = UUID.randomUUID(); row.put("lease", lease);
            db.update("UPDATE automation_runs SET state='executing',attempts=attempts+1,lease_until=now()+interval '30 seconds',lease_token=? WHERE run_id=?", lease, row.get("run_id"));
            return row;
        });
        if (work == null || work.isEmpty()) return;
        UUID id = (UUID) work.get("run_id"), lease = (UUID) work.get("lease");
        try {
            // The two current actions only enqueue local durable work. Their effect and this
            // result commit together, so lease recovery cannot duplicate notifications.
            transaction.executeWithoutResult(status -> {
                var owned = db.queryForList("SELECT run_id FROM automation_runs WHERE run_id=? AND lease_token=? AND state='executing' FOR UPDATE", id, lease);
                if (owned.isEmpty()) return;
                var result = actions.get((String) work.get("action_id")).enqueue(id, json.readTree((String) work.get("payload")), json.readTree((String) work.get("arguments")));
                db.update("UPDATE automation_runs SET state='enqueued',result=?::jsonb,last_error=NULL,completed_at=now(),lease_until=NULL WHERE run_id=?", json.writeValueAsString(result), id);
            });
        } catch (Exception failure) {
            String reason = failure instanceof ResponseStatusException response && response.getReason() != null ? response.getReason() : "action_enqueue_failed";
            db.update("""
                UPDATE automation_runs SET state=CASE WHEN attempts<3 THEN 'pending' ELSE 'failed' END,
                    last_error=?,next_attempt_at=now()+interval '60 seconds',lease_until=NULL WHERE run_id=? AND lease_token=?
                """, reason, id, lease);
        }
    }
    private void generateDaily() {
        var now = Instant.now();
        for (var row : db.queryForList("SELECT rule_id,version,arguments::text AS arguments FROM automation_rules WHERE enabled AND trigger_type='schedule.daily'")) {
            var arguments = json.readTree((String) row.get("arguments"));
            DailySchedule.due(LocalTime.parse(arguments.path("localTime").asText()), ZoneId.of(arguments.path("zone").asText()), now)
                .ifPresent(day -> events.daily((UUID) row.get("rule_id"), (Integer) row.get("version"), day));
        }
    }
    private void dispatch() {
        db.update("""
            INSERT INTO automation_runs(run_id,rule_id,rule_version,event_id)
            SELECT gen_random_uuid(),r.rule_id,r.version,e.event_id FROM core_events e JOIN automation_rules r ON r.trigger_type=e.type
            WHERE r.enabled AND e.created_at>=r.enabled_at
              AND ((e.type='message.received' AND e.source='halo' AND e.payload->>'dataClass'='public_comment')
                OR (e.type='schedule.daily' AND e.target_rule_id=r.rule_id AND e.rule_version=r.version))
              AND NOT EXISTS(SELECT 1 FROM automation_runs x WHERE x.rule_id=r.rule_id AND x.rule_version=r.version AND x.event_id=e.event_id)
            ORDER BY e.created_at LIMIT 100 ON CONFLICT(rule_id,rule_version,event_id) DO NOTHING
            """);
    }
}
