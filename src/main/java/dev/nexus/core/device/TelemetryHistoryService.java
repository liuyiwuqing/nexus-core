package dev.nexus.core.device;

import dev.nexus.core.web.ApiRows;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Append-only telemetry history. The latest snapshot stays in device_telemetry;
 * this table only records bounded observations for charts and postmortems.
 */
@Service
public class TelemetryHistoryService {
    private static final Logger LOG=LoggerFactory.getLogger(TelemetryHistoryService.class);
    private static final int MAX_POINTS=2000,BATCH=10000;
    private static final Duration MAX_WINDOW=Duration.ofDays(30),MAX_RAW_WINDOW=Duration.ofHours(12);
    private static final Map<String,String> BUCKETS=Map.of(
        "1m","1 minute","5m","5 minutes","15m","15 minutes","1h","1 hour","1d","1 day");

    private final JdbcTemplate db;
    private final int sampleSeconds,changeSeconds,retentionDays;

    public TelemetryHistoryService(JdbcTemplate db,
        @Value("${nexus.telemetry.sample-seconds:30}") int sampleSeconds,
        @Value("${nexus.telemetry.change-seconds:5}") int changeSeconds,
        @Value("${nexus.telemetry.retention-days:30}") int retentionDays) {
        if(sampleSeconds<5||sampleSeconds>3600||changeSeconds<1||changeSeconds>sampleSeconds||
            retentionDays<1||retentionDays>365)throw new IllegalArgumentException("invalid_telemetry_history_config");
        this.db=db;this.sampleSeconds=sampleSeconds;this.changeSeconds=changeSeconds;this.retentionDays=retentionDays;
    }

    /**
     * Records a validated sample when it advances time and either changed a
     * meaningful state or reached the heartbeat interval. Retained replays and
     * out-of-order observations are ignored.
     */
    void record(String device,JsonNode value) {
        long observed=value.get("observedAt").asLong();
        var rows=db.queryForList("""
            SELECT observed_at,firmware_version,config_revision,volume,brightness,muted,dnd,unread,audio_faulted,
                wifi_ip,wifi_ssid,wifi_channel,wifi_bssid,wifi_channel_width,wifi_disconnect_reason
            FROM device_telemetry_samples WHERE device_id=? ORDER BY observed_at DESC LIMIT 1
            """,device);
        if(!rows.isEmpty()) {
            var last=rows.getFirst();
            long previous=((Timestamp)last.get("observed_at")).toInstant().getEpochSecond();
            if(observed<=previous)return;
            long floor=changed(last,value)?changeSeconds:sampleSeconds;
            if(observed-previous<floor)return;
        }
        db.update("""
            INSERT INTO device_telemetry_samples(device_id,observed_at,firmware_version,config_revision,
                volume,brightness,muted,dnd,unread,free_heap,min_free_heap,
                audio_underruns,inference_max_ms,display_max_ms,audio_faulted,
                wifi_ip,wifi_rssi,wifi_ssid,wifi_channel,wifi_bssid,wifi_channel_width,
                wifi_disconnect_reason,wifi_disconnected_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(device_id,observed_at) DO NOTHING
            """,device,Timestamp.from(Instant.ofEpochSecond(observed)),
            value.get("firmwareVersion").asText(),value.get("configRevision").asLong(),
            value.get("volume").asInt(),value.get("brightness").asInt(),
            value.get("muted").asBoolean(),value.get("dnd").asBoolean(),value.get("unread").asInt(),
            value.get("freeHeap").asLong(),value.get("minFreeHeap").asLong(),
            optionalLong(value,"audioUnderruns"),optionalLong(value,"inferenceMaxMs"),
            optionalLong(value,"displayMaxMs"),
            value.has("audioFaulted")?value.get("audioFaulted").asBoolean():null,
            value.has("wifiIp")?value.get("wifiIp").asText():null,optionalLong(value,"wifiRssi"),
            value.has("wifiSsid")?value.get("wifiSsid").asText():null,optionalLong(value,"wifiChannel"),
            value.has("wifiBssid")?value.get("wifiBssid").asText():null,optionalLong(value,"wifiChannelWidth"),
            optionalLong(value,"wifiDisconnectReason"),
            value.has("wifiDisconnectedAt")?Timestamp.from(Instant.ofEpochSecond(value.get("wifiDisconnectedAt").asLong())):null);
    }

