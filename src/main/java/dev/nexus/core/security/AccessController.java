package dev.nexus.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccessController {
    private final RoleTokens tokens;
    public AccessController(RoleTokens tokens){this.tokens=tokens;}
    public record Issue(@NotBlank String name,@NotBlank String role,@NotNull Instant expiresAt){}
    public record Version(@Min(1) int expectedVersion){}
    @PostMapping("/api/v1/session") Map<String,Object> login(HttpServletRequest request){return session(request);}
    @GetMapping("/api/v1/session") Map<String,Object> session(HttpServletRequest request){return Map.of("role",request.getAttribute("nexus.role"),"actor",request.getAttribute("nexus.actor"));}
    @GetMapping("/api/v1/access/tokens") Map<String,Object> list(){return Map.of("items",tokens.list());}
    @PostMapping("/api/v1/access/tokens") Map<String,Object> issue(@Valid @RequestBody Issue input){return tokens.issue(input.name(),input.role(),input.expiresAt());}
    @PostMapping("/api/v1/access/tokens/{id}/revoke") ResponseEntity<?> revoke(@PathVariable UUID id,@Valid @RequestBody Version input){tokens.revoke(id,input.expectedVersion());return ResponseEntity.noContent().build();}
}
