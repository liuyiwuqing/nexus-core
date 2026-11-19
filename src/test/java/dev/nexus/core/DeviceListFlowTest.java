package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * Server-side device filtering and paging through public HTTP with isolated PostgreSQL.
 *
 * <p>The counts the list reports must describe the whole table, not the returned page; the console
 * header and the shell device picker rely on that distinction.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceListFlowTest {
    static final String TOKEN="device-list-test-token-at-least-32";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core device-list-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"device-list-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String method,String path,String body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type","application/json").header("Authorization","Bearer "+TOKEN);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode list(String query)throws Exception {
        var response=request("GET","devices"+query,"");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    void register(String id)throws Exception {
        var response=request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
    }
    List<String> ids(JsonNode value)throws Exception {
        var result=new java.util.ArrayList<String>();
        for(var row:value.path("items"))result.add(row.path("deviceId").asText());
        return result;
    }
    Map<String,Integer> counts(JsonNode value)throws Exception {
        var result=new LinkedHashMap<String,Integer>();
        for(String key:List.of("online","offline","disabled"))result.put(key,value.path("counts").path(key).asInt());
        return result;
    }
    Map<String,Integer> waiting(JsonNode value)throws Exception {
        var result=new LinkedHashMap<String,Integer>();
        for(var row:value.path("items"))result.put(row.path("deviceId").asText(),row.path("waitingDeliveries").asInt());
        return result;
    }
    UUID message(String upstream) {
        var id=UUID.randomUUID();
        fixtures.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,summary,sender,original_url,priority,created_at,expires_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,now(),now()+interval '1 day')
            """,id,"list-connector","rss","upstream-"+upstream,"message","标题","正文","摘要","发送者","https://example.invalid/item","normal");
        return id;
    }
    void deliver(String deviceId,String status) {
        fixtures.update("INSERT INTO deliveries(event_id,message_id,device_id,status) VALUES(?,?,?,?)",
            UUID.randomUUID(),message(deviceId+"-"+status),deviceId,status);
    }

    @BeforeEach void prepare()throws Exception {
        fixtures.execute("TRUNCATE devices CASCADE");
        for(String id:List.of("dev-a","dev-b","dev-c"))register(id);
        // a: 在线；b: 启用但离线；c: 已停用（存储的在线标记仍是 true，必须归到停用而不是离线）。
        fixtures.update("UPDATE devices SET online=true,last_seen=now() WHERE device_id IN ('dev-a','dev-c')");
        fixtures.update("UPDATE devices SET online=false,last_seen=NULL WHERE device_id='dev-b'");
        fixtures.update("UPDATE devices SET enabled=false WHERE device_id='dev-c'");
    }

    @Test void defaultRequestKeepsTheLegacyShapeAndCountsEveryState()throws Exception {
        var value=list("");
        assertThat(ids(value)).containsExactly("dev-a","dev-b","dev-c");
        assertThat(value.path("total").asLong()).isEqualTo(3);
        assertThat(value.path("matched").asLong()).isEqualTo(3);
        assertThat(value.path("page").asInt()).isEqualTo(1);
        assertThat(value.path("size").asInt()).isEqualTo(100);
        assertThat(value.path("state").asText()).isEqualTo("all");
        assertThat(value.path("q").asText()).isEmpty();
        assertThat(value.path("sort").asText()).isEqualTo("created");
        assertThat(value.path("order").asText()).isEqualTo("asc");
        assertThat(counts(value)).containsExactly(Map.entry("online",2),Map.entry("offline",1),Map.entry("disabled",1));
    }

    @Test void stateFiltersMatchTheCountsAndIgnoreStaleOnlineFlags()throws Exception {
        var online=list("?state=online");
        assertThat(ids(online)).containsExactly("dev-a","dev-c");
        assertThat(online.path("matched").asLong()).isEqualTo(2);
        assertThat(online.path("total").asLong()).isEqualTo(3);
        var offline=list("?state=offline");
        assertThat(ids(offline)).containsExactly("dev-b");
        assertThat(offline.path("matched").asLong()).isEqualTo(1);
        var disabled=list("?state=disabled");
        assertThat(ids(disabled)).containsExactly("dev-c");
        assertThat(disabled.path("matched").asLong()).isEqualTo(1);
        // 在线标记没有新鲜度就是离线：90 秒外的心跳不能把设备留在在线筛选里。
        fixtures.update("UPDATE devices SET online=true,last_seen=now()-interval '2 minutes' WHERE device_id='dev-b'");
        assertThat(ids(list("?state=online"))).containsExactly("dev-a","dev-c");
        assertThat(ids(list("?state=offline"))).containsExactly("dev-b");
    }

    @Test void pagingSplitsTheFilteredSetWhileCountsStayGlobal()throws Exception {
        register("dev-d");register("dev-e");
        fixtures.update("UPDATE devices SET enabled=true,online=false,last_seen=NULL WHERE device_id IN ('dev-d','dev-e')");
        fixtures.update("UPDATE devices SET created_at=now()+interval '1 second' WHERE device_id='dev-a'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '2 seconds' WHERE device_id='dev-b'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '3 seconds' WHERE device_id='dev-c'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '4 seconds' WHERE device_id='dev-d'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '5 seconds' WHERE device_id='dev-e'");
        var second=list("?page=2&size=2");
        assertThat(ids(second)).containsExactly("dev-c","dev-d");
        assertThat(second.path("total").asLong()).isEqualTo(5);
        assertThat(second.path("matched").asLong()).isEqualTo(5);
        assertThat(second.path("page").asInt()).isEqualTo(2);
        var last=list("?page=3&size=2");
        assertThat(ids(last)).containsExactly("dev-e");
        assertThat(counts(last)).containsExactly(Map.entry("online",2),Map.entry("offline",3),Map.entry("disabled",1));
        // 筛选后的分页：停用只有一条，第二页为空但计数不跟着页面清零。
        var disabled=list("?state=disabled&page=2&size=2");
        assertThat(ids(disabled)).isEmpty();
        assertThat(disabled.path("matched").asLong()).isEqualTo(1);
        assertThat(disabled.path("total").asLong()).isEqualTo(5);
        assertThat(request("GET","devices?state=unknown","").statusCode()).isEqualTo(400);
        assertThat(request("GET","devices?size=0","").statusCode()).isEqualTo(400);
    }

    @Test void searchMatchesNameAndIdLiterallyAndCombinesWithState()throws Exception {
        fixtures.update("UPDATE devices SET name='客厅在线' WHERE device_id='dev-a'");
        fixtures.update("UPDATE devices SET name='客厅离线' WHERE device_id='dev-b'");
        fixtures.update("UPDATE devices SET name='厨房停用' WHERE device_id='dev-c'");
        var living=list("?q="+URLEncoder.encode("客厅",StandardCharsets.UTF_8));
        assertThat(ids(living)).containsExactly("dev-a","dev-b");
        assertThat(living.path("matched").asLong()).isEqualTo(2);
        assertThat(living.path("total").asLong()).isEqualTo(3);
        // 三类计数按搜索结果统计，不是整表；这样页头与状态筛选不会各说各话。
        assertThat(counts(living)).containsExactly(Map.entry("online",1),Map.entry("offline",1),Map.entry("disabled",0));
        var online=list("?q="+URLEncoder.encode("客厅",StandardCharsets.UTF_8)+"&state=online");
        assertThat(ids(online)).containsExactly("dev-a");
        assertThat(online.path("matched").asLong()).isEqualTo(1);
        assertThat(counts(online)).containsExactly(Map.entry("online",1),Map.entry("offline",1),Map.entry("disabled",0));
        // 设备 ID 也参与搜索，大小写不敏感。
        assertThat(ids(list("?q=DEV-B"))).containsExactly("dev-b");
        // % 与 _ 按字面量匹配，不是 SQL 通配符。
        fixtures.update("UPDATE devices SET name='50%_off' WHERE device_id='dev-a'");
        fixtures.update("UPDATE devices SET name='50Xoff' WHERE device_id='dev-b'");
        assertThat(ids(list("?q=%25"))).containsExactly("dev-a");
        assertThat(ids(list("?q=_o"))).containsExactly("dev-a");
        // 纯空白搜索等同于不搜索。
        assertThat(ids(list("?q=%20%20"))).containsExactly("dev-a","dev-b","dev-c");
    }

    @Test void sortAndSizeChoicesAreAppliedServerSideAndUnknownValuesRejected()throws Exception {
        fixtures.update("UPDATE devices SET name='Beta' WHERE device_id='dev-a'");
        fixtures.update("UPDATE devices SET name='alpha' WHERE device_id='dev-b'");
        fixtures.update("UPDATE devices SET name='Gamma' WHERE device_id='dev-c'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '1 second' WHERE device_id='dev-a'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '2 seconds' WHERE device_id='dev-b'");
        fixtures.update("UPDATE devices SET created_at=now()+interval '3 seconds' WHERE device_id='dev-c'");
        assertThat(ids(list("?sort=name&order=asc"))).containsExactly("dev-b","dev-a","dev-c");
        assertThat(ids(list("?sort=name&order=desc"))).containsExactly("dev-c","dev-a","dev-b");
        assertThat(ids(list("?sort=created&order=desc"))).containsExactly("dev-c","dev-b","dev-a");
        // 最近连接：按时间排序，从未连接过的设备在升序和降序里都排最后。
        fixtures.update("UPDATE devices SET last_seen=now()-interval '1 hour' WHERE device_id='dev-c'");
        assertThat(ids(list("?sort=last_seen&order=desc"))).containsExactly("dev-a","dev-c","dev-b");
        assertThat(ids(list("?sort=last_seen&order=asc"))).containsExactly("dev-c","dev-a","dev-b");
        var second=list("?page=2&size=1&sort=created&order=desc");
        assertThat(ids(second)).containsExactly("dev-b");
        assertThat(second.path("size").asInt()).isEqualTo(1);
        assertThat(second.path("sort").asText()).isEqualTo("created");
        assertThat(second.path("order").asText()).isEqualTo("desc");
        assertThat(request("GET","devices?sort=signal","").statusCode()).isEqualTo(400);
        assertThat(request("GET","devices?order=sideways","").statusCode()).isEqualTo(400);
        assertThat(request("GET","devices?size=101","").statusCode()).isEqualTo(400);
        assertThat(request("GET","devices?q="+"x".repeat(81),"").statusCode()).isEqualTo(400);
    }

    @Test void waitingDeliveriesCountOnlyUnfinishedRows()throws Exception {
        deliver("dev-a","pending");
        deliver("dev-a","sent");
        for(String status:List.of("received","displayed","read","failed","expired"))deliver("dev-b",status);
        assertThat(waiting(list(""))).containsExactly(
            Map.entry("dev-a",2),Map.entry("dev-b",0),Map.entry("dev-c",0));
        // 状态筛选和分页不改变每台设备自己的未接收数。
        assertThat(waiting(list("?state=disabled"))).containsExactly(Map.entry("dev-c",0));
        assertThat(waiting(list("?page=2&size=1"))).containsExactly(Map.entry("dev-b",0));
    }
}
