package dev.nexus.core.device;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The management wire format is separate from the unchanged inbox/state protocol. */
final class ManagementCodec {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(1200).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private ManagementCodec() {}

    static JsonNode parse(byte[] bytes,int limit) {
        if(bytes.length==0||bytes.length>limit)throw new IllegalArgumentException("invalid_management_size");
        try { return JSON.readTree(bytes); }
        catch(RuntimeException ignored) { throw new IllegalArgumentException("invalid_management_json"); }
    }
    static void fields(JsonNode value,Set<String> required,Set<String> optional) {
        if(!value.isObject()||!value.propertyNames().containsAll(required))throw new IllegalArgumentException("missing_management_field");
        for(String key:value.propertyNames())if(!required.contains(key)&&!optional.contains(key))throw new IllegalArgumentException("unknown_management_field");
    }
    static long integer(JsonNode value,long min,long max) {
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<min||value.longValue()>max)
            throw new IllegalArgumentException("invalid_management_integer");
        return value.longValue();
    }
    static void bool(JsonNode value) {
        if(value==null||!value.isBoolean())throw new IllegalArgumentException("invalid_management_boolean");
    }
    static String text(JsonNode value,int max) {
        if(value==null||!value.isString()||value.asText().isEmpty()||value.asText().length()>max||
            value.asText().chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("invalid_management_text");
        return value.asText();
    }
    static ObjectNode config(JsonNode input) {
        fields(input,Set.of("schemaVersion","timezone","language","volume","brightness","dnd","message"),Set.of("muted"));
        integer(input.get("schemaVersion"),1,1);integer(input.get("volume"),0,100);integer(input.get("brightness"),0,100);
        String timezone=text(input.get("timezone"),32);
        if(!Set.of("Asia/Shanghai","UTC","Etc/UTC").contains(timezone)) {
            if(!timezone.matches("UTC[+-][0-9]{2}:[0-9]{2}"))throw new IllegalArgumentException("invalid_timezone");
            int hours=Integer.parseInt(timezone.substring(4,6)),minutes=Integer.parseInt(timezone.substring(7,9));
            int offset=(hours*60+minutes)*(timezone.charAt(3)=='-'?-1:1);
            if(minutes>59||offset< -720||offset>840)throw new IllegalArgumentException("invalid_timezone");
        }
        if(!text(input.get("language"),8).equals("zh-CN"))throw new IllegalArgumentException("unsupported_language");
        var dnd=input.get("dnd");
        fields(dnd,Set.of("enabled","startMinute","endMinute","allowCritical"),Set.of("manual"));
        bool(dnd.get("enabled"));bool(dnd.get("allowCritical"));
        integer(dnd.get("startMinute"),0,1439);integer(dnd.get("endMinute"),0,1439);
        if(dnd.has("manual"))bool(dnd.get("manual"));
        if(input.has("muted"))bool(input.get("muted"));
        var message=input.get("message");fields(message,Set.of("queueDepth","durationMs"),Set.of());
        integer(message.get("queueDepth"),1,8);integer(message.get("durationMs"),5000,30000);
        // Stable key order and explicit defaults make idempotency independent of JSON spelling.
        var out=JSON.createObjectNode();out.put("schemaVersion",1);out.put("timezone",timezone);out.put("language","zh-CN");
        out.put("volume",input.get("volume").asInt());out.put("brightness",input.get("brightness").asInt());
        out.put("muted",input.path("muted").asBoolean(false));
        var d=out.putObject("dnd");d.put("enabled",dnd.get("enabled").asBoolean());d.put("manual",dnd.path("manual").asBoolean(false));
        d.put("startMinute",dnd.get("startMinute").asInt());d.put("endMinute",dnd.get("endMinute").asInt());
        d.put("allowCritical",dnd.get("allowCritical").asBoolean());
        var m=out.putObject("message");m.put("queueDepth",message.get("queueDepth").asInt());m.put("durationMs",message.get("durationMs").asInt());
        return out;
    }
    static JsonNode telemetry(String device,byte[] bytes) {
        var value=parse(bytes,2048);
        fields(value,Set.of("schemaVersion","deviceId","observedAt","firmwareVersion","configRevision","configWritable",
            "muted","dnd","volume","brightness","unread","freeHeap","minFreeHeap"),
            Set.of("audioUnderruns","inferenceMaxMs","displayMaxMs","audioFaulted","config"));
        integer(value.get("schemaVersion"),1,1);
        if(!text(value.get("deviceId"),40).equals(device))throw new IllegalArgumentException("device_mismatch");
        integer(value.get("observedAt"),1,Instant.now().getEpochSecond()+5);
        text(value.get("firmwareVersion"),32);integer(value.get("configRevision"),0,4294967295L);
        bool(value.get("configWritable"));bool(value.get("muted"));bool(value.get("dnd"));
        integer(value.get("volume"),0,100);integer(value.get("brightness"),0,100);integer(value.get("unread"),0,8);
        for(String key:Set.of("freeHeap","minFreeHeap","audioUnderruns","inferenceMaxMs","displayMaxMs"))
            if(value.has(key))integer(value.get(key),0,4294967295L);
        if(value.has("audioFaulted"))bool(value.get("audioFaulted"));
        if(value.has("config")) {
            var settings=config(value.get("config"));
            if(settings.get("volume").asInt()!=value.get("volume").asInt()||settings.get("brightness").asInt()!=value.get("brightness").asInt())
                throw new IllegalArgumentException("config_summary_mismatch");
            ((ObjectNode)value).set("config",settings);
        }
        return value;
    }
    static byte[] bytes(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
