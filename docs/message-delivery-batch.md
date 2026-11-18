# 单条消息的投递批量撤销与重试 · MSG-BATCH-01

状态：CORE 侧软件已实现（2026-11-18），隔离 HTTP/PostgreSQL 与真实 Vue 页面验证通过。

一条消息会为每台启用设备各建一条投递。此前撤销和重试只能进单条投递的时间线操作，设备一多就要重复点 N 次，而且每次都要看一条时间线。本项提供按消息的批量入口，**不新增批量范围**：能批量的条件与单条接口完全一致。

## 1. 边界

| 已实现 | 未保留或未做 |
| --- | --- |
| 按消息批量撤销「从未开始发送」的投递 | 不撤销已预留、已发送、已回执或已结束的投递 |
| 按消息批量重试「已失败」的投递 | 不给超时/过期/已读消息重开投递 |
| 只读预演（`dryRun=true`）报告每条被跳过的原因 | 预演不核对 Broker 是否在线，也不预测设备会不会拒绝 |
| 写入需确认令牌 | 不做后台批量任务；一次请求只处理当前消息 |
| 每条改动照常写入 `delivery_history` | 不新建迁移，不改投递表结构，不发撤回命令 |

批量操作**不是**「发一条新消息撤回旧消息」：设备已经保存或展示的内容不会消失，相关限制见 [投递工作流](delivery-workflow.md) 与 [投递历史](console-delivery-history.md)。

## 2. 接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/v1/messages/{messageId}/deliveries/cancel?dryRun=true` | 只读核对可撤销条数与被跳过原因 |
| `POST /api/v1/messages/{messageId}/deliveries/cancel?confirm=CANCEL_MESSAGE_DELIVERIES` | 执行撤销 |
| `POST /api/v1/messages/{messageId}/deliveries/retry?dryRun=true` | 只读核对可重试条数与被跳过原因 |
| `POST /api/v1/messages/{messageId}/deliveries/retry?confirm=RETRY_MESSAGE_DELIVERIES` | 执行重试 |

两个动作共用一种响应：

```json
{
  "messageId": "…", "action": "cancel", "dryRun": false, "applied": 2,
  "counts": { "deliveries": 4, "candidates": 2, "skipped": 2, "remaining": 0 },
  "candidates": [ { "eventId": "…", "deviceId": "…", "status": "pending", "attempts": 0 } ],
  "skipped":    [ { "eventId": "…", "deviceId": "…", "status": "pending", "attempts": 1, "reason": "attempted" } ],
  "asOf": "2026-11-18T01:00:00Z"
}
```

`counts.candidates` 是**本次请求开始时**能处理的条数，`applied` 是实际改动的条数，`counts.remaining` 是请求结束后仍然可处理的条数。三者不一致只可能因为并发：期间有投递被 worker 预留或被设备回执推进。

`dryRun` 缺省为 `false`；不带确认令牌的非预演请求返回 `400 delivery_batch_confirmation_required`。若消息的投递超过 500 条，返回 `409 batch_too_large` 而不是静默截断——那通常意味着异常扇出，应由管理员到投递页核对。

## 3. 判定条件与跳过原因

撤销沿用单条接口的 `CAN_CANCEL`：`status='pending' AND attempts=0 AND NOT cancel_blocked AND expires_at>now() AND read_at IS NULL AND device.enabled`。
重试沿用 `CAN_RETRY`：`status='failed' AND expires_at>now() AND read_at IS NULL AND device.enabled`。

跳过原因是上面每个合取项的**否定**，按同一顺序判定，因此「`reason` 为空」与「可处理」在构造上等价，不存在第二份会漂移的规则：

| 撤销 | 含义 |
| --- | --- |
| `not_pending` | 状态已不是等待投递 |
| `attempted` | 已经预留过至少一次发送 |
| `cancel_blocked` | 历史记录永久禁止撤销（曾预留或曾离开 pending） |
| `message_expired` | 消息已过期 |
| `message_read` | 消息已读 |
| `device_disabled` | 设备已停用 |

| 重试 | 含义 |
| --- | --- |
| `not_failed` | 状态不是失败 |
| `message_expired` | 消息已过期 |
| `message_read` | 消息已读 |
| `device_disabled` | 设备已停用 |

写入是**一条** `UPDATE … RETURNING event_id`，不是逐条循环：与 `claim()` 争用的行会自然不再匹配，`RETURNING` 给出真正改动的集合，而不是回显候选列表。事务内先分类、再更新、再分类，`skipped` 用更新后的原因，因此竞态里的行会显示「已经尝试过发送」而不是原来的「可撤销」。

## 4. 权限与审计

- 撤销/重试与单条接口同级：管理员与 operator 可用，viewer 只读。`RoleTokens.permits` 的 operator 白名单新增 `POST /api/v1/messages/{uuid}/deliveries/{cancel|retry}`。
- 每条改动仍由 `delivery_history` 触发器记录 `state_changed`（撤销）或 `retry_requested`（重试），时间线因此与单条操作完全一致。
- 批量本身没有单独的「批量审计」记录：`delivery_history` 是逐条事实，HTTP 层审计仍只记请求结果，两者不互相替代。

## 5. 管理台

消息详情底部新增「投递批量操作…」折叠区（`MessageDeliveryBatch.vue`，不新增导航页，十二页与 h1 指纹不变）。展开后并发读取两个计划，分别显示「可撤销 N 条」「可重试 N 条」和按原因归并的跳过摘要，例如「跳过 2 条：已经尝试过发送 1、已经开始投递 1」。只有存在候选时才出现确认按钮；写入后自动重新核对，并给出「已撤销 2 条未开始的投递；2 条未撤销，原因见下方核对」。

读取失败时清空旧计划并显示错误，不把上一次的条数和错误并排显示成「还能撤销 N 条」。

## 6. 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=MessageDeliveryBatchFlowTest test
# 同级 04 项目，使用实际编译 Vue 与回环 RAM HTTP 夹具
bash tools/ci/test_message_batch_ui.sh
```

- `MessageDeliveryBatchFlowTest`（真实 HTTP + 隔离 PostgreSQL/MQTT，4 项）：预演不写库且判定与单条 `canCancel` 一致；撤销只改从未开始的投递并逐条写 `state_changed`；重试恢复失败投递但保留 `cancel_blocked`；确认令牌、角色、消息状态（已读/过期/设备停用）与 500 条上限。
- 完整 `mvn verify`：145 项，0 失败/0 错误，5 项条件跳过。
- 04 `test_message_batch_ui.sh`：16 项浏览器检查，覆盖展开即两次预演且不带确认令牌、跳过原因归并文案、确认令牌、写入后重新核对、无投递、读取失败与恢复、390 px 与折叠。夹具是回环 RAM，不是 CORE、Broker 或设备联调。
- 回归：04 `test_list_filters_ui.sh` 44 项、`test_message_compose_ui.sh` 33 项仍全部通过。

未验收：真实 Broker 上的批量撤销/设备侧表现、真实设备回执竞态、大数据量（接近 500 条）下的耗时，以及生产库。
