package dev.nexus.core.web;

import dev.nexus.core.delivery.MqttTransport;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {
    private final JdbcTemplate db;
    private final MqttTransport mqtt;
    public OverviewController(JdbcTemplate db,MqttTransport mqtt) { this.db=db;this.mqtt=mqtt; }
    public record Devices(long total,long online,long offline,long disabled) {}
    public record Today(long messages,long deliveries,long failedDeliveries,Double deliveryFailureRate) {}
    public record Backlog(long pending,long sent) {}
    public record Overview(Instant asOf,Instant dayStart,String timezone,Devices devices,Today today,
                           Backlog backlog,long failedDeliveries,String mqtt) {}

    @GetMapping("/api/v1/overview")
    Overview overview() {
        // One statement gives all retained-row counts the same PostgreSQL snapshot.
        // A source-supplied created_at must never move an imported message into another day.
        return db.queryForObject("""
            WITH bounds AS (
                SELECT now() AS as_of,date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS day_start
            ), device_counts AS (
                SELECT count(*) AS total,
                    count(*) FILTER (WHERE online AND last_seen > b.as_of-interval '90 seconds') AS online,
                    count(*) FILTER (WHERE NOT enabled) AS disabled FROM devices CROSS JOIN bounds b
            ), message_counts AS (
                SELECT count(*) AS messages FROM inbox_messages m CROSS JOIN bounds b
                WHERE m.received_at >= b.day_start AND m.received_at <= b.as_of AND m.metadata->>'test' IS DISTINCT FROM 'true'
            ), delivery_counts AS (
                SELECT count(*) FILTER (WHERE m.received_at >= b.day_start AND m.received_at <= b.as_of) AS today_total,
                    count(*) FILTER (WHERE m.received_at >= b.day_start AND m.received_at <= b.as_of AND d.status='failed') AS today_failed,
                    count(*) FILTER (WHERE d.status='pending') AS pending,
                    count(*) FILTER (WHERE d.status='sent') AS sent,
                    count(*) FILTER (WHERE d.status='failed') AS failed
                FROM deliveries d JOIN inbox_messages m ON m.message_id=d.message_id CROSS JOIN bounds b
                WHERE m.metadata->>'test' IS DISTINCT FROM 'true'
            )
            SELECT b.*,dc.total,dc.online,dc.disabled,mc.messages,dd.*
            FROM bounds b CROSS JOIN device_counts dc CROSS JOIN message_counts mc CROSS JOIN delivery_counts dd
            """,(r,n)-> {
                long total=r.getLong("total"),online=r.getLong("online"),todayTotal=r.getLong("today_total"),failed=r.getLong("today_failed");
                return new Overview(r.getTimestamp("as_of").toInstant(),r.getTimestamp("day_start").toInstant(),"UTC",
                    new Devices(total,online,total-online,r.getLong("disabled")),
                    new Today(r.getLong("messages"),todayTotal,failed,todayTotal==0?null:(double)failed/todayTotal),
                    new Backlog(r.getLong("pending"),r.getLong("sent")),r.getLong("failed"),mqtt.ready()?"ready":"unavailable");
            });
    }
}
