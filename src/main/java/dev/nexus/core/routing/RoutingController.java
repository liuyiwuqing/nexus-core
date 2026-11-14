package dev.nexus.core.routing;

import dev.nexus.core.inbox.MessageInput;
import dev.nexus.core.inbox.InboxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/routing")
public class RoutingController {
    private final MessageRouting routing;
    private final InboxService inbox;
    public RoutingController(MessageRouting routing,InboxService inbox){this.routing=routing;this.inbox=inbox;}
    public record Save(@Min(0) int expectedVersion,@NotNull MessageRouting.Definition definition){}
    public record Policy(@NotNull String mode,@Min(1) int expectedVersion){}
    public record Test(@NotNull UUID requestKey,@NotNull @Size(min=1,max=32) List<@Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String> deviceIds,
                       @NotBlank @Size(max=160) String title,@NotBlank @Size(max=8192) String content,
                       @NotNull @Pattern(regexp="low|normal|high|critical") String priority,UUID templateId){}
    @GetMapping Map<String,Object> state(){return Map.of("policy",routing.policy(),"items",routing.rules());}
    @PutMapping("/policy") Map<String,Object> policy(@Valid @RequestBody Policy input){return routing.policy(input.mode(),input.expectedVersion());}
    @PutMapping("/rules/{id}") Map<String,Object> save(@PathVariable UUID id,@Valid @RequestBody Save input){return routing.save(id,input.expectedVersion(),input.definition());}
    @GetMapping("/rules/{id}") Map<String,Object> rule(@PathVariable UUID id){return routing.rule(id);}
    @DeleteMapping("/rules/{id}") ResponseEntity<Void> delete(@PathVariable UUID id,@RequestParam int expectedVersion){routing.delete(id,expectedVersion);return ResponseEntity.noContent().build();}
    @PostMapping("/preview") Map<String,Object> preview(@Valid @RequestBody MessageInput input){return routing.preview(input);}
    @PostMapping("/test") ResponseEntity<?> test(@Valid @RequestBody Test input){
        var result=inbox.targetedTest(new MessageInput("targeted-test","manual-test",input.requestKey().toString(),"manual.test",input.title(),input.content(),null,"","",input.priority(),null,null,Map.of("test","true")),input.deviceIds(),input.templateId());
        return ResponseEntity.status(result.created()?201:200).body(Map.of("messageId",result.messageId()));
    }
}
