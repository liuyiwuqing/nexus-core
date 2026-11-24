package dev.nexus.core.device;

import dev.nexus.core.delivery.MqttTransport;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CompanionWorker {
    private final CompanionService service;
    private final MqttTransport mqtt;
    public CompanionWorker(CompanionService service,MqttTransport mqtt){this.service=service;this.mqtt=mqtt;}
    @Scheduled(fixedDelay=1000,initialDelay=500)
    void dispatch() {
        if(!service.enabled())return;
        for(var row:service.claim(mqtt.ready())) {
            String device=(String)row.get("device_id");
            try{mqtt.publishAppearance(device,((String)row.get("envelope")).getBytes(StandardCharsets.UTF_8));}
            catch(MqttException ignored){service.publishFailed(device,row.get("request_id"));}
        }
    }
}
