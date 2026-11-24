package dev.nexus.core.device;

import dev.nexus.core.delivery.MqttTransport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices/{device}/companion")
public class CompanionController {
    private final CompanionService service;
    private final MqttTransport mqtt;
    public CompanionController(CompanionService service,MqttTransport mqtt){this.service=service;this.mqtt=mqtt;}
    @GetMapping Map<String,Object> view(@PathVariable String device){return service.view(device);}
    @PostMapping("/appearance") ResponseEntity<?> submit(@PathVariable String device,@RequestBody String body) {
        var result=service.submit(device,body.getBytes(StandardCharsets.UTF_8),mqtt.ready());
        return ResponseEntity.status(result.created()?202:200).body(Map.of("requestId",result.requestId(),"created",result.created()));
    }
}
