package dev.nexus.core.web;

import dev.nexus.core.delivery.MqttTransport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ConsoleSetupController {
    private final ConsoleEvents events;private final JdbcTemplate db;private final MqttTransport mqtt;
    public ConsoleSetupController(ConsoleEvents events,JdbcTemplate db,MqttTransport mqtt){this.events=events;this.db=db;this.mqtt=mqtt;}
    @GetMapping(value="/api/v1/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(events.connect((String)request.getAttribute("nexus.authorization"),(dev.nexus.core.security.BrowserSessions.Session)request.getAttribute("nexus.browserSession")));}
    @GetMapping("/api/v1/setup") Map<String,Object> setup(){
        // credentialStatus 只反映 CORE 台账（签发/轮换/撤销），不代表 Broker 已应用；
        // 顶层 credentialsProvisioned 继续为 unknown，避免把本机台账当成 Broker 事实。
        return Map.of("devices",ApiRows.normalize(db.queryForList("""
            SELECT v.device_id AS "deviceId",v.name,v.enabled,(v.last_seen IS NOT NULL) AS "deviceObserved",
                EXISTS(SELECT 1 FROM deliveries d WHERE d.device_id=v.device_id AND d.received_at IS NOT NULL) AS "receiptObserved",
                EXISTS(SELECT 1 FROM deliveries d WHERE d.device_id=v.device_id AND d.displayed_at IS NOT NULL) AS "displayObserved",
                CASE WHEN c.device_id IS NULL THEN 'none'
                     WHEN c.revoked_at IS NOT NULL THEN 'revoked'
                     WHEN c.previous_expires_at IS NOT NULL AND c.previous_expires_at > now() THEN 'rotating'
                     ELSE 'active' END AS "credentialStatus",
                c.revision AS "credentialRevision"
            FROM devices v LEFT JOIN device_credentials c ON c.device_id=v.device_id
            ORDER BY v.created_at,v.device_id
            """)),"mqtt",mqtt.ready()?"ready":"unavailable","credentialsProvisioned","unknown");
    }
}
