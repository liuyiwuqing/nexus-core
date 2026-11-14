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
class ConsoleEventsFlowTest {
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


    @Test void authenticatedStreamUsesVersionedHintsAndRechecksRevokedTokens() throws Exception {
        var issued=request("POST","access/tokens",json.writeValueAsString(Map.of("name","SSE viewer","role","viewer","expiresAt",java.time.Instant.now().plusSeconds(300).toString())),TOKEN);
        String token=json.readTree(issued.body()).path("token").asText(),id=json.readTree(issued.body()).path("tokenId").asText();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/events")).header("Authorization","Bearer "+token).header("Accept","text/event-stream").GET().build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8));
        String first=reader.readLine();assertThat(first).contains("ready");
        String data=reader.readLine();assertThat(data).contains("schemaVersion").doesNotContain(token);
        assertThat(request("POST","access/tokens/"+id+"/revoke","{\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        var outcome=CompletableFuture.supplyAsync(()->{try{String line;while((line=reader.readLine())!=null)if(line.contains("unauthorized"))return true;return false;}catch(Exception e){return false;}});
        assertThat(outcome.get(8,TimeUnit.SECONDS)).isTrue();response.body().close();
        assertThat(request("GET","events","",null).statusCode()).isEqualTo(401);
    }
    @Test void setupDistinguishesRegistrationFromCredentialsConnectivityAndReceipts() throws Exception {
        String id="setup-"+UUID.randomUUID().toString().substring(0,8);
        assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN).statusCode()).isEqualTo(201);
        var setup=get("setup");assertThat(setup.path("credentialsProvisioned").asText()).isEqualTo("unknown");
        var row=java.util.stream.StreamSupport.stream(setup.path("devices").spliterator(),false).filter(d->d.path("deviceId").asText().equals(id)).findFirst().orElseThrow();
        assertThat(row.path("deviceObserved").asBoolean()).isFalse();assertThat(row.path("receiptObserved").asBoolean()).isFalse();
        assertThat(row.path("displayObserved").asBoolean()).isFalse();
        assertThat(request("GET","setup","",null).statusCode()).isEqualTo(401);
    }
}
