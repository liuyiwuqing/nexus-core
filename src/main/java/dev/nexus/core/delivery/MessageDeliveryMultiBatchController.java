package dev.nexus.core.delivery;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bulk cancel/retry across a small set of messages.
 *
 * <p>Reading the combined plan is a plain POST with {@code dryRun=true}; writing requires the
 * matching batch confirm token, distinct from the single-message tokens so a reviewed per-message
 * plan can never authorise a wider write. Every message in the selection must still exist: the
 * whole request is refused instead of applying the readable subset.
 */
@RestController
@RequestMapping("/api/v1/deliveries")
public class MessageDeliveryMultiBatchController {
    private final MessageDeliveryBatchService batches;

    public MessageDeliveryMultiBatchController(MessageDeliveryBatchService batches) { this.batches = batches; }

    public record Selection(List<UUID> messageIds) {}

    @PostMapping("/batch-cancel")
    Map<String,Object> cancel(@RequestParam(defaultValue="false") boolean dryRun,
                              @RequestParam(required=false) String confirm,
                              @RequestBody(required=false) Selection selection) {
        if (!dryRun && !"CANCEL_MESSAGE_DELIVERIES_BATCH".equals(confirm))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_batch_confirmation_required");
        return batches.runMany(selection == null ? null : selection.messageIds(), true, dryRun);
    }

    @PostMapping("/batch-retry")
    Map<String,Object> retry(@RequestParam(defaultValue="false") boolean dryRun,
                             @RequestParam(required=false) String confirm,
                             @RequestBody(required=false) Selection selection) {
        if (!dryRun && !"RETRY_MESSAGE_DELIVERIES_BATCH".equals(confirm))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_batch_confirmation_required");
        return batches.runMany(selection == null ? null : selection.messageIds(), false, dryRun);
    }
}
