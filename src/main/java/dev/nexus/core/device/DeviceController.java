package dev.nexus.core.device;

import dev.nexus.core.web.ApiRows;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
    private final JdbcTemplate db;
    // Same freshness rule as the list column and the overview alert: a stored online flag alone
    // goes stale when a device disappears without a goodbye packet.
    private static final String ONLINE = "(d.online AND d.last_seen > now()-interval '90 seconds')";
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

    /**
     * Device list with the same server-side filtering, paging, search and sorting as the console.
     * Defaults ({@code state=all}, {@code page=1}, {@code size=100}, {@code sort=created},
     * {@code order=asc}) keep the pre-paging shape: callers that send no parameters still receive
     * up to 100 devices ordered by creation.
     */
    @GetMapping
    Map<String,Object> list(@RequestParam(defaultValue="all") @Pattern(regexp="all|online|offline|disabled") String state,
                            @RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
                            @RequestParam(defaultValue="100") @Min(1) @Max(100) int size,
                            @RequestParam(defaultValue="") @Size(max=80) String q,
                            @RequestParam(defaultValue="created") @Pattern(regexp="created|name|last_seen") String sort,
                            @RequestParam(defaultValue="asc") @Pattern(regexp="asc|desc") String order) {
        String query = q.strip();
        // 搜索按字面量匹配名称或设备 ID：% 和 _ 不是通配符，两侧空白忽略。
        String search = query.isEmpty() ? "" : " WHERE (strpos(lower(d.name),lower(?))>0 OR strpos(lower(d.device_id),lower(?))>0)";
        var conditions = new ArrayList<String>();
        if (!query.isEmpty()) conditions.add("(strpos(lower(d.name),lower(?))>0 OR strpos(lower(d.device_id),lower(?))>0)");
        switch (state) {
            case "online" -> conditions.add(ONLINE);
            // Disabled devices are their own state even when the stored flag still says online.
            case "offline" -> conditions.add("d.enabled AND NOT COALESCE(" + ONLINE + ",false)");
            case "disabled" -> conditions.add("NOT d.enabled");
            default -> { }
        }
        String filter = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        // total 始终是整表；matched 和三类计数只受搜索影响，state 只收窄当前页。
        List<String> searchArgs = query.isEmpty() ? List.of() : List.of(query, query);
        var counts = db.queryForMap("""
            SELECT (SELECT count(*) FROM devices) AS total,
                   count(*) AS searched,
                   count(*) FILTER (WHERE %s) AS online,
                   count(*) FILTER (WHERE d.enabled AND NOT COALESCE(%s,false)) AS offline,
                   count(*) FILTER (WHERE NOT d.enabled) AS disabled
            FROM devices d
            """.formatted(ONLINE, ONLINE) + search, searchArgs.toArray());
        long matched = switch (state) {
            case "online" -> ((Number) counts.get("online")).longValue();
            case "offline" -> ((Number) counts.get("offline")).longValue();
            case "disabled" -> ((Number) counts.get("disabled")).longValue();
            default -> ((Number) counts.get("searched")).longValue();
        };
        String direction = "desc".equals(order) ? "DESC" : "ASC";
        String orderBy = switch (sort) {
            case "name" -> "lower(d.name) " + direction + ", d.device_id " + direction;
            // 从未连接过的设备无论升序降序都排在最后，避免空值占据首页。
            case "last_seen" -> "d.last_seen " + direction + " NULLS LAST, d.device_id " + direction;
            default -> "d.created_at " + direction + ", d.device_id " + direction;
        };
        var listArgs = new ArrayList<Object>(searchArgs);
        listArgs.add(size);
        listArgs.add((page - 1) * size);
        return Map.of("items", ApiRows.normalize(db.queryForList("""
            SELECT d.device_id AS "deviceId",d.name,d.enabled,d.last_seen AS "lastSeen",
                   (d.online AND d.last_seen > now()-interval '90 seconds') AS online,d.created_at AS "createdAt",
                   t.wifi_ip AS "wifiIp",t.wifi_rssi AS "wifiRssi",t.wifi_ssid AS "wifiSsid",
                   t.observed_at AS "telemetryAt",COALESCE(w.waiting,0) AS "waitingDeliveries"
            FROM devices d
            LEFT JOIN LATERAL (
                SELECT wifi_ip,wifi_rssi,wifi_ssid,observed_at FROM device_telemetry_samples s
                WHERE s.device_id=d.device_id AND (s.wifi_ip IS NOT NULL OR s.wifi_ssid IS NOT NULL)
                ORDER BY observed_at DESC LIMIT 1
            ) t ON true
            LEFT JOIN (
                SELECT device_id,count(*) AS waiting FROM deliveries
                WHERE status IN ('pending','sent') GROUP BY device_id
            ) w ON w.device_id=d.device_id
            """ + filter + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?", listArgs.toArray())),
            "total", ((Number) counts.get("total")).longValue(), "matched", matched, "page", page, "size", size,
            "state", state, "q", query, "sort", sort, "order", order,
            "counts", Map.of("online", ((Number) counts.get("online")).longValue(),
                "offline", ((Number) counts.get("offline")).longValue(),
                "disabled", ((Number) counts.get("disabled")).longValue()));
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

    /**
     * 删除只用于清理登记错误且从未使用的设备。任何消息、遥测、配置、升级、成长或凭据历史，
     * 以及路由/来源引用，都意味着这台设备已经产生了不可回退的事实，只能用停用保留历史。
     * 不存在的设备返回 204，响应丢失后的重试因此是幂等的。
     */
    @Transactional
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        var rows = db.queryForList("SELECT device_id FROM devices WHERE device_id=? FOR UPDATE", id);
        if (rows.isEmpty()) return ResponseEntity.noContent().build();
        boolean hasHistory = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM deliveries WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_telemetry WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_telemetry_samples WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_config_requests WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_ota_observations WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_ota_deployments WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_ota_boots WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_growth_observations WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_growth_boots WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_credentials WHERE device_id=?)
                OR EXISTS(SELECT 1 FROM device_credential_events WHERE device_id=?)
            """, Boolean.class, id, id, id, id, id, id, id, id, id, id, id));
        if (hasHistory) throw new ResponseStatusException(HttpStatus.CONFLICT, "device_has_history");
        boolean referenced = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM routing_rules WHERE NOT deleted AND definition->'deviceIds' @> to_jsonb(?::text))
                OR EXISTS(SELECT 1 FROM webhook_sources WHERE device_ids @> to_jsonb(?::text))
            """, Boolean.class, id, id));
        if (referenced) throw new ResponseStatusException(HttpStatus.CONFLICT, "device_referenced");
        db.update("DELETE FROM devices WHERE device_id=?", id);
        return ResponseEntity.noContent().build();
    }
}
