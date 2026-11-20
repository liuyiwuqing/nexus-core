package dev.nexus.core.device;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Read-only telemetry history; the live snapshot remains under /management. */
@RestController
@RequestMapping("/api/v1/devices/{device}/telemetry")
public class DeviceTelemetryController {
    private final TelemetryHistoryService service;
    public DeviceTelemetryController(TelemetryHistoryService service) { this.service=service; }
    @GetMapping Map<String,Object> query(@PathVariable String device,
        @RequestParam(required=false) String from,@RequestParam(required=false) String to,
        @RequestParam(required=false) String bucket) {
        return service.query(device,from,to,bucket);
    }
    @GetMapping("/disconnects") Map<String,Object> disconnects(@PathVariable String device,
        @RequestParam(required=false) String limit,@RequestParam(required=false) String reason) {
        return service.disconnects(device,limit,reason);
    }
}
