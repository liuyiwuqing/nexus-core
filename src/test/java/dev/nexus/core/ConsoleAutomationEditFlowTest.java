package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
class ConsoleAutomationEditFlowTest {
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
    String rule(String key,String name) throws Exception {
        return json.writeValueAsString(Map.of("requestKey",key,"name",name,"trigger","schedule.daily","action","notify_device",
            "localTime","08:00","zone","Asia/Shanghai","title","隔离提醒","content","创建检查"));
    }
    @Test void disabledRuleCanBeEditedWithoutChangingItsIdentityOrOriginalCreationKey() throws Exception {
        String key=UUID.randomUUID().toString(),original=rule(key,"编辑前");
        var created=request("POST","automation/rules",original,TOKEN);
        String id=json.readTree(created.body()).path("ruleId").asText();
        String body=json.writeValueAsString(Map.of("expectedVersion",1,"name","编辑后","localTime","09:30","zone","UTC","title","新标题","content","新内容"));
        var saved=request("PUT","automation/rules/"+id,body,TOKEN);
        assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).path("ruleId").asText()).isEqualTo(id);
        assertThat(json.readTree(saved.body()).path("version").asInt()).isEqualTo(2);
        assertThat(json.readTree(saved.body()).path("enabled").asBoolean()).isFalse();
        assertThat(request("PUT","automation/rules/"+id,body,TOKEN).statusCode()).isEqualTo(200);
        var current=get("automation/rules/"+id);
        assertThat(current.path("name").asText()).isEqualTo("编辑后");
        assertThat(current.path("arguments").path("localTime").asText()).isEqualTo("09:30");
        assertThat(current.path("version").asInt()).isEqualTo(2);
        assertThat(current.path("canEdit").asBoolean()).isTrue();
        assertThat(request("POST","automation/rules",original,TOKEN).statusCode()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(get("automation/rules").path("items").spliterator(),false).filter(r -> r.path("ruleId").asText().equals(id)).count()).isEqualTo(1);
        assertThat(get("automation/rules/"+id).path("name").asText()).isEqualTo("编辑后");
        String unchanged=body.replace("\"expectedVersion\":1","\"expectedVersion\":2");
        assertThat(request("PUT","automation/rules/"+id,unchanged,TOKEN).statusCode()).isEqualTo(200);
        assertThat(get("automation/rules/"+id).path("version").asInt()).isEqualTo(2);
    }
    // Keep non-scheduler cases outside the due window; each case owns distinct rule IDs.
    String quietTime() { return java.time.LocalTime.now(java.time.ZoneOffset.UTC).plusHours(6).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
    String createDaily(String name,String time) throws Exception {
        var response=request("POST","automation/rules",json.writeValueAsString(Map.of("name",name,"trigger","schedule.daily","action","notify_device",
            "localTime",time,"zone","UTC","title","原提醒","content","原正文")),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("ruleId").asText();
    }
    String editBody(int version,String name) {
        return json.writeValueAsString(Map.of("expectedVersion",version,"name",name,"localTime","09:30","zone","UTC","title","新提醒","content","新正文"));
    }
    String run(String rule,int version,String state) {
        UUID event=UUID.randomUUID(),run=UUID.randomUUID();
        fixtures.update("INSERT INTO core_events(event_id,event_key,type,source,payload,target_rule_id,rule_version) VALUES(?,?,'schedule.daily','scheduler','{}',?,?)",event,event.toString(),UUID.fromString(rule),version);
        fixtures.update("INSERT INTO automation_runs(run_id,rule_id,rule_version,event_id,state,next_attempt_at,lease_until,attempts) VALUES(?,?,?,?,?,now()+interval '1 hour',now()+interval '1 hour',1)",run,UUID.fromString(rule),version,event,state);
        return run.toString();
    }
    @Test void editingRequiresStoppedRuleAndNoInFlightExecution() throws Exception {
        String id=createDaily("状态保护",quietTime());
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(get("automation/rules/"+id).path("editBlock").asText()).isEqualTo("rule_enabled");
        assertThat(request("PUT","automation/rules/"+id,editBody(2,"不应保存"),TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":false,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(204);
        String active=run(id,2,"executing");
        var blocked=get("automation/rules/"+id);
        assertThat(blocked.path("canEdit").asBoolean()).isFalse();
        assertThat(blocked.path("editBlock").asText()).isEqualTo("rule_execution_in_progress");
        assertThat(request("PUT","automation/rules/"+id,editBody(3,"不应改在途参数"),TOKEN).statusCode()).isEqualTo(409);
        assertThat(get("automation/rules/"+id).path("arguments").path("title").asText()).isEqualTo("原提醒");
        fixtures.update("UPDATE automation_runs SET state='enqueued',completed_at=now() WHERE run_id=?",UUID.fromString(active));
        assertThat(request("PUT","automation/rules/"+id,editBody(3,"完成后编辑"),TOKEN).statusCode()).isEqualTo(200);
        var history=get("automation/runs?ruleId="+id).path("items");
        assertThat(history).hasSize(1);assertThat(history.get(0).path("state").asText()).isEqualTo("enqueued");
        assertThat(history.get(0).path("ruleVersion").asInt()).isEqualTo(2);
    }
    @Test void staleEnableCannotActivateANewDefinitionAndOldEnableCallsStillWork() throws Exception {
        String id=createDaily("版本保护",quietTime());
        assertThat(request("PUT","automation/rules/"+id,editBody(1,"新定义"),TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(409);
        assertThat(get("automation/rules/"+id).path("enabled").asBoolean()).isFalse();
        String enable="{\"enabled\":true,\"expectedVersion\":2}";
        assertThat(request("PUT","automation/rules/"+id+"/enabled",enable,TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("PUT","automation/rules/"+id+"/enabled",enable,TOKEN).statusCode()).isEqualTo(204);
        assertThat(get("automation/rules/"+id).path("version").asInt()).isEqualTo(3);
        assertThat(request("PUT","automation/rules/"+id,editBody(1,"新定义"),TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":false}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(get("automation/rules/"+id).path("version").asInt()).isEqualTo(4);
        assertThat(request("PUT","automation/rules/"+id,editBody(2,"过期修改"),TOKEN).statusCode()).isEqualTo(409);
    }
    @Test void concurrentDifferentDefinitionsAcceptOnlyOneVersionAndIdenticalRetriesDoNotBumpAgain() throws Exception {
        String id=createDaily("并发保护",quietTime());
        var gate=new CountDownLatch(1);
        var requests=new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
        for(int i=0;i<8;i++) {String name="并发编辑"+i;requests.add(CompletableFuture.supplyAsync(()->{
            try {gate.await();return request("PUT","automation/rules/"+id,editBody(1,name),TOKEN);}
            catch(Exception failure){throw new RuntimeException(failure);}
        }));}
        gate.countDown();var responses=requests.stream().map(CompletableFuture::join).toList();
        assertThat(responses.stream().filter(r->r.statusCode()==200).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r->r.statusCode()==409).count()).isEqualTo(7);
        var current=get("automation/rules/"+id);assertThat(current.path("version").asInt()).isEqualTo(2);
        String replay=editBody(1,current.path("name").asText());
        requests.clear();for(int i=0;i<8;i++)requests.add(CompletableFuture.supplyAsync(()->{
            try{return request("PUT","automation/rules/"+id,replay,TOKEN);}catch(Exception failure){throw new RuntimeException(failure);}
        }));
        assertThat(requests.stream().map(CompletableFuture::join).map(HttpResponse::statusCode)).containsOnly(200);
        assertThat(get("automation/rules/"+id).path("version").asInt()).isEqualTo(2);
    }
    @Test void editCancelsUnstartedRowsAndOldFailedVersionsStayNonRetryable() throws Exception {
        String id=createDaily("历史保护",quietTime());
        String pending=run(id,1,"pending"),failed=run(id,1,"failed");
        assertThat(request("PUT","automation/rules/"+id,editBody(1,"改名后"),TOKEN).statusCode()).isEqualTo(200);
        var rows=get("automation/runs?ruleId="+id).path("items");
        for(var row:rows) {
            assertThat(row.path("ruleVersion").asInt()).isEqualTo(1);
            assertThat(row.path("state").asText()).isEqualTo(row.path("runId").asText().equals(pending)?"cancelled":"failed");
        }
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("POST","automation/runs/"+failed+"/retry","",TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("POST","automation/runs/"+pending+"/retry","",TOKEN).statusCode()).isEqualTo(409);
    }
    @Test void invalidEditsAndOtherScopesCannotWriteAndCommentRuleKeepsItsType() throws Exception {
        String id=createDaily("校验规则","23:59"),body=editBody(1,"有效修改");
        assertThat(request("PUT","automation/rules/"+id,body,null).statusCode()).isEqualTo(401);
        assertThat(request("PUT","automation/rules/"+id,body,"other-scope-token").statusCode()).isEqualTo(401);
        assertThat(request("GET","automation/rules/"+id,"",null).statusCode()).isEqualTo(401);
        for(String invalid:new String[]{body.replace("09:30","25:00"),body.replace("UTC","not-a-zone"),body.replace("新正文"," "),body.replace("有效修改"," "),"{\"name\":\"缺少版本\"}","{\"name\":\"非法版本\",\"expectedVersion\":0}"})
            assertThat(request("PUT","automation/rules/"+id,invalid,TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("PUT","automation/rules/"+id,body.substring(0,body.length()-1)+",\"trigger\":\"message.received\"}",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("PUT","automation/rules/"+UUID.randomUUID(),body,TOKEN).statusCode()).isEqualTo(404);
        assertThat(request("POST","automation/rules",rule(UUID.randomUUID().toString(),"非法时区").replace("Asia/Shanghai","not-a-zone"),TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("automation/rules/"+id).path("version").asInt()).isEqualTo(1);
        var created=request("POST","automation/rules","{\"name\":\"评论规则\",\"trigger\":\"message.received\",\"action\":\"analyze_public_comment\"}",TOKEN);
        String comment=json.readTree(created.body()).path("ruleId").asText();
        var edited=request("PUT","automation/rules/"+comment,"{\"name\":\"新评论名称\",\"expectedVersion\":1}",TOKEN);
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(get("automation/rules/"+comment).path("arguments").isEmpty()).isTrue();
        assertThat(get("automation/rules/"+comment).path("trigger").asText()).isEqualTo("message.received");
        assertThat(get("automation/rules/"+comment).path("action").asText()).isEqualTo("analyze_public_comment");
    }
    @Test void realSchedulerUsesNewArgumentsAndNeverReplaysTheSameDailyDateAfterEdit() throws Exception {
        String time=java.time.LocalTime.now(java.time.ZoneOffset.UTC).withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String id=createDaily("调度验证",time);
        assertThat(request("PUT","automation/rules/"+id,editBody(1,"调度新定义").replace("09:30",time),TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(204);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        JsonNode runs;
        do {runs=get("automation/runs?ruleId="+id).path("items");if(!runs.isEmpty()&&runs.get(0).path("state").asText().equals("enqueued"))break;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertThat(runs).hasSize(1);assertThat(runs.get(0).path("state").asText()).isEqualTo("enqueued");
        String messageId=runs.get(0).path("result").path("messageId").asText();
        var reminder=java.util.stream.StreamSupport.stream(get("messages?source=automation&size=100").path("items").spliterator(),false)
            .filter(message -> message.path("messageId").asText().equals(messageId)).findFirst().orElseThrow();
        assertThat(reminder.path("title").asText()).isEqualTo("新提醒");
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":false,\"expectedVersion\":3}",TOKEN).statusCode()).isEqualTo(204);
        String second=editBody(4,"当天再编辑").replace("09:30",time).replace("新提醒","不应重复");
        assertThat(request("PUT","automation/rules/"+id,second,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true,\"expectedVersion\":5}",TOKEN).statusCode()).isEqualTo(204);
        Thread.sleep(400);
        var after=get("automation/runs?ruleId="+id).path("items");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("result").path("messageId").asText()).isEqualTo(messageId);
    }
}
