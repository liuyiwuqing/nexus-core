package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rule editing through HTTP with the real scheduler and isolated PostgreSQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentExportFlowTest {
    static final String TOKEN="history-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core history-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"history-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
        p.add("nexus.automation.tick-ms",()->"100");
        p.add("nexus.delivery.max-attempts",()->"1");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    @Autowired dev.nexus.core.routing.MessageRouting routing;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path)throws Exception {
        var response=request("GET",path,"",TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    @Test void administratorConfirmationBoundsAndEscapingProtectContentExport() throws Exception {
        fixtures.execute("TRUNCATE inbox_messages CASCADE");
        String source="body-export";
        var accepted=request("POST","messages",json.writeValueAsString(Map.of("connectorId",source,"source",source,"eventId",UUID.randomUUID(),"eventType","export.test","title","=FORMULA,\"quoted\"","content","正文\n第二行","sender","@sender")),TOKEN);
        assertThat(accepted.statusCode()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(accepted.body()).path("messageId").asText());
        assertThat(request("GET","messages/content-export?confirm=no","",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",null).statusCode()).isEqualTo(401);
        var viewer=request("POST","access/tokens",json.writeValueAsString(Map.of("name","readonly","role","viewer","expiresAt",java.time.Instant.now().plusSeconds(3600).toString())),TOKEN);
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",json.readTree(viewer.body()).path("token").asText()).statusCode()).isEqualTo(403);
        assertThat(request("GET","messages/content-export;matrix=1?confirm=EXPORT_MESSAGE_CONTENT","",json.readTree(viewer.body()).path("token").asText()).statusCode()).isEqualTo(403);
        var csv=request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT&source="+source,"",TOKEN);
        assertThat(csv.statusCode()).isEqualTo(200);assertThat(csv.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(csv.body()).startsWith("\ufeff").contains("正文\n第二行","'=FORMULA,\"\"quoted\"\"","'@sender").doesNotContain("token_hash");
        fixtures.update("UPDATE inbox_messages SET received_at=now()-interval '8 days' WHERE message_id=?",id);
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",TOKEN).body()).doesNotContain("正文");
        fixtures.update("UPDATE inbox_messages SET received_at=now(),content_purged_at=now() WHERE message_id=?",id);
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",TOKEN).body()).doesNotContain("正文");
        fixtures.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at,metadata)
            SELECT gen_random_uuid(),'body-export','body-export',n::text,'test','x','x','x','','','normal',now(),now()+interval '1 hour','{}'::jsonb FROM generate_series(1,1001) n
            """);
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",TOKEN).statusCode()).isEqualTo(409);
        fixtures.update("DELETE FROM inbox_messages WHERE message_id IN (SELECT message_id FROM inbox_messages WHERE connector_id='body-export' AND upstream_event_id='1001')");
        assertThat(request("GET","messages/content-export?confirm=EXPORT_MESSAGE_CONTENT","",TOKEN).statusCode()).isEqualTo(200);
    }
}
