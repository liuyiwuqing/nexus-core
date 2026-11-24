package dev.nexus.core.device;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class CompanionCodec {
    private CompanionCodec() {}
    static String requestId(JsonNode value) {
        String id=ManagementCodec.text(value,36);
        if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||UUID.fromString(id).equals(new UUID(0,0)))
            throw new IllegalArgumentException("invalid_appearance_request_id");
        return id;
    }
    static JsonNode appearance(JsonNode a) {
        ManagementCodec.fields(a,Set.of("theme","outfit","slots"),Set.of());
        ManagementCodec.integer(a.get("theme"),0,2);ManagementCodec.integer(a.get("outfit"),0,6);
        var slots=a.get("slots");array(slots,3,12);
        int seen=0;
        for(var slot:slots) {
            int n=slot.asInt();
            if(n!=0&&(n<7||(seen&(1<<n))!=0))throw new IllegalArgumentException("invalid_furnishing_slot");
            if(n!=0)seen|=1<<n;
        }
        return a;
    }
    static void owned(JsonNode a,int owned,int themes) {
        if((themes&(1<<a.path("theme").asInt()))==0)throw new IllegalArgumentException("theme_locked");
        int outfit=a.path("outfit").asInt();
        if(outfit!=0&&(owned&(1<<(outfit-1)))==0)throw new IllegalArgumentException("outfit_not_owned");
        for(var slot:a.path("slots"))if(slot.asInt()!=0&&(owned&(1<<(slot.asInt()-1)))==0)throw new IllegalArgumentException("furnishing_not_owned");
    }
    static void array(JsonNode a,int size,int max) {
        if(a==null||!a.isArray()||a.size()!=size)throw new IllegalArgumentException("invalid_companion_array");
        for(var n:a)ManagementCodec.integer(n,0,max);
    }
    static JsonNode snapshot(String device,byte[] bytes) {
        var v=ManagementCodec.parse(bytes,3072);
        ManagementCodec.fields(v,Set.of("schemaVersion","catalogVersion","deviceId","bootId","reportSequence","observedAt","revision",
            "appearanceRevision","owned","eligible","themes","writable","lastCommand","appearance","best","played","focusCompleted","memories"),Set.of());
        ManagementCodec.integer(v.get("schemaVersion"),1,1);ManagementCodec.integer(v.get("catalogVersion"),1,1);
        if(!ManagementCodec.text(v.get("deviceId"),40).equals(device))throw new IllegalArgumentException("device_mismatch");
        for(String key:Set.of("bootId","lastCommand"))if(!ManagementCodec.text(v.get(key),32).matches("[a-f0-9]{32}"))throw new IllegalArgumentException("invalid_companion_identity");
        ManagementCodec.integer(v.get("reportSequence"),1,4294967295L);
        ManagementCodec.integer(v.get("observedAt"),1,Instant.now().getEpochSecond()+5);
        ManagementCodec.integer(v.get("revision"),0,4294967295L);ManagementCodec.integer(v.get("appearanceRevision"),0,4294967295L);
        int own=(int)ManagementCodec.integer(v.get("owned"),0,4095);
        ManagementCodec.integer(v.get("eligible"),0,4095);int themes=(int)ManagementCodec.integer(v.get("themes"),1,7);
        if((themes&1)==0)throw new IllegalArgumentException("default_theme_missing");
        ManagementCodec.bool(v.get("writable"));appearance(v.get("appearance"));
        // Historical appearance can remain visible after restoring an older level.
        owned(v.get("appearance"),own,7);
        array(v.get("best"),3,500);ManagementCodec.integer(v.get("best").get(1),0,250);ManagementCodec.integer(v.get("best").get(2),0,100);
        array(v.get("played"),3,65535);ManagementCodec.integer(v.get("focusCompleted"),0,65535);
        var memories=v.get("memories");if(!memories.isArray()||memories.size()>8)throw new IllegalArgumentException("invalid_companion_memories");
        long previous=4294967296L;
        for(var m:memories) {
            ManagementCodec.fields(m,Set.of("sequence","at","kind","detail","theme","outfit"),Set.of());
            long seq=ManagementCodec.integer(m.get("sequence"),1,4294967295L);
            if(seq>=previous)throw new IllegalArgumentException("invalid_memory_order");previous=seq;
            ManagementCodec.integer(m.get("at"),0,4294967295L);ManagementCodec.integer(m.get("kind"),1,8);
            ManagementCodec.integer(m.get("detail"),0,255);ManagementCodec.integer(m.get("theme"),0,2);ManagementCodec.integer(m.get("outfit"),0,6);
        }
        return v;
    }
}
