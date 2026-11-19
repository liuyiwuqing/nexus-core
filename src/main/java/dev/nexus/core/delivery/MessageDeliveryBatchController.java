package dev.nexus.core.delivery;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bulk cancel/retry for every delivery of one message. Reading the plan is a plain POST with
 * {@code dryRun=true}; writing requires the matching confirm token, the same two-step shape the
 * console already uses for message-content export and device credentials.
 */
@RestController
@RequestMapping("/api/v1/messages/{messageId}/deliveries")
public class MessageDeliveryBatchController {
    private final DeliveryBatchService batches;

    public MessageDeliveryBatchController(DeliveryBatchService batches) { this.batches = batches; }

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
