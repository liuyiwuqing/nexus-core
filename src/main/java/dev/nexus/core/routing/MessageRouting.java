package dev.nexus.core.routing;

import dev.nexus.core.inbox.MessageInput;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MessageRouting {
    public record Definition(String name, boolean enabled, String source, String eventType, String match,
                             String expression, List<String> deviceIds, String priority, String from, String before, String zone) {}
    public record Target(String deviceId, String priority) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public MessageRouting(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    public Map<String,Object> policy() { return db.queryForMap("SELECT mode,version FROM routing_policy WHERE singleton"); }
    @Transactional public Map<String,Object> policy(String mode,int expectedVersion) {
        if (!List.of("broadcast","rules").contains(mode) || expectedVersion<1) invalid();
        var current=db.queryForMap("SELECT mode,version FROM routing_policy WHERE singleton FOR UPDATE");
        int version=(Integer)current.get("version");
        if (version!=expectedVersion && !(version==(long)expectedVersion+1 && mode.equals(current.get("mode")))) conflict();
        if (!mode.equals(current.get("mode"))) db.update("UPDATE routing_policy SET mode=?,version=version+1 WHERE singleton",mode);
        return policy();
    }
    private Map<String,Object> row(Map<String,Object> row) {
        row.put("definition",json.readTree((String)row.get("definition"))); return row;
    }
    public List<Map<String,Object>> rules() {
        return db.queryForList("SELECT rule_id AS \"ruleId\",definition::text,version,deleted FROM routing_rules WHERE NOT deleted ORDER BY created_at,rule_id")
            .stream().map(this::row).toList();
    }
    public Map<String,Object> rule(UUID id) {
        var rows=db.queryForList("SELECT rule_id AS \"ruleId\",definition::text,version,deleted FROM routing_rules WHERE rule_id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return row(rows.getFirst());
    }
    @Transactional public Map<String,Object> save(UUID id,int expectedVersion,Definition definition) {
        validate(definition);
        if(expectedVersion<0)invalid();
        String serialized=json.writeValueAsString(definition);
        if(expectedVersion==0) {
            db.update("INSERT INTO routing_rules(rule_id,definition) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING",id,serialized);
        }
        var current=db.queryForList("SELECT definition::text,version,deleted FROM routing_rules WHERE rule_id=? FOR UPDATE",id);
        if(current.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row=current.getFirst();int version=(Integer)row.get("version");
        if(Boolean.TRUE.equals(row.get("deleted")))throw new ResponseStatusException(HttpStatus.GONE,"route_deleted");
        boolean same=json.readTree((String)row.get("definition")).equals(json.readTree(serialized));
        if(version!=expectedVersion && !(version==(long)expectedVersion+1 && same))conflict();
        if(!same)db.update("UPDATE routing_rules SET definition=?::jsonb,version=version+1 WHERE rule_id=?",serialized,id);
        return rule(id);
    }
    @Transactional public void delete(UUID id,int expectedVersion) {
        if(expectedVersion<1)invalid();
        var rows=db.queryForList("SELECT version,deleted FROM routing_rules WHERE rule_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var current=rows.getFirst();int version=(Integer)current.get("version");
        if(Boolean.TRUE.equals(current.get("deleted")) && version==(long)expectedVersion+1)return;
        if(version!=expectedVersion || Boolean.TRUE.equals(current.get("deleted")))conflict();
        db.update("UPDATE routing_rules SET deleted=true,version=version+1 WHERE rule_id=?",id);
    }
    public void validate(Definition d) {
        if(d==null || d.name()==null || d.name().isBlank() || d.name().length()>80 || !identity(d.source(),32) || !identity(d.eventType(),64)
            || !List.of("all","keyword","regex").contains(d.match()==null?"":d.match()) || d.expression()==null || d.expression().length()>160
            || d.deviceIds()==null || d.deviceIds().isEmpty() || d.deviceIds().size()>32 || d.deviceIds().stream().anyMatch(v->v==null||!v.matches("[a-zA-Z0-9_-]{1,40}"))
            || d.priority()==null || !List.of("","low","normal","high","critical").contains(d.priority()))invalid();
        if(!d.match().equals("all") && d.expression().isBlank())invalid();
        if(d.match().equals("regex"))safePattern(d.expression());
        if(d.from()==null || d.before()==null || d.zone()==null)invalid();
        try {
            ZoneId.of(d.zone());
            if(!d.from().isEmpty() || !d.before().isEmpty()) {
                if(!d.from().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || !d.before().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || d.from().equals(d.before()))invalid();
                LocalTime.parse(d.from());LocalTime.parse(d.before());
            }
        }catch(java.time.DateTimeException error){invalid();}
        for(String device:d.deviceIds())if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=?)",Boolean.class,device)))invalid();
    }
    private boolean identity(String value,int max) {return value!=null && (value.equals("*")||value.matches("[a-zA-Z0-9_.-]{1,"+max+"}"));}
    private Pattern safePattern(String value) {
        // Restrict regex to anchors, quoted literal runs and at most one wildcard.
        // No groups, alternation, backreferences or nested/unbounded quantifiers.
        String literal=value.replaceFirst("^\\^","").replaceFirst("\\$$","");
        String[] pieces=literal.split("\\.\\*",-1);
        if(pieces.length>2 || java.util.Arrays.stream(pieces).anyMatch(v->v.chars().anyMatch(c->c<32 || "\\.[]{}()*+?|^$".indexOf(c)>=0)))invalid();
        try{return Pattern.compile(value);}catch(java.util.regex.PatternSyntaxException error){invalid();throw error;}
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(MessageInput input) {
        return Map.of("dryRun",true,"policy",policy(),"targets",targets(input,Instant.now()));
    }
    public List<Target> targets(MessageInput input,Instant now) {
        var enabled=db.queryForList("SELECT device_id FROM devices WHERE enabled ORDER BY device_id",String.class);
        if(policy().get("mode").equals("broadcast"))return enabled.stream().map(d->new Target(d,null)).toList();
        var selected=new LinkedHashMap<String,Target>();
        for(var row:rules()) {
            Definition rule=json.treeToValue((tools.jackson.databind.JsonNode)row.get("definition"),Definition.class);
            if(!rule.enabled() || (!rule.source().equals("*")&&!rule.source().equals(input.source())) || (!rule.eventType().equals("*")&&!rule.eventType().equals(input.eventType())))continue;
            String text=input.title()+"\n"+input.content();
            if(rule.match().equals("keyword")&&!text.contains(rule.expression()))continue;
            if(rule.match().equals("regex")&&!safePattern(rule.expression()).matcher(text).find())continue;
            if(!rule.from().isEmpty()) {
                LocalTime time=now.atZone(ZoneId.of(rule.zone())).toLocalTime(),from=LocalTime.parse(rule.from()),before=LocalTime.parse(rule.before());
                if(from.isBefore(before)?time.isBefore(from)||!time.isBefore(before):time.isBefore(from)&&!time.isBefore(before))continue;
            }
            for(String device:rule.deviceIds())if(enabled.contains(device))selected.putIfAbsent(device,new Target(device,rule.priority().isEmpty()?null:rule.priority()));
        }
        return new ArrayList<>(selected.values());
    }
    private void invalid(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_route");}
    private void conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"route_version_conflict");}
}
