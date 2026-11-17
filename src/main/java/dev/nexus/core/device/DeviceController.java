package dev.nexus.core.device;

import dev.nexus.core.web.ApiRows;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
    private final JdbcTemplate db;
    public DeviceController(JdbcTemplate db) { this.db = db; }
    public record Registration(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String deviceId,
                               @NotBlank @Size(max=80) String name) {}
    public record Enabled(boolean enabled) {}
    public record Rename(@NotBlank @Size(max=80) String name,
                         @NotBlank @Size(max=80) String expectedName) {}

    @PostMapping
    ResponseEntity<?> register(@Valid @RequestBody Registration input) {
        int count = db.update("INSERT INTO devices(device_id,name) VALUES(?,?) ON CONFLICT DO NOTHING", input.deviceId(), input.name());
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "device_already_registered");
        return ResponseEntity.status(201).body(Map.of("deviceId", input.deviceId()));
    }

    @GetMapping
    Map<String,Object> list() {
        return Map.of("items", ApiRows.normalize(db.queryForList("""
            SELECT d.device_id AS "deviceId",d.name,d.enabled,d.last_seen AS "lastSeen",
                   (d.online AND d.last_seen > now()-interval '90 seconds') AS online,d.created_at AS "createdAt",
                   t.wifi_ip AS "wifiIp",t.wifi_rssi AS "wifiRssi",t.wifi_ssid AS "wifiSsid",
                   t.observed_at AS "telemetryAt"
            FROM devices d
            LEFT JOIN LATERAL (
                SELECT wifi_ip,wifi_rssi,wifi_ssid,observed_at FROM device_telemetry_samples s
                WHERE s.device_id=d.device_id AND (s.wifi_ip IS NOT NULL OR s.wifi_ssid IS NOT NULL)
                ORDER BY observed_at DESC LIMIT 1
            ) t ON true
            ORDER BY d.created_at LIMIT 100
            """)));
    }

    @Transactional
    @PatchMapping("/{id}/name")
    Map<String,String> rename(@PathVariable String id,@Valid @RequestBody Rename input) {
        String name=input.name().strip();
        if(name.isBlank()||name.codePoints().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_request");
        // A row lock makes the comparison atomic. The current desired name also
        // acknowledges a safe retry after a response was lost.
        var rows=db.queryForList("SELECT name FROM devices WHERE device_id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        String current=(String)rows.getFirst().get("name");
        if(!current.equals(name)) {
            if(!current.equals(input.expectedName()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"device_name_conflict");
            db.update("UPDATE devices SET name=? WHERE device_id=?",name,id);
        }
        return Map.of("deviceId",id,"name",name);
    }

    @Transactional
    @PutMapping("/{id}/enabled")
    ResponseEntity<Void> enable(@PathVariable String id, @RequestBody Enabled input) {
        if (db.update("UPDATE devices SET enabled=? WHERE device_id=?", input.enabled(), id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!input.enabled()) db.update("UPDATE deliveries SET status='expired',last_error='device_disabled' WHERE device_id=? AND status IN ('pending','sent')", id);
        return ResponseEntity.noContent().build();
    }
}
