package dev.nexus.core.ai;

import dev.nexus.core.connector.HaloConnector;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Content-free ledger and deletion outbox. A write reservation survives a
 * crash or local retention; a deletion cannot overtake a known in-flight write. */
@Service
public class MemoryCopyService {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final OpenVikingClient memory;
    private final HaloConnector halo;
    public MemoryCopyService(JdbcTemplate db,TransactionTemplate transaction,OpenVikingClient memory,
                             HaloConnector halo) {
        this.db=db;this.transaction=transaction;this.memory=memory;this.halo=halo;
    }
    public Map<String,Object> health() {
        return Map.of("configured",memory.configured(),"dataPolicy","public_comments_only",
            "invalidationPolicy","stop_retrieval_and_delete",
            "storedCopies",count("stored"),"pendingDeletions",count("delete_pending")+count("deleting"),
            "deletedCopies",count("deleted"),"deletionErrors",db.queryForObject(
                "SELECT count(*) FROM memory_copies WHERE state IN ('delete_pending','deleting') AND last_error IS NOT NULL",Long.class),
            "identityBlockedCopies",db.queryForObject("SELECT count(*) FROM memory_copies WHERE state<>'deleted' AND memory_identity IS DISTINCT FROM ?",Long.class,memory.identity()));
    }
    private long count(String state) {
        return db.queryForObject("SELECT count(*) FROM memory_copies WHERE state=?",Long.class,state);
    }
    public void requireSaveable(UUID id) {
        if(db.queryForObject("SELECT count(*) FROM memory_copies WHERE message_id=? AND memory_identity IS DISTINCT FROM ?",Integer.class,id,memory.identity())>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"memory_identity_changed");
        if(db.queryForObject("SELECT count(*) FROM memory_copies WHERE message_id=? AND state IN ('writing','delete_pending','deleting')",Integer.class,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"memory_delete_pending");
    }
    /** Called after the current public source/analysis lease are verified and
     * while the local message's shared lock prevents retention racing the reservation. */
    public boolean reserve(UUID id,String hash,String connector,String event,UUID lease) {
        return db.update("""
            INSERT INTO memory_copies(message_id,memory_uri,memory_identity,content_hash,connector_id,upstream_event_id,state,write_until,write_token)
            VALUES(?,?,?,?,?,?,'writing',now()+interval '2 minutes',?)
            ON CONFLICT(message_id) DO UPDATE SET memory_uri=excluded.memory_uri,content_hash=excluded.content_hash,
                connector_id=excluded.connector_id,upstream_event_id=excluded.upstream_event_id,
                state='writing',write_until=excluded.write_until,write_token=excluded.write_token,lease_token=NULL,lease_until=NULL,
                attempts=0,reason=NULL,last_error=NULL,checked_at=now(),updated_at=now()
            WHERE memory_copies.state='deleted' AND memory_copies.memory_uri=excluded.memory_uri
                AND memory_copies.memory_identity=excluded.memory_identity
            """,id,memory.uri(id),memory.identity(),hash,connector,event,lease)==1;
    }
    public void cancelUnfinishedExport(UUID id) {
        // A confirmed external copy follows source visibility, independently of
        // local 7/30-day retention. Only unfinished writes need compensation.
        db.update("UPDATE memory_copies SET state='delete_pending',reason='content_retention_expired',next_attempt_at=now(),updated_at=now() WHERE message_id=? AND state='writing'",id);
    }
    public void invalidateIfUnchanged(UUID id,String hash,String reason) {
        transaction.executeWithoutResult(status->{
            var rows=db.queryForList("SELECT content_hash FROM memory_copies WHERE message_id=? AND state='stored' FOR UPDATE",id);
            if(!rows.isEmpty()&&hash.equals(rows.getFirst().get("content_hash")))invalidate(id,reason);
        });
    }
    public void invalidate(UUID id,String reason) {
        transaction.executeWithoutResult(status->{
            db.update("""
                UPDATE memory_copies SET state='delete_pending',reason=?,next_attempt_at=now(),updated_at=now()
                WHERE message_id=? AND state IN ('writing','stored')
                """,reason,id);
            db.update("""
                UPDATE comment_analysis SET status='blocked',last_error=?,lease_token=NULL,lease_until=NULL,
                    memory_status='blocked',memory_error=?,memory_lease_token=NULL,memory_lease_until=NULL
                WHERE message_id=?
                """,reason,reason,id);
        });
    }
    /** The caller has validated its analysis lease and current retention state. */
    public boolean stored(UUID id,UUID lease) {
        return db.update("UPDATE memory_copies SET state='stored',write_until=NULL,checked_at=now(),updated_at=now() WHERE message_id=? AND state='writing' AND write_token=?",id,lease)==1;
    }
    public void writeFinished(UUID id,UUID lease) {
        db.update("UPDATE memory_copies SET write_until=NULL WHERE message_id=? AND write_token=?",id,lease);
    }
    @Scheduled(fixedDelayString="${nexus.memory.maintenance-ms:5000}",initialDelay=1500)
    void maintain() {
        if(!memory.configured())return;
        // A timed-out writer may have committed remotely. Delete its fixed URI
        // before another explicit save can reuse it.
        var abandoned=db.queryForList("SELECT message_id FROM memory_copies WHERE state='writing' AND write_until<now() LIMIT 10",UUID.class);
        for(UUID id:abandoned)invalidate(id,"memory_write_unconfirmed");
        var rows=db.queryForList("""
            SELECT c.message_id,c.content_hash,c.connector_id,c.upstream_event_id FROM memory_copies c
            WHERE c.state='stored' AND c.memory_identity=? AND c.checked_at<=now()-interval '1 minute'
            ORDER BY c.checked_at LIMIT 1
            """,memory.identity());
        if(!rows.isEmpty()) {
            var row=rows.getFirst();UUID id=(UUID)row.get("message_id");
            try {
                if(!halo.canRevalidate((String)row.get("connector_id"),(String)row.get("upstream_event_id")))
                    throw new AnalysisFailure("source_recheck_failed");
                var fresh=halo.revalidatePublic((String)row.get("connector_id"),(String)row.get("upstream_event_id"),Duration.ofSeconds(2));
                if(fresh.isEmpty())invalidateIfUnchanged(id,(String)row.get("content_hash"),"no_longer_public");
                else if(!AnalysisService.hash(fresh.get().content()).equals(row.get("content_hash")))invalidateIfUnchanged(id,(String)row.get("content_hash"),"comment_changed");
                db.update("UPDATE memory_copies SET checked_at=now(),last_error=NULL WHERE message_id=? AND state='stored'",id);
            }catch(Exception failure) {
                db.update("UPDATE memory_copies SET checked_at=now(),last_error='source_recheck_failed' WHERE message_id=? AND state='stored'",id);
            }
        }
        deleteOne();
    }
    private void deleteOne() {
        var work=transaction.execute(status->{
            var rows=db.queryForList("""
                SELECT message_id,memory_uri,memory_identity FROM memory_copies WHERE
                    (state='delete_pending' OR (state='deleting' AND lease_until<now()))
                    AND next_attempt_at<=now() AND (write_until IS NULL OR write_until<now())
                ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if(rows.isEmpty())return Map.<String,Object>of();
            var row=rows.getFirst();UUID lease=UUID.randomUUID();row.put("lease",lease);
            db.update("UPDATE memory_copies SET state='deleting',lease_token=?,lease_until=now()+interval '1 minute',attempts=attempts+1 WHERE message_id=?",lease,row.get("message_id"));
            return row;
        });
        if(work==null||work.isEmpty())return;
        UUID id=(UUID)work.get("message_id"),lease=(UUID)work.get("lease");
        try {
            if(!memory.uri(id).equals(work.get("memory_uri"))||!memory.identity().equals(work.get("memory_identity")))
                throw new AnalysisFailure("memory_identity_changed");
            memory.delete(id);
            db.update("UPDATE memory_copies SET state='deleted',lease_until=NULL,last_error=NULL,updated_at=now() WHERE message_id=? AND lease_token=?",id,lease);
        }catch(Exception failure) {
            db.update("""
                UPDATE memory_copies SET state='delete_pending',lease_until=NULL,last_error=?,
                    next_attempt_at=now()+least(attempts,60)*interval '1 minute',updated_at=now()
                WHERE message_id=? AND lease_token=?
                """,failure instanceof AnalysisFailure?failure.getMessage():"memory_delete_failed",id,lease);
        }
    }
}
