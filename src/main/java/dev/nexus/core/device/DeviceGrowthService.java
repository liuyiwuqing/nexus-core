package dev.nexus.core.device;

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

/** A read-only mirror. Neither local NVS nor the independent CORE reward profile is overwritten. */
@Service
public class DeviceGrowthService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    public DeviceGrowthService(JdbcTemplate db,ObjectMapper json,@Value("${nexus.growth-sync.enabled:false}") boolean enabled) {
        this.db=db;this.json=json;this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    private JsonNode current(String device) {
        var rows=db.queryForList("SELECT body::text FROM device_growth_observations WHERE device_id=?",device);
        return rows.isEmpty()?null:json.readTree((String)rows.getFirst().get("body"));
    }
    private Map<String,Object> view(String device,JsonNode snapshot) {
        var view=new LinkedHashMap<String,Object>();view.put("enabled",enabled);
        view.put("deviceId",device);view.put("snapshot",snapshot);
        view.put("fresh",enabled&&snapshot!=null&&snapshot.path("observedAt").asLong()>Instant.now().minusSeconds(90).getEpochSecond());
        return view;
    }
    public Map<String,Object> view(String device) {
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=?",device).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        return view(device,current(device));
    }
    public Map<String,Object> devices() {
        var rows=db.queryForList("""
            SELECT d.device_id,d.name,d.enabled,o.body::text FROM devices d
            LEFT JOIN device_growth_observations o USING(device_id) ORDER BY d.created_at LIMIT 100
            """);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var item=view((String)row.get("device_id"),row.get("body")==null?null:json.readTree((String)row.get("body")));
            item.put("name",row.get("name"));item.put("deviceEnabled",row.get("enabled"));items.add(item);
        }
        return Map.of("enabled",enabled,"items",items);
    }
    @Transactional
    public void observation(String device,byte[] bytes,boolean retained) {
        if(!enabled||retained)return;
        var value=DeviceGrowthCodec.snapshot(device,bytes);
        if(db.queryForList("SELECT device_id FROM devices WHERE device_id=? AND enabled FOR UPDATE",device).isEmpty())return;
        var old=current(device);String boot=value.path("bootId").asText();long observed=value.path("observedAt").asLong();
        if(old!=null) {
            if(observed<old.path("observedAt").asLong())return;
            if(boot.equals(old.path("bootId").asText())) {
                if(value.path("reportSequence").asLong()<=old.path("reportSequence").asLong())return;
            } else if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM device_growth_boots WHERE device_id=? AND boot_id=?)",Boolean.class,device,boot)))return;
        }
        db.update("INSERT INTO device_growth_boots(device_id,boot_id) VALUES(?,?) ON CONFLICT DO NOTHING",device,boot);
        db.update("""
            INSERT INTO device_growth_observations(device_id,body,observed_at) VALUES(?,?::jsonb,?)
            ON CONFLICT(device_id) DO UPDATE SET body=excluded.body,observed_at=excluded.observed_at,received_at=now()
            """,device,value.toString(),Timestamp.from(Instant.ofEpochSecond(observed)));
    }
}
