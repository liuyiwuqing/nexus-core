# NEXUS CORE

Java 21 / Spring Boot 的模块化单进程服务，PostgreSQL 保存业务状态，Mosquitto TLS 连接设备，Vue 管理台随 JAR 一起发布。平台令牌和 AI 密钥只留在服务端。

需求范围、已验证基线和逐项交付约定见 [交付文档](docs/delivery-workflow.md)。每项同步实现与文档、验证、提交并推送后继续；生产部署与真实设备验收独立记录。

## 当前能力

| 模块 | 已实现的行为 | 验证边界 |
| --- | --- | --- |
| Inbox | 统一消息、唯一事件去重、历史、已读、优先级、事务 outbox | 真实 HTTP / PostgreSQL / Mosquitto 集成测试 |
| Halo | 官方 Console API 只读轮询；仅已审核、未隐藏且所属文章/页面公开发布的评论 | 现有博客真实 GET；历史评论需显式导入 |
| 设备 | 注册、重命名、启停新投递、心跳、重试、回执分离；遥测/完整设置回读、配置 CAS/审计 | 真实 HTTP/PostgreSQL/MQTT 与 Java/C 配置回环；真实 ESP32 待烧录 |
| AI | 公开状态二次核验、摘要/分类/重要性/回复草稿、持久队列、失败重试 | 本机已有兼容模型真实调用通过；草稿不会发送 |
| 记忆 | 显式保存、固定目录检索、源复核/失效删除、管理页及独立 MCP/stdio 工具 | HTTP/PostgreSQL 与浏览器夹具验证；默认关闭，真实身份/权限/删除及语音接入待验收 |
| 工具 | 固定工具目录、参数校验、确认绑定、持久审计、未知结果核对 | 真实 Python Bridge 进程测试；本机只启用查询能力 |
| 自动化 | 新公开评论→分析队列；每日定时→设备消息；启停、失败重试、时区、每日去重 | 真实数据库集成测试；规则默认停用 |
| 成长 | 服务端专注计时、经验/金币/亲密度、等级、历史、并发单次结算 | HTTP/数据库并发测试；独立 CORE 档案，未同步旧设备存档 |

此版本没有接管小智 ASR/聊天/TTS，也没有接入邮件、私信、录音、iPhone、Home Assistant 或社交平台私有 API。

## 本机安装

需要 Python 3.11+、Java 21+、Maven、Node/npm、Docker Compose。首次安装在本目录执行：

```sh
python3 tools/init_local.py
docker compose --env-file data/local.env up -d
python3 tools/run_local.py build
python3 tools/run_local.py run
```

`init_local.py` 生成随机管理员/数据库/Broker/设备/Bridge 凭据、私有 CA、Broker 证书及 ACL；目录 `data/` 为 700、私有文件为 600。已有配置时保留原件，不旋转凭据。`run_local.py` 自动查找本机 Java 21，也支持 `NEXUS_JAVA_HOME`。项目 Maven 设置仅使用公共 Central，不修改全局 JDK 或 Maven 设置。

