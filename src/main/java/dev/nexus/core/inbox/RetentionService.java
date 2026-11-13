package dev.nexus.core.inbox;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import dev.nexus.core.web.ApiRows;
import dev.nexus.core.ai.MemoryCopyService;

/** One bounded transaction; preview is read-only and enabling requires deployment opt-in. */
@Service
public class RetentionService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final MemoryCopyService copies;
    private static final int BATCH=200;
    public RetentionService(JdbcTemplate db,TransactionTemplate transaction,MemoryCopyService copies,
        @Value("${nexus.retention.enabled:false}") boolean enabled) {
        this.db=db;this.transaction=transaction;this.enabled=enabled;this.copies=copies;
    }
    public Map<String,Object> preview() {
        return Map.of("enabled",enabled,"bodyDays",7,"metadataDays",30,"batchSize",BATCH,
            "bodiesDue",db.queryForObject("SELECT count(*) FROM inbox_messages WHERE received_at<=now()-interval '7 days' AND content_purged_at IS NULL",Long.class),
            "messagesDue",db.queryForObject("SELECT count(*) FROM inbox_messages WHERE received_at<=now()-interval '30 days'",Long.class),
            "history",ApiRows.normalize(db.queryForList("SELECT actor,bodies_purged AS \"bodiesPurged\",messages_deleted AS \"messagesDeleted\",created_at AS \"createdAt\" FROM retention_audit ORDER BY sequence DESC LIMIT 25")));
    }
    public Map<String,Integer> run(String actor) {
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"retention_disabled");
        return transaction.execute(status->{
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(726384901)",Boolean.class)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"retention_busy");
            int deleted=0,purged=0;
            var expired=db.queryForList("SELECT message_id FROM inbox_messages WHERE received_at<=now()-interval '30 days' ORDER BY received_at,message_id LIMIT ? FOR UPDATE",UUID.class,BATCH);
            for(UUID id:expired) {
                removeMessageWork(id,true);
                db.update("DELETE FROM deliveries WHERE message_id=?",id);
                deleted+=db.update("DELETE FROM inbox_messages WHERE message_id=?",id);
            }
            var bodies=db.queryForList("SELECT message_id FROM inbox_messages WHERE received_at<=now()-interval '7 days' AND content_purged_at IS NULL ORDER BY received_at,message_id LIMIT ? FOR UPDATE",UUID.class,BATCH);
            for(UUID id:bodies) {
                removeMessageWork(id,false);
                db.update("UPDATE deliveries SET status='expired',last_error='content_retention_expired' WHERE message_id=? AND status IN ('pending','sent','received','displayed','failed')",id);
                purged+=db.update("UPDATE inbox_messages SET title='',content='',summary='',sender='',original_url='',metadata='{}'::jsonb,content_purged_at=now() WHERE message_id=?",id);
            }
            db.update("DELETE FROM retention_audit WHERE created_at<now()-interval '30 days'");
            db.update("INSERT INTO retention_audit(actor,bodies_purged,messages_deleted) VALUES(?,?,?)",actor,purged,deleted);
            return Map.of("bodiesPurged",purged,"messagesDeleted",deleted);
        });
    }
    private void removeMessageWork(UUID id,boolean removeMetadata) {
        copies.cancelUnfinishedExport(id);
        // Lease-bound completion cannot recreate an analysis row after this transaction deletes it.
        db.update("DELETE FROM comment_analysis WHERE message_id=?",id);
        if(removeMetadata) {
            db.update("DELETE FROM automation_runs WHERE event_id IN (SELECT event_id FROM core_events WHERE type='message.received' AND payload->>'messageId'=?)",id.toString());
            db.update("DELETE FROM core_events WHERE type='message.received' AND payload->>'messageId'=?",id.toString());
        } else {
            db.update("UPDATE automation_runs SET state='cancelled',last_error='content_retention_expired',completed_at=now(),lease_until=NULL,lease_token=NULL WHERE state IN ('pending','executing') AND event_id IN (SELECT event_id FROM core_events WHERE type='message.received' AND payload->>'messageId'=?)",id.toString());
        }
    }
    @Scheduled(cron="${nexus.retention.cron:0 0 4 * * *}",zone="UTC")
    void scheduled() {
        if(!enabled)return;
        try {run("scheduler");}
        catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RetentionService.class).warn("Message retention failed; transaction rolled back, inspect authenticated preview");
        }
    }
}
