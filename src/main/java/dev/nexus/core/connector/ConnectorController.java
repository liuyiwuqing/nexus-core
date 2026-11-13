package dev.nexus.core.connector;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {
    private final List<InboxConnector> connectors;
    private final HaloConnector halo;
    public ConnectorController(List<InboxConnector> connectors, HaloConnector halo) { this.connectors = connectors; this.halo = halo; }
    @GetMapping Map<String,Object> list() { return Map.of("items", connectors.stream().map(InboxConnector::health).toList()); }
    @PostMapping("/halo/poll") Map<String,Integer> pollHalo() { return Map.of("received", halo.poll()); }
    @PostMapping("/halo/comments/{name}/import") ResponseEntity<?> importComment(@PathVariable String name) {
        var result = halo.importComment(name);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Map.of("messageId", result.messageId()));
    }
}
