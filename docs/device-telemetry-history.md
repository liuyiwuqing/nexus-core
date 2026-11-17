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
2. **状态变化优先**：`firmwareVersion`、`configRevision`、`volume`、`brightness`、`muted`、`dnd`、`unread`、`audioFaulted`、`wifiIp`、`wifiSsid`、`wifiChannel`、`wifiBssid`、`wifiChannelWidth`、`wifiDisconnectReason` 任一变化即视为变化样本。地址、SSID 或 BSSID 变化通常意味着重连或换 AP，信道宽度变化意味着一次重新协商，断线原因变化意味着刚发生过掉线，都值得独立成条。
3. **变化下限**：变化样本距上一条历史样本至少 `change-seconds`（默认 5 秒）才写入，避免设备抖动导致刷表。
4. **心跳上限**：无上述变化时，至少间隔 `sample-seconds`（默认 30 秒）写入一条。`freeHeap`、`minFreeHeap`、`audioUnderruns`、`inferenceMaxMs`、`displayMaxMs`、`wifiRssi`、`wifiDisconnectedAt` 的波动本身不触发立即写入，它们随心跳进入历史。
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
      "wifiRssi": -58,
      "wifiSsid": "HomeLab-2.4G",
      "wifiChannel": 6,
      "wifiDisconnectReason": 201,
      "wifiDisconnectedAt": "2026-11-16T00:04:30Z"
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
| `wifiSsid` | 样本值或 `null` | 桶内最后一个非 `null` SSID |
| `wifiChannel` | 样本值或 `null` | 桶内最后一个非 `null` 信道（求平均没有物理意义） |
| `wifiBssid` | 样本值或 `null` | 桶内最后一个非 `null` BSSID（大写 MAC，仅用于识别接入点） |
| `wifiChannelWidth` | 样本值或 `null` | 桶内最后一个非 `null` 信道宽度（20/40 MHz） |
| `wifiDisconnectReason` | 样本值或 `null` | 桶内最后一个非 `null` 断线原因（ESP-IDF `wifi_err_reason_t`） |
| `wifiDisconnectedAt` | 样本值或 `null` | 桶内最后一个非 `null` 断线时刻 |

`wifiSsid`、`wifiChannel`、`wifiBssid`、`wifiChannelWidth`、`wifiDisconnectReason`、`wifiDisconnectedAt` 都由固件按需上报：SSID 的空口字节不是可打印 UTF-8 时整条省略；BSSID 固定为 `AA:BB:CC:DD:EE:FF` 形式，服务端只接受大写十六进制，不含口令；信道宽度只接受 20 或 40 MHz；未关联或取不到 AP 信息时这些字段全部省略，非法值整条拒绝。断线原因只统计本次启动以来的运行期断线（正常关机或主动 `stop` 不计），从未断线时这两个字段都不出现；设备尚未对时则只报原因、不报时刻。

前端若展示“实时”标签，仍应以 `GET .../management` 的 `fresh` 和 `observedAt` 为准；历史接口的最后一个桶不代表设备当前在线。

## 断线事件列表（TELEMETRY-08）

`GET /api/v1/devices/{deviceId}/telemetry/disconnects`

只读、管理员 Bearer 鉴权，接口不写入样本、不触发设备动作。事件来自 `device_telemetry_samples` 中已记录的断线原因，不新建表、不迁移，也不回放或补造历史。

| 参数 | 约束 | 默认 |
| --- | --- | --- |
| `limit` | 1–100 的整数 | 20 |

判定口径：按 `observed_at` 逐条比较相邻样本，只有 `(wifi_disconnect_reason, wifi_disconnected_at)` 与上一条不同、且原因非 `null` 的样本才算一次事件。

- 原因变化立即成为事件（采样规则本就把原因变化视为变化样本）。
- 原因相同但断线时刻更新时，`wifiDisconnectedAt` 本身不触发立即写入，事件会在下一次心跳样本（默认 30 秒内）出现。
- 同一（原因、时刻）在后续心跳中重复上报只算一次；原因回到 `null` 不算事件，只表示设备已重新上报可用网络。
- 设备未对时只报原因、不报时刻时，`disconnectedAt` 为 `null`，仍按上报顺序列出。
- 事件量为零（从未断线或样本已过保留期）时返回空数组，不补造“在线”或“从未断线”记录。

响应：

