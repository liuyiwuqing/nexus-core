package dev.nexus.core.automation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/automation")
public class AutomationController {
    private final AutomationService automation;
    public AutomationController(AutomationService automation) { this.automation = automation; }
    public record Rule(@NotBlank @Size(max=80) String name, @NotBlank String trigger, @NotBlank String action,
                       String localTime, String zone, String title, String content) {}
    public record Enabled(boolean enabled) {}
    @GetMapping("/rules") Map<String,Object> rules() { return Map.of("items", automation.rules()); }
    @PostMapping("/rules") ResponseEntity<?> create(@Valid @RequestBody Rule rule) {
        return ResponseEntity.status(201).body(Map.of("ruleId", automation.create(rule.name(), rule.trigger(), rule.action(), rule.localTime(), rule.zone(), rule.title(), rule.content())));
    }
    @PutMapping("/rules/{id}/enabled") ResponseEntity<Void> enable(@PathVariable UUID id, @RequestBody Enabled enabled) {
        automation.enable(id, enabled.enabled()); return ResponseEntity.noContent().build();
    }
    @GetMapping("/runs") Map<String,Object> runs(@RequestParam(required=false) UUID ruleId) { return Map.of("items", automation.runs(ruleId)); }
    @PostMapping("/runs/{id}/retry") ResponseEntity<Void> retry(@PathVariable UUID id) { automation.retry(id); return ResponseEntity.accepted().build(); }
}
