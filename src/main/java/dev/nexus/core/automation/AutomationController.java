package dev.nexus.core.automation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
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
                       String localTime, String zone, String title, String content, UUID requestKey, Map<String,String> conditions) {}
    public record Enabled(boolean enabled, @Min(1) Integer expectedVersion) {}
    public record Preview(@NotNull @Min(1) Integer expectedVersion) {}
    public record Edit(@NotBlank @Size(max=80) String name, @NotNull @Min(1) Integer expectedVersion,
                       String localTime, String zone, String title, String content, Map<String,String> conditions) {}
    @GetMapping("/rules") Map<String,Object> rules() { return Map.of("items", automation.rules()); }
    @GetMapping("/rules/{id}") Map<String,Object> rule(@PathVariable UUID id) { return automation.rule(id); }
    @PostMapping("/rules") ResponseEntity<?> create(@Valid @RequestBody Rule rule) {
        var result=automation.create(rule.name(), rule.trigger(), rule.action(), rule.localTime(), rule.zone(), rule.title(), rule.content(), rule.requestKey(),rule.conditions());
        return ResponseEntity.status(result.created()?201:200).body(Map.of("ruleId",result.ruleId()));
    }
    @PutMapping("/rules/{id}") Map<String,Object> edit(@PathVariable UUID id, @Valid @RequestBody Edit input) {
        return automation.edit(id, input.expectedVersion(), input.name(), input.localTime(), input.zone(), input.title(), input.content(),input.conditions());
    }
    @PutMapping("/rules/{id}/enabled") ResponseEntity<Void> enable(@PathVariable UUID id, @Valid @RequestBody Enabled enabled) {
        automation.enable(id, enabled.enabled(), enabled.expectedVersion()); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/rules/{id}") ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam @Min(1) int expectedVersion) {
        automation.delete(id, expectedVersion); return ResponseEntity.noContent().build();
    }
    @PostMapping("/rules/{id}/preview") Map<String,Object> preview(@PathVariable UUID id, @Valid @RequestBody Preview input) {
        return automation.preview(id, input.expectedVersion());
    }
    @GetMapping("/runs") Map<String,Object> runs(@RequestParam(required=false) UUID ruleId) { return Map.of("items", automation.runs(ruleId)); }
    @PostMapping("/runs/{id}/retry") ResponseEntity<Void> retry(@PathVariable UUID id) { automation.retry(id); return ResponseEntity.accepted().build(); }
}
