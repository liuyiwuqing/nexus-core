package dev.nexus.core.device;

import dev.nexus.core.delivery.MqttTransport;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RemoteOtaWorker {
    private final RemoteOtaService service;
    private final MqttTransport mqtt;
    public RemoteOtaWorker(RemoteOtaService service,MqttTransport mqtt){this.service=service;this.mqtt=mqtt;}
    @Scheduled(fixedDelayString="${nexus.ota.tick-ms:1000}",initialDelay=500)
    void dispatch() {
        for(var row:service.claim(mqtt.ready())) {
            String device=(String)row.get("device_id");
            try {mqtt.publishOta(device,((String)row.get("envelope")).getBytes(StandardCharsets.UTF_8));}
            catch(MqttException ignored){service.publishFailed(device,(String)row.get("request_id"));}
        }
    }
}
