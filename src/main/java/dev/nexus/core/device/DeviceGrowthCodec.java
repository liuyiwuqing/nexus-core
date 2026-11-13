package dev.nexus.core.device;

import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Display snapshots carry their own ordering; the NVS revision does not order unsaved changes. */
final class DeviceGrowthCodec {
    private DeviceGrowthCodec() {}
    static JsonNode snapshot(String device,byte[] bytes) {
        var value=ManagementCodec.parse(bytes,1536);
        ManagementCodec.fields(value,Set.of("schemaVersion","deviceId","bootId","reportSequence","observedAt","growthSchema","growth"),Set.of());
        ManagementCodec.integer(value.get("schemaVersion"),1,1);
        if(!ManagementCodec.text(value.get("deviceId"),40).equals(device))throw new IllegalArgumentException("device_mismatch");
        if(!ManagementCodec.text(value.get("bootId"),32).matches("[0-9a-f]{32}"))throw new IllegalArgumentException("invalid_growth_boot");
        ManagementCodec.integer(value.get("reportSequence"),1,4294967295L);
        ManagementCodec.integer(value.get("observedAt"),1,Instant.now().getEpochSecond()+5);
        ManagementCodec.integer(value.get("growthSchema"),4,4);
        var g=value.get("growth");
        ManagementCodec.fields(g,Set.of("revision","level","experience","experienceToNext","coins","intimacy","mood","energy","hunger","health",
            "state","sleeping","taskDay","taskProgress","taskClaimed","achievements","timeReady","unsaved","storageReady","saveRecovered","environment"),Set.of());
        ManagementCodec.integer(g.get("revision"),0,4294967295L);
        long level=ManagementCodec.integer(g.get("level"),1,99),n=level-1,threshold=80+40*n+8*n*n;
        ManagementCodec.integer(g.get("experienceToNext"),threshold,threshold);
        ManagementCodec.integer(g.get("experience"),0,level==99?0:threshold-1);
        ManagementCodec.integer(g.get("coins"),0,999999);
        for(String key:Set.of("intimacy","energy","hunger","health"))ManagementCodec.integer(g.get(key),0,100);
        ManagementCodec.integer(g.get("mood"),-100,100);
        ManagementCodec.integer(g.get("taskDay"),0,4294967295L);
        long claimed=ManagementCodec.integer(g.get("taskClaimed"),0,7);
        ManagementCodec.integer(g.get("achievements"),0,7);
        var progress=g.get("taskProgress");
        if(!progress.isArray()||progress.size()!=3)throw new IllegalArgumentException("invalid_growth_tasks");
        for(int i=0;i<3;i++) {
            long max=i==1?3:1,actual=ManagementCodec.integer(progress.get(i),0,max);
            if((claimed&(1<<i))!=0&&actual!=max)throw new IllegalArgumentException("invalid_growth_claim");
        }
        for(String key:Set.of("sleeping","timeReady","unsaved","storageReady","saveRecovered"))ManagementCodec.bool(g.get(key));
        if(!Set.of("idle","happy","excited","sad","sleepy","hungry","sick","sleeping").contains(ManagementCodec.text(g.get("state"),16))||
            !Set.of("none","cold","hot","dry","humid").contains(ManagementCodec.text(g.get("environment"),8)))
            throw new IllegalArgumentException("invalid_growth_state");
        return value;
    }
}
