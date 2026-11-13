package dev.nexus.core.inbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {
    private final RetentionService retention;
    public RetentionController(RetentionService retention) {this.retention=retention;}
    public record Confirmation(@NotNull @Pattern(regexp="DELETE_EXPIRED_MESSAGES") String confirm) {}
    @GetMapping Map<String,Object> preview() {return retention.preview();}
    @PostMapping("/runs") Map<String,Integer> run(@Valid @RequestBody Confirmation input) {return retention.run("admin");}
}
