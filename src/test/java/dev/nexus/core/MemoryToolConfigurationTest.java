package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import dev.nexus.core.security.MemoryToolAccess;
import dev.nexus.core.security.TokenCredentials;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemoryToolConfigurationTest {
    @TempDir Path directory;
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(MemoryToolAccess.class,TokenCredentials.class)
        .withPropertyValues("nexus.admin-token=administrator-fixture-token-long-enough");
    @Test void enablingTheToolWithoutASeparateValidCredentialFailsStartup() {
        for(String token:new String[]{"","short","administrator-fixture-token-long-enough","contains whitespace but otherwise long enough"})
            context.withPropertyValues("nexus.memory.mcp.enabled=true","nexus.memory.mcp.token="+token)
                .run(c->assertThat(c.getStartupFailure()).hasRootCauseMessage("NEXUS_MEMORY_MCP_TOKEN must be a separate 32-256 character machine credential"));
    }
    @Test void protectedFileStartupRequiresSeparateSecretsAndPrivatePermissions()throws Exception {
        Path file=directory.resolve("tokens.json");
        String body="{\"format\":1,\"revision\":1,\"adminActor\":\""+"a".repeat(64)+"\",\"admin\":{\"current\":{\"id\":\"admin\",\"token\":\"admin-file-credential-fixture-long-enough\"}},\"memory\":{\"current\":{\"id\":\"memory\",\"token\":\"memory-file-credential-fixture-long-enough\"}}}";
        Files.writeString(file,body);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        var configured=context.withPropertyValues("nexus.admin-token=","nexus.memory.mcp.enabled=true","nexus.auth.token-file="+file);
        configured.run(c->assertThat(c).hasNotFailed());
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        configured.run(c->assertThat(c.getStartupFailure()).hasRootCauseMessage("NEXUS_AUTH_TOKEN_FILE is unavailable or invalid"));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file,body.replace("memory-file-credential-fixture-long-enough","admin-file-credential-fixture-long-enough"));
        configured.run(c->assertThat(c.getStartupFailure()).hasRootCauseMessage("NEXUS_AUTH_TOKEN_FILE is unavailable or invalid"));
    }
}
