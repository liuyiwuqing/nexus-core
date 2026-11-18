package dev.nexus.core.web;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Fixed predicates shared by list, count and metadata export; values are always bound. */
public record ConsoleListQuery(String where, List<Object> values) {
    public static ConsoleListQuery messages(String readStatus, String source, String from, String before) {
        var sql = new StringBuilder(" WHERE 1=1");
        var values = new ArrayList<Object>();
        if (readStatus != null) {
            require(readStatus.matches("read|unread"));
            sql.append(" AND m.read_at IS ").append("read".equals(readStatus) ? "NOT NULL" : "NULL");
        }
        if (source != null) {
            require(source.matches("[a-zA-Z0-9_.-]{1,32}"));
            sql.append(" AND m.source=?"); values.add(source);
        }
        return window(sql, values, from, before);
    }

    public static ConsoleListQuery deliveries(String status, String device, String reason, String from, String before) {
        var sql = new StringBuilder(" WHERE 1=1");
        var values = new ArrayList<Object>();
        if (status != null) {
            require(status.matches("pending|sent|received|displayed|read|failed|expired|cancelled"));
            sql.append(" AND d.status=?"); values.add(status);
        }
        if (device != null) {
            require(device.matches("[a-zA-Z0-9_-]{1,40}"));
            sql.append(" AND d.device_id=?"); values.add(device);
        }
        // 失败原因取自代码里写死的字符串集合；未知但格式合法的值返回空列表，不当作错误。
        // `unknown` 是概览对 NULL 的归并名，必须落回 IS NULL，否则"1 条未记录原因"点进去会是空的。
        if (reason != null) {
            require(reason.matches("[a-z_]{1,32}"));
            if ("unknown".equals(reason)) sql.append(" AND d.last_error IS NULL");
            else { sql.append(" AND d.last_error=?"); values.add(reason); }
        }
        return window(sql, values, from, before);
    }

    private static ConsoleListQuery window(StringBuilder sql, ArrayList<Object> values, String from, String before) {
        Instant start = timestamp(from), end = timestamp(before);
        require(start == null || end == null || start.isBefore(end));
        if (start != null) { sql.append(" AND m.received_at>=?"); values.add(Timestamp.from(start)); }
        if (end != null) { sql.append(" AND m.received_at<?"); values.add(Timestamp.from(end)); }
        return new ConsoleListQuery(sql.toString(), List.copyOf(values));
    }

    private static Instant timestamp(String value) {
        if (value == null) return null;
        try {
            var date = OffsetDateTime.parse(value);
            require(date.getYear() >= 1 && date.getYear() <= 9999);
            return date.toInstant();
        } catch (DateTimeException e) { throw new IllegalArgumentException("invalid_received_window"); }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("invalid_list_filter");
    }

    public Object[] parameters(Object... tail) {
        var all = new ArrayList<>(values); all.addAll(List.of(tail)); return all.toArray();
    }
}
