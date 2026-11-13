# 设备成长只读镜像

2026-11-13。`NEXUS_GROWTH_SYNC_ENABLED=false` 默认关闭；启用后读取 `nexus/v1/devices/+/growth_status`，将已注册且启用设备的快照写入 V12。设备端开关、Schema 和完整配套说明见 [04 文档 21](https://github.com/liuyiwuqing/ai-desktop-pet/blob/main/docs/21-growth-sync.md)。

接口沿用管理令牌鉴权：

- `GET /api/v1/devices/{id}/growth` → `enabled/deviceId/fresh/snapshot`；没有数据时 snapshot 为 null，不存在设备返回 404。
- `GET /api/v1/pet/devices` → `enabled/items`，最多 100 个设备，附名称、启用状态和快照。

设备以 QoS 1、非 retained 上传最大 1536 B 的 JSON，包含随机 bootId、启动内 reportSequence、observedAt、growthSchema=4 与成长视图。严格字段/范围校验；相同启动拒绝序号不增或时间倒退，已退役启动拒绝重新成为当前启动，记录与启动集合事务持久化。重复报告不延长 90 秒新鲜度，旧数据仍可查；不修改在线心跳。

成长页展示等级、本级经验、金币、亲密和状态，保留过期、未对时、存档不可写、回退与未落盘提示。数据只读；CORE 专注档案继续执行原有独立规则，不合并两端总量、不按设备快照发奖或回写 NVS。服务端奖励下发为二期，需要单独的档案身份、去重和兼容方案。

Broker 新规则仅授予 CORE 读、设备写自己的 growth_status；`tools/upgrade_broker_policy.py` 默认预览，只识别标准旧规则，应用时新建 `.growth-sync-v1-backup` 并保留既有备份。自定义 ACL 需人工合并，本轮未改真实配置。

验证：完整 `python3 tools/run_local.py build` 64 条中 59 通过、5 条条件跳过；公开 HTTP/真实 PostgreSQL/MQTT 的成长用例 3 项通过，可选 C producer 用例由配套脚本单独通过。ACL CLI 4 项、管理台类型/构建和成长浏览器 18 项通过。浏览器使用隔离 HTTP 夹具；实际 C→MQTT→CORE 回环另有独立证明。真实设备/NVS/Wi-Fi/TLS、生产迁移和常驻部署尚未执行。
