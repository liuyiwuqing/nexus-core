package dev.nexus.core.inbox;

import dev.nexus.core.web.MetadataCsv;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/messages")
public class InboxController {
    private final InboxService inbox;
    public InboxController(InboxService inbox) { this.inbox = inbox; }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody MessageInput input) {
        var result = inbox.ingest(input);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Map.of("messageId", result.messageId()));
    }

    @GetMapping
    Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
                            @RequestParam(defaultValue="30") @Min(1) @Max(100) int size,
                            @RequestParam(required=false) @Pattern(regexp="read|unread") String readStatus,
                            @RequestParam(required=false) String source,
                            @RequestParam(required=false) String receivedFrom,
                            @RequestParam(required=false) String receivedBefore) {
        return inbox.list(page, size, readStatus, source, receivedFrom, receivedBefore);
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable UUID id) {
        inbox.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/export")
    ResponseEntity<String> export(@RequestParam(required=false) String readStatus,
                                  @RequestParam(required=false) String source,
                                  @RequestParam(required=false) String receivedFrom,
                                  @RequestParam(required=false) String receivedBefore) {
        return MetadataCsv.response("messages-metadata.csv", inbox.export(readStatus, source, receivedFrom, receivedBefore),
            "messageId", "connectorId", "source", "eventType", "priority", "receivedAt", "createdAt", "expiresAt", "readAt", "readStatus", "contentPurgedAt");
    }
    @GetMapping("/content-export")
    ResponseEntity<String> contentExport(@RequestParam String confirm,@RequestParam(required=false) String readStatus,
        @RequestParam(required=false) String source,@RequestParam(required=false) String receivedFrom,@RequestParam(required=false) String receivedBefore){
        if(!"EXPORT_MESSAGE_CONTENT".equals(confirm))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"content_export_confirmation_required");
        return MetadataCsv.response("messages-content.csv",inbox.exportContent(readStatus,source,receivedFrom,receivedBefore),"messageId","source","eventType","title","content","summary","sender","originalUrl","priority","receivedAt","createdAt");
    }
}
