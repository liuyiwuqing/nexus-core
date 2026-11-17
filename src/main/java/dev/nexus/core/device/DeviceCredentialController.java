package dev.nexus.core.device;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设备凭据管理：查询对全体角色可读（不含密钥），签发/轮换/撤销只允许管理员。
 * 响应中的 secret 只在本次返回，不落库、不写审计、不提供再次读取。
 */
@RestController
@RequestMapping("/api/v1/devices/{deviceId}/credentials")
public class DeviceCredentialController {
    private final DeviceCredentials credentials;

    public DeviceCredentialController(DeviceCredentials credentials) { this.credentials = credentials; }

    public record Issue(@NotBlank String confirm) {}
    public record Rotate(@Min(1) int expectedRevision, Integer graceHours, @NotBlank String confirm) {
        @Override public String toString() { return "Rotate[confirm=redacted]"; }
    }
    public record Revoke(@Min(1) int expectedRevision, @NotBlank String confirm) {}

    private static void admin(HttpServletRequest request) {
        Object role = request.getAttribute("nexus.role");
        if (!"admin".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("nexus.actor");
        return actor == null ? "admin" : actor.toString();
    }

    // 路径变量本身不落库查询前先限长，避免超长标识进入 SQL 参数和日志。
    private static String device(String deviceId) {
        if (deviceId == null || deviceId.isEmpty() || deviceId.length() > 40)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device_not_found");
        return deviceId;
    }

    @GetMapping
    Map<String,Object> describe(@PathVariable String deviceId) {
        return credentials.describe(device(deviceId));
    }

    @PostMapping
    ResponseEntity<?> issue(@PathVariable String deviceId, @Valid @RequestBody Issue input, HttpServletRequest request) {
        admin(request);
        if (!"ISSUE_DEVICE_CREDENTIAL".equals(input.confirm()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        return ResponseEntity.status(201).body(credentials.issue(device(deviceId), actor(request)));
    }

    @PostMapping("/rotate")
    Map<String,Object> rotate(@PathVariable String deviceId, @Valid @RequestBody Rotate input, HttpServletRequest request) {
        admin(request);
        if (!"ROTATE_DEVICE_CREDENTIAL".equals(input.confirm()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        int grace = input.graceHours() == null ? DeviceCredentials.DEFAULT_GRACE_HOURS : input.graceHours();
        return credentials.rotate(device(deviceId), input.expectedRevision(), grace, actor(request));
    }

    @PostMapping("/revoke")
    ResponseEntity<?> revoke(@PathVariable String deviceId, @Valid @RequestBody Revoke input, HttpServletRequest request) {
        admin(request);
        if (!"REVOKE_DEVICE_CREDENTIAL".equals(input.confirm()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        credentials.revoke(device(deviceId), input.expectedRevision(), actor(request));
        return ResponseEntity.noContent().build();
    }
}
