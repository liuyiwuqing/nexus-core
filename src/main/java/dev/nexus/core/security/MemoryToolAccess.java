package dev.nexus.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A separate machine credential grants this endpoint only, never admin APIs. */
@Component
public final class MemoryToolAccess {
    public static final String PATH="/mcp/memory";
    private final boolean enabled;
    private final TokenCredentials credentials;
    public MemoryToolAccess(@Value("${nexus.memory.mcp.enabled:false}") boolean enabled,
        TokenCredentials credentials) {
        this.enabled=enabled;
        this.credentials=credentials;
    }
    public boolean enabled(){return enabled;}
    public boolean accepts(String value){return enabled&&credentials.acceptsMemory(value);}
}
