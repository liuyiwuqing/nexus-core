package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
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
 * 真实 HTTP + 隔离 PostgreSQL/MQTT：验证设备凭据台账的一次性密钥、修订冲突、
 * 轮换宽限期与撤销，以及管理员限定。Broker 侧应用不在本测试内，CORE 不写 Broker 配置。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceCredentialFlowTest {
    static final String TOKEN="credential-test-token-at-least-32-characters";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core credential-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("nexus.admin-token",()->TOKEN);p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"credential-password");p.add("nexus.mqtt.allow-insecure",()->"true");p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.delivery.tick-ms",()->"600000");p.add("nexus.automation.tick-ms",()->"600000");
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
    JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return response.body().isEmpty()?json.readTree("{}"):json.readTree(response.body());
    }
    String register(String name) throws Exception {
        String id=name+"-"+UUID.randomUUID().toString().substring(0,8);
        body(request("POST","devices",json.writeValueAsString(Map.of("deviceId",id,"name",id)),TOKEN),201);
        return id;
    }
    static String fingerprint(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void lifecycleKeepsTheSecretOneTimeAndRotatesWithGrace() throws Exception {
        String id=register("cred"), base="devices/"+id+"/credentials";
        var empty=body(request("GET",base,"",TOKEN),200);
        assertThat(empty.path("status").asText()).isEqualTo("none");
        assertThat(empty.path("revision").asInt()).isZero();
        assertThat(empty.has("secret")).isFalse();
        assertThat(empty.path("acl").get(0).asText()).isEqualTo("topic readwrite nexus/v1/devices/"+id+"/#");

        assertThat(request("POST",base,"{\"confirm\":\"nope\"}",TOKEN).statusCode()).isEqualTo(400);

        var issued=body(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",TOKEN),201);
        String first=issued.path("secret").asText();
        assertThat(issued.path("username").asText()).isEqualTo("dev-"+id);
        assertThat(issued.path("status").asText()).isEqualTo("active");
        assertThat(issued.path("revision").asInt()).isEqualTo(1);
        assertThat(first.length()).isGreaterThanOrEqualTo(32);

        // 库里只有指纹：明文既不等于指纹，也不出现在任何凭据行里。
        assertThat(fixtures.queryForObject("SELECT secret_fingerprint FROM device_credentials WHERE device_id=?",String.class,id))
            .isEqualTo(fingerprint(first)).isNotEqualTo(first);

        var read=body(request("GET",base,"",TOKEN),200);
        assertThat(read.path("status").asText()).isEqualTo("active");
        assertThat(read.path("revision").asInt()).isEqualTo(1);
        assertThat(read.path("issuedAt").asText()).isNotBlank();
        assertThat(read.has("secret")).isFalse();

        var conflict=body(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",TOKEN),409);
        assertThat(conflict.path("error").asText()).isEqualTo("device_credential_exists");

        var stale=body(request("POST",base+"/rotate","{\"expectedRevision\":9,\"confirm\":\"ROTATE_DEVICE_CREDENTIAL\"}",TOKEN),409);
        assertThat(stale.path("error").asText()).isEqualTo("device_credential_version_conflict");

        var rotated=body(request("POST",base+"/rotate","{\"expectedRevision\":1,\"graceHours\":2,\"confirm\":\"ROTATE_DEVICE_CREDENTIAL\"}",TOKEN),200);
        String second=rotated.path("secret").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(rotated.path("revision").asInt()).isEqualTo(2);
        assertThat(rotated.path("status").asText()).isEqualTo("rotating");
        assertThat(Instant.parse(rotated.path("previousExpiresAt").asText())).isAfter(Instant.now());

        var grace=body(request("GET",base,"",TOKEN),200);
        assertThat(grace.path("status").asText()).isEqualTo("rotating");
        assertThat(grace.path("previousActive").asBoolean()).isTrue();
        assertThat(fixtures.queryForObject("SELECT previous_fingerprint FROM device_credentials WHERE device_id=?",String.class,id))
            .isEqualTo(fingerprint(first));

        var revoked=request("POST",base+"/revoke","{\"expectedRevision\":2,\"confirm\":\"REVOKE_DEVICE_CREDENTIAL\"}",TOKEN);
        assertThat(revoked.statusCode()).withFailMessage(revoked.body()).isEqualTo(204);
        // 响应丢失后的同一请求重放按幂等处理。
        assertThat(request("POST",base+"/revoke","{\"expectedRevision\":2,\"confirm\":\"REVOKE_DEVICE_CREDENTIAL\"}",TOKEN).statusCode()).isEqualTo(204);

        var after=body(request("GET",base,"",TOKEN),200);
        assertThat(after.path("status").asText()).isEqualTo("revoked");
        assertThat(after.path("revision").asInt()).isEqualTo(3);
        assertThat(after.get("previousExpiresAt").isNull()).isTrue();
        assertThat(fixtures.queryForObject("SELECT count(*) FROM device_credentials WHERE device_id=? AND previous_fingerprint IS NOT NULL",Integer.class,id)).isZero();

        var revokedRotation=body(request("POST",base+"/rotate","{\"expectedRevision\":3,\"confirm\":\"ROTATE_DEVICE_CREDENTIAL\"}",TOKEN),409);
        assertThat(revokedRotation.path("error").asText()).isEqualTo("device_credential_revoked");

        var reissued=body(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",TOKEN),201);
        assertThat(reissued.path("revision").asInt()).isEqualTo(4);
        assertThat(reissued.path("status").asText()).isEqualTo("active");
        assertThat(body(request("GET",base,"",TOKEN),200).path("status").asText()).isEqualTo("active");

        var events=fixtures.queryForList("SELECT event,revision FROM device_credential_events WHERE device_id=? ORDER BY sequence",id);
        assertThat(events.stream().map(row->row.get("event").toString()).toList())
            .containsExactly("issued","rotated","revoked","reissued");
    }

    @Test void missingDeviceAndMissingCredentialAreDifferentErrors() throws Exception {
        var device=body(request("GET","devices/no-such-device/credentials","",TOKEN),404);
        assertThat(device.path("error").asText()).isEqualTo("device_not_found");

        String id=register("empty");
        var credential=body(request("POST","devices/"+id+"/credentials/revoke","{\"expectedRevision\":1,\"confirm\":\"REVOKE_DEVICE_CREDENTIAL\"}",TOKEN),404);
        assertThat(credential.path("error").asText()).isEqualTo("device_credential_not_found");
    }

    @Test void onlyAdminIssuesOrRevokesWhileOthersMayRead() throws Exception {
        String id=register("roles"), base="devices/"+id+"/credentials";
        var operatorResponse=body(request("POST","access/tokens",
            json.writeValueAsString(Map.of("name","credential operator","role","operator","expiresAt",Instant.now().plusSeconds(300).toString())),TOKEN),200);
        String operator=operatorResponse.path("token").asText();

        assertThat(request("GET",base,"",operator).statusCode()).isEqualTo(200);
        var denied=body(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",operator),403);
        assertThat(denied.path("error").asText()).isEqualTo("forbidden");
        assertThat(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",null).statusCode()).isEqualTo(401);

        body(request("POST",base,"{\"confirm\":\"ISSUE_DEVICE_CREDENTIAL\"}",TOKEN),201);
        assertThat(request("POST",base+"/revoke","{\"expectedRevision\":1,\"confirm\":\"REVOKE_DEVICE_CREDENTIAL\"}",operator).statusCode()).isEqualTo(403);

        // 接入引导按设备暴露台账状态，但顶层仍是 unknown，不把本机台账当作 Broker 事实。
        var setup=body(request("GET","setup","",TOKEN),200);
        var row=java.util.stream.StreamSupport.stream(setup.path("devices").spliterator(),false)
            .filter(item->item.path("deviceId").asText().equals(id)).findFirst().orElseThrow();
        assertThat(row.path("credentialStatus").asText()).isEqualTo("active");
        assertThat(row.path("credentialRevision").asInt()).isEqualTo(1);
        assertThat(setup.path("credentialsProvisioned").asText()).isEqualTo("unknown");
    }
}
