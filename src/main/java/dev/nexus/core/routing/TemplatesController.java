package dev.nexus.core.routing;

import dev.nexus.core.inbox.MessageInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplatesController {
    private final MessageTemplates templates;
    public TemplatesController(MessageTemplates templates){this.templates=templates;}
    public record Save(@Min(0) int expectedVersion,@NotNull MessageTemplates.Definition definition){}
    public record Preview(@NotNull MessageTemplates.Definition definition,@NotNull @Valid MessageInput message){}
    @GetMapping Map<String,Object> list(){return Map.of("items",templates.list(),"variables",templates.variables(),"capabilities",Map.of("sounds",java.util.List.of("none","chime","alert"),"minDurationSeconds",5,"maxDurationSeconds",30,"titleBytes",96,"summaryBytes",180));}
    @GetMapping("/{id}") Map<String,Object> get(@PathVariable UUID id){return templates.get(id);}
    @PutMapping("/{id}") Map<String,Object> save(@PathVariable UUID id,@Valid @RequestBody Save input){return templates.save(id,input.expectedVersion(),input.definition());}
    @DeleteMapping("/{id}") ResponseEntity<?> delete(@PathVariable UUID id,@RequestParam int expectedVersion){templates.delete(id,expectedVersion);return ResponseEntity.noContent().build();}
    @PostMapping("/preview") MessageTemplates.Rendered preview(@Valid @RequestBody Preview input){return templates.render(input.definition(),input.message(),0);}
}
