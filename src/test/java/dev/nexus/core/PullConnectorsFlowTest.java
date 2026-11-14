package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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

/** Pull ingestion through HTTP and local provider boundaries, using isolated PostgreSQL. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PullConnectorsFlowTest {
    static final java.util.concurrent.atomic.AtomicReference<String> FEED=new java.util.concurrent.atomic.AtomicReference<>("<rss version=\"2.0\"><channel><item><guid>old</guid><title>历史文章</title><description>旧正文</description></item></channel></rss>");
    static final java.util.concurrent.atomic.AtomicLong WEATHER_TIME=new java.util.concurrent.atomic.AtomicLong(InstantHolder.NOW-3600);
    static class InstantHolder {static final long NOW=java.time.Instant.now().getEpochSecond();}
    static final java.util.concurrent.atomic.AtomicReference<String> SECURITY=new java.util.concurrent.atomic.AtomicReference<>("<rss version=\"2.0\"><channel/></rss>");
    static final java.util.concurrent.atomic.AtomicBoolean REDIRECT=new java.util.concurrent.atomic.AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicInteger SECRETS=new java.util.concurrent.atomic.AtomicInteger();
    static volatile java.util.concurrent.CountDownLatch STARTED=new java.util.concurrent.CountDownLatch(0);
    static final java.util.concurrent.atomic.AtomicInteger DELAY=new java.util.concurrent.atomic.AtomicInteger();
    static final com.sun.net.httpserver.HttpServer PROVIDER=provider();
    static com.sun.net.httpserver.HttpServer provider(){try{var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/rss",e->{byte[] raw=FEED.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/rss+xml");e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.createContext("/weather",e->{byte[] raw=("{\"current\":{\"time\":"+WEATHER_TIME.get()+",\"temperature_2m\":18.5,\"relative_humidity_2m\":65,\"weather_code\":3},\"current_units\":{\"temperature_2m\":\"°C\",\"relative_humidity_2m\":\"%\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();});server.createContext("/security",e->{STARTED.countDown();try{Thread.sleep(DELAY.get());}catch(InterruptedException ignored){Thread.currentThread().interrupt();}if(REDIRECT.get()){e.getResponseHeaders().set("Location","http://127.0.0.1:"+e.getLocalAddress().getPort()+"/secret");e.sendResponseHeaders(302,-1);}else{byte[] raw=SECURITY.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/xml");e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);}e.close();});server.createContext("/secret",e->{SECRETS.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();return server;}catch(Exception failure){throw new IllegalStateException(failure);}}
    @org.junit.jupiter.api.AfterAll static void closeProvider(){PROVIDER.stop(0);}
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
        p.add("nexus.automation.tick-ms",()->"600000");
        p.add("nexus.pull.tick-ms",()->"600000");
        p.add("nexus.pull.allow-insecure",()->"true");
        p.add("nexus.pull.feeds-json",()->"[{\"id\":\"rss-one\",\"name\":\"测试订阅\",\"kind\":\"rss\",\"url\":\"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/rss\",\"intervalMinutes\":15,\"deviceIds\":[\"pull-pet\"]},{\"id\":\"weather-one\",\"name\":\"测试城市\",\"kind\":\"weather\",\"latitude\":31.2,\"longitude\":121.5,\"intervalMinutes\":60,\"deviceIds\":[\"weather-pet\"]},{\"id\":\"rss-security\",\"name\":\"安全测试\",\"kind\":\"rss\",\"url\":\"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/security\",\"intervalMinutes\":15,\"deviceIds\":[\"security-pet\"]}]");
        p.add("nexus.pull.weather-base-url",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/weather");
        p.add("nexus.delivery.max-attempts",()->"1");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixtures;
    final HttpClient http=HttpClient.newHttpClient();
    String configuration;

    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        if(configuration!=null&&path.startsWith("connectors/pull/")&&(method.equals("PUT")||path.endsWith("/poll"))){var input=json.readTree(body);((tools.jackson.databind.node.ObjectNode)input).put("configuration",configuration);body=json.writeValueAsString(input);}
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path)throws Exception {
        var response=request("GET",path,"",TOKEN);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    @Test void defaultDisabledProbeBaselineAndNewItemsRespectScopeAndDeduplication() throws Exception {
        var feeds=get("connectors/pull").path("items");assertThat(feeds).hasSize(3);configuration=feeds.get(0).path("configuration").asText();assertThat(feeds.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(request("POST","devices","{\"deviceId\":\"pull-pet\",\"name\":\"拉取设备\"}",TOKEN).statusCode()).isIn(200,201);
        assertThat(request("POST","devices","{\"deviceId\":\"other-pet\",\"name\":\"其他设备\"}",TOKEN).statusCode()).isIn(200,201);
        assertThat(request("POST","connectors/pull/rss-one/poll","{\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(409);
        var probe=request("POST","connectors/pull/rss-one/probe","{}",TOKEN);assertThat(probe.statusCode()).isEqualTo(200);assertThat(json.readTree(probe.body()).path("items").asInt()).isEqualTo(1);
        assertThat(get("messages?source=rss.rss-one").path("total").asInt()).isZero();
        assertThat(request("PUT","connectors/pull/rss-one/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(200);
        var first=request("POST","connectors/pull/rss-one/poll","{\"expectedVersion\":2}",TOKEN);assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(200);assertThat(json.readTree(first.body()).path("baseline").asBoolean()).isTrue();assertThat(json.readTree(first.body()).path("received").asInt()).isZero();
        FEED.set("<rss version=\"2.0\"><channel><item><guid>new</guid><title>新文章</title><description>&lt;b&gt;新正文&lt;/b&gt;</description><link>https://example.com/new</link></item><item><guid>old</guid><title>历史文章</title><description>旧正文</description></item></channel></rss>");
        due();var next=request("POST","connectors/pull/rss-one/poll","{\"expectedVersion\":2}",TOKEN);assertThat(next.statusCode()).withFailMessage(next.body()).isEqualTo(200);assertThat(json.readTree(next.body()).path("received").asInt()).isEqualTo(1);
        var messages=get("messages?source=rss.rss-one");assertThat(messages.path("total").asInt()).isEqualTo(1);assertThat(messages.path("items").get(0).path("content").asText()).isEqualTo("新正文");
        var deliveries=get("deliveries").path("items");assertThat(deliveries).hasSize(1);assertThat(deliveries.get(0).path("deviceId").asText()).isEqualTo("pull-pet");
        due();assertThat(json.readTree(request("POST","connectors/pull/rss-one/poll","{\"expectedVersion\":2}",TOKEN).body()).path("received").asInt()).isZero();assertThat(get("messages?source=rss.rss-one").path("total").asInt()).isEqualTo(1);
    }

    @Test void weatherIsOutdoorHourlyDeduplicatedAndNeverOverridesSensorData() throws Exception {
        var feeds=get("connectors/pull").path("items");configuration=feeds.get(1).path("configuration").asText();
        assertThat(request("POST","devices","{\"deviceId\":\"weather-pet\",\"name\":\"天气设备\"}",TOKEN).statusCode()).isIn(200,201);
        var probe=request("POST","connectors/pull/weather-one/probe","{}",TOKEN);assertThat(probe.statusCode()).withFailMessage(probe.body()).isEqualTo(200);assertThat(probe.body()).contains("室外天气","Open-Meteo");
        assertThat(request("PUT","connectors/pull/weather-one/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("POST","connectors/pull/weather-one/poll","{\"expectedVersion\":2}",TOKEN).body()).path("baseline").asBoolean()).isTrue();
        // Advance only isolated provider/database time fixtures; never wait an actual hour.
        fixtures.update("UPDATE pull_connector_state SET baseline_at=now()-interval '2 hours' WHERE connector_id='weather-one'");WEATHER_TIME.set(InstantHolder.NOW);due();
        var next=request("POST","connectors/pull/weather-one/poll","{\"expectedVersion\":2}",TOKEN);assertThat(next.statusCode()).withFailMessage(next.body()).isEqualTo(200);assertThat(json.readTree(next.body()).path("received").asInt()).isEqualTo(1);
        due();request("POST","connectors/pull/weather-one/poll","{\"expectedVersion\":2}",TOKEN);
        var messages=get("messages?source=weather.weather-one");assertThat(messages.path("total").asInt()).isEqualTo(1);assertThat(messages.path("items").get(0).path("eventType").asText()).isEqualTo("weather.updated");
        assertThat(messages.path("items").get(0).path("content").asText()).contains("室外天气","18.5","65.0","Open-Meteo");
        WEATHER_TIME.set(InstantHolder.NOW-172800);due();var stale=request("POST","connectors/pull/weather-one/poll","{\"expectedVersion\":2}",TOKEN);assertThat(stale.statusCode()).isEqualTo(502);assertThat(stale.body()).doesNotContain("127.0.0.1","latitude");
    }

    @Test void malformedXmlRedirectsAndConcurrentPullsCannotBypassControls() throws Exception {
        configuration=get("connectors/pull").path("items").get(2).path("configuration").asText();
        assertThat(request("POST","devices","{\"deviceId\":\"security-pet\",\"name\":\"测试设备\"}",TOKEN).statusCode()).isIn(200,201);
        assertThat(request("PUT","connectors/pull/rss-security/enabled","{\"enabled\":true,\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(200);
        String attack="<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/secret\">]><rss version=\"2.0\"><channel><item><guid>x</guid><title>&xxe;</title></item></channel></rss>";
        SECURITY.set(attack);assertThat(request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(502);assertThat(SECRETS.get()).isZero();
        var failed=get("connectors/pull").path("items").get(2);assertThat(failed.path("baseline").asBoolean()).isFalse();assertThat(failed.path("lastError").asText()).isEqualTo("pull_provider_failed");
        due();SECURITY.set("<rss version=\"2.0\"><channel><item><guid>blank</guid><title>&lt;script&gt;discard&lt;/script&gt;</title></item></channel></rss>");
        assertThat(request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(502);
        due();REDIRECT.set(true);assertThat(request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(502);assertThat(SECRETS.get()).isZero();REDIRECT.set(false);
        SECURITY.set("<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>atom-old</id><title>Atom 标题</title><summary>正文</summary></entry></feed>");due();
        assertThat(json.readTree(request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).body()).path("baseline").asBoolean()).isTrue();
        for(String role:java.util.List.of("viewer","operator")){
            var token=request("POST","access/tokens",json.writeValueAsString(Map.of("name",role,"role",role,"expiresAt",java.time.Instant.now().plusSeconds(3600))),TOKEN);String value=json.readTree(token.body()).path("token").asText();
            assertThat(request("POST","connectors/pull/rss-security/probe","{}",value).statusCode()).isEqualTo(403);
            assertThat(request("PUT","connectors/pull/rss-security/enabled","{\"expectedVersion\":2,\"enabled\":false}",value).statusCode()).isEqualTo(403);
        }
        due();DELAY.set(700);
        var a=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode();}catch(Exception e){throw new RuntimeException(e);}});
        var b=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode();}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(java.util.List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);DELAY.set(0);
        assertThat(request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(429);
        due();DELAY.set(700);STARTED=new java.util.concurrent.CountDownLatch(1);SECURITY.set("<rss version=\"2.0\"><channel><item><guid>late</guid><title>停用时已发出的读取</title></item></channel></rss>");
        var pending=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return request("POST","connectors/pull/rss-security/poll","{\"expectedVersion\":2}",TOKEN).statusCode();}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(STARTED.await(3,TimeUnit.SECONDS)).isTrue();
        assertThat(request("PUT","connectors/pull/rss-security/enabled","{\"enabled\":false,\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(200);
        assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo(409);DELAY.set(0);
        assertThat(get("connectors/pull").path("items").get(2).path("enabled").asBoolean()).isFalse();
        assertThat(get("messages?source=rss.rss-security").path("total").asInt()).isZero();
    }
    void due(){fixtures.update("UPDATE pull_connector_state SET last_attempt_at=now()-interval '2 minutes',next_poll_at=now()-interval '1 second'");}
}
