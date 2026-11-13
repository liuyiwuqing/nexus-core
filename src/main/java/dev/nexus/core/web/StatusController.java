package dev.nexus.core.web;

import dev.nexus.core.delivery.MqttTransport;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final JdbcTemplate db;
    private final MqttTransport mqtt;
    public StatusController(JdbcTemplate db, MqttTransport mqtt) { this.db = db; this.mqtt = mqtt; }
    @GetMapping("/api/v1/status") Map<String,Object> status() {
        return Map.of("database", db.queryForObject("SELECT 1", Integer.class) == 1 ? "ready" : "unavailable",
            "mqtt", mqtt.ready() ? "ready" : "unavailable", "mqttError", mqtt.lastError(), "schemaVersion", 1);
    }
}
