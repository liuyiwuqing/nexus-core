# 伙伴空间与外观同步

栖团的设备存档决定收藏、游戏纪录、回忆与外观；CORE 专注档案独立结算。

## 配置与契约

固件启用 `CONFIG_PET_GROWTH_SYNC_ENABLED` 与 `CONFIG_PET_COMPANION_SYNC_ENABLED`（后者默认关闭，full 测试预设启用）；CORE 设置 `NEXUS_COMPANION_ENABLED=true`，配套运行已有 Broker 策略升级流程。标准 ACL 为 CORE 增加读取 `companion_status` / `appearance_acks`、写入 `appearance`，设备只访问自己的主题。Broker 消息限制至少 3072 字节。

主题均为 `nexus/v1/devices/{deviceId}/...`，QoS 1、非 retained。设备每15秒回传伙伴状态。schema 位于配套固件仓 `config/schemas/{companion-status,appearance-command,appearance-result}.schema.json`，目录版本1。设备未知目录/结构、过期、retained、跨设备、未拥有内容均拒绝，不改奖励数据。

HTTP GET `/api/v1/devices/{deviceId}/companion` 返回 enabled/online/fresh、设备快照和最近10次换装记录。管理员 POST 同路径 `/appearance`，参数为 UUID `requestId`、`expectedRevision` 和 `appearance {theme,outfit,slots:[a,b,c]}`。同一身份同参数重试幂等，身份复用不同参数冲突。

请求30秒有效，需设备启用、在线且90秒内状态、存档可写和版本匹配；断连后不继续排队。202只表示接收，pending/sent等待设备持久保存；applied才完成，rejected/expired明确失败，unconfirmed等待核对。丢失ACK可通过后续快照的持久命令身份与版本确认，不能仅凭外观相同确认。设备写失败不显示成功，不远程发币或解锁。有伙伴历史的设备只能停用。

## 本地验证与边界（2026-11-24）

配套固件 `PET_HOST_SANITIZE=1 python3 tools/ci/test_companion_contract.py` 四项真实 C 服务/双槽逻辑、MQTT、PostgreSQL、HTTP 集成全部通过，含ACK丢失核对与重复命令不重复写。CORE `MAVEN_ARGS=-Dtest=CompanionFlowTest python3 tools/run_local.py test` 通过，独立执行时跨语言项按环境跳过；测试容器无真实库。`python3 tools/test_broker_policy.py` 4/4通过。固件完整主机回归及 full ESP-IDF 目标构建通过；构建中发现并修正私有网络函数误用。没有烧录、真实部署、真实消息或实物NVS验收。
