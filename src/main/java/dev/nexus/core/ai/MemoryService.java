package dev.nexus.core.ai;

import dev.nexus.core.connector.HaloConnector;
import dev.nexus.core.inbox.ContentRetention;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemoryService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final HaloConnector halo;
    private final OpenVikingClient memory;
    private final ContentRetention retention;
    private final MemoryCopyService copies;
    public MemoryService(JdbcTemplate db, TransactionTemplate transaction, HaloConnector halo, OpenVikingClient memory,ContentRetention retention,MemoryCopyService copies) {
        this.db = db; this.transaction = transaction; this.halo = halo; this.memory = memory;
        this.retention=retention;
        this.copies=copies;
    }
    public boolean configured() { return memory.configured(); }
    @Transactional
    public void enqueue(UUID id) {
        if (!"public_comment".equals(retention.requireContent(id))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "data_policy_denied");
        if (!configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, "memory_not_configured");
        if (db.queryForObject("SELECT count(*) FROM comment_analysis WHERE message_id=? AND status='succeeded'", Integer.class, id) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "analysis_required");
        copies.requireSaveable(id);
        db.update("""
            UPDATE comment_analysis SET memory_status='pending',memory_attempts=0,memory_next_attempt_at=now(),memory_error=NULL
            WHERE message_id=? AND memory_status IN ('off','error','blocked')
            """, id);
    }
    @Scheduled(fixedDelayString="${nexus.memory.tick-ms:3000}", initialDelay=800)
    void process() {
        if (!configured()) return;
        db.update("UPDATE comment_analysis SET memory_status='error',memory_error='execution_interrupted',memory_lease_until=NULL WHERE memory_status='pending' AND memory_attempts>=3 AND memory_lease_until<now()");
        var work = transaction.execute(status -> {
            var rows = db.queryForList("""
                SELECT a.message_id,m.connector_id,m.upstream_event_id,a.summary,a.category,a.content_hash
                FROM comment_analysis a JOIN inbox_messages m USING(message_id)
                WHERE a.status='succeeded' AND a.memory_status='pending' AND m.data_class='public_comment' AND %s
                  AND a.memory_attempts<3 AND a.memory_next_attempt_at<=now() AND (a.memory_lease_until IS NULL OR a.memory_lease_until<now())
                ORDER BY a.memory_next_attempt_at LIMIT 1 FOR UPDATE OF a SKIP LOCKED
                """.formatted(ContentRetention.ELIGIBLE),retention.enabled());
            if (rows.isEmpty()) return Map.<String,Object>of();
            var row = rows.getFirst(); UUID lease = UUID.randomUUID(); row.put("lease", lease);
            db.update("UPDATE comment_analysis SET memory_lease_token=?,memory_lease_until=now()+interval '2 minutes',memory_attempts=memory_attempts+1 WHERE message_id=?", lease, row.get("message_id"));
            return row;
        });
        if (work == null || work.isEmpty()) return;
        UUID id = (UUID) work.get("message_id"), lease = (UUID) work.get("lease");
        boolean reserved=false;
        try {
            var fresh = halo.revalidatePublic((String) work.get("connector_id"), (String) work.get("upstream_event_id"));
            if (fresh.isEmpty() || !AnalysisService.hash(fresh.get().content()).equals(work.get("content_hash"))) {
                db.update("""
                    UPDATE comment_analysis SET memory_status='blocked',memory_error=?,memory_lease_until=NULL,
                        status=CASE WHEN ? THEN 'blocked' ELSE status END,
                        last_error=CASE WHEN ? THEN 'comment_changed' ELSE last_error END
                    WHERE message_id=? AND memory_lease_token=?
                    """,fresh.isEmpty()?"no_longer_public":"comment_changed",fresh.isPresent(),fresh.isPresent(),id,lease);
                return;
            }
            reserved=Boolean.TRUE.equals(transaction.execute(status->{
                var current=db.queryForList("SELECT message_id FROM inbox_messages m WHERE message_id=? AND "+ContentRetention.ELIGIBLE+" FOR SHARE",id,retention.enabled());
                return !current.isEmpty()&&db.queryForObject("SELECT count(*) FROM comment_analysis WHERE message_id=? AND status='succeeded' AND memory_status='pending' AND memory_lease_token=?",Integer.class,id,lease)==1
                    &&copies.reserve(id,(String)work.get("content_hash"),(String)work.get("connector_id"),(String)work.get("upstream_event_id"),lease);
            }));
            if(!reserved)return;
            String content = "# 公开博客评论\n\n来源: " + fresh.get().originalUrl() + "\n\n" + fresh.get().content()
                + "\n\n## AI 摘要（推断，非事实来源）\n\n" + work.get("summary") + "\n分类: " + work.get("category") + "\n";
            String uri = memory.write(id, content);
            transaction.executeWithoutResult(status->{
                // Retention locks message first, then copy/analysis; use the same
                // order to prevent purging and completion from deadlocking.
                var current=db.queryForList("SELECT message_id FROM inbox_messages m WHERE message_id=? AND "+ContentRetention.ELIGIBLE+" FOR SHARE",id,retention.enabled());
                if(!current.isEmpty()&&db.queryForObject("SELECT count(*) FROM comment_analysis WHERE message_id=? AND status='succeeded' AND memory_status='pending' AND memory_lease_token=? AND content_hash=?",Integer.class,id,lease,work.get("content_hash"))==1&&copies.stored(id,lease))
                    db.update("UPDATE comment_analysis SET memory_status='stored',memory_uri=?,memory_at=now(),memory_error=NULL,memory_lease_until=NULL WHERE message_id=? AND memory_lease_token=?",uri,id,lease);
                else copies.invalidate(id,"content_retention_expired");
            });
        } catch (Exception failure) {
            if(reserved)copies.invalidate(id,"memory_write_unconfirmed");
            else db.update("""
                UPDATE comment_analysis SET memory_status=CASE WHEN memory_attempts<3 THEN 'pending' ELSE 'error' END,
                    memory_error='source_recheck_failed',memory_next_attempt_at=now()+interval '60 seconds',memory_lease_until=NULL
                WHERE message_id=? AND memory_lease_token=?
                """,id,lease);
        } finally {if(reserved)copies.writeFinished(id,lease);}
    }
}
