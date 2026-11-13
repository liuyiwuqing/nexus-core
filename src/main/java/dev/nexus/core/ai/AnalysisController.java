package dev.nexus.core.ai;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
    private final AnalysisService analysis;
    public AnalysisController(AnalysisService analysis) { this.analysis = analysis; }
    @GetMapping("/ai") Map<String,Object> configuration() { return analysis.configuration(); }
    @GetMapping("/messages/{id}/analysis") Map<String,Object> get(@PathVariable UUID id) { return analysis.get(id); }
    @PostMapping("/messages/{id}/analysis") ResponseEntity<Void> enqueue(@PathVariable UUID id) { analysis.enqueue(id, false); return ResponseEntity.accepted().build(); }
    @PostMapping("/messages/{id}/analysis/retry") ResponseEntity<Void> retry(@PathVariable UUID id) { analysis.enqueue(id, true); return ResponseEntity.accepted().build(); }
}
