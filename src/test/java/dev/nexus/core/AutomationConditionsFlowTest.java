package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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

/** Conditional dispatch through HTTP with the real scheduler and isolated PostgreSQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutomationConditionsFlowTest {
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

    String create(Map<String,String> conditions) throws Exception {
        var result=request("POST","automation/rules",json.writeValueAsString(Map.of("name","条件提醒","trigger","message.received","action","notify_device","title","匹配提醒","content","匹配后固定正文","conditions",conditions,"requestKey",UUID.randomUUID())),TOKEN);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);return json.readTree(result.body()).path("ruleId").asText();
    }
    String message(String key,String content,String priority)throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","conditional","source","conditional","eventId",key,"eventType","alert.created","title","输入事件","content",content,"priority",priority)),TOKEN);
        assertThat(response.statusCode()).isIn(200,201);return json.readTree(response.body()).path("messageId").asText();
    }
    @Test void messageConditionsCombineAndOnlyOneDurableNotificationIsEnqueuedWithoutFeedbackLoop() throws Exception {
        String id=create(Map.of("source","conditional","eventType","alert.created","keyword","紧急","priority","high"));
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        message(UUID.randomUUID().toString(),"紧急但优先级不同","normal");message(UUID.randomUUID().toString(),"无匹配内容","high");
        String key=UUID.randomUUID().toString();message(key,"紧急事件","high");message(key,"紧急事件","high");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JsonNode runs;
        do{runs=get("automation/runs?ruleId="+id).path("items");if(runs.size()==1&&runs.get(0).path("state").asText().equals("enqueued"))break;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertThat(runs).hasSize(1);assertThat(runs.get(0).path("state").asText()).isEqualTo("enqueued");
        Thread.sleep(300);assertThat(get("automation/runs?ruleId="+id).path("items")).hasSize(1);
        assertThat(fixtures.queryForObject("SELECT count(*) FROM inbox_messages m JOIN automation_runs r ON m.upstream_event_id=r.run_id::text WHERE m.source='automation' AND r.rule_id=?",Integer.class,UUID.fromString(id))).isEqualTo(1);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":false,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(204);
        var body=Map.of("expectedVersion",3,"name","编辑条件","title","新提醒","content","新正文","conditions",Map.of("source","conditional","keyword","修改"));
        assertThat(request("PUT","automation/rules/"+id,json.writeValueAsString(body),TOKEN).statusCode()).isEqualTo(200);
        assertThat(get("automation/rules/"+id).path("arguments").path("keywordFilter").asText()).isEqualTo("修改");
        assertThat(request("PUT","automation/rules/"+id,json.writeValueAsString(body),TOKEN).statusCode()).isEqualTo(200);
    }
    @Test void invalidConditionsUnsupportedActionsAndPrivateAnalysisAreNotAccepted() throws Exception {
        String definition=json.writeValueAsString(Map.of("name","非法条件","trigger","message.received","action","notify_device","title","x","content","x","conditions",Map.of("sql","delete")));
        assertThat(request("POST","automation/rules",definition,TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("POST","automation/rules",definition.replace("notify_device","arbitrary_shell"),TOKEN).statusCode()).isEqualTo(400);
        var result=request("POST","automation/rules",json.writeValueAsString(Map.of("name","只分析公开评论","trigger","message.received","action","analyze_public_comment","conditions",Map.of("source","conditional"))),TOKEN);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);String id=json.readTree(result.body()).path("ruleId").asText();
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        message(UUID.randomUUID().toString(),"私有来源不应被分析","normal");Thread.sleep(300);
        assertThat(get("automation/runs?ruleId="+id).path("items")).isEmpty();
    }
    @Test void unrestrictedNotificationDoesNotProcessItsOwnOutput() throws Exception {
        String id=create(Map.of());
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        message(UUID.randomUUID().toString(),"无条件输入","normal");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JsonNode runs;
        do{runs=get("automation/runs?ruleId="+id).path("items");if(runs.size()==1&&runs.get(0).path("state").asText().equals("enqueued"))break;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertThat(runs).hasSize(1);assertThat(runs.get(0).path("state").asText()).isEqualTo("enqueued");
        Thread.sleep(350);assertThat(get("automation/runs?ruleId="+id).path("items")).hasSize(1);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":false,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(204);
    }

}
