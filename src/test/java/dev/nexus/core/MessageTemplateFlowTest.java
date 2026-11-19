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
class MessageTemplateFlowTest {
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


    Map<String,Object> template(String title,String summary) {return displayTemplate("none",8,title,summary);}
    Map<String,Object> displayTemplate(String sound,int seconds,String title,String summary) {return Map.of("name","模板","title",title,"summary",summary,"icon","comment","sound",sound,"durationSeconds",seconds);}
    String input(){return json.writeValueAsString(Map.of("connectorId","template-test","source","template-test","eventId",UUID.randomUUID(),"eventType","comment.test","title","原标题","content","很长的中文内容".repeat(80),"sender","甲"));}
    @Test void variablesAreLiteralBoundedAndCapabilitiesAreValidated() throws Exception {
        var definition=displayTemplate("chime",15,"{source}: {title}","{sender}：{content}");
        String body=json.writeValueAsString(Map.of("definition",definition,"message",json.readTree(input())));
        var preview=request("POST","templates/preview",body,TOKEN);
        assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
        var rendered=json.readTree(preview.body());
        assertThat(rendered.path("title").asText()).isEqualTo("template-test: 原标题");
        assertThat(rendered.path("summary").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(180);
        assertThat(rendered.path("eventType").asText()).isEqualTo("comment.template");
        assertThat(rendered.path("sound").asText()).isEqualTo("chime");
        assertThat(rendered.path("durationMs").asInt()).isEqualTo(15000);
        for(var invalid:List.of(template("{missing}","text"),template("{content}","{nested:{title}}"),new java.util.HashMap<>(definition),
            displayTemplate("beep",8,"{title}","text"),displayTemplate("none",4,"{title}","text"),displayTemplate("none",31,"{title}","text"))) {
            if(invalid.equals(definition))invalid.put("sound","beep");
            assertThat(request("POST","templates/preview",json.writeValueAsString(Map.of("definition",invalid,"message",json.readTree(input()))),TOKEN).statusCode()).isEqualTo(400);
        }
        assertThat(request("POST","templates/preview",body,null).statusCode()).isEqualTo(401);
    }
    @Test void routingUsesImmutableRenderedSnapshotsAndPurgesTheirBodiesWithSource() throws Exception {
        String device="template-pet";
        assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",device,"name",device)),TOKEN).statusCode()).isEqualTo(201);
        String id=UUID.randomUUID().toString(),route=UUID.randomUUID().toString();
        String definition=json.writeValueAsString(Map.of("expectedVersion",0,"definition",template("旧 {title}","{sender}:{content}")));
        assertThat(request("PUT","templates/"+id,definition,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("PUT","templates/"+id,definition,TOKEN).statusCode()).isEqualTo(200);
        var rule=new java.util.LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("name","模板路由"),Map.entry("enabled",true),Map.entry("source","template-test"),Map.entry("eventType","*"),Map.entry("match","all"),Map.entry("expression",""),Map.entry("deviceIds",List.of(device)),Map.entry("priority",""),Map.entry("from",""),Map.entry("before",""),Map.entry("zone","UTC"),Map.entry("templateId",id)));
        assertThat(request("PUT","routing/rules/"+route,json.writeValueAsString(Map.of("expectedVersion",0,"definition",rule)),TOKEN).statusCode()).isEqualTo(200);
        fixtures.update("UPDATE routing_policy SET mode='rules'");
        var accepted=request("POST","messages",input(),TOKEN);String message=json.readTree(accepted.body()).path("messageId").asText();
        assertThat(fixtures.queryForObject("SELECT title_override FROM deliveries WHERE message_id=?",String.class,UUID.fromString(message))).isEqualTo("旧 原标题");
        assertThat(request("PUT","templates/"+id,json.writeValueAsString(Map.of("expectedVersion",1,"definition",template("新 {title}","{summary}"))),TOKEN).statusCode()).isEqualTo(200);
        assertThat(fixtures.queryForObject("SELECT title_override FROM deliveries WHERE message_id=?",String.class,UUID.fromString(message))).isEqualTo("旧 原标题");
        assertThat(request("DELETE","templates/"+id+"?expectedVersion=2","",TOKEN).statusCode()).isEqualTo(409);
        fixtures.update("UPDATE inbox_messages SET content_purged_at=now(),content='',summary='',title='' WHERE message_id=?",UUID.fromString(message));
        assertThat(fixtures.queryForObject("SELECT summary_override FROM deliveries WHERE message_id=?",String.class,UUID.fromString(message))).isNull();
        assertThat(request("DELETE","routing/rules/"+route+"?expectedVersion=1","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("DELETE","templates/"+id+"?expectedVersion=2","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("DELETE","templates/"+id+"?expectedVersion=2","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("PUT","templates/"+id,definition,TOKEN).statusCode()).isEqualTo(410);
        fixtures.update("UPDATE routing_policy SET mode='broadcast'");
    }
    @Test void targetedTestSnapshotsTemplateSoundAndDuration() throws Exception {
        String device="template-display-pet";
        assertThat(request("POST","devices",json.writeValueAsString(Map.of("deviceId",device,"name",device)),TOKEN).statusCode()).isEqualTo(201);
        String id=UUID.randomUUID().toString();
        var definition=json.writeValueAsString(Map.of("expectedVersion",0,"definition",displayTemplate("chime",15,"{title}","{sender}：{content}")));
        assertThat(request("PUT","templates/"+id,definition,TOKEN).statusCode()).isEqualTo(200);
        String requestKey=UUID.randomUUID().toString();
        String body=json.writeValueAsString(Map.of("requestKey",requestKey,"deviceIds",List.of(device),"title","定向提醒","content","请查看模板显示选项","priority","high","templateId",id));
        var accepted=request("POST","routing/test",body,TOKEN);
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(201);
        String message=json.readTree(accepted.body()).path("messageId").asText();
        var snapshot=fixtures.queryForMap("SELECT sound,duration_ms FROM deliveries WHERE message_id=? AND device_id=?",UUID.fromString(message),device);
        assertThat(snapshot).containsEntry("sound","chime").containsEntry("duration_ms",15000);
        var replay=request("POST","routing/test",body,TOKEN);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("messageId").asText()).isEqualTo(message);
        var updated=json.writeValueAsString(Map.of("expectedVersion",1,"definition",displayTemplate("alert",30,"新 {title}","新 {summary}")));
        assertThat(request("PUT","templates/"+id,updated,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("POST","routing/test",body,TOKEN).statusCode()).isEqualTo(200);
        var unchanged=fixtures.queryForMap("SELECT sound,duration_ms,title_override FROM deliveries WHERE message_id=? AND device_id=?",UUID.fromString(message),device);
        assertThat(unchanged).containsEntry("sound","chime").containsEntry("duration_ms",15000).containsEntry("title_override","定向提醒");
    }
}
