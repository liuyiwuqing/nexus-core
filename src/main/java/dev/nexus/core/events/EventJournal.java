package dev.nexus.core.events;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Called inside the owning transaction; events are not emitted before business state commits. */
@Component
public class EventJournal {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public EventJournal(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    public void messageReceived(UUID message, String source, String dataClass) {
        db.update("INSERT INTO core_events(event_id,event_key,type,source,payload) VALUES(?,?,'message.received',?,?::jsonb) ON CONFLICT(event_key) DO NOTHING",
            UUID.randomUUID(), "message:" + message, source, json.writeValueAsString(Map.of("messageId", message, "dataClass", dataClass)));
    }
    public void daily(UUID rule, int version, String day) {
        db.update("""
            INSERT INTO core_events(event_id,event_key,type,source,payload,target_rule_id,rule_version)
            VALUES(?,?,'schedule.daily','scheduler',?::jsonb,?,?) ON CONFLICT(event_key) DO NOTHING
            """, UUID.randomUUID(), "daily:" + rule + ":" + day,
            json.writeValueAsString(Map.of("localDate", day)), rule, version);
    }
    public void focusCompleted(UUID session, int minutes, int experience) {
        db.update("INSERT INTO core_events(event_id,event_key,type,source,payload) VALUES(?,?,'focus.completed','pet',?::jsonb) ON CONFLICT(event_key) DO NOTHING",
            UUID.randomUUID(), "focus:" + session, json.writeValueAsString(Map.of("sessionId", session, "minutes", minutes, "experience", experience)));
    }
}
