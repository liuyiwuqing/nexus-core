package dev.nexus.core.web;

import dev.nexus.core.delivery.MqttTransport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
public class OverviewController {
    // 桶只开放这几个粒度：更细的窗口没有足够保留数据，更粗的桶在 7 天窗口里没有意义。
    private static final Map<String,Integer> BUCKETS = Map.of("15m",900,"1h",3600,"6h",21600,"1d",86400);
    private static final int MAX_POINTS = 400;
    private final JdbcTemplate db;
    private final MqttTransport mqtt;
    private final ObjectMapper json;
    public OverviewController(JdbcTemplate db,MqttTransport mqtt,ObjectMapper json) { this.db=db;this.mqtt=mqtt;this.json=json; }
    public record Devices(long total,long online,long offline,long disabled) {}
    public record Today(long messages,long deliveries,long failedDeliveries,Double deliveryFailureRate) {}
    public record Backlog(long pending,long sent) {}
    /** 今日失败投递按 last_error 归并后的一行；`code` 取自代码里的固定字符串集合。 */
    public record FailureReason(String code,long count) {}
    public record Overview(Instant asOf,Instant dayStart,String timezone,Devices devices,Today today,
                           Backlog backlog,long failedDeliveries,String mqtt,
                           OverviewAlerts.Thresholds thresholds,List<OverviewAlerts.Alert> alerts,
                           List<FailureReason> failureReasons) {}

    /** 告警与计数来自同一条查询的同一快照；阈值由服务端给出，页面不再自带一份。 */
    @GetMapping("/api/v1/overview")
    Overview overview() {
        // One statement gives all retained-row counts the same PostgreSQL snapshot.
        // A source-supplied created_at must never move an imported message into another day.
        return db.queryForObject("""
            WITH bounds AS (
                SELECT now() AS as_of,now()-interval '90 seconds' AS online_cutoff,
                    date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS day_start
            ), device_counts AS (
                -- 在线阈值只写一次：概览计数和告警判定共用同一个截止时间。
                SELECT count(*) AS total,
                    count(*) FILTER (WHERE online AND last_seen > b.online_cutoff) AS online,
                    count(*) FILTER (WHERE NOT enabled) AS disabled,
                    count(*) FILTER (WHERE enabled) AS enabled_total,
                    count(*) FILTER (WHERE enabled AND online AND last_seen > b.online_cutoff) AS online_enabled
                FROM devices CROSS JOIN bounds b
            ), message_counts AS (
                SELECT count(*) AS messages FROM inbox_messages m CROSS JOIN bounds b
                WHERE m.received_at >= b.day_start AND m.received_at <= b.as_of AND m.metadata->>'test' IS DISTINCT FROM 'true'
            ), failed_today AS (
                -- 今日失败投递按 last_error 归并；today_failed 由这里求和，两个数字不可能对不上。
                SELECT coalesce(d.last_error,'unknown') AS code,count(*) AS n
                FROM deliveries d JOIN inbox_messages m ON m.message_id=d.message_id CROSS JOIN bounds b
                WHERE m.metadata->>'test' IS DISTINCT FROM 'true'
                    AND m.received_at >= b.day_start AND m.received_at <= b.as_of AND d.status='failed'
                GROUP BY 1
            ), delivery_counts AS (
                SELECT count(*) FILTER (WHERE m.received_at >= b.day_start AND m.received_at <= b.as_of) AS today_total,
                    (SELECT coalesce(sum(n),0) FROM failed_today) AS today_failed,
                    count(*) FILTER (WHERE d.status='pending') AS pending,
                    count(*) FILTER (WHERE d.status='sent') AS sent,
                    count(*) FILTER (WHERE d.status='failed') AS failed
                FROM deliveries d JOIN inbox_messages m ON m.message_id=d.message_id CROSS JOIN bounds b
                WHERE m.metadata->>'test' IS DISTINCT FROM 'true'
            )
            SELECT b.*,dc.total,dc.online,dc.disabled,dc.enabled_total,dc.online_enabled,mc.messages,dd.*,
                (SELECT coalesce(json_agg(json_build_object('code',code,'count',n) ORDER BY n DESC,code),'[]'::json)
                 FROM failed_today)::text AS failure_reasons
            FROM bounds b CROSS JOIN device_counts dc CROSS JOIN message_counts mc CROSS JOIN delivery_counts dd
            """,(r,n)-> {
                long total=r.getLong("total"),online=r.getLong("online"),todayTotal=r.getLong("today_total"),failed=r.getLong("today_failed");
                boolean ready=mqtt.ready();
                List<FailureReason> reasons=new ArrayList<>();
                for (var node : json.readTree(r.getString("failure_reasons")))
                    reasons.add(new FailureReason(node.path("code").asText(),node.path("count").asLong()));
                return new Overview(r.getTimestamp("as_of").toInstant(),r.getTimestamp("day_start").toInstant(),"UTC",
                    new Devices(total,online,total-online,r.getLong("disabled")),
                    new Today(r.getLong("messages"),todayTotal,failed,todayTotal==0?null:(double)failed/todayTotal),
                    new Backlog(r.getLong("pending"),r.getLong("sent")),r.getLong("failed"),ready?"ready":"unavailable",
                    OverviewAlerts.THRESHOLDS,
                    OverviewAlerts.evaluate(r.getLong("enabled_total"),r.getLong("online_enabled"),
                        r.getLong("pending"),todayTotal,failed,ready),
                    reasons);
            });
    }

