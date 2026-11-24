package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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

/** Device snapshots are observable through HTTP, without merging the independent CORE profile. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompanionFlowTest {
    static final String TOKEN="growth-sync-test-token-at-least-32-characters";
    static final String FIRST_BOOT="11111111111111111111111111111111", SECOND_BOOT="22222222222222222222222222222222";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883).withCopyToContainer(Transferable.of("""
            listener 1883
            allow_anonymous false
            password_file /tmp/passwd
            acl_file /mosquitto/config/acl
            message_size_limit 3072
            """),"/mosquitto/config/mosquitto.conf")
        .withCopyToContainer(Transferable.of("""
            user nexus-core
            topic write nexus/v1/devices/+/inbox
            topic write nexus/v1/devices/+/config
            topic read nexus/v1/devices/+/acks
            topic read nexus/v1/devices/+/state
            topic read nexus/v1/devices/+/config_acks
            topic read nexus/v1/devices/+/telemetry
            topic read nexus/v1/devices/+/growth_status
            topic read nexus/v1/devices/+/companion_status
            topic read nexus/v1/devices/+/appearance_acks
            topic write nexus/v1/devices/+/appearance
            pattern read nexus/v1/devices/%u/appearance
            pattern write nexus/v1/devices/%u/companion_status
            pattern write nexus/v1/devices/%u/appearance_acks
            pattern write nexus/v1/devices/%u/state
            pattern write nexus/v1/devices/%u/growth_status
            """),"/mosquitto/config/acl")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core core-test-password && for device in growth-owner growth-order growth-invalid growth-stale growth-spy growth-peer; do mosquitto_passwd -b /tmp/passwd $device device-test-password; done && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("nexus.admin-token",()->TOKEN);
        p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"core-test-password");p.add("nexus.mqtt.allow-insecure",()->"true");
        p.add("nexus.mqtt.ca-cert",()->"");p.add("nexus.growth-sync.enabled",()->"true");p.add("nexus.companion.enabled",()->"true");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired dev.nexus.core.device.CompanionService companions;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path))
            .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    void register(String id)throws Exception {
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"成长设备\"}",TOKEN).statusCode()).isEqualTo(201);
    }
    MqttClient device(String id)throws Exception {
        var c=new MqttClient("tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883),"growth-"+UUID.randomUUID(),new MemoryPersistence());
        var o=new MqttConnectOptions();o.setUserName(id);o.setPassword("device-test-password".toCharArray());c.connect(o);return c;
    }
    void waitForCore()throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(!json.readTree(request("GET","status","",TOKEN).body()).path("mqtt").asText().equals("ready")) {
            assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
        }
    }
    static String snapshot(String id,String boot,int sequence,long epoch,int coins) {
        return "{\"schemaVersion\":1,\"deviceId\":\""+id+"\",\"bootId\":\""+boot+"\",\"reportSequence\":"+sequence+",\"observedAt\":"+epoch+
            ",\"growthSchema\":4,\"growth\":{\"revision\":7,\"level\":2,\"experience\":15,\"experienceToNext\":128,\"coins\":"+coins+
            ",\"intimacy\":10,\"mood\":20,\"energy\":80,\"hunger\":60,\"health\":100,\"state\":\"idle\",\"sleeping\":false,"+
            "\"taskDay\":20770,\"taskProgress\":[1,2,0],\"taskClaimed\":0,\"achievements\":0,\"timeReady\":true,"+
            "\"unsaved\":true,\"storageReady\":true,\"saveRecovered\":false,\"environment\":\"none\"}}";
    }
    void send(MqttClient d,String id,String body)throws Exception {d.publish("nexus/v1/devices/"+id+"/growth_status",body.getBytes(StandardCharsets.UTF_8),1,false);}
    JsonNode view(String id)throws Exception {
        var r=request("GET","devices/"+id+"/growth","",TOKEN);assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return json.readTree(r.body());
    }
    JsonNode await(String id,Predicate<JsonNode> check)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(6).toNanos();JsonNode result;
        do {result=view(id);if(check.test(result))return result;assertThat(System.nanoTime()).withFailMessage(result.toString()).isLessThan(until);Thread.sleep(30);}while(true);
    }
    String companionStatus(String id,int sequence,long epoch,int revision,String last) {
        return "{\"schemaVersion\":1,\"catalogVersion\":1,\"deviceId\":\""+id+"\",\"bootId\":\""+FIRST_BOOT+"\",\"reportSequence\":"+sequence+",\"observedAt\":"+epoch+
            ",\"revision\":1,\"appearanceRevision\":"+revision+",\"owned\":0,\"eligible\":65,\"themes\":1,\"writable\":true,\"lastCommand\":\""+last+"\",\"appearance\":{\"theme\":0,\"outfit\":0,\"slots\":[0,0,0]},\"best\":[0,0,0],\"played\":[0,0,0],\"focusCompleted\":0,\"memories\":[]}";
    }
    void online(MqttClient client,String id)throws Exception {
        client.publish("nexus/v1/devices/"+id+"/state",("{\"schemaVersion\":1,\"deviceId\":\""+id+"\",\"online\":true,\"observedAt\":"+Instant.now().getEpochSecond()+"}").getBytes(StandardCharsets.UTF_8),1,false);
    }
    void publish(MqttClient client,String id,String suffix,String body)throws Exception {client.publish("nexus/v1/devices/"+id+"/"+suffix,body.getBytes(StandardCharsets.UTF_8),1,false);}
    JsonNode space(String id)throws Exception {var r=request("GET","devices/"+id+"/companion","",TOKEN);assertThat(r.statusCode()).isEqualTo(200);return json.readTree(r.body());}
    JsonNode awaitSpace(String id,Predicate<JsonNode> condition)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(true) {var result=space(id);if(condition.test(result))return result;assertThat(System.nanoTime()).withFailMessage(result.toString()).isLessThan(until);Thread.sleep(40);}
    }
    String command(String id,int revision,int outfit) {return "{\"requestId\":\""+id+"\",\"expectedRevision\":"+revision+",\"appearance\":{\"theme\":0,\"outfit\":"+outfit+",\"slots\":[0,0,0]}}";}
    @Test void offlineAndUnownedWritesAreRejectedAndSubmissionIsNotCompletion()throws Exception {
        String id="growth-owner";register(id);waitForCore();String rid=UUID.randomUUID().toString();
        assertThat(request("GET","devices/"+id+"/companion","",null).statusCode()).isEqualTo(401);
        assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,0),TOKEN).statusCode()).isEqualTo(409);
        try(var d=device(id)) {
            var received=new java.util.concurrent.LinkedBlockingQueue<String>();
            d.subscribe("nexus/v1/devices/"+id+"/appearance",1,(topic,message)->received.add(new String(message.getPayload(),StandardCharsets.UTF_8)));
            publish(d,id,"companion_status",companionStatus(id,1,Instant.now().getEpochSecond(),0,"00000000000000000000000000000000"));
            awaitSpace(id,v->!v.path("snapshot").isNull());
            assertThat(request("DELETE","devices/"+id,"",TOKEN).statusCode()).isEqualTo(409);
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,0),TOKEN).statusCode()).isEqualTo(409);
            online(d,id);awaitSpace(id,v->v.path("online").asBoolean());
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,1),TOKEN).statusCode()).isEqualTo(400);
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,1,0),TOKEN).statusCode()).isEqualTo(409);
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,0),TOKEN).statusCode()).isEqualTo(202);
            String payload=received.poll(8,java.util.concurrent.TimeUnit.SECONDS);assertThat(payload).isNotNull();
            assertThat(json.readTree(payload).path("requestId").asText()).isEqualTo(rid);
            assertThat(space(id).path("requests").get(0).path("status").asText()).isEqualTo("sent");
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,0),TOKEN).statusCode()).isEqualTo(200);
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,1),TOKEN).statusCode()).isEqualTo(409);
            publish(d,id,"appearance_acks","{\"schemaVersion\":1,\"requestId\":\""+rid+"\",\"status\":\"applied\",\"appearanceRevision\":1,\"code\":0}");
            awaitSpace(id,v->v.path("requests").get(0).path("status").asText().equals("applied"));
            d.disconnect();
        }
    }
    @Test void staleRetainedAndMalformedSnapshotsDoNotBecomeWritable()throws Exception {
        String id="growth-invalid";register(id);waitForCore();long now=Instant.now().getEpochSecond();
        try(var d=device(id);var spy=device("growth-spy")) {
            String valid=companionStatus(id,1,now,0,"00000000000000000000000000000000");
            for(String bad:new String[]{valid.replace("\"owned\":0","\"owned\":4096"),valid.replace("\"outfit\":0","\"outfit\":1"),
                valid.replace("\"catalogVersion\":1","\"catalogVersion\":2"),valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),valid+" {}"})publish(d,id,"companion_status",bad);
            // MQTT 3 forwards a live retained publish with RETAIN=0. Test replay rejection
            // at the adapter boundary, where a retained subscription replay is flagged.
            companions.observation(id,valid.getBytes(StandardCharsets.UTF_8),true);
            publish(spy,id,"companion_status",valid.replace("\"reportSequence\":1","\"reportSequence\":999"));
            publish(d,id,"companion_status",companionStatus(id,2,now-180,0,"00000000000000000000000000000000"));
            var v=awaitSpace(id,x->x.path("snapshot").path("reportSequence").asInt()==2);
            assertThat(v.path("fresh").asBoolean()).isFalse();assertThat(v.path("snapshot").path("reportSequence").asInt()).isEqualTo(2);
            publish(d,id,"companion_status",companionStatus(id,3,now,0,"00000000000000000000000000000000"));
            awaitSpace(id,x->x.path("snapshot").path("reportSequence").asInt()==3);
            publish(d,id,"companion_status",companionStatus(id,2,now,9,"00000000000000000000000000000000"));
            publish(d,id,"companion_status",companionStatus(id,4,now,0,"00000000000000000000000000000000"));
            assertThat(awaitSpace(id,x->x.path("snapshot").path("reportSequence").asInt()==4).path("snapshot").path("appearanceRevision").asInt()).isZero();
            d.disconnect();spy.disconnect();
        }
    }
    @Test void writesRequireAdminRole() {
        String path="/api/v1/devices/a/companion/appearance";
        assertThat(dev.nexus.core.security.RoleTokens.permits("viewer","POST",path)).isFalse();
        assertThat(dev.nexus.core.security.RoleTokens.permits("operator","POST",path)).isFalse();
        assertThat(dev.nexus.core.security.RoleTokens.permits("admin","POST",path)).isTrue();
        assertThat(dev.nexus.core.security.RoleTokens.permits("viewer","GET","/api/v1/devices/a/companion")).isTrue();
    }
    @Test void actualFirmwarePersistsTheAppearanceBeforeCoreConfirmsIt()throws Exception {
        String peer=System.getenv("NEXUS_COMPANION_PEER");org.junit.jupiter.api.Assumptions.assumeTrue(peer!=null,"Run 04 tools/ci/test_companion_contract.py");
        String id="growth-peer";register(id);waitForCore();
        var process=new ProcessBuilder(peer,id).start();
        try(var reader=process.inputReader();var writer=process.outputWriter();var d=device(id)) {
            String initial=reader.readLine();assertThat(initial).isNotNull();
            var received=new java.util.concurrent.LinkedBlockingQueue<String>();
            d.subscribe("nexus/v1/devices/"+id+"/appearance",1,(topic,message)->received.add(new String(message.getPayload(),StandardCharsets.UTF_8)));
            online(d,id);publish(d,id,"companion_status",initial);
            awaitSpace(id,v->v.path("online").asBoolean()&&!v.path("snapshot").isNull());
            String rid=UUID.randomUUID().toString();
            assertThat(request("POST","devices/"+id+"/companion/appearance",command(rid,0,3),TOKEN).statusCode()).isEqualTo(202);
            String payload=received.poll(8,java.util.concurrent.TimeUnit.SECONDS);assertThat(payload).isNotNull();
            writer.write(payload+"\n");writer.flush();var output=json.readTree(reader.readLine());
            assertThat(output.path("ack").path("status").asText()).isEqualTo("applied");
            assertThat(output.path("snapshot").path("appearance").path("outfit").asInt()).isEqualTo(3);
            // Lose the ACK deliberately: the persisted command identity in the observation is the confirmation.
            publish(d,id,"companion_status",output.path("snapshot").toString());
            var confirmed=awaitSpace(id,v->v.path("requests").get(0).path("status").asText().equals("applied"));
            assertThat(confirmed.path("snapshot").path("appearanceRevision").asInt()).isEqualTo(1);
            writer.write(payload+"\n");writer.flush();var replay=json.readTree(reader.readLine());
            assertThat(replay.path("writes")).isEqualTo(output.path("writes"));
            assertThat(replay.path("ack").path("status").asText()).isEqualTo("applied");
            d.disconnect();
        } finally {process.destroy();process.waitFor();}
    }
}
