package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexus.core.web.OverviewController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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

/** Counts through the public HTTP boundary, with isolated database/Broker fixtures. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleOverviewFlowTest {
    static final String TOKEN="overview-test-token-at-least-32-characters";
    /** 非 UTC 时区才能证明“今日”真的跟着配置走，而不是把 UTC 写死在 SQL 里。 */
    static final String ZONE="Asia/Shanghai";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core overview-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"overview-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");
        p.add("nexus.overview.timezone",()->ZONE);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();

    HttpResponse<String> request(String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview")).timeout(Duration.ofSeconds(5));
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode overview()throws Exception {
        var response=request(TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    /** 传输连接是异步建立的：等它就绪再做告警断言，不把启动竞争写成告警逻辑的问题。 */
    void awaitReady()throws Exception {
        for (int i=0;i<100;i++) {
            if (overview().path("mqtt").asText().equals("ready")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("MQTT transport never reported ready");
    }
    @BeforeEach void prepare() {
        fixtures.execute("TRUNCATE inbox_messages,devices CASCADE");
        // 阈值单行表跨用例共享：每个用例都从迁移默认值开始，避免执行顺序影响告警断言。
        fixtures.update("""
            UPDATE alert_thresholds SET failure_rate=0.1,failure_rate_min_sample=20,backlog_pending=100,
                online_min=1,version=1,updated_by='system',updated_at=now()
            """);
        fixtures.execute("DELETE FROM alert_threshold_audit");
    }

    @Test void overviewTimezoneDefaultsToUtcAndRejectsUnknownZones() {
        // 配置非法时启动失败，而不是静默把“今日”换成别的日期。
        assertThat(OverviewController.resolveZone(null).getId()).isEqualTo("UTC");
        assertThat(OverviewController.resolveZone("  ").getId()).isEqualTo("UTC");
        assertThat(OverviewController.resolveZone("Asia/Shanghai").getId()).isEqualTo(ZONE);
        assertThatThrownBy(()->OverviewController.resolveZone("Mars/Olympus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nexus.overview.timezone");
    }

    @Test void emptyOverviewPreservesUnknownRateAndRequiresAdmin()throws Exception {
        assertThat(request(null).statusCode()).isEqualTo(401);
        awaitReady();
        var result=overview();
        assertThat(result.path("timezone").asText()).isEqualTo(ZONE);
        assertThat(result.path("devices").path("total").asInt()).isZero();
        assertThat(result.path("today").path("messages").asInt()).isZero();
        assertThat(result.path("today").path("deliveryFailureRate").isNull()).isTrue();
        assertThat(result.path("backlog").path("pending").asInt()).isZero();
        assertThat(result.path("backlog").path("sent").asInt()).isZero();
        // 没有数据就是没有告警，不返回"健康"占位；阈值始终随快照返回。
        assertThat(codes(result)).isEmpty();
        assertThat(result.path("thresholds").path("failureRate").asDouble()).isEqualTo(0.1);
        assertThat(result.path("thresholds").path("failureRateMinSample").asInt()).isEqualTo(20);
        assertThat(result.path("thresholds").path("backlogPending").asInt()).isEqualTo(100);
        assertThat(result.path("thresholds").path("onlineMin").asInt()).isEqualTo(1);
        assertThat(result.path("failureReasons").isEmpty()).isTrue();
    }

    @Test void failureReasonsSumToTodaysFailedDeliveriesAndExcludeTestMessages()throws Exception {
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('live','在线',true,true,now())");
        String one=createMessage("超时一"),two=createMessage("超时二"),broker=createMessage("Broker 不可用"),
            hidden=createMessage("测试消息"),yesterday=createMessage("昨日失败");
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE message_id IN (?,?)",
            UUID.fromString(one),UUID.fromString(two));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='broker_unavailable' WHERE message_id=?",UUID.fromString(broker));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE message_id=?",UUID.fromString(hidden));
        fixtures.update("UPDATE inbox_messages SET metadata=metadata||'{\"test\":\"true\"}'::jsonb WHERE message_id=?",UUID.fromString(hidden));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE message_id=?",UUID.fromString(yesterday));
        fixtures.update("UPDATE inbox_messages SET received_at=date_trunc('day',now() AT TIME ZONE '" + ZONE
                + "') AT TIME ZONE '" + ZONE + "'-interval '1 second' WHERE message_id=?",
            UUID.fromString(yesterday));
        var result=overview();
        assertThat(result.path("today").path("failedDeliveries").asInt()).isEqualTo(3);
        var reasons=result.path("failureReasons");
        assertThat(reasons.size()).isEqualTo(2);
        // 计数多的在前，同数量按 code 升序，顺序稳定不随查询计划变化。
        assertThat(reasons.get(0).path("code").asText()).isEqualTo("ack_timeout");
        assertThat(reasons.get(0).path("count").asInt()).isEqualTo(2);
        assertThat(reasons.get(1).path("code").asText()).isEqualTo("broker_unavailable");
        // 折行合计必须等于今日失败数：两个数字由同一个 CTE 派生，不可能对不上。
        int sum=0;
        for (var row : reasons) sum+=row.path("count").asInt();
        assertThat(sum).isEqualTo(result.path("today").path("failedDeliveries").asInt());
    }

    @Test void alertsStayQuietWithoutEnabledDevicesAndReportAnOfflineFleetPlusBacklog()throws Exception {
        awaitReady();
        // 只有停用设备：没有启用设备不是"全部离线"。
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('disabled','停用',false,true,now())");
        assertThat(codes(overview())).isEmpty();
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('idle','离线',true,false,now())");
        var device=overview().path("alerts").get(0);
        assertThat(codes(overview())).containsExactly("devices_online");
        assertThat(device.path("level").asText()).isEqualTo("warn");
        // 比较方向随告警一起返回：消费方不需要再猜"value 与 threshold 谁大算命中"。
        assertThat(device.path("comparison").asText()).isEqualTo("lt");
        assertThat(device.path("value").asDouble()).isZero();
        assertThat(device.path("threshold").asDouble()).isEqualTo(1.0);
        assertThat(device.path("unit").asText()).isEqualTo("count");

        // 积压低于阈值不报，等于阈值就报（含等于，不是严格大于）。
        seedMessages(99); seedDeliveries("idle","pending",99);
        assertThat(overview().path("backlog").path("pending").asInt()).isEqualTo(99);
        assertThat(codes(overview())).containsExactly("devices_online");
        seedMessages(1); seedDeliveries("idle","pending",1);
        var backlog=overview();
        assertThat(backlog.path("backlog").path("pending").asInt()).isEqualTo(100);
        assertThat(codes(backlog)).containsExactly("devices_online","delivery_backlog");
        assertThat(backlog.path("alerts").get(1).path("value").asDouble()).isEqualTo(100.0);
        assertThat(backlog.path("alerts").get(1).path("comparison").asText()).isEqualTo("gte");
        // 钻取目标随告警下发：页面不需要自己维护"积压该跳到哪个筛选"的映射。
        assertThat(backlog.path("alerts").get(0).path("target").path("tab").asText()).isEqualTo("devices");
        // 设备告警落到"启用且离线"这一现存筛选，不是只打开页面。
        assertThat(backlog.path("alerts").get(0).path("target").path("status").asText()).isEqualTo("offline");
        assertThat(backlog.path("alerts").get(1).path("target").path("tab").asText()).isEqualTo("deliveries");
        assertThat(backlog.path("alerts").get(1).path("target").path("status").asText()).isEqualTo("pending");
    }

    @Test void failureRateAlertWaitsForTwentyDeliveriesAndTreatsTheThresholdAsInclusive()throws Exception {
        awaitReady();
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('live','在线',true,true,now())");
        // 19 条投递全失败：比例 100%，但样本不足 20，不判定。
        seedMessages(19); seedDeliveries("live","failed",19);
        var thin=overview();
        assertThat(thin.path("today").path("deliveryFailureRate").asDouble()).isEqualTo(1.0);
        assertThat(codes(thin)).isEmpty();

        seedMessages(1); seedDeliveries("live","failed",1);
        fixtures.update("UPDATE deliveries SET status='sent' WHERE event_id IN (SELECT event_id FROM deliveries ORDER BY event_id LIMIT 19)");
        var low=overview();
        assertThat(low.path("today").path("deliveries").asInt()).isEqualTo(20);
        assertThat(low.path("today").path("failedDeliveries").asInt()).isEqualTo(1);
        assertThat(low.path("today").path("deliveryFailureRate").asDouble()).isEqualTo(0.05);
        assertThat(codes(low)).isEmpty();

        // 再失败一条正好 2/20=10%：等于阈值也播。
        fixtures.update("UPDATE deliveries SET status='failed' WHERE event_id IN (SELECT event_id FROM deliveries WHERE status='sent' LIMIT 1)");
        var boundary=overview();
        assertThat(boundary.path("today").path("deliveryFailureRate").asDouble()).isEqualTo(0.1);
        assertThat(codes(boundary)).containsExactly("delivery_failure_rate");
        assertThat(boundary.path("alerts").get(0).path("value").asDouble()).isEqualTo(0.1);
        assertThat(boundary.path("alerts").get(0).path("threshold").asDouble()).isEqualTo(0.1);
        assertThat(boundary.path("alerts").get(0).path("unit").asText()).isEqualTo("ratio");
        assertThat(boundary.path("alerts").get(0).path("level").asText()).isEqualTo("warn");
        assertThat(boundary.path("alerts").get(0).path("target").path("status").asText()).isEqualTo("failed");
    }

    @Test void thresholdsAreReadableEditableAuditedAndVersioned()throws Exception {
        awaitReady();
        var read=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview/thresholds"))
            .header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        var current=json.readTree(read.body());
        assertThat(current.path("version").asInt()).isEqualTo(1);
        assertThat(current.path("thresholds").path("failureRate").asDouble()).isEqualTo(0.1);
        assertThat(current.path("thresholds").path("onlineMin").asInt()).isEqualTo(1);
        assertThat(current.path("history").size()).isZero();
        assertThat(current.path("historyTotal").asInt()).isZero();

        // 版本对不上不覆盖；越界值直接拒绝，概览仍读原阈值。
        assertThat(putThresholds(2,0.25,5,2,3,TOKEN).statusCode()).isEqualTo(409);
        assertThat(putThresholds(1,1.5,5,2,3,TOKEN).statusCode()).isEqualTo(400);
        assertThat(putThresholds(1,0.25,0,2,3,TOKEN).statusCode()).isEqualTo(400);
        assertThat(overview().path("thresholds").path("failureRate").asDouble()).isEqualTo(0.1);

        var updated=putThresholds(1,0.25,5,2,3,TOKEN);
        assertThat(updated.statusCode()).withFailMessage(updated.body()).isEqualTo(200);
        var saved=json.readTree(updated.body());
        assertThat(saved.path("version").asInt()).isEqualTo(2);
        assertThat(saved.path("thresholds").path("failureRate").asDouble()).isEqualTo(0.25);
        assertThat(saved.path("history").size()).isEqualTo(1);
        assertThat(saved.path("historyTotal").asInt()).isEqualTo(1);
        assertThat(saved.path("history").get(0).path("fromVersion").asInt()).isEqualTo(1);
        assertThat(saved.path("history").get(0).path("toVersion").asInt()).isEqualTo(2);
        assertThat(saved.path("history").get(0).path("onlineMin").asInt()).isEqualTo(3);
        // 审计记录的是令牌派生身份，不是明文令牌。
        assertThat(saved.path("updatedBy").asText()).hasSize(64);
        assertThat(saved.path("history").get(0).path("actor").asText()).isEqualTo(saved.path("updatedBy").asText());

        // 概览立即采用新阈值：1 台启用离线 < 3、2 条积压 ≥ 2、2/7 失败 ≥ 25%（样本 7 ≥ 5）。
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('idle','离线',true,false,now())");
        seedMessages(2); seedDeliveries("idle","pending",2);
        seedMessages(5); seedDeliveries("idle","failed",2); seedDeliveries("idle","sent",3);
        var judged=overview();
        assertThat(judged.path("thresholds").path("onlineMin").asInt()).isEqualTo(3);
        assertThat(codes(judged)).containsExactly("devices_online","delivery_backlog","delivery_failure_rate");
        assertThat(judged.path("alerts").get(1).path("threshold").asDouble()).isEqualTo(2);
        assertThat(judged.path("alerts").get(2).path("threshold").asDouble()).isEqualTo(0.25);

        // 旧版本再提交一次：仍然 409，不会把 v2 覆盖回 v1 的内容。
        assertThat(putThresholds(1,0.4,5,2,3,TOKEN).statusCode()).isEqualTo(409);
        assertThat(overview().path("thresholds").path("failureRate").asDouble()).isEqualTo(0.25);

        // operator 能读不能写：写路径由 RoleTokens 在控制器之前拦下。
        var issued=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/access/tokens"))
            .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("name","operator-thresholds",
                "role","operator","expiresAt",Instant.now().plusSeconds(3600).toString()))))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertThat(issued.statusCode()).withFailMessage(issued.body()).isEqualTo(200);
        String operator=json.readTree(issued.body()).path("token").asText();
        assertThat(putThresholds(2,0.3,5,2,3,operator).statusCode()).isEqualTo(403);
        assertThat(overview().path("thresholds").path("failureRate").asDouble()).isEqualTo(0.25);
    }

    @Test void thresholdAuditPagesBackwardsWithoutDuplicates()throws Exception {
        awaitReady();
        for(int i=1;i<=25;i++)
            fixtures.update("""
                INSERT INTO alert_threshold_audit(actor,from_version,to_version,failure_rate,
                    failure_rate_min_sample,backlog_pending,online_min)
                VALUES(?,?,?,?,?,?,?)
                ""","tester",i-1,i,0.1,20,100,1);

        var first=json.readTree(thresholdAudit("",TOKEN).body());
        assertThat(first.path("total").asInt()).isEqualTo(25);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("items").get(0).path("toVersion").asInt()).isEqualTo(25);
        assertThat(first.path("items").get(19).path("toVersion").asInt()).isEqualTo(6);

        // 用已加载最旧一行的 sequence 作游标，只取更早的 5 条，不重复。
        long oldest=first.path("items").get(19).path("sequence").asLong();
        var older=json.readTree(thresholdAudit("?before="+oldest,TOKEN).body());
        assertThat(older.path("total").asInt()).isEqualTo(25);
        assertThat(older.path("hasMore").asBoolean()).isFalse();
        assertThat(older.path("items").size()).isEqualTo(5);
        assertThat(older.path("items").get(0).path("toVersion").asInt()).isEqualTo(5);
        assertThat(older.path("items").get(4).path("toVersion").asInt()).isEqualTo(1);

        // size 与游标组合：边界前一行开始，hasMore 仍为真。
        long cursor20=first.path("items").get(5).path("sequence").asLong();
        var sized=json.readTree(thresholdAudit("?before="+cursor20+"&size=5",TOKEN).body());
        assertThat(sized.path("items").size()).isEqualTo(5);
        assertThat(sized.path("items").get(0).path("toVersion").asInt()).isEqualTo(19);
        assertThat(sized.path("items").get(4).path("toVersion").asInt()).isEqualTo(15);
        assertThat(sized.path("hasMore").asBoolean()).isTrue();

        var none=json.readTree(thresholdAudit("?before=1",TOKEN).body());
        assertThat(none.path("items").size()).isZero();
        assertThat(none.path("hasMore").asBoolean()).isFalse();

        // 当前阈值读取同时给出真实总数与最新 20 条。
        var read=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview/thresholds"))
            .header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        var current=json.readTree(read.body());
        assertThat(current.path("historyTotal").asInt()).isEqualTo(25);
        assertThat(current.path("history").size()).isEqualTo(20);

        assertThat(thresholdAudit("",null).statusCode()).isEqualTo(401);
        assertThat(thresholdAudit("?before=0",TOKEN).statusCode()).isEqualTo(400);
        assertThat(thresholdAudit("?size=0",TOKEN).statusCode()).isEqualTo(400);
        assertThat(thresholdAudit("?size=101",TOKEN).statusCode()).isEqualTo(400);
    }

    HttpResponse<String> thresholdAudit(String query,String token)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview/thresholds/audit"+query))
            .timeout(Duration.ofSeconds(5));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> putThresholds(int version,double rate,int minSample,int pending,int onlineMin,String token)throws Exception {
        var body=json.writeValueAsString(Map.of("version",version,"thresholds",Map.of("failureRate",rate,
            "failureRateMinSample",minSample,"backlogPending",pending,"onlineMin",onlineMin)));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview/thresholds"))
            .header("Content-Type","application/json").timeout(Duration.ofSeconds(5));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void snapshotCountsUseReceiptDayAndTheExistingOnlineThreshold()throws Exception {
        fixtures.update("""
            INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES
            ('live','在线',true,true,now()),('stale','超时',true,true,now()-interval '95 seconds'),
            ('reported-offline','离线',true,false,now()),('unknown','未连接',true,true,NULL),
            ('disabled','停用',false,true,now())
            """);
        var zero=overview();
        assertThat(zero.path("devices").path("total").asInt()).isEqualTo(5);
        assertThat(zero.path("devices").path("online").asInt()).isEqualTo(2);
        assertThat(zero.path("devices").path("offline").asInt()).isEqualTo(3);
        assertThat(zero.path("devices").path("disabled").asInt()).isEqualTo(1);
        String today=createMessage("旧来源时间仍在今日收件"),old=createMessage("昨日收件"),future=createMessage("未来收件");
        fixtures.update("UPDATE inbox_messages SET created_at=now()-interval '3 days' WHERE message_id=?",UUID.fromString(today));
        fixtures.update("UPDATE inbox_messages SET received_at=date_trunc('day',now() AT TIME ZONE '" + ZONE
            + "') AT TIME ZONE '" + ZONE + "'-interval '1 second' WHERE message_id=?",UUID.fromString(old));
        fixtures.update("UPDATE inbox_messages SET received_at=now()+interval '1 day' WHERE message_id=?",UUID.fromString(future));
        fixtures.update("UPDATE deliveries SET status='expired'");
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id=? AND device_id='live'",UUID.fromString(today));
        fixtures.update("UPDATE deliveries SET status='pending' WHERE message_id=? AND device_id='stale'",UUID.fromString(today));
        fixtures.update("UPDATE deliveries SET status='sent' WHERE message_id=? AND device_id='reported-offline'",UUID.fromString(old));
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id=? AND device_id='unknown'",UUID.fromString(old));
        var result=overview();
        assertThat(result.path("today").path("messages").asInt()).isEqualTo(1);
        assertThat(result.path("today").path("deliveries").asInt()).isEqualTo(4);
        assertThat(result.path("today").path("failedDeliveries").asInt()).isEqualTo(1);
        assertThat(result.path("today").path("deliveryFailureRate").asDouble()).isEqualTo(0.25);
        assertThat(result.path("failedDeliveries").asInt()).isEqualTo(2);
        assertThat(result.path("backlog").path("pending").asInt()).isEqualTo(1);
        assertThat(result.path("backlog").path("sent").asInt()).isEqualTo(1);
        Instant asOf=Instant.parse(result.path("asOf").asText()),start=Instant.parse(result.path("dayStart").asText());
        assertThat(start).isEqualTo(asOf.atZone(ZoneId.of(ZONE)).toLocalDate().atStartOfDay(ZoneId.of(ZONE)).toInstant());
        // A second read never changes the delivery/message state or manufactures an acknowledgment.
        overview();
        var deliveries=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/deliveries?status=pending"))
            .header("Authorization","Bearer "+TOKEN).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(deliveries.body()).path("total").asInt()).isEqualTo(1);
    }

    @Test void trendBuildsFixedBucketsAndCountsFailureEvents()throws Exception {
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('live','在线',true,true,now())");
        String recent=createMessage("近 30 分钟"),ninety=createMessage("90 分钟前"),three=createMessage("三小时前"),
            hidden=createMessage("测试消息"),future=createMessage("未来收件");
        fixtures.update("UPDATE inbox_messages SET received_at=now()-interval '30 minutes' WHERE message_id=?",UUID.fromString(recent));
        fixtures.update("UPDATE inbox_messages SET received_at=now()-interval '90 minutes' WHERE message_id=?",UUID.fromString(ninety));
        fixtures.update("UPDATE inbox_messages SET received_at=now()-interval '3 hours' WHERE message_id=?",UUID.fromString(three));
        fixtures.update("UPDATE inbox_messages SET metadata=metadata||'{\"test\":\"true\"}'::jsonb WHERE message_id=?",UUID.fromString(hidden));
        fixtures.update("UPDATE inbox_messages SET received_at=now()+interval '1 hour' WHERE message_id=?",UUID.fromString(future));
        // 失败由投递历史触发器记录，所以归入事件发生的桶，而不是消息收件的桶。
        fixtures.update("UPDATE deliveries SET status='failed' WHERE message_id IN (?,?)",UUID.fromString(recent),UUID.fromString(ninety));

        var trend=trend("hours=6&bucket=1h");
        assertThat(trend.path("points").size()).isEqualTo(6);
        assertThat(trend.path("bucketSeconds").asInt()).isEqualTo(3600);
        assertThat(trend.path("totals").path("messages").asInt()).isEqualTo(3);
        assertThat(trend.path("totals").path("deliveries").asInt()).isEqualTo(3);
        assertThat(trend.path("totals").path("failures").asInt()).isEqualTo(2);
        // 三小时前的收件固定落在倒数第 3 个桶，不受执行分钟的边界影响。
        assertThat(trend.path("points").get(2).path("messages").asInt()).isEqualTo(1);
        assertThat(trend.path("points").get(5).path("partial").asBoolean()).isTrue();
        assertThat(trend.path("points").get(0).path("partial").asBoolean()).isFalse();
        // 两次失败都发生在当前桶；跨小时边界的极小概率由相邻桶合计覆盖。
        assertThat(trend.path("points").get(5).path("failures").asInt()
            +trend.path("points").get(4).path("failures").asInt()).isEqualTo(2);
        assertThat(Instant.parse(trend.path("from").asText())).isBefore(Instant.parse(trend.path("to").asText()));
        assertThat(trend.path("to").asText()).isEqualTo(trend.path("asOf").asText());
    }

    @Test void trendSplitsFailureEventsByReasonAndNarrowsTheSeries()throws Exception {
        fixtures.update("INSERT INTO devices(device_id,name,enabled,online,last_seen) VALUES ('reason-device','原因设备',true,true,now())");
        String timeout=createMessage("确认超时"),busy=createMessage("设备忙"),unrecorded=createMessage("未记录原因");
        fixtures.update("UPDATE deliveries SET status='failed',last_error='ack_timeout' WHERE message_id=?",UUID.fromString(timeout));
        fixtures.update("UPDATE deliveries SET status='failed',last_error='busy' WHERE message_id=?",UUID.fromString(busy));
        fixtures.update("UPDATE deliveries SET status='failed',last_error=NULL WHERE message_id=?",UUID.fromString(unrecorded));

        var all=trend("hours=6&bucket=1h");
        assertThat(all.path("reason").isNull()).isTrue();
        assertThat(all.path("totals").path("failures").asInt()).isEqualTo(3);
        assertThat(all.path("totals").path("allFailures").asInt()).isEqualTo(3);
        var reasons=all.path("failureReasons");
        assertThat(reasons.size()).isEqualTo(3);
        // 数量相同时按 code 排序；unknown 覆盖没有 last_error 的旧行。
        assertThat(reasons.get(0).path("code").asText()).isEqualTo("ack_timeout");
        assertThat(reasons.get(1).path("code").asText()).isEqualTo("busy");
        assertThat(reasons.get(2).path("code").asText()).isEqualTo("unknown");
        long reasonSum=0;
        for(var item:reasons)reasonSum+=item.path("count").asInt();
        assertThat(reasonSum).isEqualTo(all.path("totals").path("allFailures").asInt());
        assertThat(all.path("totals").path("messages").asInt()).isEqualTo(3);

        var busyOnly=trend("hours=6&bucket=1h&reason=busy");
        assertThat(busyOnly.path("reason").asText()).isEqualTo("busy");
        assertThat(busyOnly.path("totals").path("failures").asInt()).isEqualTo(1);
        assertThat(busyOnly.path("totals").path("allFailures").asInt()).isEqualTo(3);
        assertThat(busyOnly.path("failureReasons").size()).isEqualTo(3);
        long busyPoints=0;
        for(var point:busyOnly.path("points"))busyPoints+=point.path("failures").asInt();
        assertThat(busyPoints).isEqualTo(1);

        // 未知原因不是错误，只得到空失败序列；折行仍保留整个窗口。
        var missing=trend("hours=6&bucket=1h&reason=expired");
        assertThat(missing.path("totals").path("failures").asInt()).isZero();
        assertThat(missing.path("totals").path("allFailures").asInt()).isEqualTo(3);
        assertThat(missing.path("failureReasons").size()).isEqualTo(3);
        assertThat(missing.path("totals").path("messages").asInt()).isEqualTo(3);

        assertThat(trendRequest("hours=6&bucket=1h&reason=").statusCode()).isEqualTo(400);
        assertThat(trendRequest("hours=6&bucket=1h&reason=%20").statusCode()).isEqualTo(400);
        assertThat(trendRequest("hours=6&bucket=1h&reason="+"x".repeat(65)).statusCode()).isEqualTo(400);
    }

    @Test void trendRejectsUnboundedWindowsAndKeepsAGridWhenEmpty()throws Exception {
        assertThat(trendRequest("hours=0&bucket=1h").statusCode()).isEqualTo(400);
        assertThat(trendRequest("hours=24&bucket=7m").statusCode()).isEqualTo(400);
        // 168 小时 / 15 分钟 = 672 桶，超过单次读取上限。
        assertThat(trendRequest("hours=168&bucket=15m").statusCode()).isEqualTo(400);
        assertThat(trendRequest("hours=24&bucket=1h", null).statusCode()).isEqualTo(401);
        var empty=trend("hours=24&bucket=1h");
        assertThat(empty.path("points").size()).isEqualTo(24);
        assertThat(empty.path("totals").path("messages").asInt()).isZero();
        assertThat(empty.path("totals").path("deliveries").asInt()).isZero();
        assertThat(empty.path("totals").path("failures").asInt()).isZero();
        assertThat(empty.path("totals").path("allFailures").asInt()).isZero();
        assertThat(empty.path("failureReasons").size()).isZero();
        assertThat(empty.path("reason").isNull()).isTrue();
        assertThat(empty.path("points").get(23).path("partial").asBoolean()).isTrue();
    }

    HttpResponse<String> trendRequest(String query)throws Exception { return trendRequest(query,TOKEN); }
    HttpResponse<String> trendRequest(String query,String token)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/overview/trend?"+query)).timeout(Duration.ofSeconds(5));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode trend(String query)throws Exception {
        var response=trendRequest(query);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    /** 告警 code 的顺序由服务端固定，测试直接比对顺序而不只是集合。 */
    static List<String> codes(JsonNode overview) {
        List<String> codes=new ArrayList<>();
        overview.path("alerts").forEach(alert->codes.add(alert.path("code").asText()));
        return codes;
    }

    /** 绕过 HTTP 直接造今日收件：凑 100 条积压不值得建 100 台设备。 */
    void seedMessages(int count) {
        fixtures.update("""
            INSERT INTO inbox_messages(message_id,connector_id,source,upstream_event_id,event_type,title,content,
                summary,sender,original_url,priority,created_at,expires_at)
            SELECT gen_random_uuid(),'overview','manual','bulk-'||gen_random_uuid(),'manual.test','bulk','bulk',
                'bulk','fixture','', 'normal',now(),now()+interval '1 day'
            FROM generate_series(1,?)
            """,count);
    }

    /** 给还没有投递的收件补一条指定状态的投递；重复调用只补新增的那些。 */
    void seedDeliveries(String deviceId,String status,int count) {
        fixtures.update("""
            INSERT INTO deliveries(event_id,message_id,device_id,status)
            SELECT gen_random_uuid(),pending.message_id,?,? FROM (
                SELECT m.message_id FROM inbox_messages m
                WHERE m.connector_id='overview'
                    AND NOT EXISTS (SELECT 1 FROM deliveries d WHERE d.message_id=m.message_id)
                LIMIT ?
            ) pending
            """,deviceId,status,count);
    }

    String createMessage(String title)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/messages"))
            .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("connectorId","overview","source","manual",
                "eventId",UUID.randomUUID().toString(),"eventType","manual.test","title",title,"content",title)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("messageId").asText();
    }
}
