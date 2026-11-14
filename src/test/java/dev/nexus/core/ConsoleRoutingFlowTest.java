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
class ConsoleRoutingFlowTest {
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

    String device(String id) throws Exception {
        var response=request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN);
        assertThat(response.statusCode()).isEqualTo(201);return id;
    }
    Map<String,Object> definition(String name,String device) {
        return new java.util.LinkedHashMap<>(Map.ofEntries(Map.entry("name",name),Map.entry("enabled",true),Map.entry("source","route-test"),
            Map.entry("eventType","*"),Map.entry("match","keyword"),Map.entry("expression","alert"),Map.entry("deviceIds",List.of(device)),
            Map.entry("priority","high"),Map.entry("from",""),Map.entry("before",""),Map.entry("zone","UTC")));
    }
    String message(String key,String content) {
        return json.writeValueAsString(Map.of("connectorId","route-test","source","route-test","eventId",key,"eventType","alert.test","title","标题","content",content));
    }
    @Test void routingIsOptInDeduplicatesMatchesAndSnapshotsPriority() throws Exception {
        fixtures.execute("TRUNCATE inbox_messages,devices,routing_rules CASCADE");
        fixtures.update("UPDATE routing_policy SET mode='broadcast',version=1");
        String a=device("route-a"),b=device("route-b"),id=UUID.randomUUID().toString();
        String original=json.writeValueAsString(Map.of("expectedVersion",0,"definition",definition("仅 A",a)));
        assertThat(request("PUT","routing/rules/"+id,original,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("PUT","routing/rules/"+id,original,TOKEN).statusCode()).isEqualTo(200);
        String first=json.readTree(request("POST","messages",message("broadcast","alert"),TOKEN).body()).path("messageId").asText();
        assertThat(fixtures.queryForObject("SELECT count(*) FROM deliveries WHERE message_id=?",Integer.class,UUID.fromString(first))).isEqualTo(2);
        assertThat(request("PUT","routing/policy","{\"mode\":\"rules\",\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(200);
        String duplicate=UUID.randomUUID().toString();
        assertThat(request("PUT","routing/rules/"+duplicate,original,TOKEN).statusCode()).isEqualTo(200);
        String second=json.readTree(request("POST","messages",message("routed","alert"),TOKEN).body()).path("messageId").asText();
        assertThat(fixtures.queryForObject("SELECT count(*) FROM deliveries WHERE message_id=?",Integer.class,UUID.fromString(second))).isEqualTo(1);
        assertThat(fixtures.queryForObject("SELECT device_id FROM deliveries WHERE message_id=?",String.class,UUID.fromString(second))).isEqualTo(a);
        assertThat(fixtures.queryForObject("SELECT priority_override FROM deliveries WHERE message_id=?",String.class,UUID.fromString(second))).isEqualTo("high");
        var blocked=request("POST","routing/preview",message("preview","other text"),TOKEN);
        assertThat(blocked.statusCode()).isEqualTo(200);assertThat(json.readTree(blocked.body()).path("targets")).isEmpty();
        assertThat(fixtures.queryForObject("SELECT count(*) FROM inbox_messages",Integer.class)).isEqualTo(2);
        fixtures.update("UPDATE devices SET enabled=false WHERE device_id=?",a);
        assertThat(json.readTree(request("POST","routing/preview",message("disabled","alert"),TOKEN).body()).path("targets")).isEmpty();
        assertThat(request("DELETE","routing/rules/"+id+"?expectedVersion=1","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("DELETE","routing/rules/"+id+"?expectedVersion=1","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("PUT","routing/rules/"+id,original,TOKEN).statusCode()).isEqualTo(410);
        fixtures.update("UPDATE routing_policy SET mode='broadcast',version=1");
    }
    @Test void targetedTestBypassesBroadcastWithoutChangingOriginalIngestAndKeepsOneIdentity() throws Exception {
        String a=device("targeted-a"),b=device("targeted-b"),key=UUID.randomUUID().toString();
        String body=json.writeValueAsString(Map.of("requestKey",key,"deviceIds",List.of(a),"title","定向测试","content","隔离测试","priority","normal"));
        var response=request("POST","routing/test",body,TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        String id=json.readTree(response.body()).path("messageId").asText();
        assertThat(fixtures.queryForList("SELECT device_id FROM deliveries WHERE message_id=?",String.class,UUID.fromString(id))).containsExactly(a);
        assertThat(request("POST","routing/test",body,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("POST","routing/test",body.replace(a,b),TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("POST","routing/test",body,null).statusCode()).isEqualTo(401);
    }
    @Test void overnightWindowIncludesStartAndExcludesEnd() throws Exception {
        fixtures.update("UPDATE routing_policy SET mode='rules'");
        String a=device("overnight"),id=UUID.randomUUID().toString();
        var d=definition("夜间",a);d.put("from","23:00");d.put("before","02:00");
        assertThat(request("PUT","routing/rules/"+id,json.writeValueAsString(Map.of("expectedVersion",0,"definition",d)),TOKEN).statusCode()).isEqualTo(200);
        var input=json.readValue(message("time","alert"),dev.nexus.core.inbox.MessageInput.class);
        assertThat(routing.targets(input,java.time.Instant.parse("2026-11-15T23:00:00Z")).stream().map(t->t.deviceId()).toList()).contains(a);
        assertThat(routing.targets(input,java.time.Instant.parse("2026-11-15T01:59:59Z")).stream().map(t->t.deviceId()).toList()).contains(a);
        assertThat(routing.targets(input,java.time.Instant.parse("2026-11-15T02:00:00Z")).stream().map(t->t.deviceId()).toList()).doesNotContain(a);
        fixtures.update("UPDATE routing_policy SET mode='broadcast'");
    }
    @Test void invalidRoutesAuthorizationAndConcurrentVersionsAreRejected() throws Exception {
        String a=device("route-validation"),id=UUID.randomUUID().toString();
        var d=definition("校验",a);
        d.put("match","regex");d.put("expression","(a+)+$");
        String path="routing/rules/"+id;
        assertThat(request("PUT",path,json.writeValueAsString(Map.of("expectedVersion",0,"definition",d)),TOKEN).statusCode()).isEqualTo(400);
        d.put("expression","^alert.*");
        String body=json.writeValueAsString(Map.of("expectedVersion",0,"definition",d));
        assertThat(request("PUT",path,body,null).statusCode()).isEqualTo(401);
        assertThat(request("PUT",path,body,TOKEN).statusCode()).isEqualTo(200);
        d.put("name","另一版本");
        assertThat(request("PUT",path,json.writeValueAsString(Map.of("expectedVersion",0,"definition",d)),TOKEN).statusCode()).isEqualTo(409);
        d.put("from","23:00");d.put("before","02:00");
        assertThat(request("PUT",path,json.writeValueAsString(Map.of("expectedVersion",1,"definition",d)),TOKEN).statusCode()).isEqualTo(200);
        d.put("deviceIds",List.of("missing-device"));
        assertThat(request("PUT",path,json.writeValueAsString(Map.of("expectedVersion",2,"definition",d)),TOKEN).statusCode()).isEqualTo(400);
    }
}
