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
class DeviceGrowthFlowTest {
    static final String TOKEN="growth-sync-test-token-at-least-32-characters";
    static final String FIRST_BOOT="11111111111111111111111111111111", SECOND_BOOT="22222222222222222222222222222222";
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883).withCopyToContainer(Transferable.of("""
            listener 1883
            allow_anonymous false
            password_file /tmp/passwd
            acl_file /mosquitto/config/acl
            message_size_limit 2048
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
            pattern write nexus/v1/devices/%u/growth_status
            """),"/mosquitto/config/acl")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core core-test-password && for device in growth-owner growth-order growth-invalid growth-stale growth-spy growth-peer; do mosquitto_passwd -b /tmp/passwd $device device-test-password; done && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("nexus.admin-token",()->TOKEN);
        p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"core-test-password");p.add("nexus.mqtt.allow-insecure",()->"true");
        p.add("nexus.mqtt.ca-cert",()->"");p.add("nexus.growth-sync.enabled",()->"true");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
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
    @Test void deviceSnapshotIsSeparateFromCoreRewardsAndDoesNotClaimOnline()throws Exception {
        String id="growth-owner";register(id);
        assertThat(request("GET","devices/"+id+"/growth","",null).statusCode()).isEqualTo(401);
        assertThat(request("GET","devices/absent/growth","",TOKEN).statusCode()).isEqualTo(404);
        assertThat(view(id).path("snapshot").isNull()).isTrue();
        var coreBefore=json.readTree(request("GET","pet","",TOKEN).body());
        try(var d=device(id)) {
            waitForCore();send(d,id,snapshot(id,FIRST_BOOT,1,Instant.now().getEpochSecond(),72));
            var result=await(id,v->v.path("snapshot").path("growth").path("coins").asInt()==72);
            assertThat(result.path("fresh").asBoolean()).isTrue();
            assertThat(result.path("snapshot").path("growth").path("unsaved").asBoolean()).isTrue();
            assertThat(json.readTree(request("GET","pet","",TOKEN).body()).path("coins")).isEqualTo(coreBefore.path("coins"));
            var devices=json.readTree(request("GET","devices","",TOKEN).body()).path("items");
            for(var item:devices)if(item.path("deviceId").asText().equals(id))assertThat(item.path("online").asBoolean()).isFalse();
            var list=json.readTree(request("GET","pet/devices","",TOKEN).body());
            assertThat(list.path("items").toString()).contains(id).contains("72");d.disconnect();
        }
    }
    @Test void duplicateOutOfOrderAndPreviouslySeenBootCannotReplaceTheLatestSnapshot()throws Exception {
        String id="growth-order";register(id);long now=Instant.now().getEpochSecond();
        try(var d=device(id)) {
            waitForCore();send(d,id,snapshot(id,FIRST_BOOT,2,now,40));
            await(id,v->v.path("snapshot").path("growth").path("coins").asInt()==40);
            send(d,id,snapshot(id,FIRST_BOOT,2,now,999));send(d,id,snapshot(id,FIRST_BOOT,1,now,999));
            send(d,id,snapshot(id,FIRST_BOOT,9,now-1,999));
            send(d,id,snapshot(id,SECOND_BOOT,1,now,50));
            await(id,v->v.path("snapshot").path("growth").path("coins").asInt()==50);
            send(d,id,snapshot(id,FIRST_BOOT,10,now,999));
            send(d,id,snapshot(id,SECOND_BOOT,2,now,51));
            assertThat(await(id,v->v.path("snapshot").path("reportSequence").asInt()==2).path("snapshot").path("growth").path("coins").asInt()).isEqualTo(51);
            d.disconnect();
        }
    }
    @Test void invalidAndCrossDeviceSnapshotsCannotPoisonOrderingAndStaleDataStaysVisible()throws Exception {
        String id="growth-invalid";register(id);long now=Instant.now().getEpochSecond();
        try(var owner=device(id);var spy=device("growth-spy")) {
            waitForCore();String valid=snapshot(id,FIRST_BOOT,99,now,999);
            for(String body:new String[]{valid.replace(id,"growth-spy"),valid.replace("\"coins\":999","\"coins\":-1"),
                valid.replace("\"reportSequence\":99","\"reportSequence\":1.5"),valid.replace("\"revision\":7","\"revision\":7,\"revision\":7"),
                valid.replace("\"experienceToNext\":128","\"experienceToNext\":75"),valid.replace("\"taskClaimed\":0","\"taskClaimed\":2"),
                valid.replace("\"schemaVersion\":1","\"unknown\":true,\"schemaVersion\":1"),valid+" {}"})send(owner,id,body);
            send(spy,id,valid);send(owner,id,snapshot(id,FIRST_BOOT,1,now,72));
            assertThat(await(id,v->v.path("snapshot").path("reportSequence").asInt()==1).path("snapshot").path("growth").path("coins").asInt()).isEqualTo(72);
            owner.disconnect();spy.disconnect();
        }
        String stale="growth-stale";register(stale);
        try(var d=device(stale)) {
            send(d,stale,snapshot(stale,FIRST_BOOT,1,now-180,20));
            var result=await(stale,v->!v.path("snapshot").isNull());
            assertThat(result.path("fresh").asBoolean()).isFalse();
            assertThat(result.path("snapshot").path("growth").path("coins").asInt()).isEqualTo(20);
            assertThat(request("PUT","devices/"+stale+"/enabled","{\"enabled\":false}",TOKEN).statusCode()).isEqualTo(204);
            send(d,stale,snapshot(stale,FIRST_BOOT,2,now,999));
            // A subsequent owner update is the broker/HTTP processing barrier.
            d.disconnect();
        }
        try(var d=device(id)) {
            send(d,id,snapshot(id,FIRST_BOOT,2,now,73));await(id,v->v.path("snapshot").path("reportSequence").asInt()==2);
            assertThat(view(stale).path("snapshot").path("growth").path("coins").asInt()).isEqualTo(20);d.disconnect();
        }
    }
    @Test void actualFirmwareGrowthSnapshotRoundTripsWithoutChangingEitherRewardRule()throws Exception {
        String peer=System.getenv("NEXUS_PET_GROWTH_PEER");
        org.junit.jupiter.api.Assumptions.assumeTrue(peer!=null,"Run 04 tools/ci/test_growth_sync_contract.py for the hosted firmware producer");
        String id="growth-peer";register(id);waitForCore();
        var process=new ProcessBuilder(peer,id,Long.toString(Instant.now().getEpochSecond())).start();
        String report=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).isZero();
        try(var d=device(id)) {
            send(d,id,report);var result=await(id,v->!v.path("snapshot").isNull()).path("snapshot").path("growth");
            assertThat(result.path("coins").asInt()).isEqualTo(25);
            assertThat(result.path("experience").asInt()).isEqualTo(8);
            assertThat(result.path("taskProgress").get(0).asInt()).isEqualTo(1);d.disconnect();
        }
    }
}
