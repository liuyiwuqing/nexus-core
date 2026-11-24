package dev.nexus.core.delivery;

import dev.nexus.core.device.DeviceManagementService;
import dev.nexus.core.device.DeviceGrowthService;
import dev.nexus.core.device.CompanionService;
import dev.nexus.core.device.RemoteOtaService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MqttTransport {
    private static final Logger LOG = LoggerFactory.getLogger(MqttTransport.class);
    private final MqttAsyncClient client;
    private final MqttConnectOptions options = new MqttConnectOptions();
    private volatile boolean ready;
    private final boolean otaEnabled;
    private final boolean growthEnabled;
    private final boolean companionEnabled;
    private volatile String lastError = "not_connected";

    public MqttTransport(DeliveryService delivery, DeviceManagementService management, RemoteOtaService ota, DeviceGrowthService growth, CompanionService companion, ObjectMapper json,
        @Value("${nexus.mqtt.uri}") String uri,
        @Value("${nexus.mqtt.username}") String username,
        @Value("${nexus.mqtt.password}") String password,
        @Value("${nexus.mqtt.client-id}") String clientId,
        @Value("${nexus.mqtt.allow-insecure}") boolean allowInsecure,
        @Value("${nexus.mqtt.ca-cert:}") String caCert) throws Exception {
        otaEnabled=ota.enabled();
        growthEnabled=growth.enabled();
        companionEnabled=companion.enabled();
        URI parsed = URI.create(uri);
        if ((!"ssl".equals(parsed.getScheme()) && !(allowInsecure && "tcp".equals(parsed.getScheme())))
            || parsed.getHost() == null || parsed.getUserInfo() != null || username.isBlank() || password.isBlank())
            throw new IllegalArgumentException("MQTT requires TLS and credentials; explicitly enable insecure transport only for local tests");
        client = new MqttAsyncClient(uri, clientId, new MemoryPersistence());
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setCleanSession(true);
        options.setConnectionTimeout(3);
        options.setKeepAliveInterval(30);
        options.setMaxInflight(8);
        options.setHttpsHostnameVerificationEnabled(true);
        if (!caCert.isBlank()) {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null);
            try (var input = Files.newInputStream(Path.of(caCert))) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                int index = 0;
                for (var cert : certificates) store.setCertificateEntry("ca-" + index++, cert);
                if (index == 0) throw new IllegalArgumentException("empty MQTT CA file");
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            options.setSocketFactory(context.getSocketFactory());
        }
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { ready = false; lastError = "connection_lost"; }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            @Override public void messageArrived(String topic, MqttMessage message) {
                String[] parts = topic.split("/");
                if (parts.length != 5 || !parts[0].equals("nexus") || !parts[1].equals("v1") || !parts[2].equals("devices")
                    || !parts[3].matches("[a-zA-Z0-9_-]{1,40}")) return;
                try {
                    if(parts[4].equals("companion_status")) {companion.observation(parts[3],message.getPayload(),message.isRetained());return;}
                    if(parts[4].equals("appearance_acks")) {companion.acknowledge(parts[3],message.getPayload(),message.isRetained());return;}
                    if(parts[4].equals("growth_status")) { growth.observation(parts[3],message.getPayload(),message.isRetained());return; }
                    if(parts[4].equals("ota_status")) { ota.observation(parts[3],message.getPayload(),message.isRetained());return; }
                    if(parts[4].equals("ota_acks")) { ota.acknowledge(parts[3],message.getPayload(),message.isRetained());return; }
                    if(parts[4].equals("telemetry")) { management.telemetry(parts[3],message.getPayload());return; }
                    if(parts[4].equals("config_acks")) { management.acknowledge(parts[3],message.getPayload(),message.isRetained());return; }
                    if(message.getPayload().length>1024)return;
                    var body = json.readTree(message.getPayload());
                    if (parts[4].equals("acks")) delivery.acknowledge(parts[3], body);
                    else if (parts[4].equals("state")) delivery.state(parts[3], body);
                } catch (RuntimeException e) {
                    // Do not log message bodies or server credentials on malformed network input.
                    LOG.warn("MQTT input rejected: {}", e.getClass().getSimpleName());
                }
            }
        });
    }

    @Scheduled(fixedDelay=5000, initialDelay=100)
    void maintainConnection() {
        if (ready) return;
        try {
            if (!client.isConnected()) client.connect(options).waitForCompletion(4000);
            var topics=new java.util.ArrayList<>(java.util.List.of("nexus/v1/devices/+/acks","nexus/v1/devices/+/state","nexus/v1/devices/+/telemetry","nexus/v1/devices/+/config_acks"));
            if(otaEnabled)topics.addAll(java.util.List.of("nexus/v1/devices/+/ota_acks","nexus/v1/devices/+/ota_status"));
            if(companionEnabled)topics.addAll(java.util.List.of("nexus/v1/devices/+/companion_status","nexus/v1/devices/+/appearance_acks"));
            if(growthEnabled)topics.add("nexus/v1/devices/+/growth_status");
            int[] qosValues=new int[topics.size()];java.util.Arrays.fill(qosValues,1);
            var token=client.subscribe(topics.toArray(String[]::new),qosValues);
            token.waitForCompletion(3000);
            for (int qos : token.getGrantedQos()) if (qos == 128) throw new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED);
            ready = true;
            lastError = "";
        } catch (MqttException e) {
            ready = false;
            lastError = "connect_failed";
        }
    }

    public boolean ready() { return ready && client.isConnected(); }
    public String lastError() { return lastError; }
    public void publish(String device, byte[] payload) throws MqttException {
        if (!ready() || payload.length > 1024) throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.publish("nexus/v1/devices/" + device + "/inbox", payload, 1, false).waitForCompletion(3000);
    }
    public void publishConfig(String device,byte[] payload) throws MqttException {
        if(!device.matches("[a-zA-Z0-9_-]{1,40}")||payload.length>1200)
            throw new IllegalArgumentException("invalid_config_envelope");
        if(!ready())throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.publish("nexus/v1/devices/"+device+"/config",payload,1,false).waitForCompletion(3000);
    }

    public void publishOta(String device,byte[] payload) throws MqttException {
        if(!otaEnabled||!device.matches("[a-zA-Z0-9_-]{1,40}")||payload.length>1024)throw new IllegalArgumentException("invalid_ota_envelope");
        if(!ready())throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.publish("nexus/v1/devices/"+device+"/ota",payload,1,false).waitForCompletion(3000);
    }

    public void publishAppearance(String device,byte[] payload)throws MqttException {
        if(!companionEnabled||!device.matches("[a-zA-Z0-9_-]{1,40}")||payload.length>768)throw new IllegalArgumentException("invalid_appearance_envelope");
        if(!ready())throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        client.publish("nexus/v1/devices/"+device+"/appearance",payload,1,false).waitForCompletion(3000);
    }

    @PreDestroy
    void close() {
        ready = false;
        try {
            if (client.isConnected()) client.disconnect(500).waitForCompletion(1500);
            client.close(true);
        } catch (MqttException e) { LOG.warn("MQTT shutdown incomplete"); }
    }
}
