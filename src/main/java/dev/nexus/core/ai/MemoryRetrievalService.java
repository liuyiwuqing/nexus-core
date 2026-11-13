package dev.nexus.core.ai;

import dev.nexus.core.connector.HaloConnector;
import dev.nexus.core.inbox.ContentRetention;
import dev.nexus.core.delivery.DeviceText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Rankings never grant access: revalidate the current public source for every
 * hit, then recheck the export ledger before returning text. Local body
 * retention expires the optional summary independently of confirmed copies. */
@Service
public class MemoryRetrievalService {
    private final OpenVikingClient memory;
    private final HaloConnector halo;
    private final JdbcTemplate db;
    private final ContentRetention retention;
    private final MemoryCopyService copies;
    private final Semaphore concurrent=new Semaphore(2);
    public MemoryRetrievalService(OpenVikingClient memory,HaloConnector halo,JdbcTemplate db,ContentRetention retention,MemoryCopyService copies) {
        this.memory=memory;this.halo=halo;this.db=db;this.retention=retention;this.copies=copies;
    }
    public record Hit(UUID messageId,String content,String summary,String originalUrl,Instant verifiedAt) {}
    public record Results(List<Hit> items,boolean partial,String dataPolicy) {}
    public Results search(String query,int limit) {
        if(!memory.configured())throw new ResponseStatusException(HttpStatus.CONFLICT,"memory_not_configured");
        if(!concurrent.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"memory_search_busy");
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos();
            List<UUID> candidates;
            try {candidates=memory.find(query,Math.min(limit*2,10));}
            catch(AnalysisFailure failure){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,failure.getMessage());}
            var hits=new ArrayList<Hit>();boolean partial=false;
            for(UUID id:candidates) {
                var rows=eligible(id);if(rows.isEmpty())continue;
                var row=rows.getFirst();long remaining=deadline-System.nanoTime();
                if(remaining<=0){partial=true;break;}
                try {
                    if(!halo.canRevalidate((String)row.get("connector_id"),(String)row.get("upstream_event_id"))) {
                        partial=true;continue;
                    }
                    var fresh=halo.revalidatePublic((String)row.get("connector_id"),(String)row.get("upstream_event_id"),
                        Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(2).toNanos())));
                    if(fresh.isEmpty()||!AnalysisService.hash(fresh.get().content()).equals(row.get("content_hash"))) {
                        copies.invalidateIfUnchanged(id,(String)row.get("content_hash"),fresh.isEmpty()?"no_longer_public":"comment_changed");continue;
                    }
                    // A purge/re-export may have happened during the source request.
                    var current=eligible(id);
                    if(current.isEmpty()||!row.get("content_hash").equals(current.getFirst().get("content_hash")))continue;
                    hits.add(new Hit(id,DeviceText.fit(fresh.get().content(),1200),
                        DeviceText.fit((String)current.getFirst().get("summary"),300),fresh.get().originalUrl(),Instant.now()));
                    if(hits.size()>=limit)break;
                }catch(Exception failure){partial=true;}
            }
            return new Results(hits,partial,"verified_public_comments_only");
        }finally{concurrent.release();}
    }
    private List<Map<String,Object>> eligible(UUID id) {
        return db.queryForList("""
            SELECT c.connector_id,c.upstream_event_id,c.content_hash,
                CASE WHEN a.content_hash=c.content_hash AND %s THEN coalesce(a.summary,'') ELSE '' END AS summary
            FROM memory_copies c LEFT JOIN inbox_messages m USING(message_id) LEFT JOIN comment_analysis a USING(message_id)
            WHERE c.message_id=? AND c.memory_uri=? AND c.memory_identity=? AND c.state='stored' AND c.content_hash IS NOT NULL
            """.formatted(ContentRetention.ELIGIBLE),retention.enabled(),id,memory.uri(id),memory.identity());
    }
}