    /** State changes are recorded immediately (rate-limited); heap jitter only follows the heartbeat. */
    private static boolean changed(Map<String,Object> last,JsonNode value) {
        return !last.get("firmware_version").equals(value.get("firmwareVersion").asText())
            || ((Number)last.get("config_revision")).longValue()!=value.get("configRevision").asLong()
            || ((Number)last.get("volume")).intValue()!=value.get("volume").asInt()
            || ((Number)last.get("brightness")).intValue()!=value.get("brightness").asInt()
            || !last.get("muted").equals(value.get("muted").asBoolean())
            || !last.get("dnd").equals(value.get("dnd").asBoolean())
            || ((Number)last.get("unread")).intValue()!=value.get("unread").asInt()
            || !java.util.Objects.equals(last.get("audio_faulted"),
                value.has("audioFaulted")?value.get("audioFaulted").asBoolean():null)
            || !java.util.Objects.equals(last.get("wifi_ip"),
                value.has("wifiIp")?value.get("wifiIp").asText():null)
            || !java.util.Objects.equals(last.get("wifi_ssid"),
                value.has("wifiSsid")?value.get("wifiSsid").asText():null)
            || !java.util.Objects.equals(asInteger(last,"wifi_channel"),
                value.has("wifiChannel")?value.get("wifiChannel").asInt():null)
            || !java.util.Objects.equals(last.get("wifi_bssid"),
                value.has("wifiBssid")?value.get("wifiBssid").asText():null)
            || !java.util.Objects.equals(asInteger(last,"wifi_channel_width"),
                value.has("wifiChannelWidth")?value.get("wifiChannelWidth").asInt():null)
            || !java.util.Objects.equals(asInteger(last,"wifi_disconnect_reason"),
                value.has("wifiDisconnectReason")?value.get("wifiDisconnectReason").asInt():null);
    }

    private static Integer asInteger(Map<String,Object> row,String key) {
        return row.get(key)==null?null:((Number)row.get(key)).intValue();
    }

