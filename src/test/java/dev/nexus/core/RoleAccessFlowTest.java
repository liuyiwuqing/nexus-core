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
class RoleAccessFlowTest {
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



    JsonNode issue(String role) throws Exception {
        var response=request("POST","access/tokens",json.writeValueAsString(Map.of("name",role+" isolated","role",role,"expiresAt",java.time.Instant.now().plusSeconds(3600).toString())),TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);return json.readTree(response.body());
    }
    @Test void browserSessionSurvivesReloadAndIsBoundToRevocationAndCsrf() throws Exception {
        var issued=issue("operator");String token=issued.path("token").asText(),id=issued.path("tokenId").asText();
        var login=request("POST","session","{\"remember\":true}",token);
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie=login.headers().firstValue("Set-Cookie").orElse("");
        assertThat(cookie).contains("HttpOnly","SameSite=Strict","Path=/api/v1","Max-Age=28800");
        assertThat(cookie).doesNotContain(token);
        cookie=cookie.split(";",2)[0];
        var restored=cookieRequest("GET","session","",cookie,null);
        assertThat(restored.statusCode()).isEqualTo(200);
        var session=json.readTree(restored.body());assertThat(session.path("role").asText()).isEqualTo("operator");
        String csrf=session.path("csrfToken").asText();assertThat(csrf).hasSizeGreaterThanOrEqualTo(32);
        assertThat(cookieRequest("POST","session/logout","",cookie,null).statusCode()).isEqualTo(403);
        assertThat(cookieRequest("POST","session/logout","",cookie,csrf).statusCode()).isEqualTo(204);
        assertThat(cookieRequest("GET","session","",cookie,null).statusCode()).isEqualTo(401);
        login=request("POST","session","{\"remember\":true}",token);cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        assertThat(request("POST","access/tokens/"+id+"/revoke","{\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(cookieRequest("GET","session","",cookie,null).statusCode()).isEqualTo(401);
    }
    HttpResponse<String> cookieRequest(String method,String path,String body,String cookie,String csrf)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).header("Cookie",cookie).header("Content-Type","application/json").timeout(Duration.ofSeconds(5));
        if(csrf!=null)b.header("X-Nexus-CSRF",csrf);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void roleTokenEditsAndRotationKeepIdentityAndRejectStaleVersions() throws Exception {
        var issued=issue("viewer");String token=issued.path("token").asText(),id=issued.path("tokenId").asText();
        String update=json.writeValueAsString(Map.of("name","Updated role","role","operator","expiresAt",java.time.Instant.now().plusSeconds(7200).toString(),"expectedVersion",1));
        assertThat(request("PUT","access/tokens/"+id,update,token).statusCode()).isEqualTo(403);
        assertThat(request("PUT","access/tokens/"+id,update,TOKEN).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET","session","",token).body()).path("role").asText()).isEqualTo("operator");
        assertThat(request("PUT","access/tokens/"+id,update,TOKEN).statusCode()).isEqualTo(409);
        var rotation=request("POST","access/tokens/"+id+"/rotate","{\"expectedVersion\":2}",TOKEN);
        assertThat(rotation.statusCode()).isEqualTo(200);var next=json.readTree(rotation.body());
        assertThat(next.path("tokenId").asText()).isEqualTo(id);String secret=next.path("token").asText();assertThat(secret).isNotEqualTo(token);
        assertThat(request("GET","session","",token).statusCode()).isEqualTo(401);
        assertThat(request("GET","session","",secret).statusCode()).isEqualTo(200);
        assertThat(get("access/tokens").toString()).doesNotContain(secret,token,"token_hash");
        assertThat(request("POST","access/tokens/"+id+"/rotate","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(409);
    }
    @Test void editedPermissionsAndExpiryAreRecheckedForEveryBrowserRequest()throws Exception {
        var issued=issue("operator");String token=issued.path("token").asText(),id=issued.path("tokenId").asText();
        var login=request("POST","session","{\"remember\":true}",token);String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        String update=json.writeValueAsString(Map.of("name","Now read only","role","viewer","expiresAt",java.time.Instant.now().plusSeconds(7200).toString(),"expectedVersion",1));
        assertThat(request("PUT","access/tokens/"+id,update,TOKEN).statusCode()).isEqualTo(200);
        var session=json.readTree(cookieRequest("GET","session","",cookie,null).body());assertThat(session.path("role").asText()).isEqualTo("viewer");
        assertThat(cookieRequest("POST","messages","{}",cookie,session.path("csrfToken").asText()).statusCode()).isEqualTo(403);
        var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/session")).header("Cookie",cookie).header("Authorization","Bearer invalid-explicit-token").GET().build();
        assertThat(http.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        fixtures.update("UPDATE role_tokens SET expires_at=now()-interval '1 second' WHERE token_id=?",java.util.UUID.fromString(id));
        assertThat(cookieRequest("GET","session","",cookie,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","access/tokens/"+id+"/rotate","{\"expectedVersion\":2}",TOKEN).statusCode()).isEqualTo(409);
    }
    @Test void rolesAreEnforcedByEveryServerMutationBoundary() throws Exception {
        String viewer=issue("viewer").path("token").asText(),operator=issue("operator").path("token").asText();
        assertThat(request("POST","session","",viewer).statusCode()).isEqualTo(200);
        assertThat(request("GET","messages","",viewer).statusCode()).isEqualTo(200);
        assertThat(request("GET","access/tokens","",viewer).statusCode()).isEqualTo(403);
        assertThat(request("GET","audit","",operator).statusCode()).isEqualTo(403);
        String message=json.writeValueAsString(Map.of("connectorId","role-test","source","role-test","eventId",UUID.randomUUID(),"eventType","test","title","隔离","content","权限测试"));
        assertThat(request("POST","messages",message,viewer).statusCode()).isEqualTo(403);
        assertThat(request("POST","messages",message,operator).statusCode()).isEqualTo(201);
        for(String path:List.of("devices","sources/private/credentials","access/tokens","retention/runs","tool-jobs")) {
            assertThat(request("POST",path,"{}",operator).statusCode()).withFailMessage(path).isEqualTo(403);
        }
        assertThat(request("PUT","routing/policy","{\"mode\":\"broadcast\",\"expectedVersion\":1}",viewer).statusCode()).isEqualTo(403);
        assertThat(request("PUT","routing/policy","{\"mode\":\"broadcast\",\"expectedVersion\":1}",operator).statusCode()).isEqualTo(200);
        assertThat(request("POST","access/tokens",json.writeValueAsString(Map.of("name","invalid","role","admin","expiresAt",java.time.Instant.now().plusSeconds(3600).toString())),TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("access/tokens").toString()).doesNotContain(viewer,operator,"token_hash");
    }
    @Test void revocationAndExpiryApplyImmediatelyAndAuditNeverContainsSecretsOrBodies() throws Exception {
        var issued=issue("operator");String token=issued.path("token").asText(),id=issued.path("tokenId").asText();
        assertThat(request("POST","session","",token).statusCode()).isEqualTo(200);
        String secretBody="Never log this private title or token "+token;
        assertThat(request("POST","devices",json.writeValueAsString(Map.of("secret",secretBody)),token).statusCode()).isEqualTo(403);
        var audit=get("audit");assertThat(audit.toString()).doesNotContain(token,secretBody,"Authorization");
        assertThat(java.util.stream.StreamSupport.stream(audit.path("items").spliterator(),false).anyMatch(e->e.path("actor").asText().equals(id)&&e.path("status").asInt()==403)).isTrue();
        String path="access/tokens/"+id+"/revoke";
        assertThat(request("POST",path,"{\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("POST",path,"{\"expectedVersion\":1}",TOKEN).statusCode()).isEqualTo(204);
        assertThat(request("GET","messages","",token).statusCode()).isEqualTo(401);
        var expired=issue("viewer");fixtures.update("UPDATE role_tokens SET expires_at=now()-interval '1 second' WHERE token_id=?",UUID.fromString(expired.path("tokenId").asText()));
        assertThat(request("GET","messages","",expired.path("token").asText()).statusCode()).isEqualTo(401);
    }
    @Test void auditRetentionIsPreviewedExplicitAndBoundedAndNeverRemovesUnfinishedRecords() throws Exception {
        var old=UUID.randomUUID();var pending=UUID.randomUUID();
        fixtures.update("INSERT INTO access_audit(audit_id,method,target,status,created_at,completed_at) VALUES(?,'POST','/isolated',200,now()-interval '91 days',now()-interval '91 days')",old);
        fixtures.update("INSERT INTO access_audit(audit_id,method,target,created_at) VALUES(?,'POST','/isolated',now()-interval '91 days')",pending);
        assertThat(get("audit/retention").path("eligible").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(request("POST","audit/retention","{\"confirm\":\"wrong\"}",TOKEN).statusCode()).isEqualTo(400);
        assertThat(request("POST","audit/retention","{\"confirm\":\"DELETE_EXPIRED_AUDIT\"}",TOKEN).statusCode()).isEqualTo(200);
        assertThat(fixtures.queryForObject("SELECT count(*) FROM access_audit WHERE audit_id=?",Integer.class,old)).isZero();
        assertThat(fixtures.queryForObject("SELECT count(*) FROM access_audit WHERE audit_id=?",Integer.class,pending)).isEqualTo(1);
        String viewer=issue("viewer").path("token").asText();
        assertThat(request("POST","audit/retention","{\"confirm\":\"DELETE_EXPIRED_AUDIT\"}",viewer).statusCode()).isEqualTo(403);
    }
}
