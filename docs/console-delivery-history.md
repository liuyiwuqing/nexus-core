# 投递状态时间线与取消 · CONSOLE-04

“投递”每行可打开时间线，查看 CORE 持久化的状态、尝试次数与原因；已确认从未开始发送的投递可在详情中确认取消。取消只影响这一设备的这一条投递，不删除消息，不改变其他设备，不回撤已收到、展示或已读的事实。

## HTTP 契约

以下接口仍使用管理员 Bearer；没有新增账户或角色。`id` 是原投递的 UUID。

| 接口 | 行为 |
| --- | --- |
| `GET /api/v1/deliveries` | 原分页/筛选，增加 `canCancel`；`status` 新增 `cancelled`，显示“已取消” |
| `GET /api/v1/deliveries/{id}/history?page=1&size=30` | 按 `sequence` 升序返回 `{items,total,page,size,includesCreation,delivery}`；参数范围沿用列表，page 1–100000、size 1–100。越界页为空，未知 ID 为 404，非法 UUID/分页为 400 |
| `POST /api/v1/deliveries/{id}/cancel` | 无请求体；成功 204。同一已取消 ID 再请求仍 204，不新增历史。当前不满足取消条件为 409 `delivery_not_cancellable`，未知 ID 为 404 `delivery_not_found` |

时间线响应的 `delivery` 是同一读取事务中的 `{eventId,status,attempts,canCancel}` 当前快照；与历史页及总数使用 PostgreSQL REPEATABLE READ。`items` 每项含 `sequence/recordedAt/kind/previousStatus/status/attempts/lastError/nextAttemptAt/receivedAt/displayedAt/readAt`。时间均为 ISO 时间点；页面按本地时区显示到秒。“尝试”仍是当前重试轮次的次数，不能当作累计发送数。

`canCancel` 只是本次读取时的可用性。真正取消必须原子满足：`pending`、attempts=0、未被永久禁止取消、消息未读且未过期、设备仍启用。取消与 worker 的 claim 锁定同一投递行；任一发送预留会永久禁止取消，之后失败重试把次数清零也不能恢复资格。迁移前的 pending/0 可能是旧轮次重试，因此也禁止取消。`cancelled` 不再参与 claim/过期/重试，迟到回执不能恢复它；没有向设备发送撤回命令。

## 历史来源与保留

V13 增加 `delivery_history`、`cancel_blocked`、状态约束和两个触发器。历史在投递写入的**同一事务**记录，覆盖创建、尝试计数/计划、状态/原因、收到/展示/已读时间变化，包括其他位置已读、停用和保留策略的批量更新。事务回滚不会留下记录，无变化的重复 ACK 不重复记录；一次没有改变记录的重复网络结果也不会新增状态事件。

`kind` 包括 `created`、`attempt_reserved`、`retry_requested`、`state_changed`、`detail_changed`；它们描述数据库变化，不证明网络包已到设备，也不提供操作人的身份审计。预留尝试不是发布成功；`sent` 仍是原有服务端发布状态，收到/展示/已读分别依赖对应有效设备回执。该表不是防篡改账本或网络抓包日志。

迁移只为旧行写入 `baseline`，时间是开始留存时刻；`includesCreation=false`，页面明确提示“此前历史未知”。不能拿旧行的当前状态、旧时间字段拼出创建/发送/回执历史。新建投递从真实 INSERT 记录 `created`；`includesCreation=true`。

历史不复制消息标题/正文、凭据或完整原始回执。它随投递外键级联删除，沿用原消息元数据保留策略；本项未启用清理，也未迁移真实数据库。V13 不改 V1 旧迁移或固件线协议。升级后的源码必须与 V13 一起部署；不能把旧 JAR 的状态集合当作支持 `cancelled`。

## 页面与未知结果

打开时间线不发取消。详情确认后才 POST；提交期间禁用重复操作，可见时每 5 秒只读刷新。后台刷新保留页面高度并显示更新中；读取失败隐藏旧事件，空/加载/失败分别呈现。分页或筛选切换收起详情，普通刷新保留当前筛选；取消使行离开当前页时回到有效列表页，仍可在详情核对该 ID。

请求未知时保留原投递 URL，先 GET 历史/当前快照核对：已取消才显示“已核对”；仍为 pending 不能当作取消成功，只允许显式重试**同一投递**。409 后读取新状态并关闭取消入口。不会自动重发取消；收起/导航/退出不重放动作，迟到响应不恢复会话。收起详情后将键盘焦点还给原入口，若该行已离开筛选则回到筛选控件。

## 验证入口与边界

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=ConsoleDeliveryHistoryFlowTest test
python3 tools/run_local.py build
# 同级 04：实际 Vue，回环 RAM HTTP 边界
bash tools/ci/test_delivery_history_ui.sh
# 当前 Java → 实际固件 C 持久化/回执 → 当前 Java；SQL/传输为边界替身
JAVA_HOME="$(/usr/libexec/java_home -v 21)" python3 tools/ci/test_nexus_contract.py
```

6 项 HTTP/真实 worker/隔离 PostgreSQL 专项通过，包含幂等取消、8 次并发抢占、重试清零、事务回滚、重复/倒退回执、其他位置已读/停用、分页/鉴权/校验，以及 V12→V13 的真实旧行基线和不可撤销 guard。浏览器专项 62 项及既有 191 项通过，覆盖确认、重复点击、两类断线、同 ID 重试、409、取消筛选、分页/空/失败/恢复、导航/退出的迟到响应、401、焦点与 390px。最终完整构建退出 0：75 条中 70 通过、5 条条件跳过，0 失败/错误；JAR 的三份静态资源与本轮前端逐字节一致，无旧资源。配套提交和 CI 结果见 [04 台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。不构成真实 Broker/设备、生产迁移、目标服务器或旧离线包的验收。

规范沉淀：状态/迁移/读取口径留在本项目契约，沿用现有测试流程，无需修改通用 Skill。
