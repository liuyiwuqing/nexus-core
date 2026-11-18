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

/**
 * Cross-message cancel/retry through public HTTP with isolated PostgreSQL.
 *
 * <p>The selection is atomic in both directions: a readable subset is never applied when one
 * message disappeared, and a planned write must not leave earlier messages changed when a later
 * one fails.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageDeliveryMultiBatchFlowTest {
    static final String TOKEN="multi-batch-test-token-at-least-32";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core multi-batch-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"multi-batch-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");p.add("nexus.delivery.max-attempts",()->"1");
    }
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
    HttpResponse<String> post(String action,boolean dryRun,String confirm,List<String> ids,String token)throws Exception {
        String path="deliveries/batch-"+action+"?dryRun="+dryRun+(confirm==null?"":"&confirm="+confirm);
        var body=json.writeValueAsString(Map.of("messageIds",ids));
        return request("POST",path,body,token);
    }
    JsonNode batch(String action,boolean dryRun,String confirm,List<String> ids,String token)throws Exception {
        var response=post(action,dryRun,confirm,ids,token);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    String message(String title)throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","multi","source","manual",
            "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title",title,"content","隔离检查")),TOKEN);
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
        for(String id:List.of("multi-a","multi-b","multi-c"))
            assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN).statusCode()).isEqualTo(201);
    }

    @Test void dryRunCombinesThePerMessagePlansWithoutWriting()throws Exception {
        String first=message("第一条"), second=message("第二条");
        var firstEvents=events(first);
        var secondEvents=events(second);
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",firstEvents.get("multi-a"));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE event_id=?",firstEvents.get("multi-b"));
        var plan=batch("cancel",true,null,List.of(first,second),TOKEN);
        assertThat(plan.path("action").asText()).isEqualTo("cancel");
        assertThat(plan.path("dryRun").asBoolean()).isTrue();
        assertThat(plan.path("applied").asInt()).isZero();
        assertThat(plan.path("counts").path("messages").asInt()).isEqualTo(2);
        assertThat(plan.path("counts").path("deliveries").asInt()).isEqualTo(6);
        assertThat(plan.path("counts").path("candidates").asInt()).isEqualTo(4);
        assertThat(plan.path("counts").path("skipped").asInt()).isEqualTo(2);
        assertThat(plan.path("counts").path("remaining").asInt()).isEqualTo(4);
        assertThat(plan.path("messages")).hasSize(2);
        assertThat(plan.path("messages").get(0).path("messageId").asText()).isEqualTo(first);
        var firstReasons=new java.util.LinkedHashMap<String,String>();
        for(var item:plan.path("messages").get(0).path("skipped"))firstReasons.put(item.path("deviceId").asText(),item.path("reason").asText());
        assertThat(firstReasons).containsEntry("multi-a","attempted").containsEntry("multi-b","not_pending");
        // 预演不写：状态、尝试次数和历史条数都保持原样。
        assertThat(delivery(firstEvents.get("multi-c")).path("status").asText()).isEqualTo("pending");
        assertThat(delivery(firstEvents.get("multi-a")).path("attempts").asInt()).isEqualTo(1);
        assertThat(history(firstEvents.get("multi-b"))).isEqualTo(2);
        var retryPlan=batch("retry",true,null,List.of(first,second),TOKEN);
        assertThat(retryPlan.path("counts").path("candidates").asInt()).isEqualTo(1);
        assertThat(retryPlan.path("messages").get(0).path("candidates").get(0).path("deviceId").asText()).isEqualTo("multi-b");
    }

    @Test void oneWriteCancelsEverySelectedMessageAndAuditsEachDelivery()throws Exception {
        String first=message("第一条"), second=message("第二条");
        var firstEvents=events(first);
        var secondEvents=events(second);
        fixtures.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",secondEvents.get("multi-b"));
        int beforeFirst=history(firstEvents.get("multi-a")), beforeSecond=history(secondEvents.get("multi-a"));
        var applied=batch("cancel",false,"CANCEL_MESSAGE_DELIVERIES_BATCH",List.of(first,second),TOKEN);
        assertThat(applied.path("applied").asInt()).isEqualTo(5);
        assertThat(applied.path("counts").path("messages").asInt()).isEqualTo(2);
        assertThat(applied.path("messages").get(1).path("applied").asInt()).isEqualTo(2);
        for(var event:List.of(firstEvents.get("multi-a"),firstEvents.get("multi-c"),
            secondEvents.get("multi-a"),secondEvents.get("multi-c")))
            assertThat(delivery(event).path("status").asText()).isEqualTo("cancelled");
        assertThat(delivery(secondEvents.get("multi-b")).path("status").asText()).isEqualTo("pending");
        assertThat(history(firstEvents.get("multi-a"))).isEqualTo(beforeFirst+1);
        assertThat(history(secondEvents.get("multi-a"))).isEqualTo(beforeSecond+1);
        assertThat(get("deliveries?status=cancelled").path("total").asInt()).isEqualTo(5);
        // 再演一次只报告新原因，不会重复写，也不会复用单条消息的确认令牌。
        var again=batch("cancel",false,"CANCEL_MESSAGE_DELIVERIES_BATCH",List.of(first,second),TOKEN);
        assertThat(again.path("applied").asInt()).isZero();
        assertThat(again.path("counts").path("candidates").asInt()).isZero();
        assertThat(post("cancel",false,"CANCEL_MESSAGE_DELIVERIES",List.of(first),TOKEN).statusCode()).isEqualTo(400);
    }

    @Test void unknownMessageRejectsTheWholeSelectionBeforeAnyWrite()throws Exception {
        String first=message("唯一可读"), ghost=UUID.randomUUID().toString();
        var firstEvents=events(first);
        var refused=post("cancel",false,"CANCEL_MESSAGE_DELIVERIES_BATCH",List.of(first,ghost),TOKEN);
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(json.readTree(refused.body()).path("error").asText()).isEqualTo("message_not_found");
        // 可读的那条消息没有发生部分写入。
        assertThat(delivery(firstEvents.get("multi-a")).path("status").asText()).isEqualTo("pending");
        assertThat(history(firstEvents.get("multi-a"))).isEqualTo(1);
        assertThat(post("cancel",true,null,List.of(),TOKEN).statusCode()).isEqualTo(400);
        var oversized=new ArrayList<String>();
        for(int index=0;index<21;index++)oversized.add(UUID.randomUUID().toString());
        var tooMany=post("cancel",true,null,oversized,TOKEN);
        assertThat(tooMany.statusCode()).isEqualTo(409);
        assertThat(json.readTree(tooMany.body()).path("error").asText()).isEqualTo("batch_too_large");
        var viewer=post("cancel",true,null,List.of(first),role("viewer"));
        assertThat(viewer.statusCode()).isEqualTo(403);
        assertThat(batch("cancel",true,null,List.of(first),role("operator")).path("counts").path("messages").asInt()).isEqualTo(1);
    }

    @Test void aggregateFanOutAndMessageCountLimitsAreEnforced()throws Exception {
        var ids=new ArrayList<String>();
        for(int index=0;index<20;index++)ids.add(message("边界 "+index));
        var maximum=batch("cancel",true,null,ids,TOKEN);
        assertThat(maximum.path("counts").path("messages").asInt()).isEqualTo(20);
        assertThat(maximum.path("counts").path("deliveries").asInt()).isEqualTo(60);
        // 单条消息上限是 500；两条各 260 条投递合计越界时，整单在第一次写入前被拒绝。
        fixtures.update("INSERT INTO devices(device_id,name) SELECT 'bulk-'||g, 'bulk-'||g FROM generate_series(1,260) g ON CONFLICT (device_id) DO NOTHING");
        for(String suffix:List.of("one","two")){
            UUID id=UUID.randomUUID();
            fixtures.update("INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at) VALUES (?, 'multi', 'manual', ?, 'manual.test', '批量', '检查', '批量检查', 'multi', '', 'normal', now(), now()+interval '1 day')",id,UUID.randomUUID());
            fixtures.update("INSERT INTO deliveries(event_id,message_id,device_id) SELECT gen_random_uuid(),?,'bulk-'||g FROM generate_series(1,260) g",id);
            ids.add(id.toString());
        }
        var refused=post("cancel",true,null,ids.subList(20,22),TOKEN);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json.readTree(refused.body()).path("error").asText()).isEqualTo("batch_too_large");
    }
}
