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
class WebhookSourceFlowTest {
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


    String device() throws Exception {
        String id="hook-"+UUID.randomUUID().toString().substring(0,8);
        assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN).statusCode()).isEqualTo(201);
        return id;
    }
    JsonNode source(String id,String device)throws Exception {
        var result=request("PUT","sources/"+id,json.writeValueAsString(Map.of("name","隔离来源","deviceIds",List.of(device))),TOKEN);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);return json.readTree(result.body());
    }
    HttpResponse<String> hook(String id,String token,String secret,String body,long timestamp,boolean valid)throws Exception {
        String time=Long.toString(timestamp);
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        String signature="sha256="+java.util.HexFormat.of().formatHex(mac.doFinal((time+"."+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/hooks/"+id))
            .header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-Nexus-Timestamp",time)
            .header("X-Nexus-Signature",valid?signature:"sha256="+"0".repeat(64));
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String body(String key){return json.writeValueAsString(Map.of("eventId",key,"eventType","alert.test","title","隔离通知","content","仅测试容器","priority","normal"));}
    void enable(String id,String device,int version)throws Exception {
        assertThat(request("PATCH","sources/"+id,json.writeValueAsString(Map.of("name","隔离来源","deviceIds",List.of(device),"enabled",true,"expectedVersion",version)),TOKEN).statusCode()).isEqualTo(200);
    }
    @Test void sourceAuthenticationScopeHmacReplayAndReadAccessAreIndependentFromAdmin() throws Exception {
        String device=device(),other=device(),id="source-"+UUID.randomUUID().toString().substring(0,8);
        var created=source(id,device);String token=created.path("token").asText(),secret=created.path("secret").asText();
        assertThat(token.length()).isGreaterThanOrEqualTo(32);
        assertThat(get("sources").toString()).doesNotContain(token,secret,"token_hash","hmac_secret");
        assertThat(source(id,device).path("credentialsAvailable").asBoolean()).isFalse();
        long now=java.time.Instant.now().getEpochSecond();String key=UUID.randomUUID().toString(),body=body(key);
        assertThat(hook(id,token,secret,body,now,true).statusCode()).isEqualTo(403);
        enable(id,device,1);
        assertThat(hook(id,TOKEN,secret,body,now,true).statusCode()).isEqualTo(401);
        assertThat(hook(id,token,secret,body,now,false).statusCode()).isEqualTo(401);
        assertThat(hook(id,token,secret,body,now-301,true).statusCode()).isEqualTo(401);
        var response=hook(id,token,secret,body,now,true);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(202);
        String message=json.readTree(response.body()).path("id").asText();
        assertThat(fixtures.queryForList("SELECT device_id FROM deliveries WHERE message_id=?",String.class,UUID.fromString(message))).containsExactly(device);
        assertThat(fixtures.queryForObject("SELECT data_class FROM inbox_messages WHERE message_id=?",String.class,UUID.fromString(message))).isEqualTo("internal");
        assertThat(hook(id,token,secret,body,now,true).statusCode()).isEqualTo(202);
        assertThat(hook(id,token,secret,body.replace("隔离通知","改变正文"),now,true).statusCode()).isEqualTo(409);
        assertThat(request("GET","messages","",token).statusCode()).isEqualTo(401);
        var receipt=request("GET","hooks/"+id+"/messages/"+message,"",token);
        assertThat(receipt.statusCode()).isEqualTo(200);
        var timeline=json.readTree(receipt.body());
        assertThat(timeline.path("deliveries")).hasSize(1);
        assertThat(timeline.path("deliveries").get(0).path("deviceId").asText()).isEqualTo(device);
        assertThat(timeline.path("events")).hasSize(1);
        var first=timeline.path("events").get(0);
        assertThat(first.path("deviceId").asText()).isEqualTo(device);
        assertThat(first.path("kind").asText()).isEqualTo("created");
        assertThat(first.path("status").asText()).isEqualTo("pending");
        assertThat(first.path("previousStatus").isNull()).isTrue();
        assertThat(first.path("recordedAt").asText()).contains("T").endsWith("Z");
        assertThat(timeline.path("eventsTruncated").asBoolean()).isFalse();
        assertThat(receipt.body()).doesNotContain("lastError","nextAttemptAt","hmac_secret",token,secret);
        fixtures.update("UPDATE deliveries SET status='sent' WHERE message_id=?",UUID.fromString(message));
        var advanced=json.readTree(request("GET","hooks/"+id+"/messages/"+message,"",token).body());
        assertThat(advanced.path("deliveries").get(0).path("status").asText()).isEqualTo("sent");
        assertThat(advanced.path("events")).hasSize(2);
        assertThat(advanced.path("events").get(1).path("kind").asText()).isEqualTo("state_changed");
        assertThat(advanced.path("events").get(1).path("previousStatus").asText()).isEqualTo("pending");
        assertThat(advanced.path("events").get(1).path("status").asText()).isEqualTo("sent");
        assertThat(request("GET","hooks/"+id+"/messages/"+UUID.randomUUID(),"",token).statusCode()).isEqualTo(404);
        assertThat(fixtures.queryForObject("SELECT count(*) FROM webhook_receipts WHERE source_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(request("POST","sources/"+id+"/credentials","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(409);
    }
    @Test void receiptTimelineIsScopedBoundedAndHidesInternalDetails() throws Exception {
        String device=device(),id="time-"+UUID.randomUUID().toString().substring(0,8);
        var created=source(id,device);String token=created.path("token").asText(),secret=created.path("secret").asText();enable(id,device,1);
        long now=java.time.Instant.now().getEpochSecond();
        var accepted=hook(id,token,secret,body(UUID.randomUUID().toString()),now,true);
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(202);
        UUID message=UUID.fromString(json.readTree(accepted.body()).path("id").asText());
        UUID event=fixtures.queryForObject("SELECT event_id FROM deliveries WHERE message_id=?",UUID.class,message);
        fixtures.update("""
            INSERT INTO delivery_history(event_id,kind,previous_status,status,attempts,next_attempt_at)
            SELECT ?,'detail_changed','pending','pending',0,now() FROM generate_series(1,501)
            """,event);
        var receipt=json.readTree(request("GET","hooks/"+id+"/messages/"+message,"",token).body());
        assertThat(receipt.path("events")).hasSize(500);
        assertThat(receipt.path("eventsTruncated").asBoolean()).isTrue();
        assertThat(receipt.path("events").get(499).path("kind").asText()).isEqualTo("detail_changed");
        String other=device(),otherId="time-"+UUID.randomUUID().toString().substring(0,8);
        var second=source(otherId,other);enable(otherId,other,1);
        assertThat(request("GET","hooks/"+otherId+"/messages/"+message,"",second.path("token").asText()).statusCode()).isEqualTo(404);
    }
    @Test void sourceTestStaysOutsideProductionOverviewAndOriginalCreationIsBound() throws Exception {
        String d=device(),id="test-"+UUID.randomUUID().toString().substring(0,8);source(id,d);
        long before=get("overview").path("today").path("messages").asLong();
        String body=json.writeValueAsString(Map.of("requestKey",UUID.randomUUID(),"title","来源测试","content","私有容器"));
        var accepted=request("POST","sources/"+id+"/test",body,TOKEN);
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(get("overview").path("today").path("messages").asLong()).isEqualTo(before);
        assertThat(request("POST","sources/"+id+"/test",body,TOKEN).statusCode()).isEqualTo(202);
        assertThat(request("PUT","sources/"+id,json.writeValueAsString(Map.of("name","不同来源","deviceIds",List.of(d))),TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("POST","sources/"+id+"/test",body,null).statusCode()).isEqualTo(401);
    }
    @Test void rateLimitingRotationAndPayloadBoundsAreEnforced() throws Exception {
        String d=device(),id="rate-"+UUID.randomUUID().toString().substring(0,8);
        var created=source(id,d);String token=created.path("token").asText(),secret=created.path("secret").asText();enable(id,d,1);
        long now=java.time.Instant.now().getEpochSecond();
        fixtures.update("UPDATE webhook_sources SET tokens=0,refill_at=now() WHERE source_id=?",id);
        var limited=hook(id,token,secret,body(UUID.randomUUID().toString()),now,true);
        assertThat(limited.statusCode()).isEqualTo(429);assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        fixtures.update("UPDATE webhook_sources SET tokens=20 WHERE source_id=?",id);
        assertThat(hook(id,token,secret,body(UUID.randomUUID().toString()).replace("隔离通知","x".repeat(40000)),now,true).statusCode()).isEqualTo(413);
        assertThat(request("PATCH","sources/"+id,json.writeValueAsString(Map.of("name","隔离来源","deviceIds",List.of(d),"enabled",false,"expectedVersion",2)),TOKEN).statusCode()).isEqualTo(200);
        var rotated=request("POST","sources/"+id+"/credentials","{\"expectedVersion\":3}",TOKEN);
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rotated.body()).path("token").asText()).isNotEqualTo(token);
        assertThat(request("POST","sources/"+id+"/credentials","{\"expectedVersion\":3}",TOKEN).statusCode()).isEqualTo(409);
        assertThat(hook(id,token,secret,body(UUID.randomUUID().toString()),now,true).statusCode()).isEqualTo(401);
    }
}
