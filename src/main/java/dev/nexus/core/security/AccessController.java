package dev.nexus.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final RoleTokens tokens;private final BrowserSessions sessions;private final TokenCredentials credentials;
    public AccessController(RoleTokens tokens,BrowserSessions sessions,TokenCredentials credentials){this.credentials=credentials;this.tokens=tokens;this.sessions=sessions;}
    public record Issue(@NotBlank String name,@NotBlank String role,@NotNull Instant expiresAt){}
    public record Version(@Min(1) int expectedVersion){}
    public record Login(boolean remember){}
    public record Edit(@NotBlank String name,@NotBlank String role,@NotNull Instant expiresAt,@Min(1) int expectedVersion){}
    @PostMapping("/api/v1/session") Map<String,Object> login(HttpServletRequest request,HttpServletResponse response,@RequestBody(required=false) Login input){
        if(input!=null&&input.remember())request.setAttribute("nexus.browserSession",sessions.create(request,response,(String)request.getAttribute("nexus.authorization")));
        else {sessions.invalidate(request,response);request.setAttribute("nexus.browserSession",null);}
        return session(request);
    }
    @GetMapping("/api/v1/session") Map<String,Object> session(HttpServletRequest request){
        var value=new java.util.HashMap<String,Object>();value.put("role",request.getAttribute("nexus.role"));value.put("actor",request.getAttribute("nexus.actor"));
        var browser=(BrowserSessions.Session)request.getAttribute("nexus.browserSession");if(browser!=null)value.putAll(browser.describe());return value;
    }
    @PostMapping("/api/v1/session/logout") ResponseEntity<?> logout(HttpServletRequest request,HttpServletResponse response){sessions.invalidate(request,response);return ResponseEntity.noContent().build();}
    public record AdminChange(@Min(1) long expectedRevision,@NotBlank String currentToken,@NotBlank String newToken){@Override public String toString(){return "AdminChange[redacted]";}}
    @GetMapping("/api/v1/access/admin-token") Map<String,Object> adminToken(){return credentials.management();}
    @PutMapping("/api/v1/access/admin-token") Map<String,Object> changeAdmin(@Valid @RequestBody AdminChange input,HttpServletRequest request,HttpServletResponse response){
        if(tokens.ownsCredential(input.newToken()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"admin_token_reused");
        long revision=credentials.changeAdmin(input.expectedRevision(),input.currentToken(),input.newToken());
        if(request.getAttribute("nexus.browserSession")!=null)request.setAttribute("nexus.browserSession",sessions.create(request,response,"Bearer "+input.newToken()));
        var result=new java.util.HashMap<>(session(request));result.put("revision",revision);return result;
    }
    @GetMapping("/api/v1/access/tokens") Map<String,Object> list(){return Map.of("items",tokens.list());}
    @PostMapping("/api/v1/access/tokens") Map<String,Object> issue(@Valid @RequestBody Issue input){return tokens.issue(input.name(),input.role(),input.expiresAt());}
    @PutMapping("/api/v1/access/tokens/{id}") Map<String,Object> edit(@PathVariable UUID id,@Valid @RequestBody Edit input){return tokens.edit(id,input.expectedVersion(),input.name(),input.role(),input.expiresAt());}
    @PostMapping("/api/v1/access/tokens/{id}/rotate") Map<String,Object> rotate(@PathVariable UUID id,@Valid @RequestBody Version input){return tokens.rotate(id,input.expectedVersion());}
    @PostMapping("/api/v1/access/tokens/{id}/revoke") ResponseEntity<?> revoke(@PathVariable UUID id,@Valid @RequestBody Version input){tokens.revoke(id,input.expectedVersion());return ResponseEntity.noContent().build();}
}
