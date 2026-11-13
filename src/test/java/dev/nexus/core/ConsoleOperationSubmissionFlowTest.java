package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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

/** Public creation/replay semantics against isolated PostgreSQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleOperationSubmissionFlowTest {
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
    @BeforeEach void prepare() {
        fixtures.execute("TRUNCATE automation_rules CASCADE");
    }
    @Test void keyedCreationReplaysTheOriginalRuleWithoutChangingEnablement() throws Exception {
        String key=UUID.randomUUID().toString(),body=rule(key,"幂等规则");
        var first=request("POST","automation/rules",body,TOKEN);
        assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
        String id=json.readTree(first.body()).path("ruleId").asText();
        assertThat(request("PUT","automation/rules/"+id+"/enabled","{\"enabled\":true}",TOKEN).statusCode()).isEqualTo(204);
        var replay=request("POST","automation/rules",body,TOKEN);
        assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("ruleId").asText()).isEqualTo(id);
        assertThat(request("POST","automation/rules",rule(key,"变化的名称"),TOKEN).statusCode()).isEqualTo(409);
        assertThat(get("automation/rules").path("items").size()).isEqualTo(1);
        var saved=get("automation/rules").path("items").get(0);
        assertThat(saved.path("enabled").asBoolean()).isTrue();
        assertThat(saved.path("version").asInt()).isEqualTo(2);
    }
    @Test void concurrentSameKeyCreatesOneRuleAndDoesNotExposeReplayMetadata() throws Exception {
        String body=rule(UUID.randomUUID().toString(),"并发规则");
        var gate=new CountDownLatch(1);
        var requests=new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
        for(int i=0;i<8;i++)requests.add(CompletableFuture.supplyAsync(()->{
            try {gate.await();return request("POST","automation/rules",body,TOKEN);}
            catch(Exception failure){throw new RuntimeException(failure);}
        }));
        gate.countDown();
        var responses=requests.stream().map(CompletableFuture::join).toList();
        assertThat(responses.stream().filter(r->r.statusCode()==201).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r->r.statusCode()==200).count()).isEqualTo(7);
        assertThat(responses.stream().map(r->json.readTree(r.body()).path("ruleId").asText()).distinct().count()).isEqualTo(1);
        var saved=get("automation/rules").path("items");
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(saved.get(0).has("requestKey")).isFalse();
        assertThat(saved.get(0).has("requestFingerprint")).isFalse();
    }
    @Test void oldUnkeyedCallsRemainIndependentAndInvalidOrUnauthenticatedKeysCannotWrite() throws Exception {
        String unkeyed="{\"name\":\"旧调用\",\"trigger\":\"message.received\",\"action\":\"analyze_public_comment\"}";
        assertThat(request("POST","automation/rules",unkeyed,TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST","automation/rules",unkeyed,TOKEN).statusCode()).isEqualTo(201);
        String key=UUID.randomUUID().toString(),body=rule(key,"校验规则");
        assertThat(request("POST","automation/rules",body,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","automation/rules",rule("invalid","校验规则"),TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("POST","automation/rules",body.replace("08:00","25:00"),TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("automation/rules").path("items").size()).isEqualTo(2);
        assertThat(request("POST","automation/rules",body,TOKEN).statusCode()).isEqualTo(201);
    }
    @Test void v13UpgradePreservesExistingRuleIdentityAndState() throws Exception {
        String schema="legacy_automation_creation";
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas(schema).defaultSchema(schema).target("13").load().migrate();
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            connection.setSchema(schema);var legacy=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            UUID id=UUID.randomUUID();
            legacy.update("INSERT INTO automation_rules(rule_id,name,trigger_type,action_id,arguments,enabled,version) VALUES(?,'旧规则','message.received','analyze_public_comment','{}',true,5)",id);
            Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
            var saved=legacy.queryForMap("SELECT rule_id,name,enabled,version,create_request_key,create_request_hash FROM automation_rules WHERE rule_id=?",id);
            assertThat(saved.get("rule_id")).isEqualTo(id);
            assertThat(saved.get("name")).isEqualTo("旧规则");
            assertThat(saved.get("enabled")).isEqualTo(true);
            assertThat(saved.get("version")).isEqualTo(5);
            assertThat(saved.get("create_request_key")).isNull();
            assertThat(saved.get("create_request_hash")).isNull();
        } finally { fixtures.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
}