```json
{
  "deviceId": "dodo-1",
  "limit": 20,
  "retentionDays": 30,
  "items": [
    { "observedAt": "2026-11-17T02:15:30Z", "reason": 202, "disconnectedAt": "2026-11-17T02:10:00Z" },
    { "observedAt": "2026-11-17T01:05:00Z", "reason": 201, "disconnectedAt": null }
  ]
}
```

`observedAt` 为记录该事件的样本时间，`disconnectedAt` 为设备上报的断线时刻（可能缺失）。原因码为 ESP-IDF `wifi_err_reason_t`，服务端只透传数字，名称由管理台映射；未知码直接显示数字。设备不存在返回 404 `device_not_found`，`limit` 非整数或越界返回 400 `invalid_disconnect_limit`。

## 配置

```dotenv
NEXUS_TELEMETRY_SAMPLE_SECONDS=30
NEXUS_TELEMETRY_CHANGE_SECONDS=5
NEXUS_TELEMETRY_RETENTION_DAYS=30
NEXUS_TELEMETRY_CLEANUP_CRON=0 20 4 * * *
```

启动时校验：采样 5–3600 秒、变化下限 1–采样间隔、保留 1–365 天；非法配置拒绝启动。

## 迁移与验证

Flyway `V21__device_telemetry_history.sql` 新增样本表和过期索引，`V22__device_telemetry_network.sql` 为样本表追加 `wifi_ip`/`wifi_rssi`，`V23__device_telemetry_wifi_quality.sql` 再追加 `wifi_ssid`/`wifi_channel`/`wifi_disconnect_reason`/`wifi_disconnected_at`，`V24__device_telemetry_wifi_access_point.sql` 追加 `wifi_bssid`/`wifi_channel_width`，均不改既有表。已有实例按现有流程在独立库验证后迁移。2026-11-17 本机真实库已按此流程执行到 V23：`pg_dump -Fc` 备份到忽略目录 `data/backups/`，在临时库恢复核对后由新 JAR 的 Flyway 应用 V21–V23（`flyway_schema_history` 全部 success，schema 到 v23）。V24 只在隔离 PostgreSQL 测试库应用，长期开发库与目标服务器均未迁移；目标服务器与实机上报仍未验收。

`DeviceManagementFlowTest` 新增四项：

- 真实 HTTP/PostgreSQL/MQTT：首批样本、5 秒变化下限、30 秒心跳、原始与 1 分钟聚合查询、空窗口、401/404 与四类 400。
- 网络：IPv4/RSSI/SSID/信道/BSSID/信道宽度/断线原因写入原始样本与快照、地址或接入点变化立即成条、1 分钟聚合取桶内最新网络信息与平均信号、非法地址或越界信号/信道、小写 BSSID 与非 20/40 宽度整条拒绝。
- 断线历史：401/404/400、原因变化立即成条、时刻变化随心跳成条、同一（原因、时刻）跨心跳去重、无原因样本不计入、`limit` 截断。
- 清理：直接写入 40 天前的样本，执行清理后该设备历史为空。

设备管理「设置与状态」已提供「状态历史」区块：1 小时/6 小时/24 小时/7 天分别查询 `1m/5m/15m/1h` 桶，用无依赖内联 SVG 画可用内存与启动以来最小内存，缺失桶断线而不是补零；实时徽标只读 `management` 的 `fresh`，历史区块本身不标「实时」。指标组可在内存/音量/未读/音频/网络之间切换，全部复用同一条历史响应、不额外请求；音频视图用 `audioUnderruns` 画线，并以 `--danger` 标记 `audioFaulted=true` 的桶；网络视图用 `wifiRssi` 画 dBm，地址为空时该点不画。实时快照同时展示设备上报的网络名、地址、信道、信道宽度、信号与 BSSID；设备上报过断线时再单列「上次断线」（原因名称 + 相对时间，未知码直接显示数字）。设备卡片展示最近一条带网络信息的样本（SSID、地址、dBm、相对时间），列表接口一次 `LEFT JOIN LATERAL` 取回，不逐台补查。图内拖拽可放大时间窗口（只放大已取回桶、不重查，双击或「重置缩放」恢复），「导出 CSV」按当前范围导出全部 26 列桶数据（UTF-8 BOM + CRLF，含 SSID、信道、信道宽度、BSSID、断线原因与断线时间）。跨仓浏览器回归覆盖空态、默认 `5m` 请求、范围切换、悬停取值、五种指标、音频故障标记、快照网络信息、CSV 下载与内容、拖拽缩放/提示/重置及 390px 无横向溢出。

真实 ESP32 的长期采样密度、时钟回退、掉电与 30 天保留规模仍未实测。
