package dev.nexus.core.ai;

import dev.nexus.core.connector.HaloConnector;
import dev.nexus.core.inbox.ContentRetention;
import dev.nexus.core.web.ApiRows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final HaloConnector halo;
    private final CommentModel model;
    private final boolean automatic;
    private final ContentRetention retention;
    public AnalysisService(JdbcTemplate db, TransactionTemplate transaction, HaloConnector halo, CommentModel model,
                           ContentRetention retention,
                           @Value("${nexus.ai.auto-enabled:false}") boolean automatic) {
        this.db = db; this.transaction = transaction; this.halo = halo; this.model = model; this.automatic = automatic;
        this.retention=retention;
    }

    public Map<String,Object> configuration() { return Map.of("configured", model.configured(), "automatic", automatic, "dataPolicy", "public_comments_only"); }

    @Transactional
    public void enqueue(UUID id, boolean retry) {
        if (!"public_comment".equals(retention.requireContent(id))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "data_policy_denied");
        if (!model.configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "ai_not_configured");
        if (!retry) db.update("INSERT INTO comment_analysis(message_id) VALUES(?) ON CONFLICT DO NOTHING", id);
        else if (db.update("UPDATE comment_analysis SET status='pending',attempts=0,next_attempt_at=now(),last_error=NULL WHERE message_id=? AND status IN ('failed','blocked')", id) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "analysis_not_retryable");
    }

    public Map<String,Object> get(UUID id) {
        retention.requireContent(id);
        var rows = db.queryForList("""
            SELECT message_id AS "messageId",status,attempts,last_error AS "lastError",summary,category,importance,
                   has_question AS "hasQuestion",reply_draft AS "replyDraft",analyzed_at AS "analyzedAt",
                   memory_status AS "memoryStatus",memory_uri AS "memoryUri",memory_error AS "memoryError"
            FROM comment_analysis WHERE message_id=?
            """, id);
        if (rows.isEmpty()) return Map.of("messageId", id, "status", "not_requested");
        return ApiRows.normalize(rows).getFirst();
    }

    @Scheduled(fixedDelayString="${nexus.ai.tick-ms:10000}", initialDelay=500)
    void process() {
        if (!model.configured()) return;
        db.update("UPDATE comment_analysis SET status='failed',last_error='execution_interrupted',lease_until=NULL WHERE status='processing' AND attempts>=3 AND lease_until<now()");
        if (automatic) db.update("""
            INSERT INTO comment_analysis(message_id) SELECT message_id FROM inbox_messages m
            WHERE data_class='public_comment' AND %s AND NOT EXISTS (SELECT 1 FROM comment_analysis a WHERE a.message_id=m.message_id)
            ORDER BY received_at LIMIT 20 ON CONFLICT DO NOTHING
            """.formatted(ContentRetention.ELIGIBLE),retention.enabled());
        var work = transaction.execute(status -> {
            var rows = db.queryForList("""
                SELECT a.message_id,m.connector_id,m.upstream_event_id FROM comment_analysis a JOIN inbox_messages m USING(message_id)
                WHERE m.data_class='public_comment' AND %s AND a.attempts<3 AND
                  ((a.status='pending' AND a.next_attempt_at<=now()) OR (a.status='processing' AND a.lease_until<now()))
                ORDER BY a.next_attempt_at LIMIT 1 FOR UPDATE OF a SKIP LOCKED
                """.formatted(ContentRetention.ELIGIBLE),retention.enabled());
            if (rows.isEmpty()) return Map.<String,Object>of();
            var row = rows.getFirst();
            UUID lease = UUID.randomUUID(); row.put("lease", lease);
            db.update("UPDATE comment_analysis SET status='processing',attempts=attempts+1,lease_token=?,lease_until=now()+interval '2 minutes' WHERE message_id=?", lease, row.get("message_id"));
            return row;
        });
        if (work == null || work.isEmpty()) return;
        UUID id = (UUID) work.get("message_id"), lease = (UUID) work.get("lease");
        try {
            var publicMessage = halo.revalidatePublic((String) work.get("connector_id"), (String) work.get("upstream_event_id"));
            if (publicMessage.isEmpty()) {
                db.update("UPDATE comment_analysis SET status='blocked',last_error='no_longer_public',lease_until=NULL WHERE message_id=? AND lease_token=?", id, lease);
                return;
            }
            var input = publicMessage.get();
            var result = model.analyze(input.content());
            transaction.executeWithoutResult(status -> {
                if (db.update("""
                    UPDATE comment_analysis SET status='succeeded',summary=?,category=?,importance=?,has_question=?,reply_draft=?,
                         content_hash=?,analyzed_at=now(),last_error=NULL,lease_until=NULL WHERE message_id=? AND lease_token=?
                    """, result.summary(), result.category(), result.importance(), result.hasQuestion(), result.replyDraft(), hash(input.content()), id, lease) == 1)
                    db.update("UPDATE inbox_messages SET summary=? WHERE message_id=? AND data_class='public_comment'", result.summary(), id);
            });
        } catch (Exception failure) {
            String code = failure instanceof AnalysisFailure ? failure.getMessage() : "source_recheck_failed";
            db.update("""
                UPDATE comment_analysis SET status=CASE WHEN attempts<3 THEN 'pending' ELSE 'failed' END,
                   last_error=?,next_attempt_at=now()+interval '60 seconds',lease_until=NULL WHERE message_id=? AND lease_token=?
                """, code, id, lease);
        }
    }

    public static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
