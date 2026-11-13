package dev.nexus.core.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;

/** Authenticated, silent health probe; credentials never enter process arguments or output. */
public final class ContainerHealthcheck {
    private ContainerHealthcheck() {}

    public static void main(String[] args) {
        try {
            var json = JsonMapper.builder().build();
            String token = System.getenv("NEXUS_ADMIN_TOKEN");
            String filename = System.getenv("NEXUS_AUTH_TOKEN_FILE");
            if (filename != null && !filename.isBlank()) {
                try (var input = Files.newInputStream(Path.of(filename))) {
                    byte[] bytes = input.readNBytes(16385);
                    if (bytes.length > 16384) throw new IllegalStateException();
                    token = json.readTree(bytes).path("admin").path("current").path("token").asText();
                }
            }
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                    System.getenv().getOrDefault("NEXUS_PORT", "8088") + "/api/v1/status"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                byte[] bytes = input.readNBytes(4097);
                if (response.statusCode() != 200 || bytes.length > 4096) throw new IllegalStateException();
                var status = json.readTree(bytes);
                if (!"ready".equals(status.path("database").asText()) ||
                    !"ready".equals(status.path("mqtt").asText())) throw new IllegalStateException();
            }
        } catch (Exception ignored) {
            System.exit(1);
        }
    }
}
