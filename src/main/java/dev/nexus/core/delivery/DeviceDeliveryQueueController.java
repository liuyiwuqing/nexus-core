package dev.nexus.core.delivery;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cancels one device's still-waiting deliveries.
 *
 * <p>{@code dryRun=true} explains the whole pending+sent queue; writing requires the confirm token.
 * Like message batches, it only reaches rows the single-delivery cancel accepts, so a sent delivery
 * is reported and left in place rather than retracted from the device.
 */
@RestController
@RequestMapping("/api/v1/devices/{deviceId}/deliveries")
public class DeviceDeliveryQueueController {
    private final DeliveryBatchService batches;

    public DeviceDeliveryQueueController(DeliveryBatchService batches) { this.batches = batches; }

    @PostMapping("/cancel")
    Map<String,Object> cancel(@PathVariable String deviceId, @RequestParam(defaultValue="false") boolean dryRun,
                              @RequestParam(required=false) String confirm) {
        if (!dryRun && !"CANCEL_DEVICE_DELIVERIES".equals(confirm))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_batch_confirmation_required");
        return batches.cancelDeviceQueue(deviceId, dryRun);
    }
}
