# 设备遥测历史与采样口径

2026-11-16 · TELEMETRY-01。保留 `GET /devices/{id}/management` 的实时快照语义不变，新增只读历史查询和独立样本表。本项不改变设备 MQTT 主题、遥测 Schema、固件行为或 ACL；历史来自设备已经上报的 `telemetry` 消息。

## 与快照的关系

| 数据 | 位置 | 语义 |
| --- | --- | --- |
| 最新遥测 | `device_telemetry`，随 `GET .../management` 返回 | 每设备一行，只保留最后一条可解析遥测；`fresh` 仍按 90 秒判断 |
| 历史样本 | `device_telemetry_samples`，随 `GET .../telemetry` 返回 | 追加式，按采样口径截断；用于趋势图和事后核对 |

快照与历史分开写入：快照始终更新为最新有效遥测；历史可能因为采样节流比快照少几个点。历史缺失只表示“没有记录”，不表示设备离线或数值为零。

## 采样口径

每条通过 `ManagementCodec.telemetry` 校验、且设备已登记的消息都会进入采样判断：

1. **只追加更新的观察**：`observedAt` 必须严格晚于该设备最新历史样本；retained 重放、乱序和重复时间戳不写入。
2. **状态变化优先**：`firmwareVersion`、`configRevision`、`volume`、`brightness`、`muted`、`dnd`、`unread`、`audioFaulted`、`wifiIp` 任一变化即视为变化样本。地址变化通常意味着重连或换网，值得独立成条。
3. **变化下限**：变化样本距上一条历史样本至少 `change-seconds`（默认 5 秒）才写入，避免设备抖动导致刷表。
4. **心跳上限**：无上述变化时，至少间隔 `sample-seconds`（默认 30 秒）写入一条。`freeHeap`、`minFreeHeap`、`audioUnderruns`、`inferenceMaxMs`、`displayMaxMs`、`wifiRssi` 的波动本身不触发立即写入，它们随心跳进入历史。
5. **时钟与乱序**：窗口、排序和去重使用设备 `observedAt`；服务端 `received_at` 仅用于审计。设备时钟回退到上一条样本之前时，历史停止追加，直到观察时间重新超过最后一条。未来超过 5 秒的时间戳仍由现有编解码器拒绝。
6. **失败边界**：SQL 写入失败会回滚该条遥测的处理事务，不会出现“历史成功、快照失败”或反向的半写；MQTT 消息是否重投由 Broker/QoS 决定。

设备约 30 秒发布一次 retained 遥测，因此默认参数下稳定期基本每条都保留，最多约 2880 条/设备/天。

## 保留与清理

- `retention-days` 默认 30，允许 1–365；只删除 `device_telemetry_samples`，不动快照、配置请求和审计。
- 每天 `cleanup-cron`（默认 04:20 UTC）分批删除，每批 10000 行、单次最多 20 批；不影响在线请求。
- 查询窗口上限同样是 30 天。早于保留期的数据不会被补造或零填充。

## 查询接口

`GET /api/v1/devices/{deviceId}/telemetry`

沿用管理员 Bearer 鉴权；`viewer`/`operator` 的只读 GET 范围与现有 `/api/v1` 规则一致。只读，不触发设备动作。

| 参数 | 约束 | 默认 |
| --- | --- | --- |
| `from` | ISO-8601 带偏移时间（如 `2026-11-16T00:00:00Z`），必须早于 `to` | `to - 6 小时` |
| `to` | 同上；窗口最大 30 天 | 当前时间 |
| `bucket` | `raw`、`1m`、`5m`、`15m`、`1h`、`1d` | `5m` |

- `raw`：窗口最大 12 小时；最多返回最新 2000 个点，超出时 `truncated=true` 并丢弃最旧的点。
- 聚合：按窗口估算的桶数上限 2000；超过返回 400 `bucket_too_fine`，应改用更粗的桶。
- 桶起点以 UTC `2000-01-01T00:00:00Z` 对齐；`observedAt` 为桶起点。
- 空桶不返回，不用 0 填充；断线、设备休眠或采样节流都会表现为点与点之间的时间间隔。
- 设备不存在返回 404 `device_not_found`；非法时间返回 400 `invalid_telemetry_window`；未知桶返回 400 `invalid_telemetry_bucket`；`raw` 超过 12 小时返回 400 `bucket_required`。

响应：

