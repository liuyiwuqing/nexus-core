# 设备 MQTT 凭据台账与轮换 · DEVICE-CRED-01

状态：CORE 侧软件已实现（2026-11-18），隔离 PostgreSQL/HTTP 与真实 Vue 页面验证通过。**CORE 不写 Broker 配置**：部署侧 `mosquitto/config` 是只读绑定，CORE 容器也没有该路径，因此本项交付的是「期望状态 + 一次性密钥 + ACL 片段 + 应用步骤」，Broker 侧写入由管理员在主机执行。

## 1. 边界

| 已实现 | 未实现或保留 |
| --- | --- |
| 每设备凭据台账：用户名、修订号、SHA-256 指纹、签发/轮换/撤销时间 | 不生成 Mosquitto `passwd` 行，也不读写 `passwd`/`acl` 文件 |
| 签发/轮换一次性密钥，只在本次响应出现 | 不校验 Broker 是否已应用，也不校验设备是否用新密钥连上 |
| 轮换宽限期（旧当前指纹保留至指定时间）与撤销 | 宽限期结束不跑后台任务清理指纹，只在读取时判定状态 |
| 由 `device_id` 推导 ACL 片段 | 不创建 Broker 账号、不重载 Broker、不签发设备端 NVS |
| 接入引导按设备暴露台账状态 | 顶层 `credentialsProvisioned` 仍为 `unknown`，台账不等于 Broker 事实 |

CORE 自身连接 Broker 使用部署配置里的独立账号，不在本台账内。

## 2. 数据

`device_credentials`（V25）：`device_id` 主键，`username`（`dev-<deviceId>`）、`revision`、`secret_fingerprint`、`previous_fingerprint`/`previous_expires_at`、`issued_at`、`rotated_at`、`revoked_at`、`updated_at`、`updated_by`。指纹为明文密钥的 SHA-256 十六进制，库里没有任何明文。

`device_credential_events`：`issued`/`reissued`/`rotated`/`revoked`，只记设备、修订号、操作者与宽限期小时数，不含密钥或指纹。

状态只有四种：

| 状态 | 条件 | 含义 |
| --- | --- | --- |
| `none` | 无台账行 | 尚未签发；不代表 Broker 上没有该账号 |
| `active` | 未撤销且无有效宽限期 | 只有一个当前密钥 |
| `rotating` | `previous_expires_at > now()` | 新旧两个密钥都应被视为有效 |
| `revoked` | `revoked_at IS NOT NULL` | 应删除 Broker 账号 |

宽限期过期后状态自动回到 `active`，旧指纹仍留在行里直到下一次轮换覆盖，不作为有效凭据。

## 3. 接口

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/devices/{deviceId}/credentials` | admin/operator/viewer | 台账状态、用户名、修订号、`acl`；永不含密钥 |
| `POST /api/v1/devices/{deviceId}/credentials` | admin | 签发；`{"confirm":"ISSUE_DEVICE_CREDENTIAL"}`；已有有效凭据 `409 device_credential_exists` |
| `POST /api/v1/devices/{deviceId}/credentials/rotate` | admin | 轮换；`{"expectedRevision":n,"graceHours":1..168,"confirm":"ROTATE_DEVICE_CREDENTIAL"}`，默认 24 小时 |
| `POST /api/v1/devices/{deviceId}/credentials/revoke` | admin | 撤销；`{"expectedRevision":n,"confirm":"REVOKE_DEVICE_CREDENTIAL"}`，成功 204 |

错误码：`device_not_found`、`device_credential_not_found`、`device_credential_exists`、`device_credential_version_conflict`、`device_credential_revoked`、`forbidden`、`invalid_request`。

签发/轮换的响应额外带 `secret`（32 随机字节的 URL-safe Base64，43 字符）。同一响应不可重放获取；丢失只能再轮换一次。

## 4. 语义

- 签发：无行时插入 revision 1；`revoked` 行重新签发时沿用同一用户名、revision+1，并清空撤销与宽限期。
- 轮换：要求 `expectedRevision` 与当前一致；旧当前指纹成为 `previous_*`，旧的 `previous_*` 被覆盖（不会无限累积可用历史密钥）；revision+1。
- 撤销：revision+1，清空 `previous_*`；同一 `expectedRevision` 的重放按幂等 204 处理，不改修订号。
- 所有写操作要求管理员角色；`RoleTokens.permits` 的 operator 白名单不包含这些路径，控制器再显式校验一次。
- 未使用全局密钥或可推导密钥：密钥只由 `SecureRandom` 生成，指纹入库存证。

## 5. 应用步骤（管理员，Mosquitto 主机）

1. 在「设备 → 设置与状态 → MQTT 凭据」签发或轮换，复制本次密钥。
2. `mosquitto_passwd -b /etc/nexus/docker/mosquitto/config/passwd dev-<deviceId> <密钥>`
3. 在同一目录 `acl` 追加 `topic readwrite nexus/v1/devices/<deviceId>/#`
4. 重启 mqtt 服务（`docker compose restart mqtt` 或容器 SIGHUP）使配置生效。
5. 把同一用户名和密钥写入设备（Web 配网或串口配置），确认设备重连并上屏。
6. 轮换时等设备切换完成再移除旧密码：宽限期只表达「何时该删旧密码」，CORE 不会代删。

不同部署路径以实际 `NEXUS_*`/Compose 绑定为准；1Panel 默认值见 04 仓 `deployment/1panel/README.md` 的「设备 MQTT 凭据」一节。

## 6. 验证

- `mvn -Dtest=DeviceCredentialFlowTest test`：真实 HTTP + 隔离 PostgreSQL/MQTT 3 项，覆盖一次性密钥、指纹落库、重复签发、版本冲突、宽限期、幂等撤销、撤销后轮换拒绝、重新签发、角色限定与接入引导状态。
- 前端 `npm run check`、`npm run build`；04 `tools/ci/test_management_ui.sh` 在真实 Vue 产物上跑 73 项检查，其中 7 项为凭据区块（未签发 → 签发一次性密钥 → 轮换宽限期 → 撤销、390 px 无横向溢出）。
- 未验证：真实 Broker 应用、真实设备重连、生产库迁移、密钥丢失后的运维流程。
