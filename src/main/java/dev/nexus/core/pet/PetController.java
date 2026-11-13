package dev.nexus.core.pet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pet")
public class PetController {
    private final FocusService focus;
    public PetController(FocusService focus) { this.focus = focus; }
    public record Start(@NotNull UUID requestKey, @Min(1) @Max(180) int durationMinutes) {}
    @GetMapping Map<String,Object> profile() { return focus.profile(); }
    @GetMapping("/focus") Map<String,Object> sessions() { return Map.of("items", focus.sessions()); }
    @PostMapping("/focus") ResponseEntity<?> start(@Valid @RequestBody Start input) {
        return ResponseEntity.status(201).body(Map.of("sessionId", focus.start(input.requestKey(), input.durationMinutes())));
    }
    @PostMapping("/focus/{id}/complete") Map<String,Object> complete(@PathVariable UUID id) { return focus.complete(id); }
    @PostMapping("/focus/{id}/cancel") ResponseEntity<Void> cancel(@PathVariable UUID id) { focus.cancel(id); return ResponseEntity.noContent().build(); }
}