```json
{
  "deviceId": "dodo-1",
  "from": "2026-11-16T00:00:00Z",
  "to": "2026-11-16T06:00:00Z",
  "bucket": "5m",
  "sampleSeconds": 30,
  "retentionDays": 30,
  "truncated": false,
  "points": [
    {
      "observedAt": "2026-11-16T00:05:00Z",
      "receivedAt": "2026-11-16T00:05:01.120Z",
      "count": 10,
      "firmwareVersion": "0.4.0",
      "configRevision": 3,
      "volume": 25,
      "brightness": 80,
      "muted": false,
      "dnd": false,
      "unread": 1,
      "freeHeap": 101234,
      "freeHeapMin": 98200,
      "freeHeapMax": 105000,
      "minFreeHeap": 90000,
      "audioUnderruns": 2,
      "inferenceMaxMs": 430,
      "displayMaxMs": 21,
      "audioFaulted": false,
      "wifiIp": "192.168.1.23",
      "wifiRssi": -58
    }
  ]
}
```

`observedAt`、`receivedAt` 为 ISO-8601 UTC；其余字段：

| 字段 | `raw` | 聚合桶 |
| --- | --- | --- |
| `count` | 固定 1 | 桶内样本数 |
| `firmwareVersion`、`configRevision`、`volume`、`brightness`、`muted`、`dnd` | 样本值 | 桶内最后一条 |
| `unread` | 样本值 | 四舍五入平均值 |
| `freeHeap` | 样本值 | 四舍五入平均值 |
| `freeHeapMin`、`freeHeapMax` | 等于 `freeHeap` | 桶内最小/最大 |
| `minFreeHeap` | 样本值 | 桶内最小（更保守） |
| `audioUnderruns`、`inferenceMaxMs`、`displayMaxMs` | 样本值或 `null` | 桶内最大，全部缺失时为 `null` |
| `audioFaulted` | 样本值或 `null` | 桶内曾为 `true` 即 `true`，全部缺失时为 `null` |
| `wifiIp` | 样本值或 `null` | 桶内最后一个非 `null` 地址 |
| `wifiRssi` | 样本值或 `null` | 四舍五入平均值，全部缺失时为 `null` |

前端若展示“实时”标签，仍应以 `GET .../management` 的 `fresh` 和 `observedAt` 为准；历史接口的最后一个桶不代表设备当前在线。

## 配置

```dotenv
NEXUS_TELEMETRY_SAMPLE_SECONDS=30
NEXUS_TELEMETRY_CHANGE_SECONDS=5
NEXUS_TELEMETRY_RETENTION_DAYS=30
NEXUS_TELEMETRY_CLEANUP_CRON=0 20 4 * * *
```

启动时校验：采样 5–3600 秒、变化下限 1–采样间隔、保留 1–365 天；非法配置拒绝启动。

## 迁移与验证

Flyway `V21__device_telemetry_history.sql` 新增样本表和过期索引，`V22__device_telemetry_network.sql` 为样本表追加 `wifi_ip`/`wifi_rssi`，均不改既有表。已有实例按现有流程在独立库验证后迁移；本项未迁移真实数据库，未部署目标机。

`DeviceManagementFlowTest` 新增三项：

- 真实 HTTP/PostgreSQL/MQTT：首批样本、5 秒变化下限、30 秒心跳、原始与 1 分钟聚合查询、空窗口、401/404 与四类 400。
- 网络：IPv4/RSSI 写入原始样本、地址变化立即成条、1 分钟聚合取桶内最新地址与平均信号、非法地址或越界信号整条拒绝。
- 清理：直接写入 40 天前的样本，执行清理后该设备历史为空。

设备管理「设置与状态」已提供「状态历史」区块：1 小时/6 小时/24 小时/7 天分别查询 `1m/5m/15m/1h` 桶，用无依赖内联 SVG 画可用内存与启动以来最小内存，缺失桶断线而不是补零；实时徽标只读 `management` 的 `fresh`，历史区块本身不标「实时」。指标组可在内存/音量/未读/音频/网络之间切换，全部复用同一条历史响应、不额外请求；音频视图用 `audioUnderruns` 画线，并以 `--danger` 标记 `audioFaulted=true` 的桶；网络视图用 `wifiRssi` 画 dBm，地址为空时该点不画。实时快照同时展示设备上报的 Wi-Fi 地址与信号。图内拖拽可放大时间窗口（只放大已取回桶、不重查，双击或「重置缩放」恢复），「导出 CSV」按当前范围导出全部 20 列桶数据（UTF-8 BOM + CRLF）。跨仓浏览器回归覆盖空态、默认 `5m` 请求、范围切换、悬停取值、五种指标、音频故障标记、快照网络信息、CSV 下载与内容、拖拽缩放/提示/重置及 390px 无横向溢出。

真实 ESP32 的长期采样密度、时钟回退、掉电与 30 天保留规模仍未实测。
