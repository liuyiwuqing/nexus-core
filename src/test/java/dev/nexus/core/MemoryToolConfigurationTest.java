package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import dev.nexus.core.security.MemoryToolAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryToolConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(MemoryToolAccess.class)
        .withPropertyValues("nexus.admin-token=administrator-fixture-token-long-enough");
    @Test void enablingTheToolWithoutASeparateValidCredentialFailsStartup() {
        for(String token:new String[]{"","short","administrator-fixture-token-long-enough","contains whitespace but otherwise long enough"})
            context.withPropertyValues("nexus.memory.mcp.enabled=true","nexus.memory.mcp.token="+token)
                .run(c->assertThat(c.getStartupFailure()).hasRootCauseMessage("NEXUS_MEMORY_MCP_TOKEN must be a separate 32-256 character machine credential"));
    }
}
