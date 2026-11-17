package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real independent CORE JVMs share only the isolated database and protected session directory. */
@Testcontainers
class PersistentSessionRestartTest {
    static final String ADMIN="persistent-admin-fixture-credential-at-least-32-characters",NEXT="rotated-admin-fixture-credential-at-least-32-characters";
    static final JsonMapper JSON=JsonMapper.builder().build();
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22").withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous false\npassword_file /tmp/passwd\n"),"/mosquitto/config/mosquitto.conf")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core session-test-password && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @TempDir Path directory;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    Process core;
    int port,starts;
    void start(String token)throws Exception{
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"dev.nexus.core.NexusCore"));
        Map<String,String> properties=Map.ofEntries(
            Map.entry("server.address","127.0.0.1"),Map.entry("server.port",Integer.toString(port)),Map.entry("nexus.admin-token",token),Map.entry("nexus.auth.token-file",""),
            Map.entry("nexus.auth.session-directory",directory.resolve("sessions").toString()),Map.entry("spring.datasource.url",DB.getJdbcUrl()),Map.entry("spring.datasource.username",DB.getUsername()),Map.entry("spring.datasource.password",DB.getPassword()),
            Map.entry("nexus.mqtt.uri","tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883)),Map.entry("nexus.mqtt.password","session-test-password"),Map.entry("nexus.mqtt.allow-insecure","true"),Map.entry("nexus.mqtt.ca-cert",""),
            Map.entry("nexus.halo.enabled","false"),Map.entry("nexus.ai.enabled","false"),Map.entry("nexus.retention.enabled","false"),Map.entry("nexus.memory.enabled","false"),Map.entry("nexus.memory.mcp.enabled","false"),Map.entry("nexus.bridge.enabled","false"));
        properties.forEach((key,value)->args.add("--"+key+"="+value));
        var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(directory.resolve("core-"+(++starts)+".log").toFile());
        builder.environment().keySet().removeIf(key->key.startsWith("NEXUS_")||key.startsWith("SPRING_"));core=builder.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline&&core.isAlive()){
            try{if(request("GET","session",null,token,null,null).statusCode()==200)return;}catch(Exception starting){/* Wait for this JVM's real HTTP listener. */}
            Thread.sleep(100);
        }
        throw new AssertionError("CORE JVM did not become ready; see isolated test log "+directory.resolve("core-"+starts+".log"));
    }
    void stop()throws Exception{if(core==null)return;core.destroy();if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();assertThat(core.waitFor(5,TimeUnit.SECONDS)).isTrue();}core=null;}
    HttpResponse<String> request(String method,String path,Object body,String bearer,String cookie,String csrf)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).timeout(Duration.ofSeconds(5));
        if(bearer!=null)builder.header("Authorization","Bearer "+bearer);if(cookie!=null)builder.header("Cookie",cookie);if(csrf!=null)builder.header("X-Nexus-CSRF",csrf);
        if(body!=null)builder.header("Content-Type","application/json");
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String token)throws Exception{var response=request("POST","session",Map.of("remember",true),token,null,null);assertThat(response.statusCode()).isEqualTo(200);return response;}
    String cookie(HttpResponse<?> response){return response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];}
    JsonNode issued()throws Exception{var response=request("POST","access/tokens",Map.of("name","Restart viewer","role","operator","expiresAt",Instant.now().plusSeconds(7200).toString()),ADMIN,null,null);assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body());}
    @Test void persistentLoginSurvivesIndependentJvmsAndNeverRevivesInvalidCredentials()throws Exception{
        try{
            start(ADMIN);var adminLogin=login(ADMIN);String adminCookie=cookie(adminLogin);var original=JSON.readTree(adminLogin.body());
            var role=issued();String roleToken=role.path("token").asText(),roleId=role.path("tokenId").asText();var roleLogin=login(roleToken);String roleCookie=cookie(roleLogin),roleCsrf=JSON.readTree(roleLogin.body()).path("csrfToken").asText();
            try(var files=Files.list(directory.resolve("sessions"))){for(var file:files.filter(p->p.toString().endsWith(".json")).toList())assertThat(Files.readString(file)).doesNotContain(ADMIN,roleToken,adminCookie.substring(adminCookie.indexOf('=')+1));}
            stop();start(ADMIN);
            var restored=request("GET","session",null,null,adminCookie,null);assertThat(restored.statusCode()).isEqualTo(200);assertThat(JSON.readTree(restored.body())).isEqualTo(original);
            assertThat(request("GET","session",null,null,roleCookie,null).statusCode()).isEqualTo(200);
            assertThat(request("POST","session/logout",null,null,adminCookie,null).statusCode()).isEqualTo(403);
            assertThat(request("PUT","access/tokens/"+roleId,Map.of("name","Read only after restart","role","viewer","expiresAt",Instant.now().plusSeconds(7200).toString(),"expectedVersion",1),ADMIN,null,null).statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(request("GET","session",null,null,roleCookie,null).body()).path("role").asText()).isEqualTo("viewer");
            assertThat(request("POST","messages",Map.of(),null,roleCookie,roleCsrf).statusCode()).isEqualTo(403);
            var database=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));database.update("UPDATE role_tokens SET expires_at=now()-interval '1 second' WHERE token_id=?",UUID.fromString(roleId));
            assertThat(request("GET","session",null,null,roleCookie,null).statusCode()).isEqualTo(401);
            var revoked=issued();String revokedCookie=cookie(login(revoked.path("token").asText()));assertThat(request("POST","access/tokens/"+revoked.path("tokenId").asText()+"/revoke",Map.of("expectedVersion",1),ADMIN,null,null).statusCode()).isEqualTo(204);
            assertThat(request("GET","session",null,null,revokedCookie,null).statusCode()).isEqualTo(401);
            var rotated=issued();String rotatedCookie=cookie(login(rotated.path("token").asText()));assertThat(request("POST","access/tokens/"+rotated.path("tokenId").asText()+"/rotate",Map.of("expectedVersion",1),ADMIN,null,null).statusCode()).isEqualTo(200);
            assertThat(request("GET","session",null,null,rotatedCookie,null).statusCode()).isEqualTo(401);
            var survivingRole=issued();String survivorCookie=cookie(login(survivingRole.path("token").asText()));String adminToRotate=cookie(login(ADMIN));
            assertThat(request("POST","session/logout",null,null,adminCookie,original.path("csrfToken").asText()).statusCode()).isEqualTo(204);
            stop();start(NEXT);
            assertThat(request("GET","session",null,null,adminCookie,null).statusCode()).isEqualTo(401);
            assertThat(request("GET","session",null,null,adminToRotate,null).statusCode()).isEqualTo(401);
            assertThat(request("GET","session",null,null,roleCookie,null).statusCode()).isEqualTo(401);
            assertThat(request("GET","session",null,null,revokedCookie,null).statusCode()).isEqualTo(401);
            assertThat(request("GET","session",null,null,rotatedCookie,null).statusCode()).isEqualTo(401);
            assertThat(request("GET","session",null,null,survivorCookie,null).statusCode()).isEqualTo(200);
            assertThat(request("GET","session",null,ADMIN,null,null).statusCode()).isEqualTo(401);assertThat(request("GET","session",null,NEXT,null,null).statusCode()).isEqualTo(200);
        }finally{stop();}
    }
}
