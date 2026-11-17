package dev.nexus.core.device;

import dev.nexus.core.web.ApiRows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设备 MQTT 凭据台账。CORE 只维护期望状态：一次性密钥、修订号、轮换宽限期与撤销，
 * 不写 Broker 配置文件（部署侧 Mosquitto 配置为只读绑定，CORE 容器也没有该路径）。
 * 明文密钥只在签发/轮换响应中出现一次，库里只有 SHA-256 指纹。
 */
@Service
public class DeviceCredentials {
    public static final int DEFAULT_GRACE_HOURS = 24, MAX_GRACE_HOURS = 168;
    private final JdbcTemplate db;

    public DeviceCredentials(JdbcTemplate db) { this.db = db; }

    public static String username(String deviceId) { return "dev-" + deviceId; }

    /** 设备命名空间内的读写权限；CORE 自身使用独立的 Broker 账号，不在此台账内。 */
    public static List<String> acl(String deviceId) {
        return List.of("topic readwrite nexus/v1/devices/" + deviceId + "/#");
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void requireDevice(String deviceId) {
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_id=?)", Boolean.class, deviceId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device_not_found");
    }

    private void event(String deviceId, int revision, String event, String actor, String detail) {
        db.update("INSERT INTO device_credential_events(device_id,revision,event,actor,detail) VALUES(?,?,?,?,?)",
            deviceId, revision, event, actor, detail);
    }

    private Map<String,Object> body(String deviceId, int revision, String status) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("deviceId", deviceId);
        value.put("username", username(deviceId));
        value.put("revision", revision);
        value.put("status", status);
        value.put("acl", acl(deviceId));
        return value;
    }

    /** 没有台账行时返回 none，不代表 Broker 上没有该账号；CORE 不读取 Broker 状态。 */
    public Map<String,Object> describe(String deviceId) {
        requireDevice(deviceId);
        var rows = ApiRows.normalize(db.queryForList("""
            SELECT device_id AS "deviceId",revision,issued_at AS "issuedAt",rotated_at AS "rotatedAt",
                   previous_expires_at AS "previousExpiresAt",revoked_at AS "revokedAt",
                   (previous_expires_at IS NOT NULL AND previous_expires_at > now()) AS "previousActive"
            FROM device_credentials WHERE device_id=?
            """, deviceId));
        if (rows.isEmpty()) {
            Map<String,Object> value = body(deviceId, 0, "none");
            value.put("issuedAt", null);
            value.put("rotatedAt", null);
            value.put("previousExpiresAt", null);
            value.put("revokedAt", null);
            value.put("previousActive", false);
            return value;
        }
        var row = rows.getFirst();
        boolean revoked = row.get("revokedAt") != null, previousActive = Boolean.TRUE.equals(row.get("previousActive"));
        Map<String,Object> value = body(deviceId, (Integer) row.get("revision"),
            revoked ? "revoked" : previousActive ? "rotating" : "active");
        value.put("issuedAt", row.get("issuedAt"));
        value.put("rotatedAt", row.get("rotatedAt"));
        value.put("previousExpiresAt", row.get("previousExpiresAt"));
        value.put("revokedAt", row.get("revokedAt"));
        value.put("previousActive", previousActive);
        return value;
    }

    @Transactional
    public Map<String,Object> issue(String deviceId, String actor) {
        requireDevice(deviceId);
        var rows = db.queryForList("SELECT revision,revoked_at FROM device_credentials WHERE device_id=? FOR UPDATE", deviceId);
        String secret = secret();
        if (rows.isEmpty()) {
            db.update("INSERT INTO device_credentials(device_id,username,revision,secret_fingerprint,updated_by) VALUES(?,?,?,?,?)",
                deviceId, username(deviceId), 1, fingerprint(secret), actor);
            event(deviceId, 1, "issued", actor, "");
            return issued(deviceId, 1, secret);
        }
        if (rows.getFirst().get("revoked_at") == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_credential_exists");
        // 撤销后重新签发：沿用同一用户名，修订号继续递增，旧宽限期记录清除。
        int revision = (Integer) rows.getFirst().get("revision") + 1;
        db.update("""
            UPDATE device_credentials SET revision=?,secret_fingerprint=?,previous_fingerprint=NULL,
                previous_expires_at=NULL,issued_at=now(),rotated_at=NULL,revoked_at=NULL,updated_at=now(),updated_by=?
            WHERE device_id=?
            """, revision, fingerprint(secret), actor, deviceId);
        event(deviceId, revision, "reissued", actor, "");
        return issued(deviceId, revision, secret);
    }

    private Map<String,Object> issued(String deviceId, int revision, String secret) {
        // 签发与重新签发都清空宽限期，结果必然是 active。
        Map<String,Object> value = body(deviceId, revision, "active");
        value.put("secret", secret);
        return value;
    }

    @Transactional
    public Map<String,Object> rotate(String deviceId, int expectedRevision, int graceHours, String actor) {
        requireDevice(deviceId);
        if (graceHours < 1 || graceHours > MAX_GRACE_HOURS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        var rows = db.queryForList("SELECT revision,revoked_at FROM device_credentials WHERE device_id=? FOR UPDATE", deviceId);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device_credential_not_found");
        var row = rows.getFirst();
        if (row.get("revoked_at") != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_credential_revoked");
        int revision = (Integer) row.get("revision");
        if (revision != expectedRevision)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_credential_version_conflict");
        String secret = secret();
        // 旧当前指纹进入宽限期：既有的 previous 被覆盖，避免无限累积可用的历史密钥。
        db.update("""
            UPDATE device_credentials SET revision=?,secret_fingerprint=?,previous_fingerprint=secret_fingerprint,
                previous_expires_at=now()+? * interval '1 hour',rotated_at=now(),updated_at=now(),updated_by=?
            WHERE device_id=?
            """, revision + 1, fingerprint(secret), graceHours, actor, deviceId);
        event(deviceId, revision + 1, "rotated", actor, graceHours + "h");
        Map<String,Object> value = body(deviceId, revision + 1, "rotating");
        value.put("secret", secret);
        value.put("previousExpiresAt", previousExpiry(deviceId));
        return value;
    }

    private String previousExpiry(String deviceId) {
        Timestamp expiry = db.queryForObject("SELECT previous_expires_at FROM device_credentials WHERE device_id=?", Timestamp.class, deviceId);
        return expiry == null ? null : expiry.toInstant().toString();
    }

    @Transactional
    public void revoke(String deviceId, int expectedRevision, String actor) {
        requireDevice(deviceId);
        var rows = db.queryForList("SELECT revision,revoked_at FROM device_credentials WHERE device_id=? FOR UPDATE", deviceId);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device_credential_not_found");
        var row = rows.getFirst();
        int revision = (Integer) row.get("revision");
        // 响应丢失后的重试：已是目标撤销状态时按幂等处理，不再递增修订号。
        if (row.get("revoked_at") != null) {
            if (revision == expectedRevision + 1) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_credential_version_conflict");
        }
        if (revision != expectedRevision)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "device_credential_version_conflict");
        db.update("""
            UPDATE device_credentials SET revoked_at=now(),previous_fingerprint=NULL,previous_expires_at=NULL,
                revision=revision+1,updated_at=now(),updated_by=? WHERE device_id=?
            """, actor, deviceId);
        event(deviceId, revision + 1, "revoked", actor, "");
    }

    // 台账状态只有四种：none（未签发）、revoked、rotating（宽限期内新旧并存）、active。
    // 接入引导的 SQL 分支与此处 describe 保持一致；改口径时两处同时改。
}
