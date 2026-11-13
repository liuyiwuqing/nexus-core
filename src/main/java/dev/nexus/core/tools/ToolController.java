package dev.nexus.core.tools;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ToolController {
    private final ToolJobService jobs;
    public ToolController(ToolJobService jobs) { this.jobs = jobs; }
    public record Request(@NotNull UUID requestKey, @NotBlank String toolId, @NotNull Map<String,Object> arguments) {}
    public record Confirmation(@NotBlank String argumentsHash) {}
    @GetMapping("/tools") Map<String,Object> catalog() { return jobs.catalog(); }
    @GetMapping("/tool-jobs") Map<String,Object> list() { return Map.of("items", jobs.list()); }
    @GetMapping("/tool-jobs/{id}") Map<String,Object> get(@PathVariable UUID id) { return jobs.get(id); }
    @GetMapping("/tool-jobs/{id}/audit") Map<String,Object> audit(@PathVariable UUID id) { return Map.of("items", jobs.history(id)); }
    @PostMapping("/tool-jobs") ResponseEntity<?> prepare(@Valid @RequestBody Request input) {
        var result = jobs.prepare(input.requestKey(), input.toolId(), input.arguments());
        return ResponseEntity.status(result.created() ? 201 : 200).body(jobs.get(result.jobId()));
    }
    @PostMapping("/tool-jobs/{id}/confirm") ResponseEntity<Void> confirm(@PathVariable UUID id, @Valid @RequestBody Confirmation input) {
        jobs.confirm(id, input.argumentsHash()); return ResponseEntity.accepted().build();
    }
    @PostMapping("/tool-jobs/{id}/cancel") ResponseEntity<Void> cancel(@PathVariable UUID id) { jobs.cancel(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/tool-jobs/{id}/reconcile") ResponseEntity<Void> reconcile(@PathVariable UUID id) { jobs.reconcile(id); return ResponseEntity.noContent().build(); }
}
