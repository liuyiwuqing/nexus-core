package dev.nexus.core.automation;

import dev.nexus.core.ai.AnalysisService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class PublicCommentAnalysisAction implements AutomationAction {
    private final AnalysisService analysis;
    public PublicCommentAnalysisAction(AnalysisService analysis) { this.analysis = analysis; }
    public String id() { return "analyze_public_comment"; }
    public Map<String,Object> enqueue(UUID executionId, JsonNode event, JsonNode arguments) {
        UUID message = UUID.fromString(event.path("messageId").asText());
        analysis.enqueue(message, false);
        if (java.util.List.of("blocked", "failed").contains(analysis.get(message).get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "analysis_needs_review");
        return Map.of("messageId", message, "outcome", "analysis_queued");
    }
}
