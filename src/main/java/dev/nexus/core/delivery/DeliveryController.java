package dev.nexus.core.delivery;

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
                                       @RequestParam(required=false) @Pattern(regexp="pending|sent|received|displayed|read|failed|expired") String status) {
        return delivery.list(page, size, status);
    }
    @PostMapping("/{id}/retry") ResponseEntity<Void> retry(@PathVariable UUID id) {
        delivery.retry(id);
        return ResponseEntity.noContent().build();
    }
}
