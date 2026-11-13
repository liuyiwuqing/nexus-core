package dev.nexus.core.inbox;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Outbound work must stop at the deadline, even before the next cleanup batch. */
@Component
public class ContentRetention {
    public static final String ELIGIBLE = "m.content_purged_at IS NULL AND (NOT ? OR m.received_at>now()-interval '7 days')";
    private final JdbcTemplate db;
    private final boolean enabled;
    public ContentRetention(JdbcTemplate db,@Value("${nexus.retention.enabled:false}") boolean enabled) {
        this.db=db;this.enabled=enabled;
    }
    public boolean enabled() {return enabled;}
    /** Enqueue callers hold their transaction's shared row lock until work is reserved. */
    public String requireContent(UUID id) {
        var rows=db.queryForList("SELECT m.data_class,("+ELIGIBLE+") AS eligible FROM inbox_messages m WHERE message_id=? FOR SHARE",enabled,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(!Boolean.TRUE.equals(rows.getFirst().get("eligible")))
            throw new ResponseStatusException(HttpStatus.GONE,"content_retention_expired");
        return (String)rows.getFirst().get("data_class");
    }
}
