package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Device deletion through public HTTP with isolated PostgreSQL.
 *
 * <p>Deleting is only for cleaning up a registration that never produced durable facts. Any
 * message, telemetry, configuration, upgrade, growth or credential history — or a routing/source
 * reference — must keep the device and force the operator to disable it instead.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceDeleteFlowTest {
    static final String TOKEN="device-delete-test-token-at-least-32";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://127.0.0.1:1");
        p.add("nexus.mqtt.password",()->"device-delete-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(String method,String path,String body)throws Exception {return request(method,path,body,TOKEN);}
    JsonNode get(String path)throws Exception {
        var response=request("GET",path,"");assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    JsonNode issue(String role)throws Exception {
        var response=request("POST","access/tokens",json.writeValueAsString(Map.of("name",role+" delete","role",role,"expiresAt",Instant.now().plusSeconds(3600).toString())));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);return json.readTree(response.body());
    }
    void register(String id)throws Exception {
        var response=request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    }
    List<String> ids(JsonNode value) {
        var result=new java.util.ArrayList<String>();
        for(var row:value.path("items"))result.add(row.path("deviceId").asText());
        return result;
    }
    void deliveryHistory(String deviceId) {
        var messageId=UUID.randomUUID();
        fixtures.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at)
            VALUES (?, 'delete-test','delete-test',?,'test','删除保护','正文','摘要','夹具','http://example.invalid','normal',now(),now()+interval '1 day')
            """,messageId,messageId.toString());
        fixtures.update("INSERT INTO deliveries(event_id,message_id,device_id) VALUES (?,?,?)",UUID.randomUUID(),messageId,deviceId);
    }

    @BeforeEach void prepare()throws Exception {
        fixtures.execute("TRUNCATE devices CASCADE");
        fixtures.execute("TRUNCATE routing_rules");
        fixtures.execute("TRUNCATE webhook_sources CASCADE");
        for(String id:List.of("dev-free","dev-used","dev-routed","dev-sourced"))register(id);
    }

    @Test void unusedDeviceIsDeletedAndRepeatAndReRegistrationStaySafe()throws Exception {
        assertThat(request("DELETE","devices/dev-free","").statusCode()).isEqualTo(204);
        assertThat(ids(get("devices"))).containsExactly("dev-used","dev-routed","dev-sourced");
        assertThat(get("devices").path("total").asLong()).isEqualTo(3);
        // 响应丢失后的重复删除是幂等的；不存在的设备同样返回 204。
        assertThat(request("DELETE","devices/dev-free","").statusCode()).isEqualTo(204);
        assertThat(request("DELETE","devices/never-existed","").statusCode()).isEqualTo(204);
        register("dev-free");
        assertThat(ids(get("devices"))).contains("dev-free");
    }

    @Test void anyDeliveryHistoryBlocksDeletionAndKeepsTheDevice()throws Exception {
        deliveryHistory("dev-used");
        var refused=request("DELETE","devices/dev-used","");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json.readTree(refused.body()).path("error").asText()).isEqualTo("device_has_history");
        assertThat(ids(get("devices"))).contains("dev-used");
    }

    @Test void routingAndWebhookReferencesMustBeRemovedFirst()throws Exception {
        fixtures.update("INSERT INTO routing_rules(rule_id,definition) VALUES (?,?::jsonb)",UUID.randomUUID(),"{\"deviceIds\":[\"dev-routed\"]}");
        var routed=request("DELETE","devices/dev-routed","");
        assertThat(routed.statusCode()).isEqualTo(409);
        assertThat(json.readTree(routed.body()).path("error").asText()).isEqualTo("device_referenced");
        // 删除路由规则后即可删除设备；已删除规则的悬空引用不再阻止清理。
        fixtures.update("UPDATE routing_rules SET deleted=true WHERE definition->'deviceIds' @> '[\"dev-routed\"]'::jsonb");
        assertThat(request("DELETE","devices/dev-routed","").statusCode()).isEqualTo(204);

        fixtures.update("INSERT INTO webhook_sources(source_id,name,device_ids,token_hash,hmac_secret,creation_hash) VALUES ('delete-src','删除来源','[\"dev-sourced\"]'::jsonb,repeat('a',64),repeat('b',64),repeat('c',64))");
        var sourced=request("DELETE","devices/dev-sourced","");
        assertThat(sourced.statusCode()).isEqualTo(409);
        assertThat(json.readTree(sourced.body()).path("error").asText()).isEqualTo("device_referenced");
        fixtures.update("DELETE FROM webhook_sources WHERE source_id='delete-src'");
        assertThat(request("DELETE","devices/dev-sourced","").statusCode()).isEqualTo(204);
    }

    @Test void operatorAndViewerCannotDelete()throws Exception {
        String operator=issue("operator").path("token").asText();
        String viewer=issue("viewer").path("token").asText();
        assertThat(request("DELETE","devices/dev-free","",operator).statusCode()).isEqualTo(403);
        assertThat(request("DELETE","devices/dev-free","",viewer).statusCode()).isEqualTo(403);
        assertThat(ids(get("devices"))).contains("dev-free");
    }
}
