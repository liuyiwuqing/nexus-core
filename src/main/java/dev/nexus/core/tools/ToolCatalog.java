package dev.nexus.core.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** CORE and Bridge both enforce this small, explicit capability boundary. */
public final class ToolCatalog {
    public record Definition(String id, String title, String risk, String argument) {}
    public static final List<Definition> TOOLS = List.of(
        new Definition("system.info", "系统信息", "read_only", ""),
        new Definition("project.status", "项目状态", "read_only", "projectId"),
        new Definition("app.open", "打开应用", "confirmation", "applicationId"));
    private ToolCatalog() {}
    public static Definition definition(String id) {
        return TOOLS.stream().filter(tool -> tool.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "tool_not_allowed"));
    }
    public static String arguments(Definition tool, Map<String,Object> arguments, ObjectMapper json) {
        if (arguments == null || !arguments.keySet().equals(tool.argument().isEmpty() ? java.util.Set.of() : java.util.Set.of(tool.argument())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_tool_arguments");
        if (!tool.argument().isEmpty() && (!(arguments.get(tool.argument()) instanceof String value) || !value.matches("[a-zA-Z0-9_-]{1,40}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_tool_arguments");
        return json.writeValueAsString(new TreeMap<>(arguments));
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
