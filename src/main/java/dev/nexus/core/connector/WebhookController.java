package dev.nexus.core.connector;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WebhookController {
    private final WebhookSources sources;
    public WebhookController(WebhookSources sources){this.sources=sources;}
    public record Create(@NotBlank String name,@NotNull List<String> deviceIds){}
    public record Update(@NotBlank String name,boolean enabled,@NotNull List<String> deviceIds,@Min(1) int expectedVersion){}
    public record Version(@Min(1) int expectedVersion){}
    public record Test(@NotNull UUID requestKey,@NotBlank String title,@NotBlank String content){}
    @GetMapping("/api/v1/sources") Map<String,Object> list(){return Map.of("items",sources.list());}
    @PutMapping("/api/v1/sources/{id}") Map<String,Object> create(@PathVariable String id,@Valid @RequestBody Create input){return sources.create(id,input.name(),input.deviceIds());}
    @PatchMapping("/api/v1/sources/{id}") Map<String,Object> update(@PathVariable String id,@Valid @RequestBody Update input){return sources.update(id,input.expectedVersion(),input.name(),input.enabled(),input.deviceIds());}
    @PostMapping("/api/v1/sources/{id}/credentials") Map<String,Object> rotate(@PathVariable String id,@Valid @RequestBody Version input){return sources.rotate(id,input.expectedVersion());}
    @PostMapping("/api/v1/sources/{id}/test") ResponseEntity<?> test(@PathVariable String id,@Valid @RequestBody Test input){return ResponseEntity.accepted().body(sources.test(id,input.requestKey(),input.title(),input.content()));}
    @PostMapping("/api/v1/hooks/{id}") ResponseEntity<?> receive(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String token,
        @RequestHeader(value="X-Nexus-Timestamp",required=false) String timestamp,@RequestHeader(value="X-Nexus-Signature",required=false) String signature,@RequestBody byte[] raw,HttpServletResponse response){
        try{return ResponseEntity.accepted().body(sources.receive(id,token,timestamp,signature,raw));}
        catch(ResponseStatusException e){if(e.getStatusCode().value()==429)response.setHeader("Retry-After","60");throw e;}
    }
    @GetMapping("/api/v1/hooks/{id}/messages/{message}") Map<String,Object> receipt(@PathVariable String id,@PathVariable UUID message,@RequestHeader(value="Authorization",required=false) String token){return sources.receipt(id,message,token);}
}
