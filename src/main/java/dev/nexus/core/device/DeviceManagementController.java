package dev.nexus.core.device;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices/{device}/management")
public class DeviceManagementController {
    private final DeviceManagementService service;
    public DeviceManagementController(DeviceManagementService service) { this.service=service; }
    @GetMapping Map<String,Object> view(@PathVariable String device) { return service.view(device); }
    @PostMapping("/config-requests") ResponseEntity<?> submit(@PathVariable String device,@RequestBody String body) {
        var result=service.submit(device,body.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(result.created()?202:200).body(Map.of("requestId",result.requestId(),"created",result.created()));
    }
    @GetMapping("/config-requests/{request}/audit") Map<String,Object> history(@PathVariable String device,@PathVariable String request) {
        return Map.of("items",service.history(device,request));
    }
}
