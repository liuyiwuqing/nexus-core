package dev.nexus.core.web;

import dev.nexus.core.web.OverviewAlerts.Thresholds;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 告警阈值读写。单行表 + 版本号：概览每次读取都与计数同快照，管理台改完立即生效；
 * 并发写用 {@code FOR UPDATE} 比对版本，后提交的人收到 409 而不是覆盖前一个人的值。
 * 每次成功写入都在同一事务里追加变更审计，审计行保存写入后的完整阈值。
 */
@Service
public class AlertThresholds {
    public record Update(int version, Thresholds thresholds) {}

    private static final int AUDIT_PAGE = 20;
    private static final int AUDIT_MAX = 100;
    private static final String AUDIT_COLUMNS = """
        sequence,actor,from_version AS "fromVersion",to_version AS "toVersion",
        failure_rate AS "failureRate",failure_rate_min_sample AS "failureRateMinSample",
        backlog_pending AS "backlogPending",online_min AS "onlineMin",recorded_at AS "recordedAt"
        """;

    private final JdbcTemplate db;

    public AlertThresholds(JdbcTemplate db) { this.db = db; }

    /** 概览之外的调用方（如测试）读当前值；概览本身用同一条 SQL 取阈值，避免两次读。 */
    public Thresholds current() {
        return db.queryForObject("""
            SELECT failure_rate,failure_rate_min_sample,backlog_pending,online_min FROM alert_thresholds
            """,(r,n) -> new Thresholds(r.getDouble(1),r.getLong(2),r.getLong(3),r.getLong(4)));
    }

    /** 当前阈值、版本与最近 20 次变更；history 只用于展示和核对，不参与判定。 */
    public Map<String,Object> read() {
        return db.queryForObject("""
            SELECT failure_rate AS "failureRate",failure_rate_min_sample AS "failureRateMinSample",
                   backlog_pending AS "backlogPending",online_min AS "onlineMin",
                   version,updated_by AS "updatedBy",updated_at AS "updatedAt"
            FROM alert_thresholds
            """,(r,n) -> {
                Map<String,Object> value = new LinkedHashMap<>();
                value.put("thresholds",new Thresholds(r.getDouble("failureRate"),r.getLong("failureRateMinSample"),
                    r.getLong("backlogPending"),r.getLong("onlineMin")));
                value.put("version",r.getInt("version"));
                value.put("updatedBy",r.getString("updatedBy"));
                value.put("updatedAt",r.getTimestamp("updatedAt").toInstant());
                value.put("history",history());
                value.put("historyTotal",auditTotal(null));
                return value;
            });
    }

    private List<Map<String,Object>> history() {
        return db.queryForList("SELECT " + AUDIT_COLUMNS
            + " FROM alert_threshold_audit ORDER BY sequence DESC LIMIT " + AUDIT_PAGE);
    }

    /**
     * 审计按 {@code sequence} 向前翻页：{@code before} 是当前已加载最旧一行的序号，
     * 新写入只会出现在更大序号一侧，因此翻页不会因并发保存而重复或跳过。
     * {@code from} 为可选的 ISO-8601 起始时刻（含），用于只看最近一段时间的变更；
     * 总数与 hasMore 都按同一时间范围计算。
     */
    public Map<String,Object> audit(Long before,int size,String from) {
        if (size < 1 || size > AUDIT_MAX || (before != null && before < 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_request");
        Instant since = parseSince(from);
        StringBuilder sql = new StringBuilder("SELECT ").append(AUDIT_COLUMNS)
            .append(" FROM alert_threshold_audit WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (before != null) { sql.append(" AND sequence<?"); args.add(before); }
        if (since != null) { sql.append(" AND recorded_at>=?"); args.add(Timestamp.from(since)); }
        sql.append(" ORDER BY sequence DESC LIMIT ?");
        args.add(size);
        List<Map<String,Object>> items = db.queryForList(sql.toString(),args.toArray());
        boolean hasMore = false;
        if (!items.isEmpty()) {
            long oldest = ((Number)items.getLast().get("sequence")).longValue();
            hasMore = since == null
                ? Boolean.TRUE.equals(db.queryForObject(
                    "SELECT exists(SELECT 1 FROM alert_threshold_audit WHERE sequence<?)",Boolean.class,oldest))
                : Boolean.TRUE.equals(db.queryForObject(
                    "SELECT exists(SELECT 1 FROM alert_threshold_audit WHERE sequence<? AND recorded_at>=?)",
                    Boolean.class,oldest,Timestamp.from(since)));
        }
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("items",items);
        value.put("total",auditTotal(since));
        value.put("hasMore",hasMore);
        return value;
    }

    private static Instant parseSince(String from) {
        if (from == null || from.isBlank()) return null;
        try {
            return Instant.parse(from.trim());
        } catch (DateTimeParseException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_request");
        }
    }

    private long auditTotal(Instant since) {
        Long total = since == null
            ? db.queryForObject("SELECT count(*) FROM alert_threshold_audit",Long.class)
            : db.queryForObject("SELECT count(*) FROM alert_threshold_audit WHERE recorded_at>=?",
                Long.class,Timestamp.from(since));
        return total == null ? 0 : total;
    }

    @Transactional
    public Map<String,Object> update(Update input,String actor) {
        Thresholds next = validated(input);
        Integer current = db.queryForObject("SELECT version FROM alert_thresholds FOR UPDATE",Integer.class);
        if (current == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"alert_thresholds_missing");
        if (input.version() != current)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"alert_threshold_version_conflict");
        int nextVersion = current + 1;
        db.update("""
            UPDATE alert_thresholds SET failure_rate=?,failure_rate_min_sample=?,backlog_pending=?,online_min=?,
                version=?,updated_by=?,updated_at=now()
            """,next.failureRate(),next.failureRateMinSample(),next.backlogPending(),next.onlineMin(),nextVersion,actor);
        db.update("""
            INSERT INTO alert_threshold_audit(actor,from_version,to_version,failure_rate,
                failure_rate_min_sample,backlog_pending,online_min)
            VALUES(?,?,?,?,?,?,?)
            """,actor,current,nextVersion,next.failureRate(),next.failureRateMinSample(),
            next.backlogPending(),next.onlineMin());
        return read();
    }

    private static Thresholds validated(Update input) {
        if (input == null || input.version() < 1 || input.thresholds() == null)
            throw invalid();
        Thresholds value = input.thresholds();
        // 比例用 0–1；样本、积压、在线下限都要求 ≥1：0 只会得到永远为真或永远为假的告警。
        if (!Double.isFinite(value.failureRate()) || value.failureRate() < 0 || value.failureRate() > 1
            || value.failureRateMinSample() < 1 || value.failureRateMinSample() > 100_000
            || value.backlogPending() < 1 || value.backlogPending() > 1_000_000
            || value.onlineMin() < 1 || value.onlineMin() > 1_000)
            throw invalid();
        return value;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_alert_thresholds");
    }
}
