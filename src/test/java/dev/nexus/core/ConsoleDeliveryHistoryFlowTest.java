package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexus.core.delivery.DeliveryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

/** Public HTTP, real worker entry points and isolated SQL migration/transaction boundaries. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleDeliveryHistoryFlowTest {
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
    @Autowired DeliveryService worker;
    @Autowired PlatformTransactionManager transactions;
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
    String message()throws Exception {
        var response=request("POST","messages",json.writeValueAsString(Map.of("connectorId","history","source","manual",
            "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title","历史检查","content","隔离检查")),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        String id=json.readTree(response.body()).path("messageId").asText();
        for(var row:get("deliveries?size=100").path("items"))
            if(row.path("messageId").asText().equals(id))return row.path("eventId").asText();
        throw new AssertionError("delivery missing");
    }
    JsonNode delivery(String id)throws Exception {
        for(var row:get("deliveries?size=100").path("items"))if(row.path("eventId").asText().equals(id))return row;
        throw new AssertionError("delivery missing");
    }
    @BeforeEach void prepare()throws Exception {
        fixtures.execute("TRUNCATE inbox_messages,devices CASCADE");
        assertThat(request("POST","devices","{\"deviceId\":\"history-device\",\"name\":\"历史检查\"}",TOKEN).statusCode()).isEqualTo(201);
    }

    @Test void unstartedDeliveryCanBeCancelledIdempotentlyWithPersistentHistory()throws Exception {
        String id=message();
        var created=get("deliveries/"+id+"/history");
        assertThat(created.path("includesCreation").asBoolean()).isTrue();
        assertThat(created.path("items").size()).isEqualTo(1);
        assertThat(created.path("items").get(0).path("kind").asText()).isEqualTo("created");
        assertThat(delivery(id).path("canCancel").asBoolean()).isTrue();
        assertThat(request("POST","deliveries/"+id+"/cancel","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("POST","deliveries/"+id+"/cancel","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(delivery(id).path("status").asText()).isEqualTo("cancelled");
        assertThat(delivery(id).path("canCancel").asBoolean()).isFalse();
        var history=get("deliveries/"+id+"/history");
        assertThat(history.path("total").asInt()).isEqualTo(2);
        assertThat(history.path("items").get(1).path("status").asText()).isEqualTo("cancelled");
        assertThat(history.path("items").get(1).path("lastError").asText()).isEqualTo("cancelled_by_admin");
        assertThat(request("POST","deliveries/"+id+"/retry","",TOKEN).statusCode()).isEqualTo(409);
        worker.acknowledge("history-device",json.valueToTree(Map.of("schemaVersion",1,"deviceId","history-device",
            "eventId",id,"messageId",delivery(id).path("messageId").asText(),"status","read")));
        assertThat(delivery(id).path("status").asText()).isEqualTo("cancelled");
        assertThat(get("deliveries/"+id+"/history").path("total").asInt()).isEqualTo(2);
        assertThat(get("deliveries?status=cancelled").path("total").asInt()).isEqualTo(1);
    }

    @Test void claimingPermanentlyBlocksCancelEvenWhenRetryResetsAttempts()throws Exception {
        String id=message();
        worker.claim();worker.publishFailed(UUID.fromString(id));
        assertThat(request("POST","deliveries/"+id+"/cancel","",TOKEN).statusCode()).isEqualTo(409);
        fixtures.update("UPDATE deliveries SET next_attempt_at=now()-interval '1 second' WHERE event_id=?",UUID.fromString(id));
        worker.expire();
        assertThat(delivery(id).path("status").asText()).isEqualTo("failed");
        assertThat(request("POST","deliveries/"+id+"/retry","",TOKEN).statusCode()).isEqualTo(204);
        assertThat(delivery(id).path("attempts").asInt()).isZero();
        assertThat(delivery(id).path("canCancel").asBoolean()).isFalse();
        assertThat(request("POST","deliveries/"+id+"/cancel","",TOKEN).statusCode()).isEqualTo(409);
        var events=get("deliveries/"+id+"/history?size=100");
        var kinds=new java.util.ArrayList<String>();
        for(var item:events.path("items"))kinds.add(item.path("kind").asText());
        assertThat(kinds).contains("attempt_reserved","detail_changed","state_changed","retry_requested");
        var second=get("deliveries/"+id+"/history?size=1&page=2");
        assertThat(second.path("total").asInt()).isEqualTo(events.path("total").asInt());
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("kind").asText()).isEqualTo("attempt_reserved");
        assertThat(get("deliveries/"+id+"/history?size=1&page=100").path("items").isEmpty()).isTrue();
    }

    @Test void allCommittedStatusChangesAreRecordedButRollbackAndDuplicateAcksAreNot()throws Exception {
        assertThat(request("POST","devices","{\"deviceId\":\"history-other\",\"name\":\"另一设备\"}",TOKEN).statusCode()).isEqualTo(201);
        String id=message();String message=delivery(id).path("messageId").asText();
        var rollback=new TransactionTemplate(transactions);
        rollback.executeWithoutResult(status->{
            fixtures.update("UPDATE deliveries SET status='failed',last_error='invalid' WHERE event_id=?",UUID.fromString(id));
            status.setRollbackOnly();
        });
        assertThat(get("deliveries/"+id+"/history").path("total").asInt()).isEqualTo(1);
        worker.claim();worker.published(UUID.fromString(id));
        for(String next:List.of("received","displayed","read"))
            worker.acknowledge(delivery(id).path("deviceId").asText(),json.valueToTree(Map.of("schemaVersion",1,"deviceId",delivery(id).path("deviceId").asText(),
                "eventId",id,"messageId",message,"status",next)));
        var before=get("deliveries/"+id+"/history");
        assertThat(before.path("total").asInt()).isEqualTo(6);
        assertThat(before.path("items").get(5).path("status").asText()).isEqualTo("read");
        worker.acknowledge(delivery(id).path("deviceId").asText(),json.valueToTree(Map.of("schemaVersion",1,"deviceId",delivery(id).path("deviceId").asText(),
            "eventId",id,"messageId",message,"status","received")));
        assertThat(get("deliveries/"+id+"/history").path("total").asInt()).isEqualTo(6);
        for(var other:get("deliveries").path("items"))if(!other.path("eventId").asText().equals(id)) {
            var history=get("deliveries/"+other.path("eventId").asText()+"/history");
            assertThat(history.path("items").get(history.path("items").size()-1).path("lastError").asText()).isEqualTo("read_elsewhere");
        }
        String disabled=message();
        assertThat(request("PUT","devices/"+delivery(disabled).path("deviceId").asText()+"/enabled","{\"enabled\":false}",TOKEN).statusCode()).isEqualTo(204);
        var history=get("deliveries/"+disabled+"/history");
        assertThat(history.path("items").get(history.path("items").size()-1).path("lastError").asText()).isEqualTo("device_disabled");
    }

    @Test void cancelAndClaimHaveOnlyOneWinner()throws Exception {
        for(int i=0;i<8;i++) {
            String id=message();var gate=new CountDownLatch(1);
            var claim=CompletableFuture.runAsync(()->{await(gate);worker.claim();});
            var cancel=CompletableFuture.supplyAsync(()->{
                await(gate);
                try{return request("POST","deliveries/"+id+"/cancel","",TOKEN).statusCode();}
                catch(Exception e){throw new RuntimeException(e);}
            });
            gate.countDown();claim.get(10,TimeUnit.SECONDS);int status=cancel.get(10,TimeUnit.SECONDS);
            var row=delivery(id);
            if(status==204) {
                assertThat(row.path("status").asText()).isEqualTo("cancelled");
                assertThat(row.path("attempts").asInt()).isZero();
            } else {
                assertThat(status).isEqualTo(409);
                assertThat(row.path("status").asText()).isEqualTo("pending");
                assertThat(row.path("attempts").asInt()).isGreaterThan(0);
            }
            assertThat(row.path("canCancel").asBoolean()).isFalse();
        }
    }
    static void await(CountDownLatch gate) {
        try{if(!gate.await(5,TimeUnit.SECONDS))throw new AssertionError("race barrier timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
    }

    @Test void historyAndCancelKeepAuthenticationValidationAndNotFoundResponses()throws Exception {
        String id=message();
        assertThat(request("GET","deliveries/"+id+"/history","",null).statusCode()).isEqualTo(401);
        assertThat(request("POST","deliveries/"+id+"/cancel","",null).statusCode()).isEqualTo(401);
        String missing=UUID.randomUUID().toString();
        assertThat(request("GET","deliveries/"+missing+"/history","",TOKEN).statusCode()).isEqualTo(404);
        assertThat(request("POST","deliveries/"+missing+"/cancel","",TOKEN).statusCode()).isEqualTo(404);
        for(String path:List.of("deliveries/"+id+"/history?page=0","deliveries/"+id+"/history?size=101","deliveries/not-a-uuid/history"))
            assertThat(request("GET",path,"",TOKEN).statusCode()).isEqualTo(400);
    }

    @Test void v12UpgradeStartsAtAnHonestBaselineAndBlocksUnknownDispatchHistory()throws Exception {
        String schema="legacy_delivery_history";
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas(schema).defaultSchema(schema).target("12").load().migrate();
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            connection.setSchema(schema);var legacy=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            legacy.execute("INSERT INTO devices(device_id,name) VALUES('old','旧设备')");
            UUID message=UUID.randomUUID(),event=UUID.randomUUID();
            legacy.update("""
                INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at)
                VALUES(?,'old','manual','old','manual.test','','','','','','normal',now()-interval '1 day',now()+interval '1 day')
                """,message);
            legacy.update("INSERT INTO deliveries(event_id,message_id,device_id) VALUES(?,?,'old')",event,message);
            Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
            var baseline=legacy.queryForMap("SELECT kind,previous_status,status,attempts FROM delivery_history WHERE event_id=?",event);
            assertThat(baseline.get("kind")).isEqualTo("baseline");
            assertThat(baseline.get("previous_status")).isNull();
            assertThat(baseline.get("status")).isEqualTo("pending");
            assertThat(baseline.get("attempts")).isEqualTo(0);
            assertThat(legacy.queryForObject("SELECT cancel_blocked FROM deliveries WHERE event_id=?",Boolean.class,event)).isTrue();
            legacy.update("UPDATE deliveries SET attempts=1 WHERE event_id=?",event);
            legacy.update("UPDATE deliveries SET attempts=0,cancel_blocked=false WHERE event_id=?",event);
            assertThat(legacy.queryForObject("SELECT cancel_blocked FROM deliveries WHERE event_id=?",Boolean.class,event)).isTrue();
        } finally { fixtures.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
}
