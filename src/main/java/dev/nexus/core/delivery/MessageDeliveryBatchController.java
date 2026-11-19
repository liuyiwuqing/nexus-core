package dev.nexus.core.delivery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read the per-device deliveries of one message, and bulk cancel/retry them. Reading the batch plan
 * is a plain POST with {@code dryRun=true}; writing requires the matching confirm token, the same
 * two-step shape the console already uses for message-content export and device credentials.
 */
@Validated
@RestController
@RequestMapping("/api/v1/messages/{messageId}/deliveries")
public class MessageDeliveryBatchController {
    private final DeliveryBatchService batches;
    private final DeliveryService deliveries;

    public MessageDeliveryBatchController(DeliveryBatchService batches, DeliveryService deliveries) {
        this.batches = batches;
        this.deliveries = deliveries;
    }

    @GetMapping
    Map<String,Object> list(@PathVariable UUID messageId,
                            @RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
                            @RequestParam(defaultValue="100") @Min(1) @Max(100) int size) {
        return deliveries.forMessage(messageId, page, size);
    }

    @PostMapping("/cancel")
    Map<String,Object> cancel(@PathVariable UUID messageId, @RequestParam(defaultValue="false") boolean dryRun,
                              @RequestParam(required=false) String confirm) {
        return run(messageId, true, dryRun, confirm, "CANCEL_MESSAGE_DELIVERIES");
    }

    @PostMapping("/retry")
    Map<String,Object> retry(@PathVariable UUID messageId, @RequestParam(defaultValue="false") boolean dryRun,
                             @RequestParam(required=false) String confirm) {
        return run(messageId, false, dryRun, confirm, "RETRY_MESSAGE_DELIVERIES");
    }

    private Map<String,Object> run(UUID messageId, boolean cancel, boolean dryRun, String confirm, String token) {
        if (!dryRun && !token.equals(confirm)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "delivery_batch_confirmation_required");
        return batches.run(messageId, cancel, dryRun);
    }
}
