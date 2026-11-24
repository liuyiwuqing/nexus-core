package dev.nexus.core.device;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import dev.nexus.core.web.ApiRows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CompanionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    public CompanionService(JdbcTemplate db,ObjectMapper json,@Value("${nexus.companion.enabled:false}") boolean enabled) {
        this.db=db;this.json=json;this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    private JsonNode current(String device) {
        var rows=db.queryForList("SELECT body::text FROM device_companion_observations WHERE device_id=?",device);
        return rows.isEmpty()?null:json.readTree((String)rows.getFirst().get("body"));
    }
    private boolean online(String device) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT enabled AND online AND last_seen>now()-interval '90 seconds' FROM devices WHERE device_id=?",Boolean.class,device));
    }
    private boolean fresh(JsonNode v) {return v!=null&&v.path("observedAt").asLong()>Instant.now().minusSeconds(90).getEpochSecond();}
    public Map<String,Object> view(String device) {
        var devices=db.queryForList("SELECT name FROM devices WHERE device_id=?",device);
        if(devices.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        var snapshot=current(device);var result=new LinkedHashMap<String,Object>();
        result.put("enabled",enabled);result.put("deviceId",device);result.put("name",devices.getFirst().get("name"));
        result.put("snapshot",snapshot);result.put("fresh",enabled&&fresh(snapshot));result.put("online",online(device));
        result.put("requests",ApiRows.normalize(db.queryForList("""
            SELECT request_id AS "requestId",expected_revision AS "expectedRevision",status,attempts,last_error AS "lastError",
              created_at AS "createdAt",updated_at AS "updatedAt" FROM device_appearance_requests
            WHERE device_id=? ORDER BY created_at DESC LIMIT 10
            """,device)));
        return result;
    }
    public record Submitted(String requestId,boolean created) {}
    @Transactional
    public Submitted submit(String device,byte[] bytes,boolean connected) {
        var v=ManagementCodec.parse(bytes,768);
        ManagementCodec.fields(v,Set.of("requestId","expectedRevision","appearance"),Set.of());
        String id=CompanionCodec.requestId(v.get("requestId"));UUID uuid=UUID.fromString(id);
        long expected=ManagementCodec.integer(v.get("expectedRevision"),0,4294967294L);
        var appearance=CompanionCodec.appearance(v.get("appearance"));
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        var old=db.queryForList("SELECT expected_revision,appearance::text FROM device_appearance_requests WHERE device_id=? AND request_id=?",device,uuid);
        if(!old.isEmpty()) {
            if(((Number)old.getFirst().get("expected_revision")).longValue()!=expected||!json.readTree((String)old.getFirst().get("appearance")).equals(appearance))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
            return new Submitted(id,false);
        }
        var snapshot=current(device);
        if(!enabled||!connected||!online(device)||!fresh(snapshot))throw new ResponseStatusException(HttpStatus.CONFLICT,"companion_offline");
        if(!snapshot.path("writable").asBoolean())throw new ResponseStatusException(HttpStatus.CONFLICT,"companion_readonly");
        if(snapshot.path("appearanceRevision").asLong()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"appearance_revision_conflict");
        if(db.queryForObject("SELECT count(*) FROM device_appearance_requests WHERE device_id=? AND status IN ('pending','sent','unconfirmed')",Integer.class,device)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"appearance_result_pending");
        CompanionCodec.owned(appearance,snapshot.path("owned").asInt(),snapshot.path("themes").asInt());
        long issued=Instant.now().getEpochSecond();var envelope=json.createObjectNode();
        envelope.put("schemaVersion",1);envelope.put("catalogVersion",1);envelope.put("deviceId",device);envelope.put("requestId",id);
        envelope.put("expectedRevision",expected);envelope.put("issuedAt",issued);envelope.put("expiresAt",issued+30);envelope.set("appearance",appearance);
        db.update("INSERT INTO device_appearance_requests(device_id,request_id,expected_revision,appearance,envelope,status,expires_at) VALUES(?,?,?,?::jsonb,?::jsonb,'pending',?)",
            device,uuid,expected,appearance.toString(),envelope.toString(),Timestamp.from(Instant.ofEpochSecond(issued+30)));
        return new Submitted(id,true);
    }
    @Transactional
    public List<Map<String,Object>> claim(boolean connected) {
        db.update("UPDATE device_appearance_requests SET status=CASE WHEN attempts=0 THEN 'expired' ELSE 'unconfirmed' END,last_error='confirmation_timeout',updated_at=now() WHERE status IN ('pending','sent') AND expires_at<=now()");
        // Offline edits remain client drafts; newly disconnected subscribers must not receive them later.
        db.update("""
            UPDATE device_appearance_requests r SET status=CASE WHEN attempts=0 THEN 'expired' ELSE 'unconfirmed' END,last_error='device_offline',updated_at=now()
            WHERE status IN ('pending','sent') AND (? OR NOT EXISTS(SELECT 1 FROM devices d JOIN device_companion_observations o USING(device_id)
              WHERE d.device_id=r.device_id AND d.enabled AND d.online AND d.last_seen>now()-interval '90 seconds' AND (o.body->>'observedAt')::bigint>extract(epoch FROM now())-90))
            """,!connected);
        if(!enabled||!connected)return List.of();
        var rows=db.queryForList("SELECT device_id,request_id,envelope::text FROM device_appearance_requests WHERE status IN ('pending','sent') AND next_attempt_at<=now() AND expires_at>now() ORDER BY created_at LIMIT 8 FOR UPDATE SKIP LOCKED");
        for(var row:rows)db.update("UPDATE device_appearance_requests SET status='sent',attempts=attempts+1,next_attempt_at=now()+interval '5 seconds',updated_at=now() WHERE device_id=? AND request_id=?",row.get("device_id"),row.get("request_id"));
        return rows;
    }
    @Transactional
    public void publishFailed(String device,Object id) {db.update("UPDATE device_appearance_requests SET last_error='broker_unconfirmed',updated_at=now() WHERE device_id=? AND request_id=? AND status='sent'",device,id);}
    @Transactional
    public void acknowledge(String device,byte[] bytes,boolean retained) {
        if(!enabled||retained)return;
        var v=ManagementCodec.parse(bytes,512);ManagementCodec.fields(v,Set.of("schemaVersion","requestId","status","appearanceRevision","code"),Set.of());
        ManagementCodec.integer(v.get("schemaVersion"),1,1);UUID id=UUID.fromString(CompanionCodec.requestId(v.get("requestId")));
        String status=ManagementCodec.text(v.get("status"),20);
        if(!Set.of("applied","rejected","expired","conflict","storage_error","unconfirmed").contains(status))throw new IllegalArgumentException("invalid_appearance_status");
        long revision=ManagementCodec.integer(v.get("appearanceRevision"),0,4294967295L),code=ManagementCodec.integer(v.get("code"),0,255);
        var rows=db.queryForList("SELECT expected_revision FROM device_appearance_requests WHERE device_id=? AND request_id=? AND status IN ('sent','unconfirmed') AND attempts>0 FOR UPDATE",device,id);
        if(rows.isEmpty())return;
        long expected=((Number)rows.getFirst().get("expected_revision")).longValue();
        if(status.equals("applied")?(code!=0||revision!=expected+1):code==0)throw new IllegalArgumentException("inconsistent_appearance_ack");
        String terminal=Set.of("applied","expired","unconfirmed").contains(status)?status:"rejected";
        db.update("UPDATE device_appearance_requests SET status=?,last_error=?,updated_at=now() WHERE device_id=? AND request_id=?",terminal,status.equals("applied")?"":"device_"+status,device,id);
    }
    @Transactional
    public void observation(String device,byte[] bytes,boolean retained) {
        if(!enabled||retained)return;
        var value=CompanionCodec.snapshot(device,bytes);
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? AND enabled FOR UPDATE",device).isEmpty())return;
        var old=current(device);String boot=value.path("bootId").asText();long observed=value.path("observedAt").asLong();
        if(old!=null) {
            if(observed<old.path("observedAt").asLong())return;
            if(boot.equals(old.path("bootId").asText())) {if(value.path("reportSequence").asLong()<=old.path("reportSequence").asLong())return;}
            else if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM device_companion_boots WHERE device_id=? AND boot_id=?)",Boolean.class,device,boot)))return;
        }
        db.update("INSERT INTO device_companion_boots VALUES(?,?) ON CONFLICT DO NOTHING",device,boot);
        db.update("INSERT INTO device_companion_observations(device_id,body) VALUES(?,?::jsonb) ON CONFLICT(device_id) DO UPDATE SET body=excluded.body,received_at=now()",device,value.toString());
        if(!fresh(value)||!value.path("writable").asBoolean())return;
        var requests=db.queryForList("SELECT request_id,expected_revision,envelope::text,expires_at FROM device_appearance_requests WHERE device_id=? AND status IN ('sent','unconfirmed') FOR UPDATE",device);
        for(var r:requests) {
            if(observed<json.readTree((String)r.get("envelope")).path("issuedAt").asLong())continue;
            long expected=((Number)r.get("expected_revision")).longValue(),actual=value.path("appearanceRevision").asLong();
            boolean identity=r.get("request_id").toString().replace("-","").equals(value.path("lastCommand").asText());
            String status=null;
            if(identity&&actual>=expected+1)status="applied";
            else if(actual>expected)status="rejected";
            else if(observed>=((Timestamp)r.get("expires_at")).toInstant().getEpochSecond())status="expired";
            if(status!=null)db.update("UPDATE device_appearance_requests SET status=?,last_error=?,updated_at=now() WHERE device_id=? AND request_id=?",status,status.equals("applied")?"":"appearance_observed_conflict",device,r.get("request_id"));
        }
    }
}
