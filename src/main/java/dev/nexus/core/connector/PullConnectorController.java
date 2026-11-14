package dev.nexus.core.connector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connectors/pull")
public class PullConnectorController {
    private final PullConnectors connectors;
    public PullConnectorController(PullConnectors connectors){this.connectors=connectors;}
    public record Version(@Min(1) int expectedVersion,@NotNull @Pattern(regexp="[a-f0-9]{64}") String configuration){}
    public record Enabled(@Min(1) int expectedVersion,@NotNull @Pattern(regexp="[a-f0-9]{64}") String configuration,boolean enabled){}
    @GetMapping Map<String,Object> list(){return Map.of("items",connectors.list());}
    @PutMapping("/{id}/enabled") Map<String,Object> enabled(@PathVariable String id,@Valid @RequestBody Enabled input){return connectors.enabled(id,input.configuration(),input.expectedVersion(),input.enabled());}
    @PostMapping("/{id}/probe") Map<String,Object> probe(@PathVariable String id){return connectors.probe(id);}
    @PostMapping("/{id}/poll") Map<String,Object> poll(@PathVariable String id,@Valid @RequestBody Version input){return connectors.poll(id,input.configuration(),input.expectedVersion());}
}
