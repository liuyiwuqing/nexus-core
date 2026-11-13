package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxFlowTest {
    @Autowired JdbcTemplate fixtureDb;

    @Test void remoteOtaRequiresExplicitDeploymentOptIn()throws Exception {
        var response=request("GET","/api/v1/ota/releases","",TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(response.body()).path("enabled").asBoolean()).isFalse();
        assertThat(new ObjectMapper().readTree(response.body()).path("items").isEmpty()).isTrue();
        assertThat(request("POST","/api/v1/ota/releases","{}",TOKEN).statusCode()).isEqualTo(404);
    }

    @Test void growthMirroringRequiresExplicitDeploymentOptIn()throws Exception {
        var response=request("GET","/api/v1/pet/devices","",TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("enabled").asBoolean()).isFalse();
    }

    @Test void retentionIsDisabledUnlessTheDeploymentOptsIn()throws Exception {
        var preview=request("GET","/api/v1/retention","",TOKEN);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(json.readTree(preview.body()).path("enabled").asBoolean()).isFalse();
        assertThat(request("POST","/api/v1/retention/runs","{\"confirm\":\"DELETE_EXPIRED_MESSAGES\"}",TOKEN).statusCode()).isEqualTo(409);
    }


    @Test
    void focusRewardsUseServerTimeAndSettleOnceAcrossConcurrentRequests() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"requestKey\":\"" + key + "\",\"durationMinutes\":1}";
        var created = request("POST", "/api/v1/pet/focus", body, TOKEN);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).path("sessionId").asText();
        assertThat(json.readTree(request("POST", "/api/v1/pet/focus", body, TOKEN).body()).path("sessionId").asText()).isEqualTo(id);
        assertThat(request("POST", "/api/v1/pet/focus", body.replace(":1}", ":2}"), TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/v1/pet/focus/" + id + "/complete", "", TOKEN).statusCode()).isEqualTo(409);
        assertThat(json.readTree(request("GET", "/api/v1/pet", "", TOKEN).body()).path("experience").asInt()).isZero();
        // Seed an elapsed session in the real database; wall-clock waiting adds no coverage.
        fixtureDb.update("UPDATE focus_sessions SET started_at=now()-interval '2 minutes' WHERE session_id=?", UUID.fromString(id));
        var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> completeFocus(id));
        var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> completeFocus(id));
        assertThat(one.get()).isEqualTo(200); assertThat(two.get()).isEqualTo(200);
        var profile = json.readTree(request("GET", "/api/v1/pet", "", TOKEN).body());
        assertThat(profile.path("experience").asInt()).isEqualTo(1);
        assertThat(profile.path("coins").asInt()).isEqualTo(1);
        assertThat(profile.path("bond").asInt()).isEqualTo(1);
        assertThat(profile.path("completedFocus").asInt()).isEqualTo(1);
        assertThat(fixtureDb.queryForObject("SELECT count(*) FROM growth_rewards", Integer.class)).isEqualTo(1);
        String cancelBody = "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"durationMinutes\":25}";
        String cancelled = json.readTree(request("POST", "/api/v1/pet/focus", cancelBody, TOKEN).body()).path("sessionId").asText();
        assertThat(request("POST", "/api/v1/pet/focus/" + cancelled + "/cancel", "", TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("POST", "/api/v1/pet/focus/" + cancelled + "/complete", "", TOKEN).statusCode()).isEqualTo(409);
        assertThat(json.readTree(request("GET", "/api/v1/pet", "", TOKEN).body()).path("experience").asInt()).isEqualTo(1);
    }

    int completeFocus(String id) {
        try { return request("POST", "/api/v1/pet/focus/" + id + "/complete", "", TOKEN).statusCode(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    static final String TOKEN = "integration-admin-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT = new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("""
            listener 1883
            allow_anonymous false
            password_file /tmp/nexus-passwd
            acl_file /mosquitto/config/acl
            max_packet_size 2048
            message_size_limit 1024
            """), "/mosquitto/config/mosquitto.conf")
        .withCopyToContainer(Transferable.of("""
            user nexus-core
            topic write nexus/v1/devices/+/inbox
            topic read nexus/v1/devices/+/acks
            topic read nexus/v1/devices/+/state
            pattern read nexus/v1/devices/%u/inbox
            pattern write nexus/v1/devices/%u/acks
            pattern write nexus/v1/devices/%u/state
            """), "/mosquitto/config/acl")
        .withCommand("sh", "-c", "mosquitto_passwd -b -c /tmp/nexus-passwd nexus-core core-test-password && mosquitto_passwd -b /tmp/nexus-passwd dodo-test device-test-password && chmod 644 /tmp/nexus-passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    static final HttpServer HALO = haloServer();
    static final AtomicBoolean PUBLIC_COMMENT = new AtomicBoolean(true);
    static final AtomicBoolean EXTRA_PUBLIC_COMMENT = new AtomicBoolean(false);
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    static final HttpServer MODEL = modelServer();
    static final AtomicInteger MEMORY_WRITES = new AtomicInteger();
    static final AtomicInteger MEMORY_DELETES = new AtomicInteger();
    static final java.util.concurrent.atomic.AtomicReference<String> MEMORY_URI=new java.util.concurrent.atomic.AtomicReference<>("");
    static final HttpServer MEMORY = memoryServer();
    static final BridgeFixture BRIDGE = bridgeServer();

    record BridgeFixture(Process process, int port, java.nio.file.Path directory) {}
    static BridgeFixture bridgeServer() {
        try {
            var directory = java.nio.file.Files.createTempDirectory("nexus-bridge-contract-");
            var config = directory.resolve("config.json");
            java.nio.file.Files.writeString(config, "{\"token\":\"bridge-integration-token-at-least-32-characters\",\"projects\":{},\"applications\":[]}");
            java.nio.file.Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            var output = directory.resolve("ready.log");
            var process = new ProcessBuilder("python3", "../mac-bridge/bridge.py", "--config", config.toString(), "--database", directory.resolve("calls.sqlite").toString(), "--port", "0")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            String ready = "";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && process.isAlive()) {
                ready = java.nio.file.Files.readString(output).strip();
                if (ready.startsWith("NEXUS_BRIDGE_READY ")) break;
                Thread.sleep(30);
            }
            if (!ready.startsWith("NEXUS_BRIDGE_READY ")) { process.destroy(); throw new IllegalStateException("bridge fixture failed"); }
            return new BridgeFixture(process, Integer.parseInt(ready.substring(19)), directory);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    static HttpServer memoryServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/content/write", exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("X-OpenViking-Actor-Peer")).isEqualTo("nexus-pet-tests");
                assertThat(exchange.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("memory-test-key");
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(input).contains("viking://user/default/resources/nexus-pet/public-comments/", "公开评论内容").doesNotContain("private-parent-secret", "private@example.invalid");
                MEMORY_URI.set(new ObjectMapper().readTree(input).path("uri").asText());
                MEMORY_WRITES.incrementAndGet();
                byte[] body = "{\"status\":\"ok\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.createContext("/api/v1/search/find",exchange->{
                var mapper=new ObjectMapper();var query=mapper.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(exchange.getRequestHeaders().getFirst("X-OpenViking-Actor-Peer")).isEqualTo("nexus-pet-tests");
                assertThat(query.path("target_uri").asText()).isEqualTo("viking://user/default/resources/nexus-pet/public-comments/");
                assertThat(query.path("tags").toString()).contains("project=nexus-pet","data_class=public_comment");
                var hits=mapper.createArrayNode();
                for(String uri:java.util.List.of("viking://user/another/resources/private.md",MEMORY_URI.get(),
                    "viking://user/default/resources/nexus-pet/public-comments/00000000-0000-0000-0000-000000000000.md",MEMORY_URI.get()))
                    hits.add(mapper.createObjectNode().put("uri",uri).put("level",2).put("context_type","resource").put("abstract","untrusted-upstream-summary-do-not-return"));
                byte[] body=mapper.writeValueAsBytes(java.util.Map.of("status","ok","result",java.util.Map.of("resources",hits)));
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/api/v1/fs",exchange->{
                assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
                assertThat(exchange.getRequestHeaders().getFirst("X-OpenViking-Actor-Peer")).isEqualTo("nexus-pet-tests");
                String query=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
                assertThat(query).contains("uri="+MEMORY_URI.get(),"recursive=false");
                MEMORY_DELETES.incrementAndGet();
                byte[] body="{\"status\":\"ok\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }

    static HttpServer modelServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(input).contains("公开评论内容").doesNotContain("private-parent-secret", "private@example.invalid", "198.51.100.7");
                MODEL_CALLS.incrementAndGet();
                byte[] body = """
                    {"choices":[{"message":{"content":"{\\\"summary\\\":\\\"读者提出了公开问题\\\",\\\"category\\\":\\\"question\\\",\\\"importance\\\":\\\"normal\\\",\\\"hasQuestion\\\":true,\\\"replyDraft\\\":\\\"感谢反馈，我会进一步核对。\\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }

    static HttpServer haloServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/apis/api.console.halo.run/v1alpha1/comments", exchange -> {
                if (!"Bearer halo-readonly-test".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return;
                }
                String item = """
                    {"comment":{"apiVersion":"content.halo.run/v1alpha1","kind":"Comment",
                      "metadata":{"name":"NAME","creationTimestamp":"TIME"},
                      "spec":{"approved":APPROVED,"hidden":HIDDEN,"approvedTime":"TIME",
                        "raw":"raw-only-not-public","content":"<p>CONTENT</p>","ipAddress":"198.51.100.7",
                        "owner":{"kind":"Email","name":"private@example.invalid","displayName":"公开访客"}}},
                     "subject":{"apiVersion":"content.halo.run/v1alpha1","kind":"Post",
                       "metadata":{"deletionTimestamp":null},"spec":{"publish":true,"deleted":false,"visible":"VISIBILITY"},"status":{"phase":"PUBLISHED"}},
                     "permalink":"/archives/example#halo-comment=NAME"}
                    """.replace("TIME", Instant.now().minusSeconds(30).toString());
                String publicItem = item.replace("NAME","public-1").replace("APPROVED","true").replace("HIDDEN","false").replace("VISIBILITY","PUBLIC").replace("CONTENT","公开评论内容");
                if (PUBLIC_COMMENT != null && !PUBLIC_COMMENT.get()) publicItem = publicItem.replace("\"visible\":\"PUBLIC\"", "\"visible\":\"PRIVATE\"");
                String privateItem = item.replace("NAME","private-1").replace("APPROVED","true").replace("HIDDEN","false").replace("VISIBILITY","PRIVATE").replace("CONTENT","private-parent-secret");
                String pending = item.replace("NAME","pending-1").replace("APPROVED","false").replace("HIDDEN","false").replace("VISIBILITY","PUBLIC").replace("CONTENT","pending-secret");
                String hidden = item.replace("NAME","hidden-1").replace("APPROVED","true").replace("HIDDEN","true").replace("VISIBILITY","PUBLIC").replace("CONTENT","hidden-secret");
                String deleted = publicItem.replace("public-1", "deleted-1").replace("\"deleted\":false", "\"deleted\":true").replace("公开评论内容", "subject-deleted-secret");
                String deleting = publicItem.replace("public-1", "deleting-1").replace("\"deletionTimestamp\":null", "\"deletionTimestamp\":\"2026-11-10T00:00:00Z\"").replace("公开评论内容", "subject-deleting-secret");
                String extra = EXTRA_PUBLIC_COMMENT != null && EXTRA_PUBLIC_COMMENT.get() ? "," + publicItem.replace("public-1", "rule-public-2") : "";
                byte[] bytes = ("{\"page\":1,\"size\":100,\"total\":" + (extra.isEmpty() ? 6 : 7) + ",\"hasNext\":false,\"items\":[" + publicItem + "," + privateItem + "," + pending + "," + hidden + "," + deleted + "," + deleting + extra + "]}").getBytes(StandardCharsets.UTF_8);
                String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                for (String param : query.split("&")) if (param.startsWith("fieldSelector=metadata.name=")) {
                    // Match the deployed 2.27.2 instance: '=' is the equality delimiter.
                    String requested = param.substring("fieldSelector=metadata.name=".length());
                    var mapper = new ObjectMapper(); var response = (tools.jackson.databind.node.ObjectNode) mapper.readTree(bytes);
                    var filtered = mapper.createArrayNode();
                    for (var entry : response.path("items")) if (entry.path("comment").path("metadata").path("name").asText().equals(requested)) filtered.add(entry);
                    response.set("items", filtered); response.put("total", filtered.size());
                    bytes = mapper.writeValueAsBytes(response);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @AfterAll static void closeHaloFixture() throws Exception {
        HALO.stop(0); MODEL.stop(0); MEMORY.stop(0); BRIDGE.process().destroy(); BRIDGE.process().waitFor(3, TimeUnit.SECONDS);
        try (var files = java.nio.file.Files.walk(BRIDGE.directory())) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(file);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("nexus.admin-token", () -> TOKEN);
        p.add("nexus.mqtt.uri", () -> "tcp://" + MQTT.getHost() + ":" + MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password", () -> "core-test-password");
        p.add("nexus.mqtt.allow-insecure", () -> "true");
        p.add("nexus.mqtt.ca-cert", () -> "");
        p.add("nexus.delivery.tick-ms", () -> "100");
        p.add("nexus.delivery.retry-seconds", () -> "1");
        p.add("nexus.halo.enabled", () -> "true");
        p.add("nexus.halo.url", () -> "http://127.0.0.1:" + HALO.getAddress().getPort());
        p.add("nexus.halo.token", () -> "halo-readonly-test");
        p.add("nexus.halo.since", () -> Instant.now().minusSeconds(3600).toString());
        p.add("nexus.halo.allow-insecure", () -> "true");
        p.add("nexus.ai.enabled", () -> "true");
        p.add("nexus.ai.auto-enabled", () -> "false");
        p.add("nexus.ai.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1/");
        p.add("nexus.ai.model", () -> "fixture-model");
        p.add("nexus.ai.api-key", () -> "fixture-key");
        p.add("nexus.ai.tick-ms", () -> "100");
        p.add("nexus.memory.enabled", () -> "true");
        p.add("nexus.memory.url", () -> "http://127.0.0.1:" + MEMORY.getAddress().getPort() + "/");
        p.add("nexus.memory.api-key", () -> "memory-test-key");
        p.add("nexus.memory.peer", () -> "nexus-pet-tests");
        p.add("nexus.memory.tick-ms", () -> "100");
        p.add("nexus.memory.maintenance-ms", () -> "100");
        p.add("nexus.bridge.enabled", () -> "true");
        p.add("nexus.bridge.url", () -> "http://127.0.0.1:" + BRIDGE.port() + "/");
        p.add("nexus.bridge.token", () -> "bridge-integration-token-at-least-32-characters");
        p.add("nexus.tools.tick-ms", () -> "100");
        p.add("nexus.automation.tick-ms", () -> "100");
    }

    HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void explicitHistoricalImportRechecksPublicVisibilityAndUsesThePollDeduplicationKey() throws Exception {
        assertThat(request("POST", "/api/v1/connectors/halo/comments/private-1/import", "", TOKEN).statusCode()).isEqualTo(404);
        var first = request("POST", "/api/v1/connectors/halo/comments/public-1/import", "", TOKEN);
        assertThat(first.statusCode()).isIn(200, 201);
        var replay = request("POST", "/api/v1/connectors/halo/comments/public-1/import", "", TOKEN);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
    }

    @Test
    void disabledDailyRulesDoNothingAndEnabledRulesQueueOneReminder() throws Exception {
        String time = java.time.LocalTime.now(java.time.ZoneOffset.UTC).minusMinutes(1).withSecond(0).withNano(0).toString();
        var response = request("POST", "/api/v1/automation/rules", """
            {"name":"每日提醒测试","trigger":"schedule.daily","action":"notify_device","localTime":"TIME","zone":"UTC",
             "title":"专注时刻","content":"请开始专注"}
            """.replace("TIME", time), TOKEN);
        assertThat(response.statusCode()).isEqualTo(201);
        String id = json.readTree(response.body()).path("ruleId").asText();
        Thread.sleep(250);
        assertThat(json.readTree(request("GET", "/api/v1/automation/runs?ruleId=" + id, "", TOKEN).body()).path("items")).isEmpty();
        assertThat(request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":true}", TOKEN).statusCode()).isEqualTo(204);
        awaitAutomation(id);
        Thread.sleep(250);
        assertThat(json.readTree(request("GET", "/api/v1/automation/runs?ruleId=" + id, "", TOKEN).body()).path("items")).hasSize(1);
        assertThat(request("GET", "/api/v1/messages", "", TOKEN).body()).contains("专注时刻", "请开始专注");
        assertThat(request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":false}", TOKEN).statusCode()).isEqualTo(204);
        request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":true}", TOKEN);
        Thread.sleep(250);
        assertThat(json.readTree(request("GET", "/api/v1/automation/runs?ruleId=" + id, "", TOKEN).body()).path("items")).hasSize(1);
        request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":false}", TOKEN);
    }

    @Test
    void commentRulesAcceptOnlyTrustedPublicEvents() throws Exception {
        // Establish the pre-rule boundary: enabling a rule must not replay earlier comments.
        assertThat(request("POST", "/api/v1/connectors/halo/poll", "", TOKEN).statusCode()).isEqualTo(200);
        var created = request("POST", "/api/v1/automation/rules", "{\"name\":\"公开评论摘要\",\"trigger\":\"message.received\",\"action\":\"analyze_public_comment\"}", TOKEN);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).path("ruleId").asText();
        request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":true}", TOKEN);
        request("POST", "/api/v1/messages", "{\"connectorId\":\"forged-rule\",\"source\":\"halo\",\"eventId\":\"rule-fake\",\"eventType\":\"comment.created\",\"title\":\"伪造公开来源\",\"content\":\"private-parent-secret\"}", TOKEN);
        Thread.sleep(250);
        assertThat(json.readTree(request("GET", "/api/v1/automation/runs?ruleId=" + id, "", TOKEN).body()).path("items")).isEmpty();
        EXTRA_PUBLIC_COMMENT.set(true);
        try {
            assertThat(request("POST", "/api/v1/connectors/halo/poll", "", TOKEN).statusCode()).isEqualTo(200);
            var run = awaitAutomation(id);
            awaitAnalysis(run.path("result").path("messageId").asText(), "succeeded");
        } finally {
            EXTRA_PUBLIC_COMMENT.set(false);
            request("PUT", "/api/v1/automation/rules/" + id + "/enabled", "{\"enabled\":false}", TOKEN);
        }
    }

    JsonNode awaitAutomation(String ruleId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); JsonNode last = null;
        do {
            var runs = json.readTree(request("GET", "/api/v1/automation/runs?ruleId=" + ruleId, "", TOKEN).body()).path("items");
            if (runs.isArray() && !runs.isEmpty()) {
                last = runs.get(0); if (last.path("state").asText().equals("enqueued")) return last;
            }
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("automation did not enqueue: " + last);
    }

    @Test
    void toolJobsUseTheRealBridgeAndBindConfirmationToExactArguments() throws Exception {
        var tools = request("GET", "/api/v1/tools", "", TOKEN);
        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(json.readTree(tools.body()).path("state").asText()).isEqualTo("ready");
        String key = UUID.randomUUID().toString();
        String body = "{\"requestKey\":\"" + key + "\",\"toolId\":\"system.info\",\"arguments\":{}}";
        var created = request("POST", "/api/v1/tool-jobs", body, TOKEN);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).path("jobId").asText();
        JsonNode completed = awaitJob(id, "succeeded");
        assertThat(completed.path("result").path("system").asText()).isNotBlank();
        assertThat(json.readTree(request("POST", "/api/v1/tool-jobs", body, TOKEN).body()).path("jobId").asText()).isEqualTo(id);
        assertThat(request("POST", "/api/v1/tool-jobs", body.replace("system.info", "shell"), TOKEN).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/v1/tool-jobs", body.replace("system.info", "project.status").replace("\"arguments\":{}", "\"arguments\":{\"projectId\":\"unlisted\"}"), TOKEN).statusCode()).isEqualTo(409);
        var audit = request("GET", "/api/v1/tool-jobs/" + id + "/audit", "", TOKEN);
        assertThat(audit.body()).contains("created", "executing", "succeeded").doesNotContain("bridge-integration-token");

        var proposal = request("POST", "/api/v1/tool-jobs", "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"toolId\":\"app.open\",\"arguments\":{\"applicationId\":\"codex\"}}", TOKEN);
        assertThat(proposal.statusCode()).isEqualTo(201);
        JsonNode job = json.readTree(proposal.body()); id = job.path("jobId").asText();
        assertThat(job.path("state").asText()).isEqualTo("awaiting_confirmation");
        assertThat(request("POST", "/api/v1/tool-jobs/" + id + "/confirm", "{\"argumentsHash\":\"wrong\"}", TOKEN).statusCode()).isEqualTo(409);
        assertThat(request("POST", "/api/v1/tool-jobs/" + id + "/confirm", "{\"argumentsHash\":\"" + job.path("argumentsHash").asText() + "\"}", TOKEN).statusCode()).isEqualTo(202);
        // This fixture has an empty application whitelist; it must never launch a real app.
        assertThat(awaitJob(id, "failed").path("lastError").asText()).isEqualTo("application_not_allowed");
    }

    JsonNode awaitJob(String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        JsonNode job;
        do {
            job = json.readTree(request("GET", "/api/v1/tool-jobs/" + id, "", TOKEN).body());
            if (job.path("state").asText().equals(state)) return job;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        assertThat(job.path("state").asText()).withFailMessage(job.toString()).isEqualTo(state);
        return job;
    }

    @Test
    void analysisRechecksPublicVisibilityAndRejectsForgedSourceLabels() throws Exception {
        int priorModelCalls = MODEL_CALLS.get();
        assertThat(request("POST", "/api/v1/connectors/halo/poll", "", TOKEN).statusCode()).isEqualTo(200);
        JsonNode target = null;
        for (var item : json.readTree(request("GET", "/api/v1/messages", "", TOKEN).body()).path("items"))
            if (item.path("eventId").asText().equals("comment:public-1")) target = item;
        assertThat(target).isNotNull();
        String id = target.path("messageId").asText();
        PUBLIC_COMMENT.set(false);
        try {
            assertThat(request("POST", "/api/v1/messages/" + id + "/analysis", "", TOKEN).statusCode()).isEqualTo(202);
            awaitAnalysis(id, "blocked");
            assertThat(MODEL_CALLS.get()).isEqualTo(priorModelCalls);
        } finally { PUBLIC_COMMENT.set(true); }
        assertThat(request("POST", "/api/v1/messages/" + id + "/analysis/retry", "", TOKEN).statusCode()).isEqualTo(202);
        awaitAnalysis(id, "succeeded");
        var result = request("GET", "/api/v1/messages/" + id + "/analysis", "", TOKEN);
        assertThat(result.body()).contains("读者提出了公开问题", "感谢反馈，我会进一步核对。");
        assertThat(MODEL_CALLS.get()).isEqualTo(priorModelCalls + 1);
        PUBLIC_COMMENT.set(false);
        try {
            assertThat(request("POST", "/api/v1/messages/" + id + "/memory", "", TOKEN).statusCode()).isEqualTo(202);
            awaitMemory(id, "blocked");
            assertThat(MEMORY_WRITES.get()).isZero();
        } finally { PUBLIC_COMMENT.set(true); }
        assertThat(request("POST", "/api/v1/messages/" + id + "/memory", "", TOKEN).statusCode()).isEqualTo(202);
        awaitMemory(id, "stored");
        assertThat(MEMORY_WRITES.get()).isEqualTo(1);
        assertThat(request("POST","/api/v1/memory/search","{\"query\":\"读者问题\"}",null).statusCode()).isEqualTo(401);
        assertThat(request("POST","/api/v1/memory/search","{\"query\":\" \"}",TOKEN).statusCode()).isEqualTo(400);
        var search=request("POST","/api/v1/memory/search","{\"query\":\"读者问题\",\"limit\":3}",TOKEN);
        assertThat(search.statusCode()).withFailMessage(search.body()).isEqualTo(200);
        assertThat(json.readTree(search.body()).path("items").size()).isEqualTo(1);
        assertThat(search.body()).contains(id,"公开评论内容","读者提出了公开问题").doesNotContain("untrusted-upstream-summary-do-not-return","private@example.invalid");
        PUBLIC_COMMENT.set(false);
        try {
            var withdrawn=request("POST","/api/v1/memory/search","{\"query\":\"读者问题\",\"limit\":3}",TOKEN);
            assertThat(withdrawn.statusCode()).isEqualTo(200);
            assertThat(json.readTree(withdrawn.body()).path("items").size()).isZero();
            assertThat(withdrawn.body()).doesNotContain("公开评论内容","读者提出了公开问题");
            awaitMemory(id,"blocked");
            long deleteDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(MEMORY_DELETES.get()==0&&System.nanoTime()<deleteDeadline)Thread.sleep(40);
            assertThat(MEMORY_DELETES.get()).isEqualTo(1);
            var health=request("GET","/api/v1/memory","",TOKEN);
            assertThat(json.readTree(health.body()).path("deletedCopies").asInt()).isEqualTo(1);
        }finally {PUBLIC_COMMENT.set(true);}
        var afterRestore=request("POST","/api/v1/memory/search","{\"query\":\"读者问题\"}",TOKEN);
        assertThat(json.readTree(afterRestore.body()).path("items")).isEmpty();
        var fake = request("POST", "/api/v1/messages", """
            {"connectorId":"fake-halo","source":"halo","eventId":"forged-1","eventType":"comment.created",
             "title":"不允许外发","content":"private-parent-secret"}
            """, TOKEN);
        String privateId = json.readTree(fake.body()).path("messageId").asText();
        assertThat(request("POST", "/api/v1/messages/" + privateId + "/analysis", "", TOKEN).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/v1/messages/" + privateId + "/memory", "", TOKEN).statusCode()).isEqualTo(403);
        assertThat(MODEL_CALLS.get()).isEqualTo(priorModelCalls + 1);
    }

    void awaitMemory(String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var result = request("GET", "/api/v1/messages/" + id + "/analysis", "", TOKEN);
            if (json.readTree(result.body()).path("memoryStatus").asText().equals(state)) return;
            Thread.sleep(40);
        }
        assertThat(request("GET", "/api/v1/messages/" + id + "/analysis", "", TOKEN).body()).contains("\"memoryStatus\":\"" + state + "\"");
    }

    void awaitAnalysis(String id, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var result = request("GET", "/api/v1/messages/" + id + "/analysis", "", TOKEN);
            if (json.readTree(result.body()).path("status").asText().equals(state)) return;
            Thread.sleep(40);
        }
        assertThat(request("GET", "/api/v1/messages/" + id + "/analysis", "", TOKEN).body()).contains("\"status\":\"" + state + "\"");
    }

    @Test
    void haloPollPersistsOnlyPublishedPublicCommentsAndNeverCopiesAccountDetails() throws Exception {
        var first = request("POST", "/api/v1/connectors/halo/poll", "", TOKEN);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("received").asInt()).isBetween(0, 1);
        var replay = request("POST", "/api/v1/connectors/halo/poll", "", TOKEN);
        assertThat(json.readTree(replay.body()).path("received").asInt()).isZero();
        var inbox = request("GET", "/api/v1/messages", "", TOKEN);
        var imported = new java.util.ArrayList<JsonNode>();
        for (var item : json.readTree(inbox.body()).path("items")) if (item.path("connectorId").asText().equals("halo-main")) imported.add(item);
        String content = json.writeValueAsString(imported);
        assertThat(content).contains("公开评论内容", "公开访客");
        assertThat(content).doesNotContain("private-parent-secret", "pending-secret", "hidden-secret", "subject-deleted-secret", "subject-deleting-secret", "private@example.invalid", "198.51.100.7", "raw-only-not-public");
        var health = json.readTree(request("GET", "/api/v1/connectors", "", TOKEN).body());
        assertThat(health.path("items").get(0).path("state").asText()).isEqualTo("ready");
    }

    @Test
    void messageDeliveryNeedsDeviceAcknowledgementAndReadNeverRegresses() throws Exception {
        assertThat(request("POST", "/api/v1/devices", "{\"deviceId\":\"dodo-test\",\"name\":\"桌面宠物\"}", TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/v1/devices", "{\"deviceId\":\"dodo-other\",\"name\":\"另一台设备\"}", TOKEN).statusCode()).isEqualTo(201);
        var packets = new LinkedBlockingQueue<String>();
        try (var device = new MqttClient("tcp://" + MQTT.getHost() + ":" + MQTT.getMappedPort(1883), "device-test-" + UUID.randomUUID(), new MemoryPersistence())) {
            var options = new MqttConnectOptions();
            options.setUserName("dodo-test");
            options.setPassword("device-test-password".toCharArray());
            device.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {}
                public void deliveryComplete(IMqttDeliveryToken token) {}
                public void messageArrived(String topic, MqttMessage m) { packets.add(new String(m.getPayload(), StandardCharsets.UTF_8)); }
            });
            device.connect(options);
            device.subscribe("nexus/v1/devices/dodo-test/inbox", 1);
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            JsonNode coreStatus;
            do {
                coreStatus = json.readTree(request("GET", "/api/v1/status", "", TOKEN).body());
                if (coreStatus.path("mqtt").asText().equals("ready")) break;
                Thread.sleep(50);
            } while (System.nanoTime() < readyDeadline);
            assertThat(coreStatus.path("mqtt").asText()).withFailMessage(coreStatus.toString()).isEqualTo("ready");
            var created = request("POST", "/api/v1/messages", """
                {"connectorId":"test-mqtt","source":"halo","eventId":"mqtt-comment-1","eventType":"comment.created",
                 "title":"新的博客评论","content":"这是消息投递测试","summary":"评论摘要","priority":"high"}
                """, TOKEN);
            UUID messageId = UUID.fromString(json.readTree(created.body()).path("messageId").asText());
            String packet = packets.poll(15, TimeUnit.SECONDS);
            assertThat(packet).isNotNull();
            var envelope = json.readTree(packet);
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(envelope.path("payload").path("messageId").asText()).isEqualTo(messageId.toString());
            String event = envelope.path("eventId").asText();
            awaitStatus(event, "sent");
            String malformed = json.writeValueAsString(java.util.Map.of("schemaVersion", 1.5, "eventId", event,
                "deviceId", "dodo-test", "messageId", messageId, "status", "read"));
            device.publish("nexus/v1/devices/dodo-test/acks", malformed.getBytes(StandardCharsets.UTF_8), 1, false);
            for (int probe = 0; probe < 5; ++probe) {
                Thread.sleep(50);
                assertThat(delivery(event).path("status").asText()).isEqualTo("sent");
            }
            for (String[] step : new String[][]{{"received","received"},{"displayed","displayed"},{"read","read"},{"received","read"}}) {
                String ack = json.writeValueAsString(java.util.Map.of("schemaVersion", 1, "eventId", event,
                    "deviceId", "dodo-test", "messageId", messageId, "status", step[0]));
                device.publish("nexus/v1/devices/dodo-test/acks", ack.getBytes(StandardCharsets.UTF_8), 1, false);
                awaitStatus(event, step[1]);
            }
            var inbox = json.readTree(request("GET", "/api/v1/messages", "", TOKEN).body());
            boolean found = false;
            for (JsonNode row : inbox.path("items")) {
                if (row.path("messageId").asText().equals(messageId.toString())) {
                    assertThat(row.path("readStatus").asText()).isEqualTo("read");
                    found = true;
                }
            }
            assertThat(found).isTrue();
            var all = json.readTree(request("GET", "/api/v1/deliveries", "", TOKEN).body()).path("items");
            boolean otherFound = false;
            for (var item : all) if (item.path("messageId").asText().equals(messageId.toString()) && item.path("deviceId").asText().equals("dodo-other")) {
                assertThat(item.path("status").asText()).isEqualTo("expired");
                assertThat(item.path("lastError").asText()).isEqualTo("read_elsewhere");
                otherFound = true;
            }
            assertThat(otherFound).isTrue();
            device.disconnect();
        }
    }

    JsonNode delivery(String event) throws Exception {
        var records = json.readTree(request("GET", "/api/v1/deliveries", "", TOKEN).body()).path("items");
        for (JsonNode row : records) if (row.path("eventId").asText().equals(event)) return row;
        throw new AssertionError("delivery not found");
    }

    void awaitStatus(String event, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (delivery(event).path("status").asText().equals(expected)) return;
            Thread.sleep(30);
        }
        assertThat(delivery(event).path("status").asText()).isEqualTo(expected);
    }

    @Test
    void privateInboxRequiresAnAdminCredential() throws Exception {
        assertThat(request("GET", "/api/v1/messages", "", null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/messages", "", "wrong-token").statusCode()).isEqualTo(401);
    }

    @Test
    void alternateUrlSpellingsCannotBypassAuthentication() throws Exception {
        for (String path : new String[]{"/api;matrix/v1/messages", "/a%70i/v1/messages", "//api/v1/messages"}) {
            assertThat(request("GET", path, "", null).statusCode()).isNotEqualTo(200);
        }
    }

    @Test
    void acceptedMessageCanBeRetrievedAndReplayDoesNotCreateAnotherMessage() throws Exception {
        String body = """
            {"connectorId":"test-halo","source":"halo","eventId":"comment-1","eventType":"comment.created",
             "title":"新评论","content":"希望增加专注模式","summary":"一条新评论","sender":"访客","priority":"normal"}
            """;
        var created = request("POST", "/api/v1/messages", body, TOKEN);
        assertThat(created.statusCode()).isEqualTo(201);
        var replay = request("POST", "/api/v1/messages", body, TOKEN);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(created.body());
        var inbox = request("GET", "/api/v1/messages", "", TOKEN);
        assertThat(inbox.statusCode()).isEqualTo(200);
        assertThat(inbox.body()).contains("希望增加专注模式");
    }
}
