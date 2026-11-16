package dev.nexus.core.device;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class DeviceManagementService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TelemetryHistoryService history;
    private final int lifetime,retrySeconds;
    public DeviceManagementService(JdbcTemplate db,ObjectMapper json,TelemetryHistoryService history,
        @Value("${nexus.management.request-seconds:300}") int lifetime,
        @Value("${nexus.management.retry-seconds:15}") int retrySeconds) {
        if(lifetime<1||lifetime>300||retrySeconds<1||retrySeconds>60)throw new IllegalArgumentException("invalid_management_timing");
        this.db=db;this.json=json;this.history=history;this.lifetime=lifetime;this.retrySeconds=retrySeconds;
    }

    public Map<String,Object> view(String device) {
        if(db.queryForObject("SELECT count(*) FROM devices WHERE device_id=?",Integer.class,device)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        var rows=db.queryForList("SELECT body::text,observed_at,received_at FROM device_telemetry WHERE device_id=?",device);
        var result=new LinkedHashMap<String,Object>();result.put("deviceId",device);result.put("fresh",false);
        result.put("telemetry",null);result.put("receivedAt",null);
        var requests=ApiRows.normalize(db.queryForList("""
            SELECT request_id AS "requestId",expected_revision AS "expectedRevision",status,attempts,
                reported_revision AS "reportedRevision",device_code AS "deviceCode",last_error AS "lastError",
                confirmation_source AS "confirmationSource",config::text AS "requestedConfig",
                created_at AS "createdAt",updated_at AS "updatedAt",expires_at AS "expiresAt"
            FROM device_config_requests WHERE device_id=? ORDER BY created_at DESC LIMIT 25
            """,device));
        for(var request:requests)request.put("requestedConfig",json.readTree((String)request.get("requestedConfig")));
        result.put("configRequests",requests);
        if(!rows.isEmpty()) {
            var row=rows.getFirst();Instant observed=((Timestamp)row.get("observed_at")).toInstant();
            result.put("telemetry",ManagementCodec.parse(((String)row.get("body")).getBytes(java.nio.charset.StandardCharsets.UTF_8),2048));
            result.put("fresh",observed.isAfter(Instant.now().minusSeconds(90)));
            result.put("receivedAt",((Timestamp)row.get("received_at")).toInstant().toString());
        }
        String block=configurationBlock(device,Boolean.TRUE.equals(result.get("fresh")),(JsonNode)result.get("telemetry"));
        result.put("canConfigure",block.isEmpty());result.put("configBlock",block);
        return result;
    }

    private String configurationBlock(String device,boolean fresh,JsonNode current) {
        if(!fresh||current==null||!current.has("config"))return "config_readback_required";
        if(!current.path("configWritable").asBoolean())return "config_storage_readonly";
        if(db.queryForObject("SELECT count(*) FROM device_config_requests WHERE device_id=? AND status='unconfirmed'",Integer.class,device)>0)
            return "config_result_unconfirmed";
        if(db.queryForObject("SELECT count(*) FROM device_config_requests WHERE device_id=? AND status IN ('pending','sent')",Integer.class,device)>0)
            return "config_request_in_progress";
        if(db.queryForObject("SELECT count(*) FROM device_config_requests WHERE device_id=? AND status='applied' AND reported_revision>?",Integer.class,device,current.path("configRevision").asLong())>0)
            return "config_readback_required";
        return "";
    }

    public record Submitted(String requestId,boolean created) {}

    @Transactional
    public Submitted submit(String device,byte[] bytes) {
        var body=ManagementCodec.parse(bytes,1200);
        ManagementCodec.fields(body,Set.of("requestId","expectedRevision","config"),Set.of());
        String id=ManagementCodec.text(body.get("requestId"),40);
        if(!id.matches("[a-zA-Z0-9_-]{1,40}"))throw new IllegalArgumentException("invalid_request_id");
        long expected=ManagementCodec.integer(body.get("expectedRevision"),0,4294967294L);
        var config=ManagementCodec.config(body.get("config"));
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        var previous=db.queryForList("SELECT expected_revision,config::text FROM device_config_requests WHERE device_id=? AND request_id=?",device,id);
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            if(((Number)old.get("expected_revision")).longValue()!=expected||!json.readTree((String)old.get("config")).equals(config))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
            return new Submitted(id,false);
        }
        var view=view(device);var current=(JsonNode)view.get("telemetry");
        String block=(String)view.get("configBlock");
        if(!block.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,block);
        if(current.path("configRevision").asLong()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"config_revision_conflict");
        long issued=Instant.now().getEpochSecond();
        var envelope=json.createObjectNode();envelope.put("schemaVersion",1);envelope.put("deviceId",device);envelope.put("requestId",id);
        envelope.put("expectedRevision",expected);envelope.put("issuedAt",issued);envelope.put("expiresAt",issued+lifetime);envelope.set("config",config);
        if(ManagementCodec.bytes(envelope).length>1200)throw new IllegalArgumentException("config_envelope_too_large");
        db.update("""
            INSERT INTO device_config_requests(device_id,request_id,expected_revision,config,envelope,status,expires_at)
            VALUES(?,?,?,?::jsonb,?::jsonb,'pending',?)
            """,device,id,expected,config.toString(),envelope.toString(),Timestamp.from(Instant.ofEpochSecond(issued+lifetime)));
        audit(device,id,"admin","created","");
        return new Submitted(id,true);
    }

    public List<Map<String,Object>> history(String device,String request) {
        if(db.queryForObject("SELECT count(*) FROM device_config_requests WHERE device_id=? AND request_id=?",Integer.class,device,request)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"config_request_not_found");
        return ApiRows.normalize(db.queryForList("SELECT sequence,actor,event,detail,created_at AS \"createdAt\" FROM device_config_audit WHERE device_id=? AND request_id=? ORDER BY sequence LIMIT 100",device,request));
    }
    private void audit(String device,String request,String actor,String event,String detail) {
        db.update("INSERT INTO device_config_audit(device_id,request_id,actor,event,detail) VALUES(?,?,?,?,?)",device,request,actor,event,detail);
    }

    /** Reserve before publishing: a crash after this point must be treated as an unknown device result. */
    @Transactional
    public List<Map<String,Object>> claim(boolean connected) {
        var ended=db.queryForList("""
            UPDATE device_config_requests SET status=CASE WHEN attempts=0 THEN 'expired' ELSE 'unconfirmed' END,
                last_error=CASE WHEN attempts=0 THEN 'config_not_sent' ELSE 'config_ack_timeout' END,updated_at=now()
            WHERE status IN ('pending','sent') AND expires_at<=now() RETURNING device_id,request_id,status,last_error
            """);
        for(var row:ended)audit((String)row.get("device_id"),(String)row.get("request_id"),"core",(String)row.get("status"),(String)row.get("last_error"));
        if(!connected)return List.of();
        var work=db.queryForList("""
            SELECT device_id,request_id,envelope::text FROM device_config_requests
            WHERE status IN ('pending','sent') AND expires_at>now() AND next_attempt_at<=now()
            ORDER BY created_at LIMIT 8 FOR UPDATE SKIP LOCKED
            """);
        for(var row:work) {
            db.update("UPDATE device_config_requests SET status='sent',attempts=attempts+1,next_attempt_at=now()+(?*interval '1 second'),updated_at=now() WHERE device_id=? AND request_id=?",
                retrySeconds,row.get("device_id"),row.get("request_id"));
            audit((String)row.get("device_id"),(String)row.get("request_id"),"core","publish_attempt","");
        }
        return work;
    }
    @Transactional
    public void publishFailed(String device,String id) {
        // Publication can have succeeded before a local timeout. Keep it retryable and ambiguous.
        if(db.update("UPDATE device_config_requests SET last_error='broker_unavailable',updated_at=now() WHERE device_id=? AND request_id=? AND status='sent'",device,id)>0)
            audit(device,id,"core","publish_unconfirmed","broker_unavailable");
    }
    @Transactional
    public void acknowledge(String device,byte[] bytes,boolean retained) {
        if(retained)return;
        var value=ManagementCodec.parse(bytes,512);
        ManagementCodec.fields(value,Set.of("schemaVersion","requestId","status","revision","code"),Set.of());
        ManagementCodec.integer(value.get("schemaVersion"),1,1);
        String id=ManagementCodec.text(value.get("requestId"),40),status=ManagementCodec.text(value.get("status"),24);
        long revision=ManagementCodec.integer(value.get("revision"),0,4294967295L);
        int code=(int)ManagementCodec.integer(value.get("code"),Integer.MIN_VALUE,Integer.MAX_VALUE);
        if(!id.matches("[a-zA-Z0-9_-]{1,40}")||!Set.of("applied","rejected","expired","conflict_or_storage").contains(status))
            throw new IllegalArgumentException("invalid_config_ack");
        var rows=db.queryForList("SELECT expected_revision,status,attempts FROM device_config_requests WHERE device_id=? AND request_id=? FOR UPDATE",device,id);
        if(rows.isEmpty())return;
        var row=rows.getFirst();long expected=((Number)row.get("expected_revision")).longValue();
        if(!Set.of("sent","unconfirmed").contains(row.get("status"))||((Number)row.get("attempts")).intValue()==0)return;
        if(status.equals("applied")?(code!=0||(revision!=expected&&revision!=expected+1)):code==0)
            throw new IllegalArgumentException("inconsistent_config_ack");
        String terminal=status.equals("applied")?"applied":status.equals("expired")?"expired":"rejected";
        String error=status.equals("applied")?"":"device_"+status;
        db.update("UPDATE device_config_requests SET status=?,reported_revision=?,device_code=?,last_error=?,confirmation_source='ack',updated_at=now() WHERE device_id=? AND request_id=?",
            terminal,revision,code,error,device,id);
        audit(device,id,"device",terminal,error);
    }

    @Transactional
    public void telemetry(String device,byte[] bytes) {
        var value=ManagementCodec.telemetry(device,bytes);
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())return;
        history.record(device,value);
        // Retained summaries are historical observations; only state/LWT owns online/last_seen.
        int changed=db.update("""
            INSERT INTO device_telemetry(device_id,body,observed_at)
            SELECT device_id,?::jsonb,? FROM devices WHERE device_id=?
            ON CONFLICT(device_id) DO UPDATE SET body=excluded.body,observed_at=excluded.observed_at,received_at=now()
            WHERE device_telemetry.observed_at < excluded.observed_at OR
                (device_telemetry.observed_at = excluded.observed_at AND
                 (device_telemetry.body->>'configRevision')::bigint < (excluded.body->>'configRevision')::bigint)
            """,value.toString(),Timestamp.from(Instant.ofEpochSecond(value.get("observedAt").asLong())),device);
        if(changed==0||!value.has("config")||!value.path("configWritable").asBoolean()||
            value.path("observedAt").asLong()<Instant.now().minusSeconds(90).getEpochSecond())return;
        long revision=value.get("configRevision").asLong(),observed=value.get("observedAt").asLong();
        var requests=db.queryForList("SELECT request_id,expected_revision,config::text,envelope::text,status,expires_at FROM device_config_requests WHERE device_id=? AND status IN ('sent','unconfirmed') FOR UPDATE",device);
        for(var row:requests) {
            long expected=((Number)row.get("expected_revision")).longValue();
            if(observed<json.readTree((String)row.get("envelope")).path("issuedAt").asLong())continue;
            boolean same=json.readTree((String)row.get("config")).equals(value.get("config"));
            String status=null,error="";
            if(same&&(revision==expected||revision==expected+1))status="applied";
            else if(revision>expected) {status="rejected";error="config_observed_conflict";}
            else if(revision==expected&&row.get("status").equals("unconfirmed")&&
                observed>=((Timestamp)row.get("expires_at")).toInstant().getEpochSecond()) {status="expired";error="config_not_observed";}
            if(status==null)continue;
            String id=(String)row.get("request_id");
            db.update("UPDATE device_config_requests SET status=?,reported_revision=?,confirmation_source='telemetry',last_error=?,updated_at=now() WHERE device_id=? AND request_id=?",
                status,revision,error,device,id);
            audit(device,id,"device",status,error.isEmpty()?"configuration_observed":error);
        }
    }
}
