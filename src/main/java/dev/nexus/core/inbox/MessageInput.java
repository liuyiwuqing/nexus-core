package dev.nexus.core.inbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MessageInput(
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{1,64}") String connectorId,
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{1,32}") String source,
    @NotBlank @Size(max=160) String eventId,
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{1,64}") String eventType,
    @NotBlank @Size(max=160) String title,
    @NotBlank @Size(max=8192) String content,
    @Size(max=1024) String summary,
    @Size(max=160) String sender,
    @Size(max=2048) String originalUrl,
    @Pattern(regexp="low|normal|high|critical") String priority,
    Instant createdAt,
    Instant expiresAt,
    @Size(max=16) Map<@Size(max=64) String, @Size(max=256) String> metadata,
    // 仅管理台创建可选：省略/null 保持既有路由（默认广播），显式列表只投递到这些启用设备。
    @Size(min=1,max=32) List<@Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String> devices,
    // 仅管理台创建可选：省略/null 使用原始标题与正文；显式模板会渲染成逐设备投递快照。
    UUID templateId
) {}
