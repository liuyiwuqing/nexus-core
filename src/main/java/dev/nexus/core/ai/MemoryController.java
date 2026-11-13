package dev.nexus.core.ai;

import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MemoryController {
    private final MemoryService memory;
    private final MemoryRetrievalService retrieval;
    private final MemoryCopyService copies;
    public MemoryController(MemoryService memory,MemoryRetrievalService retrieval,MemoryCopyService copies) {this.memory=memory;this.retrieval=retrieval;this.copies=copies;}
    public record Query(@NotBlank @Size(max=256) String query,@Min(1) @Max(5) Integer limit) {}
    @GetMapping("/memory") Map<String,Object> configuration() { return copies.health(); }
    @PostMapping("/messages/{id}/memory") ResponseEntity<Void> enqueue(@PathVariable UUID id) { memory.enqueue(id); return ResponseEntity.accepted().build(); }
    @PostMapping("/memory/search") MemoryRetrievalService.Results search(@Valid @RequestBody Query query) {
        return retrieval.search(query.query().strip(),query.limit()==null?3:query.limit());
    }
}
