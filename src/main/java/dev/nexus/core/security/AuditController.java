package dev.nexus.core.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuditController {
    private final AccessAudit audit;
    public AuditController(AccessAudit audit){this.audit=audit;}
    @GetMapping("/api/v1/audit") Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
        @RequestParam(required=false) @Pattern(regexp="admin|operator|viewer|anonymous|source") String role){return audit.list(page,role);}
    public record Confirm(String confirm){}
    @GetMapping("/api/v1/audit/retention") Map<String,Object> retention(){return audit.retention();}
    @PostMapping("/api/v1/audit/retention") Map<String,Object> prune(@RequestBody Confirm input){return audit.prune(input.confirm());}
}
