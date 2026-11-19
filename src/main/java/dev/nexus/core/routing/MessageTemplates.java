package dev.nexus.core.routing;

import dev.nexus.core.inbox.MessageInput;
import dev.nexus.core.delivery.DeviceText;
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
public class MessageTemplates {
    public record Definition(String name,String title,String summary,String icon,String sound,int durationSeconds){}
    public record Rendered(String title,String summary,String eventType,int version,String sound,int durationMs){}
    private static final Pattern VARIABLE=Pattern.compile("\\{([a-zA-Z]+)}");
    private static final List<String> VARIABLES=List.of("source","eventType","title","content","summary","sender");
    private static final List<String> SOUNDS=List.of("none","chime","alert");
    private final JdbcTemplate db;private final ObjectMapper json;
    public MessageTemplates(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    private void invalid(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_template");}
    private void validate(Definition d){
        if(d==null||d.name()==null||d.name().isBlank()||d.name().length()>80||d.title()==null||d.title().isBlank()||d.title().length()>512||d.summary()==null||d.summary().isBlank()||d.summary().length()>8192
            ||!List.of("text","comment","alert","growth","system").contains(d.icon()==null?"":d.icon())||!SOUNDS.contains(d.sound()==null?"":d.sound())
            ||d.durationSeconds()<5||d.durationSeconds()>30)invalid();
        for(String text:List.of(d.title(),d.summary())) {
            var matcher=VARIABLE.matcher(text);while(matcher.find())if(!VARIABLES.contains(matcher.group(1)))invalid();
            if(VARIABLE.matcher(text).replaceAll("").matches("(?s).*[{}].*"))invalid();
        }
    }
    private Map<String,Object> view(Map<String,Object> row){row.put("definition",json.readTree((String)row.get("definition")));return row;}
    public List<Map<String,Object>> list(){return db.queryForList("SELECT template_id AS \"templateId\",definition::text,version,deleted FROM message_templates WHERE NOT deleted ORDER BY created_at,template_id").stream().map(this::view).toList();}
    public Map<String,Object> get(UUID id){var rows=db.queryForList("SELECT template_id AS \"templateId\",definition::text,version,deleted FROM message_templates WHERE template_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return view(rows.getFirst());}
    @Transactional public Map<String,Object> save(UUID id,int version,Definition definition){
        validate(definition);if(version<0)invalid();String serialized=json.writeValueAsString(definition);
        if(version==0)db.update("INSERT INTO message_templates(template_id,definition) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING",id,serialized);
        var rows=db.queryForList("SELECT definition::text,version,deleted FROM message_templates WHERE template_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);var row=rows.getFirst();
        if(Boolean.TRUE.equals(row.get("deleted")))throw new ResponseStatusException(HttpStatus.GONE,"template_deleted");
        boolean same=json.readTree(serialized).equals(json.readTree((String)row.get("definition")));int current=(Integer)row.get("version");
        if(current!=version&&!(current==(long)version+1&&same))throw new ResponseStatusException(HttpStatus.CONFLICT,"template_version_conflict");
        if(!same)db.update("UPDATE message_templates SET definition=?::jsonb,version=version+1 WHERE template_id=?",serialized,id);
        return get(id);
    }
    public void require(UUID id){if(id!=null&&db.queryForList("SELECT template_id FROM message_templates WHERE template_id=? AND NOT deleted FOR SHARE",id).isEmpty())invalid();}
    @Transactional public void delete(UUID id,int version){
        if(version<1)invalid();
        var rows=db.queryForList("SELECT version,deleted FROM message_templates WHERE template_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);var row=rows.getFirst();int current=(Integer)row.get("version");
        if(Boolean.TRUE.equals(row.get("deleted"))&&current==(long)version+1)return;
        if(current!=version||Boolean.TRUE.equals(row.get("deleted")))throw new ResponseStatusException(HttpStatus.CONFLICT,"template_version_conflict");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM routing_rules WHERE NOT deleted AND definition->>'templateId'=?)",Boolean.class,id.toString())))throw new ResponseStatusException(HttpStatus.CONFLICT,"template_in_use");
        db.update("UPDATE message_templates SET deleted=true,version=version+1 WHERE template_id=?",id);
    }
    private String renderText(String pattern,MessageInput in){
        var values=Map.of("source",in.source(),"eventType",in.eventType(),"title",in.title(),"content",in.content(),"summary",in.summary()==null?in.content():in.summary(),"sender",in.sender()==null?"":in.sender());
        var matcher=VARIABLE.matcher(pattern);var output=new StringBuilder();int offset=0;
        while(matcher.find()&&output.length()<256){
            String literal=pattern.substring(offset,matcher.start()),value=values.get(matcher.group(1));
            output.append(literal,0,Math.min(literal.length(),256-output.length()));
            output.append(value,0,Math.min(value.length(),256-output.length()));offset=matcher.end();
        }
        if(output.length()<256)output.append(pattern,offset,Math.min(pattern.length(),offset+256-output.length()));
        return output.toString();
    }
    public Rendered render(UUID id,MessageInput in){
        if(id==null)return new Rendered(in.title(),in.summary()==null?in.content():in.summary(),in.eventType(),0,"none",8000);
        var row=get(id);if(Boolean.TRUE.equals(row.get("deleted")))throw new ResponseStatusException(HttpStatus.GONE,"template_deleted");
        var definition=json.treeToValue((tools.jackson.databind.JsonNode)row.get("definition"),Definition.class);
        return render(definition,in,(Integer)row.get("version"));
    }
    public Rendered render(Definition d,MessageInput in,int version){validate(d);return new Rendered(DeviceText.fit(renderText(d.title(),in),96),DeviceText.fit(renderText(d.summary(),in),180),d.icon().equals("text")?"template.text":d.icon()+".template",version,d.sound(),d.durationSeconds()*1000);}
    public List<String> variables(){return VARIABLES;}
}
