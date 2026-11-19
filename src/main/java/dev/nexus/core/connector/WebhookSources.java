package dev.nexus.core.connector;

import dev.nexus.core.inbox.InboxService;
import dev.nexus.core.inbox.MessageInput;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WebhookSources {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final InboxService inbox;
    private final Validator validator;
    public WebhookSources(JdbcTemplate db,ObjectMapper json,InboxService inbox,Validator validator){this.db=db;this.json=json;this.inbox=inbox;this.validator=validator;}
    private static final String VIEW="""
        SELECT s.source_id AS id,s.name,s.enabled,s.version,s.device_ids::text AS "deviceIds",s.created_at AS "createdAt",
            (SELECT max(r.created_at) FROM webhook_receipts r WHERE r.source_id=s.source_id) AS "lastReceivedAt",
            (SELECT count(*) FROM webhook_receipts r WHERE r.source_id=s.source_id) AS received
        FROM webhook_sources s
        """;
    private Map<String,Object> view(Map<String,Object> row){row.put("deviceIds",json.readTree((String)row.get("deviceIds")));return dev.nexus.core.web.ApiRows.normalize(List.of(row)).getFirst();}
    public List<Map<String,Object>> list(){return db.queryForList(VIEW+" ORDER BY s.created_at").stream().map(this::view).toList();}
    public Map<String,Object> get(String id){var rows=db.queryForList(VIEW+" WHERE s.source_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return view(rows.getFirst());}
    private void valid(String id,String name,List<String> devices){
        if(!id.matches("[a-z0-9][a-z0-9-]{0,23}")||name==null||name.isBlank()||name.length()>80||devices==null||devices.isEmpty()||devices.size()>32||devices.stream().anyMatch(v->v==null||!v.matches("[a-zA-Z0-9_-]{1,40}")))bad();
        for(String device:devices)if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=?)",Boolean.class,device)))bad();
    }
    private String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private void bad(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_source");}
    private void conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"source_version_conflict");}
    @Transactional public Map<String,Object> create(String id,String name,List<String> devices){
        valid(id,name,devices);String token=random(),secret=random();
        String targets=json.writeValueAsString(devices.stream().distinct().sorted().toList());
        String fingerprint=hash(json.writeValueAsBytes(List.of(name,targets)));
        if(db.update("INSERT INTO webhook_sources(source_id,name,device_ids,token_hash,hmac_secret,creation_hash) VALUES(?,?,?::jsonb,?,?,?) ON CONFLICT DO NOTHING",id,name,targets,hash(token.getBytes(StandardCharsets.UTF_8)),secret,fingerprint)==0) {
            if(!fingerprint.equals(db.queryForObject("SELECT creation_hash FROM webhook_sources WHERE source_id=?",String.class,id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
            return Map.of("source",get(id),"credentialsAvailable",false);
        }
        return Map.of("source",get(id),"credentialsAvailable",true,"token",token,"secret",secret);
    }
    @Transactional public Map<String,Object> update(String id,int version,String name,boolean enabled,List<String> devices){
        valid(id,name,devices);var current=lock(id);
        if(version<1)bad();
        String sorted=json.writeValueAsString(devices.stream().distinct().sorted().toList());
        boolean same=name.equals(current.get("name"))&&Boolean.valueOf(enabled).equals(current.get("enabled"))&&json.readTree(sorted).equals(json.readTree((String)current.get("device_ids")));
        if(!Integer.valueOf(version).equals(current.get("version")) && !((Integer)current.get("version")== (long)version+1&&same))conflict();
        if(!same)db.update("UPDATE webhook_sources SET name=?,enabled=?,device_ids=?::jsonb,version=version+1 WHERE source_id=?",name,enabled,sorted,id);
        return get(id);
    }
    private Map<String,Object> lock(String id){var rows=db.queryForList("SELECT source_id,name,enabled,version,token_hash,hmac_secret,tokens,refill_at,device_ids::text AS device_ids FROM webhook_sources WHERE source_id=? FOR UPDATE",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
    @Transactional public Map<String,Object> rotate(String id,int version){
        var current=lock(id);
        if(!Integer.valueOf(version).equals(current.get("version")))conflict();
        if(Boolean.TRUE.equals(current.get("enabled")))throw new ResponseStatusException(HttpStatus.CONFLICT,"source_enabled");
        String token=random(),secret=random();
        db.update("UPDATE webhook_sources SET token_hash=?,hmac_secret=?,version=version+1 WHERE source_id=?",hash(token.getBytes(StandardCharsets.UTF_8)),secret,id);
        return Map.of("source",get(id),"credentialsAvailable",true,"token",token,"secret",secret);
    }
    public record Payload(UUID eventId,String eventType,String title,String content,String priority){}
    @Transactional public Map<String,Object> test(String id,UUID key,String title,String content) {
        var source=lock(id);var ids=new java.util.ArrayList<String>();json.readTree((String)source.get("device_ids")).forEach(v->ids.add(v.asText()));
        var message=new MessageInput("webhook-test-"+id,"wh."+id,key.toString(),"source.test",title,content,null,"","","normal",null,null,Map.of("test","true"),null,null);
        if(!validator.validate(message).isEmpty())bad();
        var result=inbox.ingestScoped(message,ids);
        return Map.of("messageId",result.messageId(),"created",result.created());
    }
    @Transactional public Map<String,Object> receive(String id,String authorization,String timestamp,String signature,byte[] raw){
        var rows=db.queryForList("SELECT source_id,name,enabled,version,token_hash,hmac_secret,tokens,refill_at,device_ids::text AS device_ids FROM webhook_sources WHERE source_id=? FOR UPDATE",id);
        if(rows.isEmpty() || authorization==null || !authorization.startsWith("Bearer "))unauthorized();
        var source=rows.getFirst();String supplied=authorization.substring(7);
        if(!MessageDigest.isEqual(hash(supplied.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII),((String)source.get("token_hash")).getBytes(StandardCharsets.US_ASCII)))unauthorized();
        if(!Boolean.TRUE.equals(source.get("enabled")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"source_disabled");
        if(timestamp==null||!timestamp.matches("[0-9]{10}")||Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))>300||signature==null||!signature.matches("sha256=[a-f0-9]{64}"))unauthorized();
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(((String)source.get("hmac_secret")).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(mac.doFinal(raw),HexFormat.of().parseHex(signature.substring(7))))unauthorized();
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
        Payload body;
        try{body=json.readValue(raw,Payload.class);}catch(Exception e){bad();throw new IllegalStateException(e);}
        if(body==null || body.eventId()==null)bad();
        var message=new MessageInput("webhook-"+id,"wh."+id,body.eventId().toString(),body.eventType(),body.title(),body.content(),null,"","",body.priority(),null,null,Map.of(),null,null);
        if(!validator.validate(message).isEmpty())bad();
        String digest=hash(raw);
        var previous=db.queryForList("SELECT body_hash,message_id FROM webhook_receipts WHERE source_id=? AND event_key=?",id,body.eventId());
        if(!previous.isEmpty()){
            if(!digest.equals(previous.getFirst().get("body_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_conflict");
            return Map.of("id",previous.getFirst().get("message_id"),"status","queued","duplicate",true);
        }
        Instant now=Instant.now();
        double tokens=Math.min(20,((Number)source.get("tokens")).doubleValue()+Math.max(0,java.time.Duration.between(((Timestamp)source.get("refill_at")).toInstant(),now).toMillis()/1000.0));
        long count=db.queryForObject("SELECT count(*) FROM webhook_receipts WHERE source_id=? AND created_at>now()-interval '60 seconds'",Long.class,id);
        if(tokens<1||count>=60)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"source_rate_limited");
        var permitted=json.readTree((String)source.get("device_ids"));
        var ids=new java.util.ArrayList<String>();permitted.forEach(v->ids.add(v.asText()));
        var accepted=inbox.ingestScoped(message,ids);
        db.update("INSERT INTO webhook_receipts(source_id,event_key,body_hash,message_id) VALUES(?,?,?,?)",id,body.eventId(),digest,accepted.messageId());
        db.update("UPDATE webhook_sources SET tokens=?,refill_at=? WHERE source_id=?",tokens-1,Timestamp.from(now),id);
        return Map.of("id",accepted.messageId(),"status","queued","duplicate",false);
    }
    private static final int RECEIPT_EVENT_LIMIT=500;
    public Map<String,Object> receipt(String id,UUID message,String authorization){
        // Read access is deliberately limited to messages originally accepted by this source.
        var rows=db.queryForList("SELECT token_hash,enabled FROM webhook_sources WHERE source_id=?",id);
        if(rows.isEmpty()||authorization==null||!authorization.startsWith("Bearer "))unauthorized();
        if(!MessageDigest.isEqual(hash(authorization.substring(7).getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII),((String)rows.getFirst().get("token_hash")).getBytes(StandardCharsets.US_ASCII)))unauthorized();
        if(!Boolean.TRUE.equals(rows.getFirst().get("enabled")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"source_disabled");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM webhook_receipts WHERE source_id=? AND message_id=?)",Boolean.class,id,message)))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // Public timeline projection: no lastError/nextAttemptAt (internal broker and scheduling state).
        var events=dev.nexus.core.web.ApiRows.normalize(db.queryForList("""
            SELECT d.device_id AS "deviceId",h.kind,h.previous_status AS "previousStatus",h.status,
                   h.recorded_at AS "recordedAt",h.received_at AS "receivedAt",
                   h.displayed_at AS "displayedAt",h.read_at AS "readAt"
            FROM delivery_history h JOIN deliveries d ON d.event_id=h.event_id
            WHERE d.message_id=? ORDER BY d.device_id,h.sequence LIMIT ?
            """,message,RECEIPT_EVENT_LIMIT+1));
        boolean truncated=events.size()>RECEIPT_EVENT_LIMIT;
        if(truncated)events=events.subList(0,RECEIPT_EVENT_LIMIT);
        return Map.of("id",message,
            "deliveries",db.queryForList("SELECT device_id AS \"deviceId\",status,received_at AS \"receivedAt\",displayed_at AS \"displayedAt\",read_at AS \"readAt\" FROM deliveries WHERE message_id=? ORDER BY device_id",message),
            "events",events,"eventsTruncated",truncated);
    }
    private void unauthorized(){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"unauthorized");}
}
