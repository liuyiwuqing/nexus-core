package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * Device-scoped queue cancel through public HTTP with isolated PostgreSQL.
 *
 * <p>The scope is the same pending+sent set the device card counts, so a plan must explain sent
 * rows as retained instead of silently dropping them. The single-delivery cancel stays the only
 * definition of what may be cancelled.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceDeliveryQueueFlowTest {
    static final String TOKEN="device-queue-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core queue-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"queue-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");p.add("nexus.delivery.max-attempts",()->"1");
    }
    // worker 启动 500ms 后会先跑一拍，把 pending 行发布成 sent/attempted；本类只验证队列谓词。
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
    JsonNode queue(String deviceId,boolean dryRun,String confirm,String token)throws Exception {
        String path="devices/"+deviceId+"/deliveries/cancel?dryRun="+dryRun+(confirm==null?"":"&confirm="+confirm);
        var response=request("POST",path,"",token);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    String message()throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","queue","source","manual",
            "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title","队列检查","content","隔离检查")),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("messageId").asText();
    }
    Map<String,UUID> events(String messageId)throws Exception {
        var result=new LinkedHashMap<String,UUID>();
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
        for(String id:List.of("queue-a","queue-b"))
            assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN).statusCode()).isEqualTo(201);
    }

    @Test void dryRunExplainsTheWaitingQueueWithoutWriting()throws Exception {
        String message=message();
        var events=events(message);
        fixtures.update("UPDATE deliveries SET status='sent',attempts=1,last_error=NULL WHERE event_id=?",events.get("queue-a"));
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",events.get("queue-b"));
        int historyBefore=history(events.get("queue-a"));
        var plan=queue("queue-a",true,null,TOKEN);
        assertThat(plan.path("deviceId").asText()).isEqualTo("queue-a");
        assertThat(plan.path("action").asText()).isEqualTo("cancel");
        assertThat(plan.path("dryRun").asBoolean()).isTrue();
        assertThat(plan.path("applied").asInt()).isZero();
        assertThat(plan.path("counts").path("waiting").asInt()).isEqualTo(1);
        assertThat(plan.path("counts").path("candidates").asInt()).isZero();
        assertThat(plan.path("counts").path("skipped").asInt()).isEqualTo(1);
        assertThat(plan.path("skipped").get(0).path("reason").asText()).isEqualTo("not_pending");
        assertThat(plan.path("skipped").get(0).path("status").asText()).isEqualTo("sent");
        var attempted=queue("queue-b",true,null,TOKEN);
        assertThat(attempted.path("counts").path("waiting").asInt()).isEqualTo(1);
        assertThat(attempted.path("counts").path("candidates").asInt()).isZero();
        assertThat(attempted.path("skipped").get(0).path("reason").asText()).isEqualTo("attempted");
        // A dry run must not write: statuses, attempts and history stay as they were.
        assertThat(delivery(events.get("queue-a")).path("status").asText()).isEqualTo("sent");
        assertThat(delivery(events.get("queue-b")).path("attempts").asInt()).isEqualTo(1);
        assertThat(history(events.get("queue-a"))).isEqualTo(historyBefore);
    }

    @Test void cancelLeavesSentDeliveriesAndOtherDevicesUntouched()throws Exception {
        String first=message();
        String second=message();
        var firstEvents=events(first);
        var secondEvents=events(second);
        fixtures.update("UPDATE deliveries SET status='sent',attempts=1 WHERE event_id=?",secondEvents.get("queue-a"));
        int before=history(firstEvents.get("queue-a"));
        var plan=queue("queue-a",true,null,TOKEN);
        assertThat(plan.path("counts").path("waiting").asInt()).isEqualTo(2);
        assertThat(plan.path("counts").path("candidates").asInt()).isEqualTo(1);
        var applied=queue("queue-a",false,"CANCEL_DEVICE_DELIVERIES",TOKEN);
        assertThat(applied.path("applied").asInt()).isEqualTo(1);
        assertThat(applied.path("counts").path("waiting").asInt()).isEqualTo(2);
        assertThat(applied.path("counts").path("skipped").asInt()).isEqualTo(1);
        assertThat(applied.path("counts").path("remaining").asInt()).isZero();
        var cancelled=delivery(firstEvents.get("queue-a"));
        assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");
        assertThat(cancelled.path("canCancel").asBoolean()).isFalse();
        assertThat(delivery(secondEvents.get("queue-a")).path("status").asText()).isEqualTo("sent");
        assertThat(delivery(firstEvents.get("queue-b")).path("status").asText()).isEqualTo("pending");
        assertThat(delivery(secondEvents.get("queue-b")).path("status").asText()).isEqualTo("pending");
        assertThat(history(firstEvents.get("queue-a"))).isEqualTo(before+1);
        var timeline=get("deliveries/"+firstEvents.get("queue-a")+"/history?size=10").path("items");
        var last=timeline.get(timeline.size()-1);
        assertThat(last.path("kind").asText()).isEqualTo("state_changed");
        assertThat(last.path("previousStatus").asText()).isEqualTo("pending");
        assertThat(last.path("status").asText()).isEqualTo("cancelled");
        assertThat(last.path("lastError").asText()).isEqualTo("cancelled_by_admin");
        assertThat(get("deliveries?status=cancelled").path("total").asInt()).isEqualTo(1);
        // Replaying reports the remaining sent row instead of resending the old plan.
        var again=queue("queue-a",true,null,TOKEN);
        assertThat(again.path("applied").asInt()).isZero();
        assertThat(again.path("counts").path("waiting").asInt()).isEqualTo(1);
        assertThat(again.path("counts").path("candidates").asInt()).isZero();
        assertThat(again.path("skipped").get(0).path("status").asText()).isEqualTo("sent");
    }

    @Test void confirmationRoleUnknownDeviceAndQueueSizeAreEnforced()throws Exception {
        String message=message();
        var events=events(message);
        var missing=request("POST","devices/queue-a/deliveries/cancel","",TOKEN);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missing.body()).path("error").asText()).isEqualTo("delivery_batch_confirmation_required");
        assertThat(request("POST","devices/queue-a/deliveries/cancel?dryRun=false&confirm=CANCEL_MESSAGE_DELIVERIES","",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("POST","devices/queue-a/deliveries/cancel?dryRun=false&confirm=CANCEL_DEVICE_DELIVERIES","",role("viewer")).statusCode()).isEqualTo(403);
        // 设备路径沿用管理员边界，operator 不能借队列撤销绕开设备权限。
        assertThat(request("POST","devices/queue-a/deliveries/cancel?dryRun=true","",role("operator")).statusCode()).isEqualTo(403);
        var unknown=request("POST","devices/missing/deliveries/cancel?dryRun=true","",TOKEN);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).path("error").asText()).isEqualTo("device_not_found");
        // A runaway queue is refused instead of silently truncated.
        fixtures.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at)
            SELECT gen_random_uuid(),'bulk','manual',gen_random_uuid()::text,'manual.test','bulk','bulk','','','','normal',now(),now()+interval '1 day'
            FROM generate_series(1,501) g""");
        fixtures.update("""
            INSERT INTO deliveries(event_id,message_id,device_id)
            SELECT gen_random_uuid(),message_id,'queue-a' FROM inbox_messages WHERE connector_id='bulk'""");
        var huge=request("POST","devices/queue-a/deliveries/cancel?dryRun=true","",TOKEN);
        assertThat(huge.statusCode()).isEqualTo(409);
        assertThat(json.readTree(huge.body()).path("error").asText()).isEqualTo("batch_too_large");
        // 501 行插入后第一页已不含原投递，直接查库确认拒绝没有写入。
        assertThat(fixtures.queryForObject("SELECT status FROM deliveries WHERE event_id=?", String.class,
            events.get("queue-a"))).isEqualTo("pending");
    }
}
