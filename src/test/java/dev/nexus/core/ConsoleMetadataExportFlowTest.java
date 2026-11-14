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
class ConsoleMetadataExportFlowTest {
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

    @Test void sourceAndReceivedWindowCombineWithReadStateAndPagination()throws Exception {
        String included=message("范围内"), outside=message("截止边界"), other=message("其他来源"), read=message("已读排除");
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T08:00:00Z',created_at='2020-01-01T00:00:00Z'");
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T09:00:00Z' WHERE message_id=?",UUID.fromString(outside));
        fixtures.update("UPDATE inbox_messages SET source='halo' WHERE message_id=?",UUID.fromString(other));
        assertThat(request("POST","messages/"+read+"/read","",TOKEN).statusCode()).isEqualTo(204);
        String query="messages?source=manual&readStatus=unread&receivedFrom=2026-11-14T16:00:00%2B08:00&receivedBefore=2026-11-14T09:00:00Z&size=1";
        var result=page(query);
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("messageId").asText()).isEqualTo(included);
        var empty=page(query+"&page=2");assertThat(empty.path("total").asInt()).isEqualTo(1);assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(page("messages").path("total").asInt()).isEqualTo(4);
        assertThat(page("messages?source=missing").path("total").asInt()).isZero();
    }
    @Test void deviceAndMessageReceiptWindowCombineWithDeliveryState()throws Exception {
        assertThat(request("POST","devices","{\"deviceId\":\"other-device\",\"name\":\"其他设备\"}",TOKEN).statusCode()).isEqualTo(201);
        String first=message("起始边界"), second=message("范围内"), outside=message("截止边界");
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T08:00:00Z'");
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T08:30:00Z' WHERE message_id=?",UUID.fromString(second));
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T09:00:00Z' WHERE message_id=?",UUID.fromString(outside));
        fixtures.update("UPDATE deliveries SET status='failed',received_at='2020-01-01T00:00:00Z'");
        String query="deliveries?status=failed&deviceId=filter-device&receivedFrom=2026-11-14T08:00:00Z&receivedBefore=2026-11-14T09:00:00Z&size=1";
        var one=page(query); var two=page(query+"&page=2");
        assertThat(one.path("total").asInt()).isEqualTo(2);assertThat(two.path("total").asInt()).isEqualTo(2);
        assertThat(one.path("items").get(0).path("messageId").asText()).isEqualTo(second);
        assertThat(two.path("items").get(0).path("messageId").asText()).isEqualTo(first);
        assertThat(one.path("items").get(0).path("deviceId").asText()).isEqualTo("filter-device");
        assertThat(page(query+"&page=3").path("items").isEmpty()).isTrue();
        assertThat(page("deliveries").path("total").asInt()).isEqualTo(6);
        assertThat(page("deliveries?deviceId=missing").path("total").asInt()).isZero();
    }

    @Test void csvExportUsesTheSameFiltersAndOnlyExplicitMetadataColumns()throws Exception {
        String included=message("DO_NOT_EXPORT_TITLE"), other=message("OUTSIDE_TITLE");
        fixtures.update("UPDATE inbox_messages SET received_at='2026-11-14T08:00:00Z',content='DO_NOT_EXPORT_BODY',summary='DO_NOT_EXPORT_SUMMARY',sender='DO_NOT_EXPORT_SENDER',metadata='{\"secret\":\"DO_NOT_EXPORT_PAYLOAD\"}'::jsonb");
        fixtures.update("UPDATE inbox_messages SET source='halo' WHERE message_id=?",UUID.fromString(other));
        fixtures.update("UPDATE inbox_messages SET connector_id='-connector',event_type='  =SUM(1,2)\"\nnext',content_purged_at=now() WHERE message_id=?",UUID.fromString(included));
        var response=request("GET","messages/export?source=manual&readStatus=unread&receivedFrom=2026-11-14T08:00:00Z&receivedBefore=2026-11-14T09:00:00Z","",TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("attachment; filename=\"messages-metadata.csv\"");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
        assertThat(response.body()).startsWith("\ufeff\"messageId\",\"connectorId\",\"source\",\"eventType\",\"priority\",\"receivedAt\",\"createdAt\",\"expiresAt\",\"readAt\",\"readStatus\",\"contentPurgedAt\"\r\n");
        assertThat(response.body()).contains(included,"\"'-connector\"","\"'  =SUM(1,2)\"\"\nnext\"").doesNotContain(other,"DO_NOT_EXPORT");
        fixtures.update("UPDATE deliveries SET status='failed',last_error='DO_NOT_EXPORT_ERROR'");
        var deliveries=request("GET","deliveries/export?status=failed&deviceId=filter-device&receivedFrom=2026-11-14T08:00:00Z&receivedBefore=2026-11-14T09:00:00Z","",TOKEN);
        assertThat(deliveries.statusCode()).isEqualTo(200);
        assertThat(deliveries.body()).startsWith("\ufeff\"eventId\",\"messageId\",\"deviceId\",\"status\",\"attempts\",\"messageReceivedAt\",\"receivedAt\",\"displayedAt\",\"readAt\",\"nextAttemptAt\"\r\n");
        assertThat(deliveries.body()).contains(included,other).doesNotContain("DO_NOT_EXPORT");
        assertThat(request("GET","messages/export?source=missing","",TOKEN).body().split("\r\n")).hasSize(1);
    }

    @Test void invalidRangesIdentitiesAndUnauthorizedExportsAreRejected()throws Exception {
        for(String endpoint:new String[]{"messages","messages/export","deliveries","deliveries/export"}) {
            for(String query:new String[]{"receivedFrom=","receivedBefore=not-a-date","receivedFrom=2026-11-14T08:00:00",
                    "receivedFrom=2026-11-14T09:00:00Z&receivedBefore=2026-11-14T09:00:00Z",
                    "receivedFrom=2026-11-14T10:00:00Z&receivedBefore=2026-11-14T09:00:00Z",
                    "receivedFrom=%2B999999999-01-01T00:00:00Z"})
                assertThat(request("GET",endpoint+"?"+query,"",TOKEN).statusCode()).withFailMessage(endpoint+"?"+query).isEqualTo(400);
            String key=endpoint.startsWith("messages")?"source":"deviceId";
            for(String value:new String[]{"","bad%27%20OR%201%3D1","x".repeat(41),"%20manual"})
                assertThat(request("GET",endpoint+"?"+key+"="+value,"",TOKEN).statusCode()).withFailMessage(endpoint+"/"+value).isEqualTo(400);
            assertThat(request("GET",endpoint,"",null).statusCode()).isEqualTo(401);
            assertThat(request("GET",endpoint,"","unrelated-scope-token").statusCode()).isEqualTo(401);
        }
        assertThat(request("GET","messages/export?readStatus=all","",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("GET","deliveries/export?status=all","",TOKEN).statusCode()).isEqualTo(400);
    }

    @Test void exportsRejectMoreThan5000RowsWithoutTruncationAndAllowExactLimit()throws Exception {
        fixtures.execute("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at)
            SELECT gen_random_uuid(),'bulk','bulk',n::text,'manual.test','不导出','不导出','不导出','','','normal',now(),now()+interval '1 day'
            FROM generate_series(1,5001) n
            """);
        fixtures.execute("INSERT INTO deliveries(event_id,message_id,device_id) SELECT gen_random_uuid(),message_id,'filter-device' FROM inbox_messages");
        for(String endpoint:new String[]{"messages/export?source=bulk","deliveries/export?deviceId=filter-device"}) {
            var response=request("GET",endpoint,"",TOKEN);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).path("error").asText()).isEqualTo("export_too_large");
        }
        assertThat(request("GET","messages/export?source=missing","",TOKEN).statusCode()).isEqualTo(200);
        fixtures.execute("DELETE FROM deliveries WHERE message_id IN (SELECT message_id FROM inbox_messages WHERE upstream_event_id='5001')");
        fixtures.execute("DELETE FROM inbox_messages WHERE upstream_event_id='5001'");
        for(String endpoint:new String[]{"messages/export?source=bulk","deliveries/export?deviceId=filter-device"}) {
            var response=request("GET",endpoint,"",TOKEN);assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().split("\r\n")).hasSize(5001);
        }
    }

    @Test void csvNeutralizesFormulaPrefixesAfterWhitespaceAndQuotesControls()throws Exception {
        message("不导出标题");
        for(String vector:new String[]{"=SUM(1,2)","+1","-2","@cmd","  =1","\ttext","\r\n=1","\ufeff=1","\"quoted,\"\n=1"}) {
            fixtures.update("UPDATE inbox_messages SET event_type=?",vector);
            var response=request("GET","messages/export","",TOKEN);assertThat(response.statusCode()).isEqualTo(200);
            String expected=(vector.startsWith("\"")?"":"'")+vector.replace("\"","\"\"");
            assertThat(response.body()).contains("\""+expected+"\"");
        }
    }

}