    /**
     * 概览趋势：固定 N 个桶，最后一桶包含当前时刻，因此是部分桶。
     * 收件与投递按消息 received_at 归桶（与今日口径一致，来源时间不移动归日）；
     * 失败按 delivery_history 记录的进入 failed 事件计数，不是"当前失败按收件桶回看"。
     * 三者都只覆盖仍保留的记录，旧 baseline 不算失败事件。
     */
    @GetMapping("/api/v1/overview/trend")
    Map<String,Object> trend(@RequestParam(defaultValue="24") int hours,
                             @RequestParam(defaultValue="1h") String bucket) {
        Integer seconds = BUCKETS.get(bucket);
        long span = (long) hours * 3600;
        if (seconds == null || hours < 1 || hours > 168 || span % seconds != 0 || span / seconds > MAX_POINTS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        int points = (int) (span / seconds);
        String step = seconds + " seconds";
        List<Map<String,Object>> rows = db.queryForList("""
            WITH bounds AS (
                SELECT now() AS as_of
            ), grid AS (
                -- date_bin 支持任意间隔；固定 origin 让 15m/6h 这类桶在所有窗口里对齐。
                SELECT b.as_of,
                    date_bin(?::interval,b.as_of,'2000-01-01T00:00:00Z'::timestamptz)-(? - 1)*?::interval AS first_at
                FROM bounds b
            ), points AS (
                SELECT g.as_of,generate_series(g.first_at,
                    date_bin(?::interval,g.as_of,'2000-01-01T00:00:00Z'::timestamptz),?::interval) AS at
                FROM grid g
            ), messages AS (
                SELECT date_bin(?::interval,m.received_at,'2000-01-01T00:00:00Z'::timestamptz) AS at,count(*) AS n
                FROM inbox_messages m,grid g
                WHERE m.received_at >= g.first_at AND m.received_at <= g.as_of
                    AND m.metadata->>'test' IS DISTINCT FROM 'true'
                GROUP BY 1
            ), deliveries AS (
                SELECT date_bin(?::interval,m.received_at,'2000-01-01T00:00:00Z'::timestamptz) AS at,count(*) AS n
                FROM deliveries d JOIN inbox_messages m ON m.message_id=d.message_id,grid g
                WHERE m.received_at >= g.first_at AND m.received_at <= g.as_of
                    AND m.metadata->>'test' IS DISTINCT FROM 'true'
                GROUP BY 1
            ), failures AS (
                SELECT date_bin(?::interval,h.recorded_at,'2000-01-01T00:00:00Z'::timestamptz) AS at,count(*) AS n
                FROM delivery_history h,grid g
                WHERE h.recorded_at >= g.first_at AND h.recorded_at <= g.as_of
                    AND h.status='failed' AND h.kind<>'baseline'
                GROUP BY 1
            )
            SELECT to_char(p.at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"') AS at,
                coalesce(msg.n,0) AS messages,coalesce(del.n,0) AS deliveries,coalesce(fail.n,0) AS failures,
                (p.at=max(p.at) OVER ()) AS partial,
                to_char(p.as_of AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"') AS as_of
            FROM points p
            LEFT JOIN messages msg ON msg.at=p.at
            LEFT JOIN deliveries del ON del.at=p.at
            LEFT JOIN failures fail ON fail.at=p.at
            ORDER BY p.at
            """,step,points,step,step,step,step,step,step);
        List<Map<String,Object>> series = new ArrayList<>(rows.size());
        long messages = 0, deliveries = 0, failures = 0;
        for (var row : rows) {
            long received = ((Number) row.get("messages")).longValue();
            long delivered = ((Number) row.get("deliveries")).longValue();
            long failed = ((Number) row.get("failures")).longValue();
            messages += received; deliveries += delivered; failures += failed;
            Map<String,Object> point = new LinkedHashMap<>();
            point.put("at", row.get("at"));
            point.put("messages", received);
            point.put("deliveries", delivered);
            point.put("failures", failed);
            point.put("partial", row.get("partial"));
            series.add(point);
        }
        // 空表也返回完整网格：前端画的是"没有数据"，不是"没有窗口"。
        String asOf = rows.isEmpty() ? Instant.now().toString() : rows.getLast().get("as_of").toString();
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("asOf", asOf);
        value.put("from", rows.isEmpty() ? asOf : rows.getFirst().get("at"));
        value.put("to", asOf);
        value.put("hours", hours);
        value.put("bucket", bucket);
        value.put("bucketSeconds", seconds);
        value.put("points", series);
        value.put("totals", Map.of("messages", messages, "deliveries", deliveries, "failures", failures));
        return value;
    }
}
