package dev.nexus.core.device;

import dev.nexus.core.delivery.MqttTransport;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeviceManagementWorker {
    private final DeviceManagementService service;
    private final MqttTransport mqtt;
    public DeviceManagementWorker(DeviceManagementService service,MqttTransport mqtt) { this.service=service;this.mqtt=mqtt; }
    @Scheduled(fixedDelayString="${nexus.management.tick-ms:1000}",initialDelay=500)
    void dispatch() {
        // Expiry is processed even while disconnected; no request is sent to a stale subscriber forever.
        for(var row:service.claim(mqtt.ready())) {
            String device=(String)row.get("device_id"),id=(String)row.get("request_id");
            try { mqtt.publishConfig(device,((String)row.get("envelope")).getBytes(StandardCharsets.UTF_8)); }
            catch(MqttException ignored) { service.publishFailed(device,id); }
        }
    }
}
