package dev.nexus.core.device;

import dev.nexus.core.web.ApiRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Durable commands and device observations are separate: delivery never proves an image is healthy. */
@Service
public class RemoteOtaService {
    private static final Set<String> TERMINAL=Set.of("healthy","failed","rejected","expired","interrupted","rolled_back");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final int lifetime,retrySeconds;
    public RemoteOtaService(JdbcTemplate db,ObjectMapper json,@Value("${nexus.ota.enabled:false}") boolean enabled,
        @Value("${nexus.ota.request-seconds:300}") int lifetime,@Value("${nexus.ota.retry-seconds:15}") int retrySeconds) {
        if(lifetime<1||lifetime>300||retrySeconds<1||retrySeconds>60)throw new IllegalArgumentException("invalid_ota_timing");
        this.db=db;this.json=json;this.enabled=enabled;this.lifetime=lifetime;this.retrySeconds=retrySeconds;
    }
    public boolean enabled(){return enabled;}
    private void requireEnabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ota_management_disabled");}
    private void lock(String device) {
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
    }
    private ResponseStatusException conflict(String code){return new ResponseStatusException(HttpStatus.CONFLICT,code);}
    public Map<String,Object> releases() {
        if(!enabled)return Map.of("enabled",false,"items",List.of());
        var rows=ApiRows.normalize(db.queryForList("SELECT release_id AS \"releaseId\",target::text,identity::text,created_at AS \"createdAt\" FROM ota_releases ORDER BY created_at DESC LIMIT 100"));
        for(var row:rows)for(String key:Set.of("target","identity"))row.put(key,json.readTree((String)row.get(key)));
        return Map.of("enabled",true,"items",rows);
    }
    @Transactional
    public boolean register(byte[] bytes) {
        requireEnabled();var release=RemoteOtaCodec.release(bytes);
        int inserted=db.update("INSERT INTO ota_releases(release_id,manifest,target,identity) VALUES(?,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING",
            release.id(),release.manifest().toString(),release.target().toString(),release.identity().toString());
        if(inserted>0)return true;
        String previous=db.queryForObject("SELECT manifest::text FROM ota_releases WHERE release_id=?",String.class,release.id());
        if(!json.readTree(previous).equals(release.manifest()))throw conflict("ota_release_immutable");
        return false;
    }
    private JsonNode current(String device) {
        var rows=db.queryForList("SELECT body::text FROM device_ota_observations WHERE device_id=?",device);
        return rows.isEmpty()?null:json.readTree((String)rows.getFirst().get("body"));
    }
    private boolean fresh(JsonNode value) {return value!=null&&value.path("observedAt").asLong()>Instant.now().minusSeconds(90).getEpochSecond();}
    private boolean active(String device) {
        return db.queryForObject("SELECT count(*) FROM device_ota_deployments WHERE device_id=? AND status NOT IN ('healthy','failed','rejected','expired','interrupted','rolled_back')",Integer.class,device)>0;
    }
    private String installBlock(String device,JsonNode value) {
        if(!enabled)return "ota_management_disabled";
        if(!fresh(value))return "ota_readback_required";
        if(!value.path("available").asBoolean())return "ota_device_unavailable";
        if(active(device))return "ota_deployment_in_progress";
        if(!Set.of("idle","healthy","failed","interrupted","rolled_back").contains(value.path("state").asText()))return "ota_device_busy";
        return "";
    }
    private boolean canReboot(String device,JsonNode value) {
        if(!enabled||!fresh(value)||!value.path("available").asBoolean()||!value.path("state").asText().equals("pending_reboot")||
            !value.path("rebootId").asText().isEmpty())return false;
        var rows=db.queryForList("SELECT target::text,initial_boot_id,status FROM device_ota_deployments WHERE device_id=? AND request_id=?",device,value.path("requestId").asText());
        if(rows.isEmpty())return false;var row=rows.getFirst();
        if(!Set.of("pending_reboot","unconfirmed").contains(row.get("status"))||!row.get("initial_boot_id").equals(value.path("bootId").asText())||
            !json.readTree((String)row.get("target")).equals(value.get("target")))return false;
        return db.queryForObject("SELECT count(*) FROM device_ota_commands WHERE device_id=? AND request_id=? AND operation='reboot' AND status NOT IN ('rejected','expired')",Integer.class,device,value.path("requestId").asText())==0;
    }
    public Map<String,Object> view(String device) {
        if(db.queryForObject("SELECT count(*) FROM devices WHERE device_id=?",Integer.class,device)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        JsonNode value=current(device);var out=new LinkedHashMap<String,Object>();
        out.put("enabled",enabled);out.put("deviceId",device);out.put("fresh",fresh(value));out.put("observation",value);
        String block=installBlock(device,value);out.put("canInstall",block.isEmpty());out.put("installBlock",block);out.put("canReboot",canReboot(device,value));
        var rows=ApiRows.normalize(db.queryForList("""
            SELECT request_id AS "requestId",release_id AS "releaseId",target::text,status,last_error AS "lastError",
                confirmation_source AS "confirmationSource",created_at AS "createdAt",updated_at AS "updatedAt"
            FROM device_ota_deployments WHERE device_id=? ORDER BY created_at DESC LIMIT 25
            """,device));
        for(var row:rows) {
            row.put("target",json.readTree((String)row.get("target")));
            row.put("commands",ApiRows.normalize(db.queryForList("SELECT command_id AS \"commandId\",operation,status,attempts,expires_at AS \"expiresAt\" FROM device_ota_commands WHERE device_id=? AND request_id=? ORDER BY created_at",device,row.get("requestId"))));
        }
        out.put("deployments",rows);return out;
    }
    public record Submitted(String requestId,boolean created) {}
    @Transactional
    public Submitted submit(String device,byte[] bytes) {
        requireEnabled();var body=ManagementCodec.parse(bytes,512);
        ManagementCodec.fields(body,Set.of("requestId","releaseId","bootId"),Set.of());
        String id=RemoteOtaCodec.id(body.get("requestId"),40),release=RemoteOtaCodec.id(body.get("releaseId"),64),boot=RemoteOtaCodec.boot(body.get("bootId"));lock(device);
        var old=db.queryForList("SELECT release_id,initial_boot_id FROM device_ota_deployments WHERE device_id=? AND request_id=?",device,id);
        if(!old.isEmpty()) {
            if(!old.getFirst().get("release_id").equals(release)||!old.getFirst().get("initial_boot_id").equals(boot))throw conflict("idempotency_conflict");
            return new Submitted(id,false);
        }
        if(db.queryForObject("SELECT count(*) FROM device_ota_commands WHERE device_id=? AND command_id=?",Integer.class,device,id)>0)throw conflict("idempotency_conflict");
        JsonNode value=current(device);String block=installBlock(device,value);if(!block.isEmpty())throw conflict(block);
        if(!boot.equals(value.path("bootId").asText()))throw conflict("ota_boot_changed");
        var releases=db.queryForList("SELECT target::text,identity::text FROM ota_releases WHERE release_id=?",release);
        if(releases.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ota_release_not_found");
        var entry=releases.getFirst();JsonNode target=json.readTree((String)entry.get("target"));
        if(!json.readTree((String)entry.get("identity")).equals(value.get("identity")))throw conflict("ota_release_incompatible");
        if(target.path("releaseSequence").asLong()<=value.path("reservedSequence").asLong())throw conflict("ota_sequence_rejected");
        db.update("INSERT INTO device_ota_deployments(device_id,request_id,release_id,initial_boot_id,target,status) VALUES(?,?,?,?,?::jsonb,'pending')",device,id,release,boot,target.toString());
        command(device,id,id,"install",boot,target);audit(device,id,"admin","created","");return new Submitted(id,true);
    }
    private void command(String device,String request,String id,String operation,String boot,JsonNode target) {
        long issued=Instant.now().getEpochSecond();var value=json.createObjectNode();value.put("schemaVersion",1);value.put("deviceId",device);
        value.put("requestId",request);value.put("commandId",id);value.put("operation",operation);value.put("bootId",boot);
        value.put("issuedAt",issued);value.put("expiresAt",issued+lifetime);value.set("target",target);
        if(ManagementCodec.bytes(value).length>1024)throw new IllegalArgumentException("ota_envelope_too_large");
        db.update("INSERT INTO device_ota_commands(device_id,command_id,request_id,operation,boot_id,envelope,expires_at) VALUES(?,?,?,?,?,?::jsonb,?)",
            device,id,request,operation,boot,value.toString(),Timestamp.from(Instant.ofEpochSecond(issued+lifetime)));
    }
    @Transactional
    public Submitted reboot(String device,String request,byte[] bytes) {
        requireEnabled();var body=ManagementCodec.parse(bytes,512);ManagementCodec.fields(body,Set.of("requestId","bootId","confirm"),Set.of());
        String id=RemoteOtaCodec.id(body.get("requestId"),40),boot=RemoteOtaCodec.boot(body.get("bootId"));
        if(!ManagementCodec.text(body.get("confirm"),32).equals("REBOOT_DEVICE")||id.equals(request))throw new IllegalArgumentException("invalid_reboot_confirmation");
        lock(device);var old=db.queryForList("SELECT request_id,boot_id,operation FROM device_ota_commands WHERE device_id=? AND command_id=?",device,id);
        if(!old.isEmpty()) {
            var row=old.getFirst();if(!row.get("request_id").equals(request)||!row.get("boot_id").equals(boot)||!row.get("operation").equals("reboot"))throw conflict("idempotency_conflict");
            return new Submitted(id,false);
        }
        var value=current(device);
        if(!canReboot(device,value)||!value.path("requestId").asText().equals(request))throw conflict("ota_reboot_readback_required");
        if(!boot.equals(value.path("bootId").asText()))throw conflict("ota_boot_changed");
        command(device,request,id,"reboot",boot,value.get("target"));state(device,request,"rebooting","","admin");
        audit(device,request,"admin","reboot_created",id);return new Submitted(id,true);
    }
    public List<Map<String,Object>> history(String device,String request) {
        if(db.queryForObject("SELECT count(*) FROM device_ota_deployments WHERE device_id=? AND request_id=?",Integer.class,device,request)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ota_deployment_not_found");
        return ApiRows.normalize(db.queryForList("SELECT sequence,actor,event,detail,created_at AS \"createdAt\" FROM device_ota_audit WHERE device_id=? AND request_id=? ORDER BY sequence LIMIT 200",device,request));
    }
    private void audit(String device,String request,String actor,String event,String detail) {
        db.update("INSERT INTO device_ota_audit(device_id,request_id,actor,event,detail) VALUES(?,?,?,?,?)",device,request,actor,event,detail);
    }
    private void state(String device,String request,String status,String error,String source) {
        int changed=db.update("UPDATE device_ota_deployments SET status=?,last_error=?,confirmation_source=?,updated_at=now() WHERE device_id=? AND request_id=? AND (status<>? OR last_error<>?)",
            status,error,source,device,request,status,error);
        if(changed>0)audit(device,request,source.equals("readback")?"device":source,status,error);
    }
    /** All writers acquire the device row first. Reservation commits before any network publication. */
    @Transactional
    public List<Map<String,Object>> claim(boolean connected) {
        if(!enabled)return List.of();var work=new ArrayList<Map<String,Object>>();
        var devices=db.queryForList("""
            SELECT d.device_id FROM devices d WHERE EXISTS(SELECT 1 FROM device_ota_commands c WHERE c.device_id=d.device_id
                AND c.status IN ('pending','sent') AND (c.expires_at<=now() OR c.next_attempt_at<=now()))
            ORDER BY d.device_id LIMIT 8 FOR UPDATE OF d SKIP LOCKED
            """);
        for(var deviceRow:devices) {
            String device=(String)deviceRow.get("device_id");
            var ended=db.queryForList("UPDATE device_ota_commands SET status=CASE WHEN attempts=0 THEN 'expired' ELSE 'unconfirmed' END WHERE device_id=? AND status IN ('pending','sent') AND expires_at<=now() RETURNING request_id,operation,status",device);
            for(var row:ended) {
                String request=(String)row.get("request_id"),status=(String)row.get("status");
                if(row.get("operation").equals("install"))state(device,request,status,status.equals("expired")?"ota_not_sent":"ota_result_unconfirmed","core");
                else state(device,request,status.equals("expired")?"pending_reboot":"unconfirmed",status.equals("expired")?"ota_reboot_not_sent":"ota_reboot_unconfirmed","core");
                audit(device,request,"core","command_"+status,(String)row.get("operation"));
            }
            if(!connected)continue;
            var due=db.queryForList("SELECT device_id,command_id,request_id,operation,envelope::text FROM device_ota_commands WHERE device_id=? AND status IN ('pending','sent') AND expires_at>now() AND next_attempt_at<=now() ORDER BY created_at LIMIT 1",device);
            for(var row:due) {
                db.update("UPDATE device_ota_commands SET status='sent',attempts=attempts+1,next_attempt_at=now()+(?*interval '1 second') WHERE device_id=? AND command_id=?",retrySeconds,device,row.get("command_id"));
                if(row.get("operation").equals("install"))state(device,(String)row.get("request_id"),"sent","","core");
                audit(device,(String)row.get("request_id"),"core","publish_attempt",(String)row.get("command_id"));work.add(row);
            }
        }
        return work;
    }
    @Transactional
    public void publishFailed(String device,String request) {
        if(!enabled)return;lock(device);audit(device,request,"core","publish_unconfirmed","broker_unavailable");
    }
    @Transactional
    public void acknowledge(String device,byte[] bytes,boolean retained) {
        if(!enabled||retained)return;var value=ManagementCodec.parse(bytes,512);
        ManagementCodec.fields(value,Set.of("schemaVersion","requestId","commandId","operation","accepted","status","code"),Set.of());
        ManagementCodec.integer(value.get("schemaVersion"),1,1);String request=RemoteOtaCodec.id(value.get("requestId"),40),id=RemoteOtaCodec.id(value.get("commandId"),40);
        String operation=ManagementCodec.text(value.get("operation"),8),status=ManagementCodec.text(value.get("status"),24);ManagementCodec.bool(value.get("accepted"));
        long code=ManagementCodec.integer(value.get("code"),Integer.MIN_VALUE,Integer.MAX_VALUE);boolean accepted=value.path("accepted").asBoolean();
        if(!Set.of("install","reboot").contains(operation)||!Set.of("accepted","rejected","expired","conflict","unavailable").contains(status)||
            accepted!=status.equals("accepted")||accepted!=(code==0))throw new IllegalArgumentException("inconsistent_ota_ack");
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())return;
        var rows=db.queryForList("SELECT c.status,c.attempts,d.status AS deployment_status FROM device_ota_commands c JOIN device_ota_deployments d USING(device_id,request_id) WHERE c.device_id=? AND c.command_id=? AND c.request_id=? AND c.operation=?",device,id,request,operation);
        if(rows.isEmpty())return;var row=rows.getFirst();
        if(((Number)row.get("attempts")).intValue()==0||!Set.of("sent","unconfirmed").contains(row.get("status")))return;
        db.update("UPDATE device_ota_commands SET status=? WHERE device_id=? AND command_id=?",accepted?"accepted":"rejected",device,id);
        if(operation.equals("install")&&Set.of("pending","sent","unconfirmed").contains(row.get("deployment_status")))
            state(device,request,accepted?"accepted":"rejected",accepted?"":"ota_device_"+status,"ack");
        if(operation.equals("reboot")&&!accepted&&row.get("deployment_status").equals("rebooting"))state(device,request,"pending_reboot","ota_reboot_rejected","ack");
        audit(device,request,"device",accepted?"command_accepted":"command_rejected",operation);
    }
    @Transactional
    public void observation(String device,byte[] bytes,boolean retained) {
        if(!enabled||retained)return;var value=RemoteOtaCodec.observation(device,bytes);
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE",device).isEmpty())return;
        var previousObservation=current(device);String boot=value.path("bootId").asText();
        if(previousObservation!=null&&!previousObservation.path("bootId").asText().equals(boot)&&db.queryForObject("SELECT count(*) FROM device_ota_boots WHERE device_id=? AND boot_id=?",Integer.class,device,boot)>0)return;
        int changed=db.update("""
            INSERT INTO device_ota_observations(device_id,body,boot_id,report_sequence,observed_at) VALUES(?,?::jsonb,?,?,?)
            ON CONFLICT(device_id) DO UPDATE SET body=excluded.body,boot_id=excluded.boot_id,report_sequence=excluded.report_sequence,
                observed_at=excluded.observed_at,received_at=now()
            WHERE (device_ota_observations.boot_id=excluded.boot_id AND device_ota_observations.report_sequence<excluded.report_sequence
                    AND device_ota_observations.observed_at<=excluded.observed_at)
                OR (device_ota_observations.boot_id<>excluded.boot_id AND device_ota_observations.observed_at<=excluded.observed_at)
            """,device,value.toString(),value.path("bootId").asText(),value.path("reportSequence").asLong(),Timestamp.from(Instant.ofEpochSecond(value.path("observedAt").asLong())));
        if(changed==0)return;
        db.update("INSERT INTO device_ota_boots(device_id,boot_id) VALUES(?,?) ON CONFLICT DO NOTHING",device,boot);
        if(!fresh(value))return;
        String request=value.path("requestId").asText();
        if(request.isEmpty()) {
            // A fresh observation after expiry can prove that no command was accepted in that boot.
            var rows=db.queryForList("SELECT d.request_id FROM device_ota_deployments d JOIN device_ota_commands c USING(device_id,request_id) WHERE d.device_id=? AND d.status='unconfirmed' AND c.operation='install' AND c.status='unconfirmed' AND c.boot_id=? AND c.expires_at<=?",device,value.path("bootId").asText(),Timestamp.from(Instant.ofEpochSecond(value.path("observedAt").asLong())));
            if(value.path("state").asText().equals("idle")&&value.path("available").asBoolean())for(var row:rows) {
                state(device,(String)row.get("request_id"),"expired","ota_not_observed","readback");
                db.update("UPDATE device_ota_commands SET status='expired' WHERE device_id=? AND request_id=? AND status='unconfirmed'",device,row.get("request_id"));
            }
            return;
        }
        var rows=db.queryForList("SELECT target::text,initial_boot_id,status FROM device_ota_deployments WHERE device_id=? AND request_id=?",device,request);
        if(rows.isEmpty())return;var row=rows.getFirst();
        if(!json.readTree((String)row.get("target")).equals(value.get("target")))return;
        if(db.queryForObject("SELECT count(*) FROM device_ota_commands WHERE device_id=? AND request_id=? AND operation='install' AND attempts>0",Integer.class,device,request)==0)return;
        String status=value.path("state").asText(),previous=(String)row.get("status");
        if(TERMINAL.contains(previous)&&!(previous.equals("healthy")&&status.equals("rolled_back")))return;
        if(Set.of("verifying","healthy").contains(status)&&row.get("initial_boot_id").equals(value.path("bootId").asText()))return;
        if(!Set.of("installing","pending_reboot","reboot_requested","verifying","healthy","failed","interrupted","rolled_back","unconfirmed").contains(status))return;
        db.update("UPDATE device_ota_commands SET status='accepted' WHERE device_id=? AND request_id=? AND operation='install' AND status IN ('sent','unconfirmed')",device,request);
        String reboot=value.path("rebootId").asText();
        if(!reboot.isEmpty())db.update("UPDATE device_ota_commands SET status='accepted' WHERE device_id=? AND request_id=? AND command_id=? AND operation='reboot' AND attempts>0 AND status IN ('sent','unconfirmed')",device,request,reboot);
        else if(status.equals("pending_reboot"))db.update("UPDATE device_ota_commands SET status='expired' WHERE device_id=? AND request_id=? AND operation='reboot' AND status IN ('sent','unconfirmed') AND expires_at<=?",device,request,Timestamp.from(Instant.ofEpochSecond(value.path("observedAt").asLong())));
        // Pending readback during a still-live reboot command cannot establish that it will not execute.
        if(status.equals("pending_reboot")&&db.queryForObject("SELECT count(*) FROM device_ota_commands WHERE device_id=? AND request_id=? AND operation='reboot' AND status IN ('pending','sent','accepted','unconfirmed')",Integer.class,device,request)>0)return;
        state(device,request,status.equals("reboot_requested")?"rebooting":status,value.path("code").asInt()==0?"":"ota_device_error","readback");
    }
}
