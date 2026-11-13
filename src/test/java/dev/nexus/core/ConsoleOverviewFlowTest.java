package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Counts through the public HTTP boundary, with isolated database/Broker fixtures. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleOverviewFlowTest {
    static final String TOKEN="overview-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core overview-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"overview-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview")).timeout(Duration.ofSeconds(5));
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode overview()throws Exception {
        var response=request(TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    @BeforeEach void prepare() { fixtures.execute("TRUNCATE inbox_messages,devices CASCADE"); }

    @Test void emptyOverviewPreservesUnknownRateAndRequiresAdmin()throws Exception {
        assertThat(request(null).statusCode()).isEqualTo(401);
        var result=overview();
        assertThat(result.path("timezone").asText()).isEqualTo("UTC");
        assertThat(result.path("devices").path("total").asInt()).isZero();
        assertThat(result.path("today").path("messages").asInt()).isZero();
        assertThat(result.path("today").path("deliveryFailureRate").isNull()).isTrue();
        assertThat(result.path("backlog").path("pending").asInt()).isZero();
        assertThat(result.path("backlog").path("sent").asInt()).isZero();
    }

    @Test void snapshotCountsUseReceiptDayAndTheExistingOnlineThreshold()throws Exception {
        fixtures.update("""
            INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES
            ('live','在线',true,true,now()),('stale','超时',true,true,now()-interval '95 seconds'),
            ('reported-offline','离线',true,false,now()),('unknown','未连接',true,true,NULL),
            ('disabled','停用',false,true,now())
            """);
        var zero=overview();
        assertThat(zero.path("devices").path("total").asInt()).isEqualTo(5);
        assertThat(zero.path("devices").path("online").asInt()).isEqualTo(2);
        assertThat(zero.path("devices").path("offline").asInt()).isEqualTo(3);
        assertThat(zero.path("devices").path("disabled").asInt()).isEqualTo(1);
        String today=createMessage("旧来源时间仍在今日收件"),old=createMessage("昨日收件"),future=createMessage("未来收件");
        fixtures.update("UPDATE inbox_messages SET created_at=now()-interval '3 days' WHERE message_id=?",UUID.fromString(today));
        fixtures.update("UPDATE inbox_messages SET received_at=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'-interval '1 second' WHERE message_id=?",UUID.fromString(old));
        fixtures.update("UPDATE inbox_messages SET received_at=now()+interval '1 day' WHERE message_id=?",UUID.fromString(future));
        fixtures.update("UPDATE deliveries SET status='expired'");
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id=? AND device_id='live'",UUID.fromString(today));
        fixtures.update("UPDATE deliveries SET status='pending' WHERE message_id=? AND device_id='stale'",UUID.fromString(today));
        fixtures.update("UPDATE deliveries SET status='sent' WHERE message_id=? AND device_id='reported-offline'",UUID.fromString(old));
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id=? AND device_id='unknown'",UUID.fromString(old));
        var result=overview();
        assertThat(result.path("today").path("messages").asInt()).isEqualTo(1);
        assertThat(result.path("today").path("deliveries").asInt()).isEqualTo(4);
        assertThat(result.path("today").path("failedDeliveries").asInt()).isEqualTo(1);
        assertThat(result.path("today").path("deliveryFailureRate").asDouble()).isEqualTo(0.25);
        assertThat(result.path("failedDeliveries").asInt()).isEqualTo(2);
        assertThat(result.path("backlog").path("pending").asInt()).isEqualTo(1);
        assertThat(result.path("backlog").path("sent").asInt()).isEqualTo(1);
        Instant asOf=Instant.parse(result.path("asOf").asText()),start=Instant.parse(result.path("dayStart").asText());
        assertThat(start).isEqualTo(asOf.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant());
        // A second read never changes the delivery/message state or manufactures an acknowledgment.
        overview();
        var deliveries=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/deliveries?status=pending"))
            .header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(deliveries.body()).path("total").asInt()).isEqualTo(1);
    }

    String createMessage(String title)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/messages"))
            .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("connectorId","overview","source","manual",
                "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title",title,"content",title)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("messageId").asText();
    }
}
