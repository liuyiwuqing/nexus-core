package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

/** Public HTTP and real PostgreSQL/MQTT. Registration is metadata, not signature attestation. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteOtaFlowTest {
    static final String TOKEN="remote-ota-test-token-at-least-32-characters";
    static final String BOOT="11111111111111111111111111111111";
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
            topic write nexus/v1/devices/+/ota
            topic read nexus/v1/devices/+/acks
            topic read nexus/v1/devices/+/state
            topic read nexus/v1/devices/+/config_acks
            topic read nexus/v1/devices/+/telemetry
            topic read nexus/v1/devices/+/ota_acks
            topic read nexus/v1/devices/+/ota_status
            pattern read nexus/v1/devices/%u/ota
            pattern write nexus/v1/devices/%u/ota_acks
            pattern write nexus/v1/devices/%u/ota_status
            """),"/mosquitto/config/acl")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core core-test-password && for device in ota-owner ota-spy ota-timeout ota-peer ota-invalid ota-reboot; do mosquitto_passwd -b /tmp/passwd $device device-test-password; done && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("nexus.admin-token",()->TOKEN);
        p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"core-test-password");p.add("nexus.mqtt.allow-insecure",()->"true");
        p.add("nexus.mqtt.ca-cert",()->"");p.add("nexus.ota.enabled",()->"true");p.add("nexus.ota.request-seconds",()->"3");
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
    String release(String id) {
        String payload="{\"format\":1,\"project\":\"nexus_pet\",\"board\":\"dodo-s3-lcd\",\"version\":\"0.4.1\",\"modelSha256\":\"none\",\"partitionSha256\":\""+"a".repeat(64)+
            "\",\"imageSha256\":\""+"c".repeat(64)+"\",\"image\":\"firmware.bin\",\"imageSize\":1024,\"releaseSequence\":10,\"configSchema\":1,\"growthSchema\":4}";
        return "{\"releaseId\":\""+id+"\",\"manifest\":{\"payload\":\""+Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"\",\"signature\":\""+
            Base64.getEncoder().encodeToString(new byte[384])+"\"}}";
    }
    @Test void releaseRegistrationIsAuthenticatedImmutableAndDoesNotInstall()throws Exception {
        String body=release("release-catalog");
        assertThat(request("POST","ota/releases",body,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","ota/releases",body.replace("\"releaseId\"","\"extra\":1,\"releaseId\""),TOKEN).statusCode()).isEqualTo(400);
        var created=request("POST","ota/releases",body,TOKEN);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        assertThat(request("POST","ota/releases",body,TOKEN).statusCode()).isEqualTo(200);
        assertThat(request("POST","ota/releases",body.replace("AAAA","BAAA"),TOKEN).statusCode()).isEqualTo(409);
        var catalog=request("GET","ota/releases","",TOKEN);
        assertThat(catalog.statusCode()).isEqualTo(200);assertThat(catalog.body()).contains("release-catalog","0.4.1");
    }
    MqttClient device(String id)throws Exception {
        var d=new MqttClient("tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883),"ota-"+UUID.randomUUID(),new MemoryPersistence()) {
            @Override public void close()throws MqttException {if(isConnected())disconnect();super.close();}
        };
        var options=new MqttConnectOptions();options.setUserName(id);options.setPassword("device-test-password".toCharArray());
        d.connect(options);return d;
    }
    JsonNode awaitView(String device,Predicate<JsonNode> condition)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();JsonNode last=null;
        do {
            var response=request("GET","devices/"+device+"/ota","",TOKEN);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            last=json.readTree(response.body());if(condition.test(last))return last;Thread.sleep(40);
        }while(System.nanoTime()<until);
        throw new AssertionError("OTA readback not observed: "+last);
    }
    String observation(String device,String boot,String state,int sequence,String request,String reboot,JsonNode target) {
        var out=json.createObjectNode();out.put("schemaVersion",1);out.put("deviceId",device);out.put("bootId",boot);
        out.put("observedAt",Instant.now().getEpochSecond());out.put("reportSequence",sequence);out.put("available",true);
        out.put("state",state);out.put("code",0);out.put("requestId",request);out.put("rebootId",reboot);
        out.put("writtenBytes",0);out.put("reservedSequence",target==null?0:10);
        var identity=out.putObject("identity");identity.put("project","nexus_pet");identity.put("board","dodo-s3-lcd");
        identity.put("modelSha256","none");identity.put("partitionSha256","a".repeat(64));identity.put("configSchema",1);identity.put("growthSchema",4);
        if(target!=null)out.set("target",target);return out.toString();
    }
    void publish(MqttClient d,String device,String suffix,String body)throws Exception {
        d.publish("nexus/v1/devices/"+device+"/"+suffix,body.getBytes(StandardCharsets.UTF_8),1,false);
    }
    void ready()throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(!json.readTree(request("GET","status","",TOKEN).body()).path("mqtt").asText().equals("ready")) {
            assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
        }
    }
    @Test void lostInstallAckBlocksNewJobUntilFreshPostExpiryReadback()throws Exception {
        String id="ota-timeout",rid="install-timeout",path="devices/"+id+"/ota";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"未知结果\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST","ota/releases",release("release-timeout"),TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            var wire=new LinkedBlockingQueue<MqttMessage>();d.subscribe("nexus/v1/devices/"+id+"/ota",1,(t,m)->wire.add(m));ready();
            publish(d,id,"ota_status",observation(id,BOOT,"idle",1,"","",null));awaitView(id,v->v.path("canInstall").asBoolean());
            String body="{\"requestId\":\""+rid+"\",\"releaseId\":\"release-timeout\",\"bootId\":\""+BOOT+"\"}";
            assertThat(request("POST",path+"/deployments",body,TOKEN).statusCode()).isEqualTo(202);
            assertThat(wire.poll(8,TimeUnit.SECONDS)).isNotNull();
            awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("unconfirmed"));
            assertThat(request("POST",path+"/deployments",body.replace(rid,"another-job"),TOKEN).statusCode()).isEqualTo(409);
            publish(d,id,"ota_status",observation(id,BOOT,"idle",2,"","",null));
            var resolved=awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("expired"));
            assertThat(resolved.path("canInstall").asBoolean()).isTrue();
            assertThat(request("POST",path+"/deployments",body,TOKEN).statusCode()).isEqualTo(200);
            assertThat(wire.poll(400,TimeUnit.MILLISECONDS)).isNull();
        }
    }
    @Test void oldBootReportsCannotReplaceCurrentBootOrEnableStaleCommand()throws Exception {
        String id="ota-invalid";assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"启动隔离\"}",TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            ready();publish(d,id,"ota_status",observation(id,BOOT,"idle",1,"","",null));awaitView(id,v->v.path("fresh").asBoolean());
            String next="22222222222222222222222222222222";
            publish(d,id,"ota_status",observation(id,next,"idle",1,"","",null));awaitView(id,v->v.path("observation").path("bootId").asText().equals(next));
            publish(d,id,"ota_status",observation(id,BOOT,"idle",2,"","",null));
            // Wait for the old report to be handled, then check the externally visible boot.
            Thread.sleep(250);
            var view=json.readTree(request("GET","devices/"+id+"/ota","",TOKEN).body());
            assertThat(view.path("observation").path("bootId").asText()).isEqualTo(next);
        }
    }
    @Test void installNeedsReadbackAndRebootRequiresSeparateConfirmation()throws Exception {
        String id="ota-owner",rid="install-owner",path="devices/"+id+"/ota";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"升级测试\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST","ota/releases",release("release-owner"),TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            var commands=new LinkedBlockingQueue<MqttMessage>();d.subscribe("nexus/v1/devices/"+id+"/ota",1,(topic,message)->commands.add(message));
            long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            while(!json.readTree(request("GET","status","",TOKEN).body()).path("mqtt").asText().equals("ready")) {
                assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
            }
            publish(d,id,"ota_status",observation(id,BOOT,"idle",1,"","",null));
            awaitView(id,v->v.path("canInstall").asBoolean());
            try(var spy=device("ota-spy")) {
                var leaked=new LinkedBlockingQueue<MqttMessage>();spy.subscribe("nexus/v1/devices/"+id+"/ota",1,(t,m)->leaked.add(m));
                publish(spy,id,"ota_status",observation(id,"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","idle",1,"","",null));
                Thread.sleep(150);
                assertThat(json.readTree(request("GET",path,"",TOKEN).body()).path("observation").path("bootId").asText()).isEqualTo(BOOT);
                String scoped="{\"requestId\":\""+rid+"\",\"releaseId\":\"release-owner\",\"bootId\":\""+BOOT+"\"}";
                assertThat(request("POST",path+"/deployments",scoped,TOKEN).statusCode()).isEqualTo(202);
                assertThat(leaked.poll(300,TimeUnit.MILLISECONDS)).isNull();
            }
            String body="{\"requestId\":\""+rid+"\",\"releaseId\":\"release-owner\",\"bootId\":\""+BOOT+"\"}";
            var created=request("POST",path+"/deployments",body,TOKEN);
            assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
            var first=commands.poll(8,TimeUnit.SECONDS);assertThat(first).isNotNull();assertThat(first.isRetained()).isFalse();
            var command=json.readTree(first.getPayload());assertThat(command.path("operation").asText()).isEqualTo("install");
            assertThat(command.path("target").path("imageSha256").asText()).isEqualTo("c".repeat(64));
            assertThat(request("POST",path+"/deployments",body,TOKEN).statusCode()).isEqualTo(200);
            assertThat(request("POST",path+"/deployments",body.replace("release-owner","release-catalog"),TOKEN).statusCode()).isEqualTo(409);
            publish(d,id,"ota_acks","{\"schemaVersion\":1,\"requestId\":\""+rid+"\",\"commandId\":\""+rid+"\",\"operation\":\"install\",\"accepted\":true,\"status\":\"accepted\",\"code\":0}");
            awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("accepted"));
            publish(d,id,"ota_status",observation(id,BOOT,"pending_reboot",2,rid,"",command.get("target")));
            awaitView(id,v->v.path("canReboot").asBoolean());
            assertThat(request("POST",path+"/deployments/"+rid+"/reboot","{\"requestId\":\"reboot-owner\",\"bootId\":\""+BOOT+"\"}",TOKEN).statusCode()).isEqualTo(400);
            String confirmation="{\"requestId\":\"reboot-owner\",\"bootId\":\""+BOOT+"\",\"confirm\":\"REBOOT_DEVICE\"}";
            assertThat(request("POST",path+"/deployments/"+rid+"/reboot",confirmation,TOKEN).statusCode()).isEqualTo(202);
            var reboot=commands.poll(8,TimeUnit.SECONDS);assertThat(reboot).isNotNull();
            assertThat(json.readTree(reboot.getPayload()).path("operation").asText()).isEqualTo("reboot");
            assertThat(request("POST",path+"/deployments/"+rid+"/reboot",confirmation,TOKEN).statusCode()).isEqualTo(200);
            publish(d,id,"ota_status",observation(id,BOOT,"healthy",3,rid,"reboot-owner",command.get("target")));
            Thread.sleep(150);
            assertThat(json.readTree(request("GET",path,"",TOKEN).body()).path("deployments").get(0).path("status").asText()).isEqualTo("rebooting");
            publish(d,id,"ota_status",observation(id,"22222222222222222222222222222222","healthy",1,rid,"reboot-owner",command.get("target")));
            awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("healthy"));
            var audit=request("GET",path+"/deployments/"+rid+"/audit","",TOKEN);
            assertThat(audit.statusCode()).isEqualTo(200);assertThat(audit.body()).contains("publish_attempt","healthy","reboot_created");
        }
    }
    @Test void rebootUnknownCannotBeForcedByChangingCommandId()throws Exception {
        String id="ota-reboot",rid="install-reboot",path="devices/"+id+"/ota";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"重启确认\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST","ota/releases",release("release-reboot"),TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            ready();var wire=new LinkedBlockingQueue<MqttMessage>();d.subscribe("nexus/v1/devices/"+id+"/ota",1,(t,m)->wire.add(m));
            publish(d,id,"ota_status",observation(id,BOOT,"idle",1,"","",null));awaitView(id,v->v.path("canInstall").asBoolean());
            assertThat(request("POST",path+"/deployments","{\"requestId\":\""+rid+"\",\"releaseId\":\"release-reboot\",\"bootId\":\""+BOOT+"\"}",TOKEN).statusCode()).isEqualTo(202);
            var install=wire.poll(8,TimeUnit.SECONDS);assertThat(install).isNotNull();
            var target=json.readTree(install.getPayload()).get("target");
            publish(d,id,"ota_status",observation(id,BOOT,"pending_reboot",2,rid,"",target));awaitView(id,v->v.path("canReboot").asBoolean());
            String body="{\"requestId\":\"reboot-lost\",\"bootId\":\""+BOOT+"\",\"confirm\":\"REBOOT_DEVICE\"}";
            String rebootPath=path+"/deployments/"+rid+"/reboot";
            assertThat(request("POST",rebootPath,body,TOKEN).statusCode()).isEqualTo(202);assertThat(wire.poll(8,TimeUnit.SECONDS)).isNotNull();
            awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("unconfirmed"));
            assertThat(request("POST",rebootPath,body.replace("reboot-lost","reboot-force"),TOKEN).statusCode()).isEqualTo(409);
            assertThat(request("POST",rebootPath,body,TOKEN).statusCode()).isEqualTo(200);
            publish(d,id,"ota_status",observation(id,BOOT,"pending_reboot",3,rid,"",target));awaitView(id,v->v.path("canReboot").asBoolean());
            assertThat(request("POST",rebootPath,body.replace("reboot-lost","reboot-after-readback"),TOKEN).statusCode()).isEqualTo(202);
        }
    }
    JsonNode peerLine(java.io.BufferedReader output)throws Exception {
        String line=java.util.concurrent.CompletableFuture.supplyAsync(()-> {
            try{return output.readLine();}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        }).get(5,TimeUnit.SECONDS);
        assertThat(line).isNotNull();return json.readTree(line);
    }
    @Test void realFirmwareTransactionRoundTripsInstallAndReboot()throws Exception {
        String peer=System.getenv("NEXUS_PET_OTA_PEER");
        org.junit.jupiter.api.Assumptions.assumeTrue(peer!=null,"Run 04 tools/ci/test_remote_ota_contract.py for the real C transaction peer");
        String id="ota-peer",rid="install-peer",path="devices/"+id+"/ota";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"C 协议互通\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("POST","ota/releases",release("release-peer"),TOKEN).statusCode()).isEqualTo(201);
        var process=new ProcessBuilder(peer,id).start();
        try(var input=process.outputWriter(StandardCharsets.UTF_8);var output=process.inputReader(StandardCharsets.UTF_8);var d=device(id)) {
            ready();var wire=new LinkedBlockingQueue<MqttMessage>();d.subscribe("nexus/v1/devices/"+id+"/ota",1,(t,m)->wire.add(m));
            publish(d,id,"ota_status",peerLine(output).get("status").toString());awaitView(id,v->v.path("canInstall").asBoolean());
            assertThat(request("POST",path+"/deployments","{\"requestId\":\""+rid+"\",\"releaseId\":\"release-peer\",\"bootId\":\""+BOOT+"\"}",TOKEN).statusCode()).isEqualTo(202);
            var packet=wire.poll(8,TimeUnit.SECONDS);assertThat(packet).isNotNull();JsonNode result=null;
            for(int repeat=0;repeat<2;repeat++) {
                input.write(new String(packet.getPayload(),StandardCharsets.UTF_8));input.newLine();input.flush();result=peerLine(output);
                assertThat(result.path("installs").asInt()).isEqualTo(1);assertThat(result.path("ack").path("accepted").asBoolean()).isTrue();
            }
            publish(d,id,"ota_acks",result.get("ack").toString());publish(d,id,"ota_status",result.get("status").toString());
            awaitView(id,v->v.path("canReboot").asBoolean());
            assertThat(request("POST",path+"/deployments/"+rid+"/reboot","{\"requestId\":\"reboot-peer\",\"bootId\":\""+BOOT+"\",\"confirm\":\"REBOOT_DEVICE\"}",TOKEN).statusCode()).isEqualTo(202);
            packet=wire.poll(8,TimeUnit.SECONDS);assertThat(packet).isNotNull();
            input.write(new String(packet.getPayload(),StandardCharsets.UTF_8));input.newLine();input.flush();result=peerLine(output);
            assertThat(result.path("reboots").asInt()).isEqualTo(1);assertThat(result.path("status").path("state").asText()).isEqualTo("healthy");
            publish(d,id,"ota_acks",result.get("ack").toString());publish(d,id,"ota_status",result.get("status").toString());
            awaitView(id,v->v.path("deployments").get(0).path("status").asText().equals("healthy"));
        }finally{process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}
    }

}
