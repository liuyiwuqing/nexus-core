package dev.nexus.core.delivery;

import java.sql.Timestamp;
import java.time.Instant;
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
            byte[] envelope = json.writeValueAsBytes(Map.of(
                "schemaVersion", 1, "type", "inbox.message", "eventId", event,
                "deviceId", row.get("device_id"), "sentAt", Instant.now().getEpochSecond(),
                "expiresAt", ((Timestamp) row.get("expires_at")).toInstant().getEpochSecond(),
                "payload", Map.of("messageId", row.get("message_id"), "source", row.get("source"),
                    "eventType", row.get("event_type"), "title", DeviceText.fit((String) row.get("title"), 96),
                    "summary", DeviceText.fit((String) row.get("summary"), 180), "priority", row.get("priority"))));
            try {
                mqtt.publish((String) row.get("device_id"), envelope);
                deliveries.published(event);
            } catch (MqttException e) {
                deliveries.publishFailed(event);
            }
        }
    }
}
