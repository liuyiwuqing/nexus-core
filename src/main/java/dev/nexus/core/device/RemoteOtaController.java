package dev.nexus.core.device;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RemoteOtaController {
    private final RemoteOtaService service;
    public RemoteOtaController(RemoteOtaService service){this.service=service;}
    @GetMapping("/ota/releases") Map<String,Object> releases(){return service.releases();}
    @PostMapping("/ota/releases") ResponseEntity<?> register(@RequestBody String body) {
        boolean created=service.register(body.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(created?201:200).body(Map.of("created",created));
    }
    @GetMapping("/devices/{device}/ota") Map<String,Object> view(@PathVariable String device){return service.view(device);}
    @PostMapping("/devices/{device}/ota/deployments") ResponseEntity<?> submit(@PathVariable String device,@RequestBody String body) {
        var result=service.submit(device,body.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(result.created()?202:200).body(Map.of("requestId",result.requestId(),"created",result.created()));
    }
    @PostMapping("/devices/{device}/ota/deployments/{request}/reboot") ResponseEntity<?> reboot(@PathVariable String device,@PathVariable String request,@RequestBody String body) {
        var result=service.reboot(device,request,body.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(result.created()?202:200).body(Map.of("requestId",result.requestId(),"created",result.created()));
    }
    @GetMapping("/devices/{device}/ota/deployments/{request}/audit") Map<String,Object> audit(@PathVariable String device,@PathVariable String request) {
        return Map.of("items",service.history(device,request));
    }
}
