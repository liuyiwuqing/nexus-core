package dev.nexus.core.automation;

import dev.nexus.core.inbox.InboxService;
import dev.nexus.core.inbox.MessageInput;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class DeviceNoticeAction implements AutomationAction {
    private final InboxService inbox;
    public DeviceNoticeAction(InboxService inbox) { this.inbox = inbox; }
    public String id() { return "notify_device"; }
    public Map<String,Object> enqueue(UUID executionId, JsonNode event, JsonNode arguments) {
        var result = inbox.ingest(new MessageInput("automation", "automation", executionId.toString(), "automation.notice",
            arguments.path("title").asText(), arguments.path("content").asText(), null, "NEXUS", "", "normal", null, null, Map.of(), null));
        return Map.of("messageId", result.messageId(), "outcome", "delivery_queued");
    }
}
