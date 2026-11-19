package dev.nexus.core.delivery;

import dev.nexus.core.web.MetadataCsv;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {
    private final DeliveryService delivery;
    public DeliveryController(DeliveryService delivery) { this.delivery = delivery; }
    @GetMapping Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
                                       @RequestParam(defaultValue="30") @Min(1) @Max(100) int size,
                                       @RequestParam(required=false) @Pattern(regexp="pending|sent|received|displayed|read|failed|expired|cancelled") String status,
                                       @RequestParam(required=false) String deviceId,
                                       @RequestParam(required=false) String lastError,
                                       @RequestParam(required=false) String receivedFrom,
                                       @RequestParam(required=false) String receivedBefore,
                                       @RequestParam(required=false) String waiting) {
        return delivery.list(page, size, status, deviceId, lastError, receivedFrom, receivedBefore, waiting);
    }
    @GetMapping("/{id}/history") Map<String,Object> history(@PathVariable UUID id,
            @RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue="30") @Min(1) @Max(100) int size) {
        return delivery.history(id,page,size);
    }
    @GetMapping("/export") ResponseEntity<String> export(@RequestParam(required=false) String status,
            @RequestParam(required=false) String deviceId, @RequestParam(required=false) String lastError,
            @RequestParam(required=false) String receivedFrom, @RequestParam(required=false) String receivedBefore,
            @RequestParam(required=false) String waiting) {
        return MetadataCsv.response("deliveries-metadata.csv", delivery.export(status, deviceId, lastError, receivedFrom, receivedBefore, waiting),
            "eventId", "messageId", "deviceId", "status", "attempts", "messageReceivedAt", "receivedAt", "displayedAt", "readAt", "nextAttemptAt");
    }
    @PostMapping("/{id}/cancel") ResponseEntity<Void> cancel(@PathVariable UUID id) {
        delivery.cancel(id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/retry") ResponseEntity<Void> retry(@PathVariable UUID id) {
        delivery.retry(id);
        return ResponseEntity.noContent().build();
    }
}
