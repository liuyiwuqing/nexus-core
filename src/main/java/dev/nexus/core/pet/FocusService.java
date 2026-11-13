package dev.nexus.core.pet;

import dev.nexus.core.events.EventJournal;
import dev.nexus.core.web.ApiRows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** CORE has its own profile; existing device NVS is never overwritten or imported implicitly. */
@Service
public class FocusService {
    private final JdbcTemplate db;
    private final EventJournal events;
    public FocusService(JdbcTemplate db, EventJournal events) { this.db = db; this.events = events; }

    public Map<String,Object> profile() {
        var row = db.queryForMap("SELECT experience,coins,bond,1+experience/100 AS level,experience%100 AS \"levelProgress\",updated_at AS \"updatedAt\" FROM pet_profile WHERE id=1");
        row.put("completedFocus", db.queryForObject("SELECT count(*) FROM focus_sessions WHERE state='completed'", Long.class));
        row.put("activeSession", sessions().stream().filter(s -> "active".equals(s.get("state"))).findFirst().orElse(null));
        return ApiRows.normalize(List.of(row)).getFirst();
    }

    public List<Map<String,Object>> sessions() {
        return ApiRows.normalize(db.queryForList("""
            SELECT s.session_id AS "sessionId",s.duration_minutes AS "durationMinutes",s.state,s.started_at AS "startedAt",
                s.started_at+s.duration_minutes*interval '1 minute' AS "dueAt",s.completed_at AS "completedAt",
                GREATEST(0,ceil(extract(epoch FROM s.started_at+s.duration_minutes*interval '1 minute'-now())))::integer AS "remainingSeconds",
                COALESCE(r.experience,0) AS "experienceReward",COALESCE(r.coins,0) AS "coinReward"
            FROM focus_sessions s LEFT JOIN growth_rewards r USING(session_id) ORDER BY s.started_at DESC LIMIT 50
            """));
    }

    @Transactional
    public UUID start(UUID requestKey, int minutes) {
        lockProfile();
        var prior = db.queryForList("SELECT session_id,duration_minutes FROM focus_sessions WHERE request_key=?", requestKey);
        if (!prior.isEmpty()) {
            if (!prior.getFirst().get("duration_minutes").equals(minutes)) throw conflict("idempotency_conflict");
            return (UUID) prior.getFirst().get("session_id");
        }
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM focus_sessions WHERE state='active')", Boolean.class)))
            throw conflict("focus_already_active");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO focus_sessions(session_id,request_key,duration_minutes) VALUES(?,?,?)", id, requestKey, minutes);
        return id;
    }

    @Transactional
    public Map<String,Object> complete(UUID id) {
        lockProfile();
        var session = require(id);
        if (session.get("state").equals("completed")) return profile();
        if (!session.get("state").equals("active")) throw conflict("focus_not_active");
        if (!Boolean.TRUE.equals(session.get("elapsed"))) throw conflict("focus_not_finished");
        int minutes = (Integer) session.get("duration_minutes");
        int earnedToday = db.queryForObject("SELECT COALESCE(sum(experience),0)::integer FROM growth_rewards WHERE (created_at AT TIME ZONE 'UTC')::date=(now() AT TIME ZONE 'UTC')::date", Integer.class);
        int experience = Math.min(Math.min(minutes, 120), Math.max(0, 240-earnedToday));
        int coins = experience == 0 ? 0 : Math.min(20, Math.max(1, experience/5));
        int bond = experience == 0 ? 0 : 1;
        db.update("INSERT INTO growth_rewards(session_id,experience,coins,bond) VALUES(?,?,?,?)", id, experience, coins, bond);
        db.update("UPDATE pet_profile SET experience=LEAST(1000000,experience+?),coins=LEAST(999999,coins+?),bond=LEAST(100,bond+?),updated_at=now() WHERE id=1", experience, coins, bond);
        db.update("UPDATE focus_sessions SET state='completed',completed_at=now() WHERE session_id=?", id);
        events.focusCompleted(id, minutes, experience);
        return profile();
    }

    @Transactional
    public void cancel(UUID id) {
        lockProfile();
        var session = require(id);
        if (session.get("state").equals("cancelled")) return;
        if (!session.get("state").equals("active")) throw conflict("focus_not_active");
        db.update("UPDATE focus_sessions SET state='cancelled',completed_at=now() WHERE session_id=?", id);
    }

    private void lockProfile() { db.queryForObject("SELECT id FROM pet_profile WHERE id=1 FOR UPDATE", Integer.class); }
    private Map<String,Object> require(UUID id) {
        var rows = db.queryForList("SELECT state,duration_minutes,(started_at+duration_minutes*interval '1 minute'<=now()) AS elapsed FROM focus_sessions WHERE session_id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
