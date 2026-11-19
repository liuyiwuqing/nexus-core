package dev.nexus.core.delivery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DeliveryWorker {
    private final DeliveryService deliveries;
    private final MqttTransport mqtt;
    private final ObjectMapper json;
    public DeliveryWorker(DeliveryService deliveries, MqttTransport mqtt, ObjectMapper json) {
        this.deliveries = deliveries; this.mqtt = mqtt; this.json = json;
    }

    @Scheduled(fixedDelayString="${nexus.delivery.tick-ms}", initialDelay=500)
    void dispatch() {
        deliveries.expire();
        if (!mqtt.ready()) return;
        for (var row : deliveries.claim()) {
            UUID event = (UUID) row.get("event_id");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("messageId", row.get("message_id"));
            payload.put("source", row.get("source"));
            payload.put("eventType", row.get("event_type"));
            payload.put("title", DeviceText.fit((String) row.get("title"), 96));
            payload.put("summary", DeviceText.fit((String) row.get("summary"), 180));
            payload.put("priority", row.get("priority"));
            var display = display(row.get("sound"), row.get("duration_ms"));
            if (!display.isEmpty()) payload.put("display", display);
            byte[] envelope = json.writeValueAsBytes(Map.of(
                "schemaVersion", 1, "type", "inbox.message", "eventId", event,
                "deviceId", row.get("device_id"), "sentAt", Instant.now().getEpochSecond(),
                "expiresAt", ((Timestamp) row.get("expires_at")).toInstant().getEpochSecond(),
                "payload", payload));
            try {
                mqtt.publish((String) row.get("device_id"), envelope);
                deliveries.published(event);
            } catch (MqttException e) {
                deliveries.publishFailed(event);
            }
        }
    }

    /** Only non-default template display options enter the v1 envelope; absent means none/8000ms. */
    static Map<String, Object> display(Object sound, Object durationMs) {
        var display = new LinkedHashMap<String, Object>();
        if (sound != null && !"none".equals(sound)) display.put("sound", sound);
        if (durationMs != null && ((Number) durationMs).intValue() != 8000)
            display.put("durationMs", ((Number) durationMs).intValue());
        return display;
    }
}
