package dev.nexus.core.device;

import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** OTA metadata never authorizes an image: the device verifies its pinned signing key. */
final class RemoteOtaCodec {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private RemoteOtaCodec() {}
    static String id(JsonNode value,int maximum) {
        String id=ManagementCodec.text(value,maximum);
        if(!id.matches("[a-zA-Z0-9_-]{1,"+maximum+"}"))throw new IllegalArgumentException("invalid_ota_id");
        return id;
    }
    static String digest(JsonNode value,boolean model) {
        String text=ManagementCodec.text(value,64);
        if(!(model&&text.equals("none"))&&!text.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid_ota_digest");
        return text;
    }
    static String boot(JsonNode value) {
        String boot=ManagementCodec.text(value,32);
        if(!boot.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("invalid_ota_boot");
        return boot;
    }
    static JsonNode observation(String device,byte[] bytes) {
        var value=ManagementCodec.parse(bytes,1536);
        ManagementCodec.fields(value,Set.of("schemaVersion","deviceId","bootId","observedAt","reportSequence","available","state","code",
            "requestId","rebootId","writtenBytes","reservedSequence","identity"),Set.of("target"));
        ManagementCodec.integer(value.get("schemaVersion"),1,1);boot(value.get("bootId"));
        if(!id(value.get("deviceId"),40).equals(device))throw new IllegalArgumentException("device_mismatch");
        ManagementCodec.integer(value.get("observedAt"),1,Instant.now().getEpochSecond()+5);
        ManagementCodec.integer(value.get("reportSequence"),1,4294967295L);ManagementCodec.bool(value.get("available"));
        String state=ManagementCodec.text(value.get("state"),24);
        if(!Set.of("idle","installing","pending_reboot","reboot_requested","verifying","healthy","failed","interrupted","rolled_back","unconfirmed","unavailable").contains(state))
            throw new IllegalArgumentException("invalid_ota_state");
        ManagementCodec.integer(value.get("code"),Integer.MIN_VALUE,Integer.MAX_VALUE);
        ManagementCodec.integer(value.get("writtenBytes"),0,4*1024*1024);ManagementCodec.integer(value.get("reservedSequence"),0,4294967295L);
        for(String key:Set.of("requestId","rebootId")) {
            if(!value.path(key).isString()||!value.path(key).asText().matches("[a-zA-Z0-9_-]{0,40}"))throw new IllegalArgumentException("invalid_ota_id");
        }
        if(value.path("requestId").asText().isEmpty()!=!value.has("target"))throw new IllegalArgumentException("invalid_ota_target");
        if(value.has("target"))target(value.get("target"));
        identity(value.get("identity"));return value;
    }
    static JsonNode target(JsonNode value) {
        ManagementCodec.fields(value,Set.of("releaseId","version","imageSha256","imageSize","releaseSequence"),Set.of());
        id(value.get("releaseId"),64);String version=ManagementCodec.text(value.get("version"),32);
        if(!version.matches("[a-zA-Z0-9._-]{1,32}"))throw new IllegalArgumentException("invalid_ota_version");
        digest(value.get("imageSha256"),false);ManagementCodec.integer(value.get("imageSize"),1,4*1024*1024);
        ManagementCodec.integer(value.get("releaseSequence"),1,4294967295L);return value;
    }
    static JsonNode identity(JsonNode value) {
        ManagementCodec.fields(value,Set.of("project","board","modelSha256","partitionSha256","configSchema","growthSchema"),Set.of());
        id(value.get("project"),40);id(value.get("board"),40);digest(value.get("modelSha256"),true);digest(value.get("partitionSha256"),false);
        ManagementCodec.integer(value.get("configSchema"),1,4294967295L);ManagementCodec.integer(value.get("growthSchema"),1,4294967295L);return value;
    }
    record Release(String id,JsonNode manifest,ObjectNode target,ObjectNode identity) {}
    static Release release(byte[] bytes) {
        var body=ManagementCodec.parse(bytes,8192);
        ManagementCodec.fields(body,Set.of("releaseId","manifest"),Set.of());
        String id=id(body.get("releaseId"),64);var manifest=body.get("manifest");
        ManagementCodec.fields(manifest,Set.of("payload","signature"),Set.of());
        byte[] raw,signature;
        try {
            raw=Base64.getDecoder().decode(ManagementCodec.text(manifest.get("payload"),3000));
            signature=Base64.getDecoder().decode(ManagementCodec.text(manifest.get("signature"),512));
        }catch(IllegalArgumentException e) { throw new IllegalArgumentException("invalid_ota_manifest_encoding"); }
        if(signature.length!=384)throw new IllegalArgumentException("invalid_ota_signature_size");
        var value=ManagementCodec.parse(raw,2000);
        ManagementCodec.fields(value,Set.of("format","project","board","version","modelSha256","partitionSha256",
            "imageSha256","image","imageSize","releaseSequence","configSchema","growthSchema"),Set.of());
        ManagementCodec.integer(value.get("format"),1,1);
        String version=ManagementCodec.text(value.get("version"),32);
        if(!version.matches("[a-zA-Z0-9._-]{1,32}")||!ManagementCodec.text(value.get("image"),64).equals("firmware.bin"))
            throw new IllegalArgumentException("invalid_ota_image");
        var target=JSON.createObjectNode();target.put("releaseId",id);target.put("version",version);
        target.put("imageSha256",digest(value.get("imageSha256"),false));
        target.put("imageSize",ManagementCodec.integer(value.get("imageSize"),1,4*1024*1024));
        target.put("releaseSequence",ManagementCodec.integer(value.get("releaseSequence"),1,4294967295L));
        var identity=JSON.createObjectNode();identity.put("project",id(value.get("project"),40));
        identity.put("board",id(value.get("board"),40));identity.put("modelSha256",digest(value.get("modelSha256"),true));
        identity.put("partitionSha256",digest(value.get("partitionSha256"),false));
        identity.put("configSchema",ManagementCodec.integer(value.get("configSchema"),1,4294967295L));
        identity.put("growthSchema",ManagementCodec.integer(value.get("growthSchema"),1,4294967295L));
        return new Release(id,manifest,target,identity);
    }
}
