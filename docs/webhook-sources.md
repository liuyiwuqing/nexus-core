# 独立来源与签名 Webhook · CONSOLE-06-A

第三方使用 `POST /api/v1/hooks/{sourceId}`，不复用现有管理员 `/messages` 入口。来源令牌只允许提交该来源的消息与读取自身回执，不允许读管理列表、改路由或操作设备。新来源默认停用，管理员配置的设备范围始终与路由匹配结果取交集；不能通过 payload 冒充 Halo 公开评论或绕过范围。

## 来源维护

管理员 `GET /api/v1/sources` 返回 ID/名称/启停/版本/设备范围/接收数/最近接收时间，无令牌或签名密钥。`PUT /sources/{id}` 接收 `{name,deviceIds}`，ID 为 1–24 位小写字母数字与短横线；创建响应仅一次返回 `token`、`secret`。原创建身份与有效定义绑定，重复返回 `credentialsAvailable:false`，定义不同 409。创建响应丢失不通过重试再次暴露凭据，需核对来源、保持停用后轮换。

`PATCH /sources/{id}` 接收 `{name,deviceIds,enabled,expectedVersion}`，同目标/原版本重试可确认，其他版本 409。`POST /sources/{id}/credentials` 传 `{expectedVersion}`，仅停用可轮换，同时更新令牌和签名密钥并增加版本；旧凭据立即失效，不自动启用。响应丢失后不得盲用新版本重发，应读列表核对后再显式轮换。页面要求来源名确认，凭据默认遮挡，退出/关闭整页清除；不写浏览器持久存储。

令牌仅 SHA-256 保存，HMAC 密钥保存在服务端数据库，不进入日志或普通响应。数据库备份需沿用私有访问和备份保护。来源读取令牌和管理员/记忆令牌相互独立；禁用来源同时拒绝写入和来源回执读取。

## 签名与重试

请求 JSON：`{eventId:UUID,eventType,title,content,priority}`，字段与长度沿用消息验证。发送头：

```text
Authorization: Bearer <来源 token>
Content-Type: application/json
X-Nexus-Timestamp: <Unix 秒，10 位>
X-Nexus-Signature: sha256=<64 位小写十六进制 HMAC>
```

签名为 `HMAC-SHA256(timestamp + "." + 原始请求字节, secret的UTF-8字节)`。时间窗 ±300 秒，签名常量时间比较；服务端验证前不信任客户端的 source/设备/公开属性。body 上限 32768 字节，未知字段拒绝。浏览器跨域 Origin 请求拒绝，不通过来源端点建立浏览器 CORS 特权。

成功 `202 {id,status:"queued",duplicate}`。相同来源 UUID/原始字节重试返回相同 ID，不增加消息或投递；相同 UUID 不同字节 409。重试时可更新签名时间，但必须保留原 eventId 与完全相同的 JSON 字节。禁止在重试期间重新序列化产生不同字节。401 未通过令牌/签名/时间窗，403 停用，400 内容错误，413 超长；新事件按每秒一令牌、桶容量 20 且滚动 60 秒最多 60 条限制，429 返回 `Retry-After:60`。已接受的相同事件重放不重复扣额。凭据/限流检查与原始回执绑定在来源行锁事务内，入库和投递一起提交。

`GET /api/v1/hooks/{id}/messages/{messageUUID}` 使用同来源 Bearer，只返回属于该来源的设备状态/收到/展示/已读，不含其他来源消息或管理员正文。找不到或越界 404。`queued` 表示已入库，不保证选中设备在线、上屏或已读；无匹配目标仍可接受消息并保留空投递。

## 测试与统计

管理员 `POST /sources/{id}/test` 使用 `{requestKey:UUID,title,content}`，只向来源范围与当前路由交集建投递，标记 `metadata.test=true`，不伪造第三方有效签名。该接口用于排查服务端路由/设备链路；发送方签名应通过真实 hooks 请求验证。标记测试消息不计入生产概览的消息/投递/失败统计，但保留在消息列表和投递页可核对。

## 验证及边界

`WebhookSourceFlowTest` 与 `ConsoleOverviewFlowTest` 验证真实 HTTP/独立 PostgreSQL：令牌作用域、停用、HMAC/时间窗、幂等/冲突、设备范围、来源只读回执、限流/Retry-After、大小上限、轮换和旧版本拒绝、测试统计排除。实际 Vue `bash tools/ci/test_sources_ui.sh` 的 34 次检查通过，覆盖凭据遮挡/关闭、状态/轮换、名称确认、创建响应丢失、固定身份重试、导航、失败恢复、401 和 390px；截图已查看。页面只用 RAM HTTP，不代表真实签名发送方、生产库或设备。

V17 仅在隔离库验证；生产来源未建立、凭据未轮换、消息未发送。RSS、天气等拉取连接器不属于本切片，不将当前 Halo/Webhook 两种能力称为全部来源完成。项目特有契约沉淀在本文和可执行检查，不更新共享 Skill。
