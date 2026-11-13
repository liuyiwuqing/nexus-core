package dev.nexus.core.web;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public final class ApiRows {
    private ApiRows() {}
    public static List<Map<String,Object>> normalize(List<Map<String,Object>> rows) {
        for (var row : rows) row.replaceAll((key,value) -> value instanceof Timestamp t ? t.toInstant().toString() : value);
        return rows;
    }
}