    public Map<String,Object> query(String device,String fromValue,String toValue,String bucket) {
        if(db.queryForObject("SELECT count(*) FROM devices WHERE device_id=?",Integer.class,device)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        Instant to=instant(toValue,Instant.now()),from=instant(fromValue,to.minus(Duration.ofHours(6)));
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(MAX_WINDOW)>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_telemetry_window");
        String mode=bucket==null||bucket.isBlank()?"5m":bucket;
        if(!mode.equals("raw")&&!BUCKETS.containsKey(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_telemetry_bucket");
        long seconds=Duration.between(from,to).getSeconds();
        if(mode.equals("raw")) {
            if(seconds>MAX_RAW_WINDOW.toSeconds())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"bucket_required");
        } else if(seconds/bucketSeconds(mode)+2>MAX_POINTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"bucket_too_fine");
        }
        List<Map<String,Object>> points;
        boolean truncated=false;
        if(mode.equals("raw")) {
            var rows=new ArrayList<>(db.queryForList("""
                SELECT observed_at AS "observedAt",received_at AS "receivedAt",1 AS count,
                    firmware_version AS "firmwareVersion",config_revision AS "configRevision",
                    volume,brightness,muted,dnd,unread,
                    free_heap AS "freeHeap",free_heap AS "freeHeapMin",free_heap AS "freeHeapMax",
                    min_free_heap AS "minFreeHeap",audio_underruns AS "audioUnderruns",
                    inference_max_ms AS "inferenceMaxMs",display_max_ms AS "displayMaxMs",
                    audio_faulted AS "audioFaulted",wifi_ip AS "wifiIp",wifi_rssi AS "wifiRssi",
                    wifi_ssid AS "wifiSsid",wifi_channel AS "wifiChannel",
                    wifi_bssid AS "wifiBssid",wifi_channel_width AS "wifiChannelWidth",
                    wifi_disconnect_reason AS "wifiDisconnectReason",wifi_disconnected_at AS "wifiDisconnectedAt"
                FROM device_telemetry_samples
                WHERE device_id=? AND observed_at>=? AND observed_at<?
                ORDER BY observed_at DESC LIMIT ?
                """,device,Timestamp.from(from),Timestamp.from(to),MAX_POINTS+1));
            truncated=rows.size()>MAX_POINTS;
            if(truncated)rows=new ArrayList<>(rows.subList(0,MAX_POINTS));
            Collections.reverse(rows);
            points=ApiRows.normalize(rows);
        } else {
            points=ApiRows.normalize(db.queryForList("""
                SELECT date_bin(INTERVAL '%s',observed_at,TIMESTAMPTZ '2000-01-01 00:00:00+00') AS "observedAt",
                    max(received_at) AS "receivedAt",count(*)::int AS count,
                    (array_agg(firmware_version ORDER BY observed_at DESC))[1] AS "firmwareVersion",
                    (array_agg(config_revision ORDER BY observed_at DESC))[1] AS "configRevision",
                    (array_agg(volume ORDER BY observed_at DESC))[1] AS volume,
                    (array_agg(brightness ORDER BY observed_at DESC))[1] AS brightness,
                    (array_agg(muted ORDER BY observed_at DESC))[1] AS muted,
                    (array_agg(dnd ORDER BY observed_at DESC))[1] AS dnd,
                    round(avg(unread))::int AS unread,
                    round(avg(free_heap))::bigint AS "freeHeap",
                    min(free_heap) AS "freeHeapMin",max(free_heap) AS "freeHeapMax",
                    min(min_free_heap) AS "minFreeHeap",max(audio_underruns) AS "audioUnderruns",
                    max(inference_max_ms) AS "inferenceMaxMs",max(display_max_ms) AS "displayMaxMs",
                    bool_or(audio_faulted) AS "audioFaulted",
                    (array_agg(wifi_ip ORDER BY observed_at DESC) FILTER (WHERE wifi_ip IS NOT NULL))[1] AS "wifiIp",
                    round(avg(wifi_rssi))::int AS "wifiRssi",
                    (array_agg(wifi_ssid ORDER BY observed_at DESC) FILTER (WHERE wifi_ssid IS NOT NULL))[1] AS "wifiSsid",
                    (array_agg(wifi_channel ORDER BY observed_at DESC) FILTER (WHERE wifi_channel IS NOT NULL))[1] AS "wifiChannel",
                    (array_agg(wifi_bssid ORDER BY observed_at DESC) FILTER (WHERE wifi_bssid IS NOT NULL))[1] AS "wifiBssid",
                    (array_agg(wifi_channel_width ORDER BY observed_at DESC)
                        FILTER (WHERE wifi_channel_width IS NOT NULL))[1] AS "wifiChannelWidth",
                    (array_agg(wifi_disconnect_reason ORDER BY observed_at DESC)
                        FILTER (WHERE wifi_disconnect_reason IS NOT NULL))[1] AS "wifiDisconnectReason",
                    (array_agg(wifi_disconnected_at ORDER BY observed_at DESC)
                        FILTER (WHERE wifi_disconnected_at IS NOT NULL))[1] AS "wifiDisconnectedAt"
                FROM device_telemetry_samples
                WHERE device_id=? AND observed_at>=? AND observed_at<?
                GROUP BY 1 ORDER BY 1
                """.formatted(BUCKETS.get(mode)),device,Timestamp.from(from),Timestamp.from(to)));
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("deviceId",device);result.put("from",from.toString());result.put("to",to.toString());
        result.put("bucket",mode);result.put("sampleSeconds",sampleSeconds);result.put("retentionDays",retentionDays);
        result.put("truncated",truncated);result.put("points",points);
        return result;
    }

    /**
     * Read-only disconnect events. A row is an event when the reported
     * (reason, time) pair differs from the previous sample, so a stable pair
     * that merely repeats on heartbeats is not listed twice. Samples without a
     * reason are not events, and the first sample of a device never produces a
     * synthetic "before" event. An optional reason filters the event list after
     * event detection, so removing other reasons never merges or hides events.
     */
    public Map<String,Object> disconnects(String device,String limitValue,String reasonValue) {
        if(db.queryForObject("SELECT count(*) FROM devices WHERE device_id=?",Integer.class,device)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"device_not_found");
        int limit=20;
        if(limitValue!=null&&!limitValue.isBlank()) {
            try { limit=Integer.parseInt(limitValue.strip()); }
            catch(NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_disconnect_limit"); }
        }
        if(limit<1||limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_disconnect_limit");
        Integer reason=null;
        if(reasonValue!=null&&!reasonValue.isBlank()) {
            try { reason=Integer.parseInt(reasonValue.strip()); }
            catch(NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_disconnect_reason"); }
            if(reason<0||reason>65535)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_disconnect_reason");
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("deviceId",device);result.put("limit",limit);result.put("retentionDays",retentionDays);
        result.put("reason",reason);
        result.put("items",ApiRows.normalize(db.queryForList("""
            SELECT observed_at AS "observedAt",reason AS "reason",disconnected_at AS "disconnectedAt"
            FROM (
                SELECT observed_at,wifi_disconnect_reason AS reason,wifi_disconnected_at AS disconnected_at,
                    lag(wifi_disconnect_reason) OVER w AS previous_reason,
                    lag(wifi_disconnected_at) OVER w AS previous_at
                FROM device_telemetry_samples WHERE device_id=?
                WINDOW w AS (ORDER BY observed_at)
            ) sampled
            WHERE reason IS NOT NULL
              AND (previous_reason IS DISTINCT FROM reason OR previous_at IS DISTINCT FROM disconnected_at)
            """+(reason==null?"":" AND reason=? ")+"""
            ORDER BY observed_at DESC LIMIT ?
            """,reason==null?new Object[]{device,limit}:new Object[]{device,reason,limit})));
        return result;
    }

    private static long bucketSeconds(String bucket) {
        return switch(bucket) {
            case "1m" -> 60; case "5m" -> 300; case "15m" -> 900; case "1h" -> 3600; case "1d" -> 86400;
            default -> throw new IllegalArgumentException("invalid_telemetry_bucket");
        };
    }

    private static Instant instant(String value,Instant fallback) {
        if(value==null||value.isBlank())return fallback;
        try {
            var parsed=OffsetDateTime.parse(value);
            if(parsed.getYear()<1||parsed.getYear()>9999)throw new DateTimeException("year");
            return parsed.toInstant();
        } catch(DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_telemetry_window");
        }
    }

    private static Long optionalLong(JsonNode value,String key) {
        return value.has(key)?value.get(key).asLong():null;
    }

    /** Bounded daily purge; never rewrites or migrates existing rows. */
    @Scheduled(cron="${nexus.telemetry.cleanup-cron:0 20 4 * * *}",zone="UTC")
    public void cleanup() {
        int total=0;
        for(int round=0;round<20;round++) {
            int deleted=db.update("""
                DELETE FROM device_telemetry_samples WHERE ctid IN (
                    SELECT ctid FROM device_telemetry_samples
                    WHERE observed_at<now()-(?::int*interval '1 day') LIMIT ?)
                """,retentionDays,BATCH);
            total+=deleted;
            if(deleted<BATCH)break;
        }
        if(total>0)LOG.info("Purged {} telemetry samples older than {} days",total,retentionDays);
    }
}
