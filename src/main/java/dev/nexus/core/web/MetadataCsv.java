package dev.nexus.core.web;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** Metadata-only exports must pass an explicit column allowlist. */
public final class MetadataCsv {
    public static final int ROW_LIMIT = 5000;
    private MetadataCsv() {}

    public static ResponseEntity<String> response(String filename, List<Map<String,Object>> rows, String... columns) {
        // Query one extra row so a concurrent write cannot turn a count check into silent truncation.
        if (rows.size() > ROW_LIMIT) throw new ResponseStatusException(HttpStatus.CONFLICT, "export_too_large");
        var csv = new StringBuilder("\ufeff").append(line(List.of(columns)));
        for (var row : rows) csv.append(line(java.util.Arrays.stream(columns).map(column -> row.get(column)).toList()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").body(csv.toString());
    }

    private static String line(List<?> values) {
        return values.stream().map(MetadataCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value instanceof Timestamp t ? t.toInstant().toString() : value.toString();
        int first = 0;
        while (first < text.length() && (Character.isWhitespace(text.charAt(first)) || Character.isISOControl(text.charAt(first)) || text.charAt(first) == '\ufeff')) first++;
        if (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0 || !text.isEmpty() && Character.isISOControl(text.charAt(0)))
            text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
