package dev.nexus.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A separate machine credential grants this endpoint only, never admin APIs. */
@Component
public final class MemoryToolAccess {
    public static final String PATH="/mcp/memory";
    private final boolean enabled;
    private final byte[] expected;
    public MemoryToolAccess(@Value("${nexus.memory.mcp.enabled:false}") boolean enabled,
        @Value("${nexus.memory.mcp.token:}") String token,@Value("${nexus.admin-token}") String admin) {
        this.enabled=enabled;
        if(enabled&&(token.length()<32||token.length()>256||token.chars().anyMatch(c->c<33||c>126)||token.equals(admin)))
            throw new IllegalStateException("NEXUS_MEMORY_MCP_TOKEN must be a separate 32-256 character machine credential");
        expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    public boolean enabled(){return enabled;}
    public boolean accepts(String value){return enabled&&value!=null&&MessageDigest.isEqual(expected,value.getBytes(StandardCharsets.UTF_8));}
}
