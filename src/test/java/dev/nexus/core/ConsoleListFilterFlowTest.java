package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

/** Public HTTP with an isolated real database; not a physical delivery acceptance. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleListFilterFlowTest {
    static final String TOKEN="console-filter-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core filter-test-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"filter-test-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
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
    JsonNode page(String path)throws Exception {
        var response=request("GET",path,"",TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    String message(String title)throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","filter-test","source","manual","eventId",UUID.randomUUID().toString(),
            "eventType","manual.test","title",title,"content",title)),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("messageId").asText();
    }
    @BeforeEach void prepare()throws Exception {
        fixtures.execute("TRUNCATE inbox_messages,devices CASCADE");
        assertThat(request("POST","devices","{\"deviceId\":\"filter-device\",\"name\":\"列表筛选设备\"}",TOKEN).statusCode()).isEqualTo(201);
    }

    @Test void messageReadFilterSharesRowsTotalsAndPagination()throws Exception {
        String read=message("已读消息"), first=message("未读消息一"), second=message("未读消息二");
        assertThat(request("POST","messages/"+read+"/read","",TOKEN).statusCode()).isEqualTo(204);
        var all=page("messages?size=100");assertThat(all.path("total").asInt()).isEqualTo(3);assertThat(all.path("items").size()).isEqualTo(3);
        var one=page("messages?readStatus=unread&size=1&page=1");
        var two=page("messages?readStatus=unread&size=1&page=2");
        assertThat(one.path("total").asInt()).isEqualTo(2);assertThat(two.path("total").asInt()).isEqualTo(2);
        assertThat(one.path("items").size()).isEqualTo(1);assertThat(two.path("items").size()).isEqualTo(1);
        assertThat(one.path("items").get(0).path("messageId").asText()).isIn(first,second);
        assertThat(two.path("items").get(0).path("messageId").asText()).isIn(first,second).isNotEqualTo(one.path("items").get(0).path("messageId").asText());
        var outside=page("messages?readStatus=unread&size=1&page=3");
        assertThat(outside.path("items").isEmpty()).isTrue();assertThat(outside.path("total").asInt()).isEqualTo(2);
        var readOnly=page("messages?readStatus=read");assertThat(readOnly.path("total").asInt()).isEqualTo(1);
        assertThat(readOnly.path("items").get(0).path("messageId").asText()).isEqualTo(read);
    }

    @Test void deliveryStatusFilterSharesRowsTotalsAndPagination()throws Exception {
        String first=message("失败一"), second=message("失败二"), expired=message("到期消息");message("已展示消息");
        // Arrange read-only list states in the isolated DB; ACK/publication transitions have separate tests.
        fixtures.update("UPDATE deliveries SET status='displayed',next_attempt_at=now()+interval '1 day'");
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id IN (?,?)",UUID.fromString(first),UUID.fromString(second));
        fixtures.update("UPDATE deliveries SET status='expired' WHERE message_id=?",UUID.fromString(expired));
        assertThat(page("deliveries?size=100").path("total").asInt()).isEqualTo(4);
        var one=page("deliveries?status=failed&size=1&page=1");
        var two=page("deliveries?status=failed&size=1&page=2");
        assertThat(one.path("total").asInt()).isEqualTo(2);assertThat(two.path("total").asInt()).isEqualTo(2);
        assertThat(one.path("items").size()).isEqualTo(1);assertThat(two.path("items").size()).isEqualTo(1);
        assertThat(one.path("items").get(0).path("messageId").asText()).isIn(first,second);
        assertThat(two.path("items").get(0).path("messageId").asText()).isIn(first,second).isNotEqualTo(one.path("items").get(0).path("messageId").asText());
        assertThat(page("deliveries?status=expired").path("items").get(0).path("messageId").asText()).isEqualTo(expired);
        for(String state:new String[]{"pending","sent","received","read"}) {
            var empty=page("deliveries?status="+state);assertThat(empty.path("total").asInt()).isZero();assertThat(empty.path("items").isEmpty()).isTrue();
        }
        assertThat(page("deliveries?status=displayed").path("total").asInt()).isEqualTo(1);
    }

    @Test void deliveryReasonFilterNarrowsToThatLastError()throws Exception {
        String timeout=message("确认超时"), broker=message("Broker 不可用"), bare=message("没记录原因");
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE message_id=?",UUID.fromString(timeout));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='broker_unavailable' WHERE message_id=?",UUID.fromString(broker));
        fixtures.update("UPDATE deliveries SET status='failed',last_error=NULL WHERE message_id=?",UUID.fromString(bare));
        assertThat(page("deliveries?status=failed&size=100").path("total").asInt()).isEqualTo(3);
        var timeouts=page("deliveries?status=failed&lastError=ack_timeout");
        assertThat(timeouts.path("total").asInt()).isEqualTo(1);
        assertThat(timeouts.path("items").get(0).path("messageId").asText()).isEqualTo(timeout);
        // 格式合法但不存在的原因不是错误，只是没有匹配。
        assertThat(page("deliveries?lastError=no_such_reason").path("total").asInt()).isZero();
        // 没记录原因的失败行在概览里归为 unknown，这里也必须能筛出来，否则计数与钻取对不上。
        var unknown=page("deliveries?status=failed&lastError=unknown");
        assertThat(unknown.path("total").asInt()).isEqualTo(1);
        assertThat(unknown.path("items").get(0).path("messageId").asText()).isEqualTo(bare);
        // 导出与列表共用同一条筛选，不能导出比列表更多的东西。
        var csv=request("GET","deliveries/export?status=failed&lastError=ack_timeout","",TOKEN);
        assertThat(csv.statusCode()).withFailMessage(csv.body()).isEqualTo(200);
        assertThat(csv.body()).contains(timeout).doesNotContain(broker);
    }

    @Test void unknownFiltersAreRejectedAndAuthenticationIsPreserved()throws Exception {
        for(String path:new String[]{"messages?readStatus=all","messages?readStatus=","messages?readStatus=read%27%20OR%201%3D1",
                "deliveries?status=unknown","deliveries?status=","deliveries?status=failed%27%20OR%201%3D1",
                "deliveries?lastError=ACK_TIMEOUT","deliveries?lastError=","deliveries?lastError=ack%27%20OR%201%3D1"})
            assertThat(request("GET",path,"",TOKEN).statusCode()).withFailMessage(path).isEqualTo(400);
        for(String path:new String[]{"messages?readStatus=unread","deliveries?status=failed"})
            assertThat(request("GET",path,"",null).statusCode()).isEqualTo(401);
    }
}
