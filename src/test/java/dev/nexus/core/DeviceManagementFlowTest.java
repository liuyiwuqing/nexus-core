package dev.nexus.core;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

/** Real HTTP/PostgreSQL/MQTT; the device is a protocol peer, not a physical ESP32. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceManagementFlowTest {
    static final String TOKEN="management-test-token-at-least-32-characters";
    static final String CONFIG="""
        {"schemaVersion":1,"timezone":"Asia/Shanghai","language":"zh-CN","volume":25,"brightness":80,
         "muted":false,"dnd":{"enabled":false,"manual":false,"startMinute":1320,"endMinute":420,"allowCritical":true},
         "message":{"queueDepth":8,"durationMs":8000}}
        """;
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:17-alpine");
    @Container static final GenericContainer<?> MQTT=new GenericContainer<>("eclipse-mosquitto:2.0.22")
        .withExposedPorts(1883)
        .withCopyToContainer(Transferable.of("""
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
            pattern read nexus/v1/devices/%u/inbox
            pattern read nexus/v1/devices/%u/config
            pattern write nexus/v1/devices/%u/acks
            pattern write nexus/v1/devices/%u/state
            pattern write nexus/v1/devices/%u/config_acks
            pattern write nexus/v1/devices/%u/telemetry
            """),"/mosquitto/config/acl")
        .withCommand("sh","-c","mosquitto_passwd -b -c /tmp/passwd nexus-core core-test-password && for device in dodo-management dodo-update dodo-reconcile dodo-invalid dodo-wire dodo-scoped dodo-spy dodo-history dodo-network; do mosquitto_passwd -b /tmp/passwd $device device-test-password; done && chmod 644 /tmp/passwd && exec mosquitto -c /mosquitto/config/mosquitto.conf")
        .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("nexus.admin-token",()->TOKEN);
        p.add("nexus.mqtt.uri",()->"tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883));
        p.add("nexus.mqtt.password",()->"core-test-password");p.add("nexus.mqtt.allow-insecure",()->"true");
        p.add("nexus.mqtt.ca-cert",()->"");
        p.add("nexus.management.request-seconds",()->"10");
    }
    @LocalServerPort int port;
    @Test void voiceMemoryEndpointRemainsDisabledByDefault()throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp/memory"))
            .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired dev.nexus.core.device.TelemetryHistoryService history;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String method,String path,String body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path))
            .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return http.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    MqttClient device()throws Exception { return device("dodo-management"); }
    MqttClient device(String id)throws Exception {
        var d=new MqttClient("tcp://"+MQTT.getHost()+":"+MQTT.getMappedPort(1883),"management-"+UUID.randomUUID(),new MemoryPersistence());
        var options=new MqttConnectOptions();options.setUserName(id);options.setPassword("device-test-password".toCharArray());
        d.connect(options);return d;
    }
    JsonNode awaitTelemetry()throws Exception { return awaitTelemetry("dodo-management"); }
    JsonNode awaitTelemetry(String device)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();JsonNode value=null;
        do {
            var response=request("GET","devices/"+device+"/management","",TOKEN);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            value=json.readTree(response.body());
            if(value.path("fresh").asBoolean())return value;
            Thread.sleep(40);
        }while(System.nanoTime()<until);
        throw new AssertionError("Telemetry was not observed: "+value);
    }
    void waitForCore()throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(!json.readTree(request("GET","status","",TOKEN).body()).path("mqtt").asText().equals("ready")) {
            assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
        }
    }
    String telemetry(String device,long revision,String config) {
        return "{\"schemaVersion\":1,\"deviceId\":\""+device+"\",\"observedAt\":"+Instant.now().getEpochSecond()+
            ",\"firmwareVersion\":\"0.4.0-dev\",\"configRevision\":"+revision+",\"configWritable\":true,\"muted\":false,\"dnd\":false,"+
            "\"volume\":"+json.readTree(config).get("volume").asInt()+",\"brightness\":80,\"unread\":0,\"freeHeap\":100000,\"minFreeHeap\":90000,\"config\":"+config+"}";
    }
    JsonNode awaitRequest(String device,String id,String status)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(status.equals("unconfirmed")?18:8).toNanos();JsonNode last=null;
        do {
            for(var entry:json.readTree(request("GET","devices/"+device+"/management","",TOKEN).body()).path("configRequests")) {
                if(entry.path("requestId").asText().equals(id)) {last=entry;if(entry.path("status").asText().equals(status))return entry;}
            }
            Thread.sleep(40);
        }while(System.nanoTime()<until);
        throw new AssertionError("Configuration did not become "+status+": "+last);
    }
    String historyWindow(long from,long to) {
        return "from="+java.net.URLEncoder.encode(Instant.ofEpochSecond(from).toString(),StandardCharsets.UTF_8)
            +"&to="+java.net.URLEncoder.encode(Instant.ofEpochSecond(to).toString(),StandardCharsets.UTF_8);
    }
    JsonNode telemetryHistory(String id,String query)throws Exception {
        var response=request("GET","devices/"+id+"/telemetry?"+query,"",TOKEN);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    JsonNode awaitHistoryPoints(String id,long from,long to,int count)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();JsonNode last=null;
        do {
            last=telemetryHistory(id,historyWindow(from,to)+"&bucket=raw");
            if(last.path("points").size()==count)return last;
            Thread.sleep(40);
        }while(System.nanoTime()<until);
        throw new AssertionError("Expected "+count+" telemetry points: "+last);
    }
    void publishTelemetryAt(MqttClient d,String id,long observedAt,int volume)throws Exception {
        String value=telemetry(id,0,CONFIG.replace("\"volume\":25","\"volume\":"+volume))
            .replaceFirst("\"observedAt\":[0-9]+","\"observedAt\":"+observedAt);
        d.publish("nexus/v1/devices/"+id+"/telemetry",value.getBytes(StandardCharsets.UTF_8),1,false);
    }
    void publishNetworkTelemetry(MqttClient d,String id,long observedAt,String ip,int rssi,int volume)throws Exception {
        String value=telemetry(id,0,CONFIG.replace("\"volume\":25","\"volume\":"+volume))
            .replaceFirst("\"observedAt\":[0-9]+","\"observedAt\":"+observedAt)
            .replace("\"freeHeap\"","\"wifiIp\":\""+ip+"\",\"wifiRssi\":"+rssi+",\"freeHeap\"");
        d.publish("nexus/v1/devices/"+id+"/telemetry",value.getBytes(StandardCharsets.UTF_8),1,false);
    }
    @Test void configurationReusesRequestAndOnlyDeviceAckConfirmsSaving()throws Exception {
        String id="dodo-update",path="devices/"+id+"/management",rid=UUID.randomUUID().toString();
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"配置测试\"}",TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            waitForCore();var wire=new LinkedBlockingQueue<MqttMessage>();
            d.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->wire.add(message));
            d.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,0,CONFIG).getBytes(StandardCharsets.UTF_8),1,true);
            awaitTelemetry(id);
            String desired=CONFIG.replace("\"volume\":25","\"volume\":30");
            String body="{\"requestId\":\""+rid+"\",\"expectedRevision\":0,\"config\":"+desired+"}";
            var created=request("POST",path+"/config-requests",body,TOKEN);
            assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(202);
            assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(200);
            assertThat(request("POST",path+"/config-requests",body.replace("\"volume\":30","\"volume\":40"),TOKEN).statusCode()).isEqualTo(409);
            var packet=wire.poll(8,TimeUnit.SECONDS);assertThat(packet).isNotNull();assertThat(packet.isRetained()).isFalse();
            var envelope=json.readTree(packet.getPayload());
            assertThat(envelope.path("requestId").asText()).isEqualTo(rid);
            assertThat(envelope.path("config").path("volume").asInt()).isEqualTo(30);
            assertThat(envelope.path("expiresAt").asLong()-envelope.path("issuedAt").asLong()).isEqualTo(10);
            awaitRequest(id,rid,"sent");
            String ack="{\"schemaVersion\":1,\"requestId\":\""+rid+"\",\"status\":\"applied\",\"revision\":1,\"code\":0}";
            d.publish("nexus/v1/devices/"+id+"/config_acks",ack.getBytes(StandardCharsets.UTF_8),1,false);
            assertThat(awaitRequest(id,rid,"applied").path("reportedRevision").asLong()).isEqualTo(1);
            var audit=request("GET",path+"/config-requests/"+rid+"/audit","",TOKEN);
            assertThat(audit.statusCode()).isEqualTo(200);assertThat(audit.body()).contains("created","publish_attempt","applied").doesNotContain(TOKEN);
            d.disconnect();
        }
    }
    @Test void renameChangesOnlyDisplayNameAndRejectsStaleEdits()throws Exception {
        String id="dodo-rename",path="devices/"+id+"/name";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"桌面宠物\"}",TOKEN).statusCode()).isEqualTo(201);
        String change="{\"name\":\"  书房伙伴  \",\"expectedName\":\"桌面宠物\"}";
        assertThat(request("PATCH",path,change,null).statusCode()).isEqualTo(401);
        var renamed=request("PATCH",path,change,TOKEN);
        assertThat(renamed.statusCode()).withFailMessage(renamed.body()).isEqualTo(200);
        assertThat(json.readTree(renamed.body()).path("name").asText()).isEqualTo("书房伙伴");
        assertThat(request("PATCH",path,change,TOKEN).statusCode()).isEqualTo(200); // Safe retry after an unknown HTTP result.
        var stale=request("PATCH",path,"{\"name\":\"旧页面的编辑\",\"expectedName\":\"桌面宠物\"}",TOKEN);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("device_name_conflict");
        for(String invalid:new String[]{"{}","{\"name\":\" \",\"expectedName\":\"书房伙伴\"}",
            "{\"name\":\"换\\n行\",\"expectedName\":\"书房伙伴\"}",
            "{\"name\":\""+"a".repeat(81)+"\",\"expectedName\":\"书房伙伴\"}"})
            assertThat(request("PATCH",path,invalid,TOKEN).statusCode()).withFailMessage(invalid).isEqualTo(400);
        assertThat(request("PATCH","devices/missing-rename/name",change,TOKEN).statusCode()).isEqualTo(404);
        JsonNode device=null;
        for(var item:json.readTree(request("GET","devices","",TOKEN).body()).path("items"))
            if(id.equals(item.path("deviceId").asText()))device=item;
        assertThat(device).isNotNull();
        assertThat(device.path("name").asText()).isEqualTo("书房伙伴");
        assertThat(device.path("enabled").asBoolean()).isTrue();
        assertThat(request("GET","devices/"+id+"/management","",TOKEN).statusCode()).isEqualTo(200);
    }
    @Test void simultaneousRenameHasOneWinner()throws Exception {
        String id="dodo-rename-race",path="devices/"+id+"/name";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"原名称\"}",TOKEN).statusCode()).isEqualTo(201);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var jobs=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=jobs.submit(()->{start.await();return request("PATCH",path,"{\"name\":\"名称甲\",\"expectedName\":\"原名称\"}",TOKEN);});
            var second=jobs.submit(()->{start.await();return request("PATCH",path,"{\"name\":\"名称乙\",\"expectedName\":\"原名称\"}",TOKEN);});
            start.countDown();var a=first.get(5,TimeUnit.SECONDS);var b=second.get(5,TimeUnit.SECONDS);
            assertThat(new int[]{a.statusCode(),b.statusCode()}).containsExactlyInAnyOrder(200,409);
            String winner=json.readTree(a.statusCode()==200?a.body():b.body()).path("name").asText();
            JsonNode device=null;
            for(var item:json.readTree(request("GET","devices","",TOKEN).body()).path("items"))
                if(id.equals(item.path("deviceId").asText()))device=item;
            assertThat(device).isNotNull();assertThat(device.path("name").asText()).isEqualTo(winner);
        }
    }
    @Test void lostAckRemainsUnconfirmedUntilFreshConfigurationReadbackResolvesIt()throws Exception {
        String id="dodo-reconcile",path="devices/"+id+"/management",rid=UUID.randomUUID().toString();
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"丢失确认测试\"}",TOKEN).statusCode()).isEqualTo(201);
        try(var d=device(id)) {
            waitForCore();var wire=new LinkedBlockingQueue<MqttMessage>();
            d.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->wire.add(message));
            d.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,0,CONFIG).getBytes(StandardCharsets.UTF_8),1,true);
            awaitTelemetry(id);
            String desired=CONFIG.replace("\"volume\":25","\"volume\":30");
            String body="{\"requestId\":\""+rid+"\",\"expectedRevision\":0,\"config\":"+desired+"}";
            assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(202);
            assertThat(wire.poll(8,TimeUnit.SECONDS)).isNotNull();
            assertThat(awaitRequest(id,rid,"unconfirmed").path("lastError").asText()).isEqualTo("config_ack_timeout");
            assertThat(request("POST",path+"/config-requests",body.replace(rid,UUID.randomUUID().toString()),TOKEN).statusCode()).isEqualTo(409);
            d.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,1,desired).getBytes(StandardCharsets.UTF_8),1,true);
            assertThat(awaitRequest(id,rid,"applied").path("confirmationSource").asText()).isEqualTo("telemetry");
            String next="{\"requestId\":\""+UUID.randomUUID()+"\",\"expectedRevision\":1,\"config\":"+CONFIG+"}";
            assertThat(request("POST",path+"/config-requests",next,TOKEN).statusCode()).isEqualTo(202);
            d.disconnect();
        }
    }
    @Test void managementRejectsInvalidInputAndAcknowledgmentsAndWaitsForConfigurationReadback()throws Exception {
        String id="dodo-invalid",path="devices/"+id+"/management",rid=UUID.randomUUID().toString();
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"校验测试\"}",TOKEN).statusCode()).isEqualTo(201);
        String desired=CONFIG.replace("\"volume\":25","\"volume\":30");
        String body="{\"requestId\":\""+rid+"\",\"expectedRevision\":2,\"config\":"+desired+"}";
        assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(409);
        try(var d=device(id)) {
            waitForCore();var wire=new LinkedBlockingQueue<MqttMessage>();
            d.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->wire.add(message));
            d.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,2,CONFIG).getBytes(StandardCharsets.UTF_8),1,true);
            assertThat(awaitTelemetry(id).path("canConfigure").asBoolean()).isTrue();
            for(String invalid:new String[]{body.replace("\"expectedRevision\":2","\"expectedRevision\":2.5"),
                body.replace("\"volume\":30","\"volume\":101"),body.replace("\"zh-CN\"","\"en-US\""),
                body.replace("\"Asia/Shanghai\"","\"UTC-12:30\""),body.replace("\"requestId\":","\"extra\":1,\"requestId\":"),
                body.replace("\"expectedRevision\":2","\"expectedRevision\":2,\"expectedRevision\":2")})
                assertThat(request("POST",path+"/config-requests",invalid,TOKEN).statusCode()).isEqualTo(400);
            assertThat(request("POST",path+"/config-requests",body.replace("\"expectedRevision\":2","\"expectedRevision\":1"),TOKEN).statusCode()).isEqualTo(409);
            assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(202);
            assertThat(wire.poll(8,TimeUnit.SECONDS)).isNotNull();
            String ack="{\"schemaVersion\":1,\"requestId\":\""+rid+"\",\"status\":\"applied\",\"revision\":3,\"code\":0}";
            for(String invalid:new String[]{ack.replace("\"revision\":3","\"revision\":999"),ack.replace("\"code\":0","\"code\":259"),ack.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")})
                d.publish("nexus/v1/devices/"+id+"/config_acks",invalid.getBytes(StandardCharsets.UTF_8),1,false);
            // The following valid telemetry is a processing barrier after the malformed ACKs.
            long observed=Instant.now().getEpochSecond()+1;
            String marker=telemetry(id,2,CONFIG).replaceFirst("\"observedAt\":[0-9]+","\"observedAt\":"+observed);
            d.publish("nexus/v1/devices/"+id+"/telemetry",marker.getBytes(StandardCharsets.UTF_8),1,false);
            long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(json.readTree(request("GET",path,"",TOKEN).body()).path("telemetry").path("observedAt").asLong()!=observed) {
                assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
            }
            awaitRequest(id,rid,"sent");
            d.publish("nexus/v1/devices/"+id+"/config_acks",ack.getBytes(StandardCharsets.UTF_8),1,false);
            awaitRequest(id,rid,"applied");
            var waiting=json.readTree(request("GET",path,"",TOKEN).body());
            assertThat(waiting.path("canConfigure").asBoolean()).isFalse();
            assertThat(waiting.path("configBlock").asText()).isEqualTo("config_readback_required");
            assertThat(request("POST",path+"/config-requests",body.replace(rid,UUID.randomUUID().toString()),TOKEN).statusCode()).isEqualTo(409);
            d.disconnect();
        }
    }
    @Test void configurationEnvelopeRoundTripsThroughRealFirmwareService()throws Exception {
        String peer=System.getenv("NEXUS_PET_CONFIG_PEER");
        org.junit.jupiter.api.Assumptions.assumeTrue(peer!=null,"Run 04 tools/ci/test_management_contract.py for the hosted C firmware peer");
        String id="dodo-wire",path="devices/"+id+"/management",rid=UUID.randomUUID().toString();
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"固件互通测试\"}",TOKEN).statusCode()).isEqualTo(201);
        var process=new ProcessBuilder(peer,id).start();
        try(var input=process.outputWriter(StandardCharsets.UTF_8);var output=process.inputReader(StandardCharsets.UTF_8);var d=device(id)) {
            waitForCore();var wire=new LinkedBlockingQueue<MqttMessage>();
            d.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->wire.add(message));
            d.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,0,CONFIG).getBytes(StandardCharsets.UTF_8),1,true);awaitTelemetry(id);
            String body="{\"requestId\":\""+rid+"\",\"expectedRevision\":0,\"config\":"+CONFIG.replace("\"volume\":25","\"volume\":35")+"}";
            assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(202);
            var packet=wire.poll(8,TimeUnit.SECONDS);assertThat(packet).isNotNull();
            JsonNode result=null;
            for(int attempt=0;attempt<2;attempt++) {
                input.write(new String(packet.getPayload(),StandardCharsets.UTF_8));input.newLine();input.flush();
                String response=java.util.concurrent.CompletableFuture.supplyAsync(()-> {
                    try { return output.readLine(); }catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }).get(5,TimeUnit.SECONDS);
                assertThat(response).isNotNull();result=json.readTree(response);
                assertThat(result.path("ack").path("status").asText()).isEqualTo("applied");
                assertThat(result.path("revision").asLong()).isEqualTo(1);
                assertThat(result.path("config").path("volume").asInt()).isEqualTo(35);
                assertThat(result.path("writes").asInt()).isEqualTo(1);
            }
            d.publish("nexus/v1/devices/"+id+"/config_acks",json.writeValueAsBytes(result.get("ack")),1,false);
            assertThat(awaitRequest(id,rid,"applied").path("confirmationSource").asText()).isEqualTo("ack");
            d.disconnect();
        } finally { process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly(); }
    }
    @Test void anotherDeviceCannotReadConfigurationOrForgeItsAcknowledgment()throws Exception {
        String id="dodo-scoped",path="devices/"+id+"/management",rid=UUID.randomUUID().toString();
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"设备隔离测试\"}",TOKEN).statusCode()).isEqualTo(201);
        try(var owner=device(id);var spy=device("dodo-spy")) {
            waitForCore();var ownWire=new LinkedBlockingQueue<MqttMessage>();var leaked=new LinkedBlockingQueue<MqttMessage>();
            owner.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->ownWire.add(message));
            spy.subscribe("nexus/v1/devices/"+id+"/config",1,(topic,message)->leaked.add(message));
            owner.publish("nexus/v1/devices/"+id+"/telemetry",telemetry(id,0,CONFIG).getBytes(StandardCharsets.UTF_8),1,true);awaitTelemetry(id);
            String body="{\"requestId\":\""+rid+"\",\"expectedRevision\":0,\"config\":"+CONFIG.replace("\"volume\":25","\"volume\":35")+"}";
            assertThat(request("POST",path+"/config-requests",body,TOKEN).statusCode()).isEqualTo(202);
            assertThat(ownWire.poll(8,TimeUnit.SECONDS)).isNotNull();
            String ack="{\"schemaVersion\":1,\"requestId\":\""+rid+"\",\"status\":\"applied\",\"revision\":1,\"code\":0}";
            spy.publish("nexus/v1/devices/"+id+"/config_acks",ack.getBytes(StandardCharsets.UTF_8),1,false);
            assertThat(leaked.poll(1,TimeUnit.SECONDS)).isNull();
            awaitRequest(id,rid,"sent");
            owner.publish("nexus/v1/devices/"+id+"/config_acks",ack.getBytes(StandardCharsets.UTF_8),1,false);awaitRequest(id,rid,"applied");
            owner.disconnect();spy.disconnect();
        }
    }
    @Test void deviceTelemetryIsReadableButDoesNotFabricateAnOnlineHeartbeat()throws Exception {
        assertThat(request("GET","devices/dodo-management/management","",null).statusCode()).isEqualTo(401);
        assertThat(request("POST","devices","{\"deviceId\":\"dodo-management\",\"name\":\"管理测试\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("GET","devices/dodo-management/management","",TOKEN).statusCode()).isEqualTo(200);
        try(var d=device()) {
            // Wait for the real CORE subscriber, not an arbitrary sleep.
            long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            while(!json.readTree(request("GET","status","",TOKEN).body()).path("mqtt").asText().equals("ready")) {
                assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(30);
            }
            String telemetry="{\"schemaVersion\":1,\"deviceId\":\"dodo-management\",\"observedAt\":"+Instant.now().getEpochSecond()+
                ",\"firmwareVersion\":\"0.4.0-dev\",\"configRevision\":0,\"configWritable\":true,\"muted\":false,\"dnd\":false,"+
                "\"volume\":25,\"brightness\":80,\"unread\":0,\"freeHeap\":100000,\"minFreeHeap\":90000,\"config\":"+CONFIG+"}";
            d.publish("nexus/v1/devices/dodo-management/telemetry",telemetry.getBytes(StandardCharsets.UTF_8),1,true);
            var view=awaitTelemetry();
            assertThat(view.path("telemetry").path("config").path("timezone").asText()).isEqualTo("Asia/Shanghai");
            var devices=json.readTree(request("GET","devices","",TOKEN).body()).path("items");
            for(var item:devices)if(item.path("deviceId").asText().equals("dodo-management"))assertThat(item.path("online").asBoolean()).isFalse();
            d.disconnect();
        }
    }
    @Test void telemetryHistoryRecordsChangesAndCapsStableHeartbeats()throws Exception {
        String id="dodo-history",path="devices/"+id+"/telemetry";
        assertThat(request("GET",path,"",null).statusCode()).isEqualTo(401);
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"遥测历史\"}",TOKEN).statusCode()).isEqualTo(201);
        assertThat(request("GET","devices/missing-history/telemetry","",TOKEN).statusCode()).isEqualTo(404);
        long base=Instant.now().getEpochSecond()-600;
        try(var d=device(id)) {
            waitForCore();
            publishTelemetryAt(d,id,base,25);
            awaitHistoryPoints(id,base-60,base+60,1);
            publishTelemetryAt(d,id,base+3,40);   // changed, but below the 5s change floor
            publishTelemetryAt(d,id,base+40,40);  // changed, accepted
            publishTelemetryAt(d,id,base+50,40);  // unchanged, below the 30s heartbeat
            publishTelemetryAt(d,id,base+80,40);  // unchanged heartbeat, accepted
            var raw=awaitHistoryPoints(id,base-60,base+120,3);
            assertThat(raw.path("bucket").asText()).isEqualTo("raw");
            assertThat(raw.path("truncated").asBoolean()).isFalse();
            assertThat(raw.path("sampleSeconds").asInt()).isEqualTo(30);
            var points=raw.path("points");
            assertThat(Instant.parse(points.get(0).path("observedAt").asText()).getEpochSecond()).isEqualTo(base);
            assertThat(points.get(0).path("volume").asInt()).isEqualTo(25);
            assertThat(points.get(0).path("count").asInt()).isEqualTo(1);
            assertThat(Instant.parse(points.get(1).path("observedAt").asText()).getEpochSecond()).isEqualTo(base+40);
            assertThat(points.get(1).path("volume").asInt()).isEqualTo(40);
            assertThat(Instant.parse(points.get(2).path("observedAt").asText()).getEpochSecond()).isEqualTo(base+80);
            assertThat(points.get(2).path("freeHeapMin").asLong()).isEqualTo(100000);
            var grouped=telemetryHistory(id,historyWindow(base-60,base+120)+"&bucket=1m");
            int samples=0;for(var point:grouped.path("points"))samples+=point.path("count").asInt();
            assertThat(samples).isEqualTo(3);
            assertThat(telemetryHistory(id,historyWindow(base+200,base+300)+"&bucket=1m").path("points").size()).isZero();
            for(String query:new String[]{"bucket=2m","from=not-a-time","bucket=1m&"+historyWindow(base+100,base),
                "bucket=1m&"+historyWindow(Instant.now().getEpochSecond()-8*86400,Instant.now().getEpochSecond()),
                "bucket=raw&"+historyWindow(Instant.now().getEpochSecond()-13*3600,Instant.now().getEpochSecond())})
                assertThat(request("GET",path+"?"+query,"",TOKEN).statusCode()).withFailMessage(query).isEqualTo(400);
            d.disconnect();
        }
    }
    @Test void telemetryHistoryKeepsNetworkAddressAndSignal()throws Exception {
        String id="dodo-network";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"网络遥测\"}",TOKEN).statusCode()).isEqualTo(201);
        long base=Instant.now().getEpochSecond()/60*60-300;
        try(var d=device(id)) {
            waitForCore();
            publishNetworkTelemetry(d,id,base,"192.168.1.23",-58,25);
            var raw=awaitHistoryPoints(id,base-60,base+120,1);
            assertThat(raw.path("points").get(0).path("wifiIp").asText()).isEqualTo("192.168.1.23");
            assertThat(raw.path("points").get(0).path("wifiRssi").asInt()).isEqualTo(-58);
            publishNetworkTelemetry(d,id,base+10,"192.168.1.24",-52,25); // 地址变化立即入库，不等待心跳
            awaitHistoryPoints(id,base-60,base+120,2);
            var grouped=telemetryHistory(id,historyWindow(base-60,base+120)+"&bucket=1m").path("points").get(0);
            assertThat(grouped.path("wifiIp").asText()).isEqualTo("192.168.1.24");
            assertThat(grouped.path("wifiRssi").asInt()).isEqualTo(-55);
            JsonNode card=null;
            for(var item:json.readTree(request("GET","devices","",TOKEN).body()).path("items"))
                if(id.equals(item.path("deviceId").asText())) card=item;
            assertThat(card).isNotNull();
            assertThat(card.path("wifiIp").asText()).isEqualTo("192.168.1.24");
            assertThat(card.path("wifiRssi").asInt()).isEqualTo(-52);
            publishNetworkTelemetry(d,id,base+20,"192.168.1.999",12,99); // 非法地址与信号整条拒绝
            publishTelemetryAt(d,id,base+40,40);
            var points=awaitHistoryPoints(id,base-60,base+120,3).path("points");
            for(var point:points)assertThat(point.path("volume").asInt()).isNotEqualTo(99);
            assertThat(points.get(2).hasNonNull("wifiIp")).isFalse();
            d.disconnect();
        }
    }
    @Test void telemetryHistoryCleanupHonoursTheConfiguredRetention()throws Exception {
        String id="dodo-history-retention";
        assertThat(request("POST","devices","{\"deviceId\":\""+id+"\",\"name\":\"保留测试\"}",TOKEN).statusCode()).isEqualTo(201);
        db.update("""
            INSERT INTO device_telemetry_samples(device_id,observed_at,firmware_version,config_revision,volume,
                brightness,muted,dnd,unread,free_heap,min_free_heap)
            VALUES (?,now()-interval '40 days','0.4.0',0,25,80,false,false,0,100000,90000)
            """,id);
        history.cleanup();
        assertThat(db.queryForObject("SELECT count(*) FROM device_telemetry_samples WHERE device_id=?",Integer.class,id)).isZero();
    }
}