打开 [管理台](http://127.0.0.1:8088)，从本机编辑器读取 `data/local.env` 的 `NEXUS_ADMIN_TOKEN` 登录。登录令牌仅保存在当前页面内存，退出/刷新页面后需重新输入。HTTP 管理端默认仅绑定回环地址。

`build` 先检查 TypeScript、构建前端，再运行 Maven verify，输出 `target/nexus-core-0.1.0-SNAPSHOT.jar`；需要 Docker 执行集成测试。`test` 不读取 `data/local.env`，测试使用独立数据库和 Broker，不接真实博客/模型。环境变量优先于本机配置。配置文件按 `KEY=value` 读取，不执行 shell，也不要添加 shell 引号或变量替换。

运行日志留在启动终端；停止前台服务用 Ctrl+C。依赖停止使用 `docker compose --env-file data/local.env stop`，保留数据库卷。不要使用 `down -v` 清理业务数据。

## Halo

编辑 `data/local.env`：

```dotenv
NEXUS_HALO_ENABLED=true
NEXUS_HALO_URL=https://blog.muyin.site
NEXUS_HALO_TOKEN=<仅本机填写的只读令牌>
NEXUS_HALO_CONNECTOR_ID=halo-muyin
```

令牌需要评论查看权限；CORE 不调用创建/编辑/回复接口。首次启动固定保存“从现在起”的边界，之后重启不会重新设定。若确需首次导入历史，可在首次启用前设置 `NEXUS_HALO_SINCE` 为 ISO-8601 时间；已经初始化的连接器不会因修改该变量重置边界。

每分钟扫描最多 50 页、每页 100 条，以稳定事件 ID 去重，捕捉后续审核通过的评论。未审核、隐藏、私密、会员可见、未发布、已删除的内容均排除；没有公开主体证据的插件/Moments 评论也排除。接入页支持按评论 ID 显式导入一条历史公开评论。导入和 AI 外发前都重新读取源站；同一 ID 重复导入返回原消息。

接口依据 [Halo 2.27.2 CommentEndpoint](https://github.com/halo-dev/halo/blob/v2.27.2/application/src/main/java/run/halo/app/core/endpoint/console/CommentEndpoint.java)。字段查询使用经该实例验证的单等号 `metadata.name=<id>`；文档中的双等号在该实例返回空集合，集成夹具已覆盖这一差异。

## AI 与长期记忆

仅通过受信 Halo Connector 核验的 `public_comment` 能进入模型和记忆队列。普通消息接入即使写 `source=halo` 也不能取得此分类。AI 不接收邮箱、IP、原始 HTML、平台令牌或工具权限。

可配置兼容 `chat/completions` 服务：

```dotenv
NEXUS_AI_ENABLED=true
NEXUS_AI_AUTO_ENABLED=false
NEXUS_AI_BASE_URL=https://example.com/v1
NEXUS_AI_MODEL=<模型名>
NEXUS_AI_API_KEY=<仅本机填写>
```

或用 `NEXUS_AI_CONFIG_FILE` 引用已有受保护配置，避免复制密钥。`NEXUS_AI_CONFIG_FORMAT=openviking` 读取现有 JSON 的 `vlm`；`codex` 读取 TOML 中 `model_providers`，用 `NEXUS_AI_CONFIG_PROVIDER` 选择提供方，并显式设置 `NEXUS_AI_MODEL`。仅本机启动器支持这两个引用格式，JAR 本身读取标准环境变量。

本机现已引用已有兼容服务，模型 `gpt-5.6-luna`；原 OpenViking 火山方舟模型真实调用返回 `InvalidSubscription`，因此没有将其报告为可用。可选 `NEXUS_AI_PROXY=http://127.0.0.1:<port>` 使用现有本地代理。自动分析默认关闭，可在消息详情手动分析，或自行启用明确的评论规则。

模型输出只保存摘要、分类、重要性判断与草稿，不改变消息优先级、不执行工具、不发送评论。分析失败保留原消息；队列最多自动尝试 3 次，可在管理台重新核验后重试。摘要更新影响管理台与尚未发送的投递，不会用同一事件重新覆盖已接收设备的提醒。

长期记忆另外配置：

```dotenv
NEXUS_MEMORY_ENABLED=true
NEXUS_MEMORY_URL=http://127.0.0.1:1933/
NEXUS_MEMORY_API_KEY=<运行时最小权限密钥>
NEXUS_MEMORY_ACCOUNT=default
NEXUS_MEMORY_USER=default
NEXUS_MEMORY_PEER=nexus-pet
```

当前未填写运行时记忆密钥，保持关闭。显式保存需要分析已完成，且源站评论仍公开、内容哈希一致；仅向 `viking://user/<user>/resources/nexus-pet/public-comments/<messageId>.md` 写入，带 `project=nexus-pet`、`data_class=public_comment` 标签和显式 actor peer。重试覆盖同一路径，不能由消息内容指定任意 URI。检索逐条重查 Halo，只返回当前公开且正文哈希一致的固定目录候选，不采用上游摘要。源评论撤回、变为非公开或改文后，停止检索并持久化排队删除副本；核验失败仅隐藏，不能据此判断已经撤回。已确认副本依据源状态管理，来源账本独立于 7/30 天本地清理且不存正文。删除失败持续重试，写入中清理有补偿；恢复公开后需重新核验并显式保存。副本绑定服务/账号/用户/peer 身份，身份变化及缺少身份历史的旧副本先隔离并提示核对。管理台“记忆”页展示检索及待删除计数。规则、接口和真实联调边界见 [15](../04-ai-desktop-pet/docs/15-memory-lifecycle.md)；独立 MCP/stdio 接口与真实供应商验收步骤见 [16](../04-ai-desktop-pet/docs/16-voice-memory-tools.md)，固定桥接、受控启动与本地 TLS 全链回归见 [17](../04-ai-desktop-pet/docs/17-voice-bridge-regression.md)。

## 设备接入

`data/device.json` 是设备私有配置；不要上传或在终端打印。默认设备 ID 为 `dodo-1`。在管理台登记同名设备并不能创建 Broker 凭据；初始凭据及 ACL 由初始化脚本生成。

ESP32 访问不了 Mac 的 `localhost`。确认 Mac 私网地址后：

```sh
python3 tools/configure_lan.py --host <Mac私网IPv4>
docker compose --env-file data/local.env up -d --force-recreate mqtt
python3 tools/run_local.py run
```

运行前应停止原 CORE。LAN 工具保留 CA 和密码、备份旧证书及配置，只重签服务证书并绑定指定私网 IP；不会烧录设备或修改 Wi-Fi。当前本机仍使用回环绑定，未执行 LAN 变更。额外设备需独立密码及仅限自身主题的 ACL，不能共享 `nexus-core` 账号。

在已安装并确认兼容的 `03` DODO 固件上，通过独占 USB 串口写入命名空间配置：

```sh
python3 tools/provision_device.py --port /dev/cu.usbmodemXXXX
```

需要 pyserial（现有 ESP-IDF 环境已包含）。脚本只发送 `NEXUS_PROVISION` 配置命令，设备校验 TLS、身份、长度后写入 `nexus/config_v1` 并重启，不写整块 NVS。保存确认不等于 MQTT 已连接或消息已显示。更新身份会被拒绝，须先设计明确的数据迁移。

设备接收 1024 字节以内的摘要包，保存最多 4 条未读和 16 条去重记录。收到 ACK 要求 NVS 提交成功；显示 ACK 要求完整帧传输确认。仅当通知实际可见时 BOOT 短按才表示已读，否则保留原语音操作。设备免打扰可在配置中加 `quietStart`/`quietEnd`（本地分钟数）及 `utcOffsetMinutes`（默认 480），起止相同表示关闭，critical 可越过免打扰。详见 [协议](../docs/PROTOCOL.md)。

不能直接把 `03` 刷到正在运行 `02` 的设备；先按 [硬件与分区说明](../docs/HARDWARE.md)备份并完成迁移验收。

## 04 设备远程配置

`04-ai-desktop-pet` 新增 telemetry/config/config_acks，与原消息协议并列；03 设备不因升级 CORE 自动具有此能力。管理台设备卡片“设置与状态”读取完整设置，支持音量、亮度、静音、手动/定时免打扰、固定时区与消息设置。设备状态过期、存储只读、设置版本变化和配置结果未知时阻止覆盖；后台轮询保留草稿。亮度 0 的熄屏后果会在保存前显示。

设备卡片还支持“修改名称”，仅更新 CORE 显示名称。`PATCH /devices/{id}/name` 提交 `{name,expectedName}`，事务内核对旧名称；冲突返回 409，目标已匹配时允许安全重试。前端保留轮询中的草稿，冲突后显式加载当前名称；HTTP 结果未知时刷新核对，不自动重发。设备 ID、Broker 凭据及投递关系不变，无新增数据库迁移。流程和当前验证见同级 [14](../04-ai-desktop-pet/docs/14-recovery-and-device-names.md)。

所有请求沿用管理员 Bearer 鉴权，单一共享令牌不提供多用户/RBAC。配置请求持久化后返回 202，不等于设备已保存；只有有效 ACK 或匹配完整回读才能确认。HTTP 中断保留原 requestId，重试不能换 ID。每设备最多一个 pending/sent/unconfirmed 请求，曾尝试发送后超时保留 unconfirmed，随后用新鲜配置核对。retained 遥测不把设备标为在线。详细状态与设备事务见 [管理契约](../04-ai-desktop-pet/integration/contracts/device-management-v1/README.md)。

新安装生成带管理主题的设备作用域 ACL；已有实例不会被 init_local.py 自动覆盖。先停 CORE、备份并在独立库验证可恢复，再允许新 JAR 的 Flyway 迁移（V8 远程管理、V9 消息保留、V10 记忆副本账本/删除队列）。Broker 在维护窗口执行：

```sh
python3 tools/upgrade_broker_policy.py
# 核对 changed 后，部署者显式执行才写文件；不自动重启 Broker。
python3 tools/upgrade_broker_policy.py --apply
```

工具备份到 config/.management-v1-backup，原权限保留，失败尽力回滚；自定义 ACL 拒绝覆盖并要求手工合并。没有在本次开发中运行真实 --apply 或迁移真实库。只有更新并重新加载 ACL 后，老 Broker 才允许管理主题。

## Mac 工具、自动化和成长

[Bridge 部署说明](../mac-bridge/README.md)包含项目/应用白名单。管理台“工具”可查询系统信息和白名单 Git 状态；打开应用必须先创建请求、核对目标、确认，确认绑定管理员、请求 ID、工具、参数摘要与 5 分钟期限。结果未知时仅核对原请求，不自动重发动作。本机应用白名单为空，未打开应用。

自动化目前仅支持两种明确规则：新公开评论进入分析队列、每日定时生成设备提醒。新规则默认停用；启用评论规则只处理之后的新事件。每日规则按 IANA 时区计算，每日最多一次，启动后补偿当天最近两小时内的提醒；DST 缺失时刻顺延、重复时刻按日期去重。停用会取消尚未开始的任务，已开始的工作可能完成。“已加入队列”不是 AI 完成、设备已展示或用户已读。规则没有任意脚本、邮件发送或高风险工具自动执行能力。

成长页提供 1～180 分钟专注、取消和到时结算；服务端验证时间，页面关闭/服务重启不会丢失。每分钟 1 经验，单次最多 120、UTC 日最多 240，每 100 经验升一级。金币为本次经验/5 取整、最少 1、最多 20；有经验奖励时亲密度增加 1，上限 100。重复/并发结算只记一笔，取消不奖励。旧 NVS 档案、设备姿态、人设、商店和虚拟空间尚未联动。

## API 与验证

### 公开记忆语音工具

独立 `/mcp/memory` 默认关闭。开启 `NEXUS_MEMORY_MCP_ENABLED=true` 时，配置 32–256 个可见 ASCII 字符的独立 `NEXUS_MEMORY_MCP_TOKEN`；复用管理员令牌或无效值会使启动失败。受限令牌只进入该路径，不能访问以下管理 API，管理员令牌也不能代用。实际检索还需 Halo/OpenViking 已配置，结果仍执行当前公开状态和哈希复核，以及既有失效删除政策。

`tools/memory_mcp_stdio.py` 只依赖 Python 标准库，从受保护环境读取 `NEXUS_MEMORY_MCP_URL` 和工具令牌；可交给常在线服务器的供应商桥接运行，设备不保存 CORE 或上游服务密钥。它不是私人聊天存档或音频代理。精确的 MCP 2025-06-18 子集见[契约](../04-ai-desktop-pet/integration/contracts/memory-tools-v1/README.md)，Linux 模板、令牌轮换、故障排查与真实验收见 [16](../04-ai-desktop-pet/docs/16-voice-memory-tools.md)。本轮仅验证隔离 HTTP/数据库与 stdio 子进程，没有接入真实语音云端。

### 管理 API

所有 `/api/v1` 请求均需 `Authorization: Bearer <admin-token>`。静态 GET 页面可公开读取，其余路径默认鉴权；管理请求体限制 32 KiB，错误码不含上游正文或密钥。

| 路径 | 用途 |
| --- | --- |
| `GET/POST /messages`，`POST /messages/{id}/read` | 消息列表、接入、已读 |
| `GET/POST /devices`，`PUT /devices/{id}/enabled`，`PATCH /devices/{id}/name` | 设备登记、新投递开关及名称 CAS |
| `GET /devices/{id}/management`，`POST .../config-requests`，`GET .../config-requests/{requestId}/audit` | 遥测/设置、CAS 请求与审计 |
| `GET /deliveries`，`POST /deliveries/{id}/retry` | 投递状态与失败重试 |
| `GET /connectors`，`POST /connectors/halo/poll` | 接入健康与同步 |
| `POST /connectors/halo/comments/{name}/import` | 显式导入单条历史公开评论 |
| `GET /ai`，`GET/POST /messages/{id}/analysis`，`POST .../analysis/retry` | 模型状态和分析 |
| `GET /memory`，`POST /messages/{id}/memory`，`POST /memory/search` | 记忆状态/删除计数、显式保存和受限检索 |
| `GET /tools`，`GET/POST /tool-jobs`，`GET /tool-jobs/{id}` | 工具能力和执行请求 |
| `POST /tool-jobs/{id}/{confirm,cancel,reconcile}`，`GET .../audit` | 确认、取消、核对、审计 |
| `GET/POST /automation/rules`，`PUT .../{id}/enabled`，`GET /automation/runs`，`POST .../{id}/retry` | 自动化 |
| `GET /pet`，`GET/POST /pet/focus`，`POST /pet/focus/{id}/{complete,cancel}` | 成长与专注 |
| `GET /status` | 数据库/Broker 健康；schemaVersion 指消息协议版本 |

```sh
python3 tools/run_local.py test
python3 tools/run_local.py live-ai-test
```

第二条明确访问本机已配置的真实模型，仅发送测试公开评论；默认测试跳过它。常规集成测试使用真实 PostgreSQL 17、Mosquitto 2.0.22、Python Bridge；Halo/模型/OpenViking 使用本地 HTTP 契约夹具，不冒充真实服务验收。编译、主机测试和 Broker 连接不能证明设备 DMA、NVS 掉电恢复、真实录音、语音并发或长期稳定性。

PostgreSQL 数据在 Compose 卷中，设备密钥/证书在 `data/`；两处均需备份。升级前可用 `pg_dump -Fc` 备份，Flyway 禁止 clean。本轮升级前备份位于本机 `data/backups/before-v7.dump`。恢复时使用独立数据库核验，不能直接清空现有卷。[部署准备与恢复](../04-ai-desktop-pet/deployment/README.md)提供 Linux 服务、TLS 代理和升级模板；尚未安装常驻服务、绑定真实域名/证书或验收生产部署。消息保留实现见下节；真实库升级/清理及 24h/7d 稳定性仍待验收。

## 消息保留（V9，默认关闭）

`NEXUS_RETENTION_ENABLED=true` 启用按接收时间计算的 7 天正文清理、30 天消息/投递元数据删除。升级已有实例前先备份，再用管理员令牌 `GET /api/v1/retention` 核对只读预览；手工执行 `POST /api/v1/retention/runs` 必须提交 `{"confirm":"DELETE_EXPIRED_MESSAGES"}`。单事务最多删除 200 条元数据、再清除 200 条正文；积压需多次执行。并发占用返回 409，失败整体回滚。UTC 04:00 定时，可用 `NEXUS_RETENTION_CRON=-` 关闭定时。

超期内容不再接受分析/重试/记忆外发，后台领取检查同一期限；清理使旧分析 lease 失效。Halo 自动轮询启用 30 天批准时间窗口以避免历史重入，明确的单条补录仍保留。已发给模型的内容不能通过本地清理撤回。已确认的 OpenViking 副本跟随源评论状态管理；在途写入的补偿及失效删除见 [15](../04-ai-desktop-pet/docs/15-memory-lifecycle.md)。V9 仅在隔离测试库验证，当前运行实例未迁移或清理。完整运维步骤见同级 `04-ai-desktop-pet/docs/13-operational-software.md`。
