package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.nexus.core.delivery.DeliveryWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk per-message cancel/retry through public HTTP with isolated PostgreSQL.
 *
 * <p>The skip reasons the batch reports must agree with the single-delivery eligibility the console
 * already shows; that cross-check is asserted directly rather than restated here.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageDeliveryBatchFlowTest {
    static final String TOKEN="batch-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core batch-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"batch-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");p.add("nexus.delivery.max-attempts",()->"1");
    }
    // 投递 worker 在启动 500ms 后会先跑一拍；它会把用例刚建的行发布成 sent/attempted，
    // 破坏“从未尝试”的前置条件。这里只关掉本类的外发，批次规则仍走真实服务与数据库。
    @MockitoBean DeliveryWorker deliveryWorker;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path)throws Exception {
        var response=request("GET",path,"",TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    JsonNode batch(String messageId,String action,boolean dryRun,String confirm,String token)throws Exception {
        String path="messages/"+messageId+"/deliveries/"+action+"?dryRun="+dryRun+(confirm==null?"":"&confirm="+confirm);
        var response=request("POST",path,"",token);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    String message()throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","batch","source","manual",
            "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title","批量检查","content","隔离检查")),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("messageId").asText();
    }
    Map<String,UUID> events(String messageId)throws Exception {
        var result=new java.util.LinkedHashMap<String,UUID>();
        for(var row:get("deliveries?size=100").path("items"))
            if(row.path("messageId").asText().equals(messageId))
                result.put(row.path("deviceId").asText(),UUID.fromString(row.path("eventId").asText()));
        return result;
    }
    JsonNode delivery(UUID event)throws Exception {
        for(var row:get("deliveries?size=100").path("items"))if(row.path("eventId").asText().equals(event.toString()))return row;
        throw new AssertionError("delivery missing");
    }
    int history(UUID event)throws Exception { return get("deliveries/"+event+"/history").path("total").asInt(); }
    String role(String name)throws Exception {
        var response=request("POST","access/tokens",json.writeValueAsString(Map.of("name",name+" isolated","role",name,
            "expiresAt",Instant.now().plusSeconds(3600).toString())),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("token").asText();
    }
    @BeforeEach void prepare()throws Exception {
        fixtures.execute("TRUNCATE inbox_messages,devices CASCADE");
        for(String id:List.of("batch-a","batch-b","batch-c"))
            assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN).statusCode()).isEqualTo(201);
    }

    @Test void dryRunExplainsEverySkipAndAgreesWithSingleDeliveryEligibility()throws Exception {
        String message=message();
        var events=events(message);
        // a: never attempted, b: attempted once, c: already failed.
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",events.get("batch-b"));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE event_id=?",events.get("batch-c"));
        var cancel=batch(message,"cancel",true,null,TOKEN);
        assertThat(cancel.path("action").asText()).isEqualTo("cancel");
        assertThat(cancel.path("dryRun").asBoolean()).isTrue();
        assertThat(cancel.path("applied").asInt()).isZero();
        assertThat(cancel.path("counts").path("deliveries").asInt()).isEqualTo(3);
        assertThat(cancel.path("counts").path("candidates").asInt()).isEqualTo(1);
        assertThat(cancel.path("candidates").get(0).path("deviceId").asText()).isEqualTo("batch-a");
        assertThat(cancel.path("candidates").get(0).has("reason")).isFalse();
        var reasons=new java.util.LinkedHashMap<String,String>();
        for(var item:cancel.path("skipped"))reasons.put(item.path("deviceId").asText(),item.path("reason").asText());
        assertThat(reasons).containsEntry("batch-b","attempted").containsEntry("batch-c","not_pending");
        // A dry run must not write: same statuses, same attempts, no new history rows.
        assertThat(delivery(events.get("batch-a")).path("status").asText()).isEqualTo("pending");
        assertThat(delivery(events.get("batch-b")).path("attempts").asInt()).isEqualTo(1);
        assertThat(history(events.get("batch-c"))).isEqualTo(2);
        var retry=batch(message,"retry",true,null,TOKEN);
        assertThat(retry.path("counts").path("candidates").asInt()).isEqualTo(1);
        assertThat(retry.path("candidates").get(0).path("deviceId").asText()).isEqualTo("batch-c");
        // The single-delivery console flag and the batch reason must describe the same rule.
        for(var row:get("deliveries?size=100").path("items")){
            String device=row.path("deviceId").asText();
            if(!events.containsKey(device))continue;
            boolean listed=reasons.containsKey(device);
            assertThat(row.path("canCancel").asBoolean()).withFailMessage("canCancel disagrees for "+device).isEqualTo(!listed);
        }
    }

    @Test void cancelTouchesOnlyUnstartedDeliveriesAndIsAuditedPerDelivery()throws Exception {
        String message=message();
        var events=events(message);
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",events.get("batch-b"));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE event_id=?",events.get("batch-c"));
        int before=history(events.get("batch-a"));
        var applied=batch(message,"cancel",false,"CANCEL_MESSAGE_DELIVERIES",TOKEN);
        assertThat(applied.path("applied").asInt()).isEqualTo(1);
        assertThat(applied.path("counts").path("remaining").asInt()).isZero();
        var cancelled=delivery(events.get("batch-a"));
        assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");
        assertThat(cancelled.path("canCancel").asBoolean()).isFalse();
        assertThat(delivery(events.get("batch-b")).path("status").asText()).isEqualTo("pending");
        assertThat(delivery(events.get("batch-c")).path("status").asText()).isEqualTo("failed");
        assertThat(history(events.get("batch-a"))).isEqualTo(before+1);
        var timeline=get("deliveries/"+events.get("batch-a")+"/history?size=10").path("items");
        var last=timeline.get(timeline.size()-1);
        assertThat(last.path("kind").asText()).isEqualTo("state_changed");
        assertThat(last.path("previousStatus").asText()).isEqualTo("pending");
        assertThat(last.path("status").asText()).isEqualTo("cancelled");
        assertThat(last.path("lastError").asText()).isEqualTo("cancelled_by_admin");
        assertThat(get("deliveries?status=cancelled").path("total").asInt()).isEqualTo(1);
        // Replaying the same batch reports the new reasons instead of resending the old plan.
        var again=batch(message,"cancel",false,"CANCEL_MESSAGE_DELIVERIES",TOKEN);
        assertThat(again.path("applied").asInt()).isZero();
        assertThat(again.path("counts").path("candidates").asInt()).isZero();
        assertThat(again.path("counts").path("skipped").asInt()).isEqualTo(3);
    }

    @Test void retryRestoresFailedDeliveriesButKeepsThePermanentCancelGuard()throws Exception {
        String message=message();
        var events=events(message);
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout',attempts=1 WHERE event_id IN (?,?)",
            events.get("batch-b"),events.get("batch-c"));
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",events.get("batch-a"));
        var applied=batch(message,"retry",false,"RETRY_MESSAGE_DELIVERIES",TOKEN);
        assertThat(applied.path("applied").asInt()).isEqualTo(2);
        for(String device:List.of("batch-b","batch-c")){
            var row=delivery(events.get(device));
            assertThat(row.path("status").asText()).isEqualTo("pending");
            assertThat(row.path("attempts").asInt()).isZero();
            assertThat(row.path("lastError").isNull()||row.path("lastError").asText().isEmpty()).isTrue();
            var kinds=new ArrayList<String>();
            for(var item:get("deliveries/"+events.get(device)+"/history?size=20").path("items"))kinds.add(item.path("kind").asText());
            assertThat(kinds).contains("retry_requested");
        }
        // attempts were reset, but cancel_blocked is permanent: the retried rows stay uncancellable.
        var cancel=batch(message,"cancel",true,null,TOKEN);
        assertThat(cancel.path("counts").path("candidates").asInt()).isZero();
        var reasons=new java.util.LinkedHashMap<String,String>();
        for(var item:cancel.path("skipped"))reasons.put(item.path("deviceId").asText(),item.path("reason").asText());
        assertThat(reasons).containsEntry("batch-a","attempted")
            .containsEntry("batch-b","cancel_blocked").containsEntry("batch-c","cancel_blocked");
    }

    @Test void confirmationRoleMessageStateAndBatchSizeAreEnforced()throws Exception {
        String message=message();
        var events=events(message);
        var missing=request("POST","messages/"+message+"/deliveries/cancel","",TOKEN);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missing.body()).path("error").asText()).isEqualTo("delivery_batch_confirmation_required");
        assertThat(request("POST","messages/"+message+"/deliveries/retry?dryRun=false&confirm=CANCEL_MESSAGE_DELIVERIES","",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("POST","messages/"+message+"/deliveries/cancel?dryRun=false&confirm=CANCEL_MESSAGE_DELIVERIES","",role("viewer")).statusCode()).isEqualTo(403);
        assertThat(batch(message,"cancel",true,null,role("operator")).path("counts").path("candidates").asInt()).isEqualTo(3);
        var unknown=request("POST","messages/"+UUID.randomUUID()+"/deliveries/cancel?dryRun=true","",TOKEN);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).path("error").asText()).isEqualTo("message_not_found");
        // Reasons for state that a dry run must report instead of silently dropping rows.
        fixtures.update("UPDATE inbox_messages SET read_at=now() WHERE message_id=?",UUID.fromString(message));
        assertThat(skipReasons(message)).containsEntry("batch-a","message_read");
        fixtures.update("UPDATE inbox_messages SET read_at=NULL,expires_at=now()-interval '1 minute' WHERE message_id=?",UUID.fromString(message));
        assertThat(skipReasons(message)).containsEntry("batch-a","message_expired");
        fixtures.update("UPDATE inbox_messages SET expires_at=now()+interval '1 day' WHERE message_id=?",UUID.fromString(message));
        fixtures.update("UPDATE devices SET enabled=false WHERE device_id='batch-a'");
        assertThat(skipReasons(message)).containsEntry("batch-a","device_disabled");
        assertThat(events).hasSize(3);
        // A runaway fan-out is refused instead of silently truncated.
        fixtures.update("INSERT INTO devices(device_id,name) SELECT 'bulk-'||g,'bulk' FROM generate_series(1,501) g");
        fixtures.update("INSERT INTO deliveries(event_id,message_id,device_id) SELECT gen_random_uuid(),?,'bulk-'||g FROM generate_series(1,501) g",UUID.fromString(message));
        var huge=request("POST","messages/"+message+"/deliveries/cancel?dryRun=true","",TOKEN);
        assertThat(huge.statusCode()).isEqualTo(409);
        assertThat(json.readTree(huge.body()).path("error").asText()).isEqualTo("batch_too_large");
    }

    @Test void messageDeliveriesExposePerDeviceSnapshotsToEveryReadRole()throws Exception {
        String message=message();
        var events=events(message);
        var listed=get("messages/"+message+"/deliveries");
        assertThat(listed.path("messageId").asText()).isEqualTo(message);
        assertThat(listed.path("total").asInt()).isEqualTo(3);
        assertThat(listed.path("counts").path("pending").asInt()).isEqualTo(3);
        assertThat(listed.path("counts").has("read")).isFalse();
        assertThat(listed.path("items").size()).isEqualTo(3);
        assertThat(listed.path("items").get(0).path("deviceId").asText()).isEqualTo("batch-a");
        // Ack progress is a read-time snapshot; counts cover devices outside the requested page.
        fixtures.update("UPDATE deliveries SET status='read',received_at=now()-interval '2 minute',displayed_at=now()-interval '1 minute',read_at=now() WHERE event_id=?",events.get("batch-b"));
        var page=get("messages/"+message+"/deliveries?page=2&size=1");
        assertThat(page.path("total").asInt()).isEqualTo(3);
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("deviceId").asText()).isEqualTo("batch-b");
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("read");
        assertThat(page.path("items").get(0).path("receivedAt").asText()).isNotBlank();
        assertThat(page.path("items").get(0).path("displayedAt").asText()).isNotBlank();
        assertThat(page.path("items").get(0).path("readAt").asText()).isNotBlank();
        assertThat(page.path("counts").path("read").asInt()).isEqualTo(1);
        assertThat(page.path("counts").path("pending").asInt()).isEqualTo(2);
        for(String role:List.of("viewer","operator"))
            assertThat(request("GET","messages/"+message+"/deliveries","",role(role)).statusCode()).isEqualTo(200);
        var unknown=request("GET","messages/"+UUID.randomUUID()+"/deliveries","",TOKEN);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).path("error").asText()).isEqualTo("message_not_found");
    }

    private Map<String,String> skipReasons(String message)throws Exception {
        var result=new java.util.LinkedHashMap<String,String>();
        for(var item:batch(message,"cancel",true,null,TOKEN).path("skipped"))result.put(item.path("deviceId").asText(),item.path("reason").asText());
        return result;
    }
}
