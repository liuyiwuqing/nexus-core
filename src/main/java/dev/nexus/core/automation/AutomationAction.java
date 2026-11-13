package dev.nexus.core.automation;

import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Actions must be idempotent by executionId and commit their local effects transactionally. */
public interface AutomationAction {
    String id();
    Map<String,Object> enqueue(UUID executionId, JsonNode event, JsonNode arguments);
}
