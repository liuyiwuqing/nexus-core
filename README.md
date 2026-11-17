# NEXUS CORE

Java 21 / Spring Boot 的模块化单进程服务，PostgreSQL 保存业务状态，Mosquitto TLS 连接设备，Vue 管理台随 JAR 一起发布。平台令牌和 AI 密钥只留在服务端。CORE/记忆工具凭据文件轮换见 [轮换说明](docs/credential-rotation.md)。

需求范围、已验证基线和逐项交付约定见 [交付文档](docs/delivery-workflow.md)。每项同步实现与文档、验证、提交并推送后继续；生产部署与真实设备验收独立记录。

## 当前能力

| 模块 | 已实现的行为 | 验证边界 |
| --- | --- | --- |
| Inbox | 统一消息、唯一事件去重、历史、已读、优先级、事务 outbox | 真实 HTTP / PostgreSQL / Mosquitto 集成测试 |
| Halo | 官方 Console API 只读轮询；仅已审核、未隐藏且所属文章/页面公开发布的评论 | 现有博客真实 GET；历史评论需显式导入 |
| 设备 | 注册、重命名、启停新投递、心跳、重试、回执分离；遥测/完整设置回读、配置 CAS/审计、遥测历史采样与查询 | 真实 HTTP/PostgreSQL/MQTT 与 Java/C 配置回环；真实 ESP32 待烧录 |
| AI | 公开状态二次核验、摘要/分类/重要性/回复草稿、持久队列、失败重试 | 本机已有兼容模型真实调用通过；草稿不会发送 |
| 记忆 | 显式保存、固定目录检索、源复核/失效删除、管理页及独立 MCP/stdio 工具 | HTTP/PostgreSQL 与浏览器夹具验证；默认关闭，真实身份/权限/删除及语音接入待验收 |
| 工具 | 固定工具目录、参数校验、确认绑定、持久审计、未知结果核对 | 真实 Python Bridge 进程测试；本机只启用查询能力 |
| 自动化 | 新公开评论→分析队列；每日定时→设备消息；停用后编辑、版本保护、启停、失败重试、时区、每日去重 | 真实数据库集成测试；规则默认停用 |
| 成长 | 服务端专注计时、经验/金币/亲密度、等级、历史、并发单次结算；设备成长只读镜像 | HTTP/数据库/MQTT 与实际 C producer；两个档案独立结算，见 [同步说明](docs/growth-sync.md) |

此版本没有接管小智 ASR/聊天/TTS，也没有接入邮件、私信、录音、iPhone、Home Assistant 或社交平台私有 API。

## Docker 线上部署

自动化页支持[停用后删除与测试预演](docs/console-automation-lifecycle.md)，删除保留历史和原创建身份，预演不产生消息或动作。

管理台支持[可选择启用的消息路由与定向测试](docs/console-routing.md)，默认保持广播，新规则只影响后续入库消息。

第三方消息使用[独立来源令牌与签名 Webhook](docs/webhook-sources.md)，来源设备范围与路由交集、限流、防重放和一次性凭据维护均由服务端执行。

管理员可在“权限”页签发/撤销独立 operator/viewer 令牌并查看[统一操作审计](docs/roles-and-audit.md)，服务器逐请求执行权限边界。

“模板”页提供[变量预览与设备能力校验](docs/message-templates.md)，可挂接路由或定向测试；投递保留创建时的模板版本与渲染快照。

管理台提供[认证 SSE、轮询回退与首次接入引导](docs/console-events-and-setup.md)，登记、连接、接收与展示分别核对。

管理台使用 [Naive UI 与统一界面约定](docs/console-ui.md)：十二个入口分组、可折叠桌面侧栏、明暗主题、手机抽屉导航、紧凑卡片布局，常用操作优先、更多条件和历史按需展开，以及统一的紧凑表格、卡片、浮层和描边/主提交/危险按钮，表单、下拉、日期和上传控件沿用 Naive UI；消息筛选/导出与设备动作状态保留，CSP 样式 nonce 随页面生成，接口和操作确认语义不变。

消息页保留元数据导出，并提供[管理员显式确认的七天正文导出](docs/content-export.md)，最多 1000 条，不恢复已清理内容。

1Panel 使用 [镜像部署](docs/1panel-deployment.md)：发布配套 CORE/初始化镜像，服务器直接拉取；一次性初始化工具生成私有配置和可粘贴的三服务编排，不上传源码，不依赖宿主 Python/Java/Node，不占用面板的 80/443。镜像发布和拉取验证以具体流水线结果为准，目标机尚未验收。

自行构建或使用独立 Caddy 时保留根目录 `Dockerfile` 与 `deploy/docker/compose.yml`，完整命令、HTTPS、持久化、备份与升级见 [Docker 部署](docs/docker-deployment.md)。该源码方案的本地和远程 Docker CI 已通过，不等于生产部署。

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

测试消息在结果未知时保留同一请求 ID 和正文，切换导航可继续重试，收到确认后才开始下一条消息。退出/刷新页面后不恢复正文；详细行为和验证边界见 [测试消息与断线重试](docs/console-messages.md)。 加载、空结果与错误反馈使用统一紧凑样式；消息和投递读取失败集中显示一次原因与重试，字段校验、权限与未知结果核对保留，见 [状态反馈约定](docs/console-ui.md#加载空状态与错误反馈console-25)。

消息可组合已读/未读、来源和收件时间，投递可组合八种状态、设备 ID 和消息收件时间；计数与分页使用同一条件，刷新/操作后保留筛选，末页变空时回到有效页。两页可导出当前筛选的元数据 CSV，最多 5000 条，不含标题、正文或原始 payload。HTTP 参数、时间口径与导出边界见[筛选与导出](docs/console-list-filters.md)。

“概览”提供同一数据库快照的设备在线/离线、UTC 今日收件、当前失败率和投递积压；无投递时失败率显示 `—`，MQTT 健康与设备在线分别呈现。口径及验证入口见[运行概览](docs/console-overview.md)。

投递详情提供事务状态时间线，仅允许取消能确认尚未开始发送的记录；旧行从迁移时基线开始留存，重试不会重新获得取消资格，未知结果核对同一投递。API、V13 和保留边界见[时间线与取消](docs/console-delivery-history.md)。

工具与规则创建在断线后保持同一提交编号和参数；规则 API 提供可选 `requestKey`，旧调用仍兼容。独立读取/审计失败会隐藏旧操作，详情见[工具与自动化故障处理](docs/console-operations.md)。

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

遥测历史单独保存为追加式样本，`GET /devices/{id}/telemetry` 支持原始点与 `1m`–`1d` 桶聚合。变化即记、5 秒变化下限、30 秒心跳封顶、30 天保留，缺失桶不补零；快照仍以 `management` 的 `fresh`/`observedAt` 为准。设备「设置与状态」提供 1 小时–7 天的状态历史图，只有快照标「实时」，历史最后一条不代表在线。口径、字段语义与配置见[遥测历史与采样](docs/device-telemetry-history.md)。

新安装生成带管理主题的设备作用域 ACL；已有实例不会被 init_local.py 自动覆盖。先停 CORE、备份并在独立库验证可恢复，再允许新 JAR 的 Flyway 迁移（V8 远程管理、V9 消息保留、V10 记忆副本账本/删除队列）。Broker 在维护窗口执行：

```sh
python3 tools/upgrade_broker_policy.py
# 核对 changed 后，部署者显式执行才写文件；不自动重启 Broker。
python3 tools/upgrade_broker_policy.py --apply
```

工具备份到 config/.management-v1-backup，原权限保留，失败尽力回滚；自定义 ACL 拒绝覆盖并要求手工合并。没有运行真实 `--apply`；本机真实库已于 2026-11-17 在备份并在临时库恢复验证后迁移到 V23，目标服务器仍未部署。只有更新并重新加载 ACL 后，老 Broker 才允许管理主题。

## Mac 工具、自动化和成长

[Bridge 部署说明](../mac-bridge/README.md)包含项目/应用白名单。管理台“工具”可查询系统信息和白名单 Git 状态；打开应用必须先创建请求、核对目标、确认，确认绑定管理员、请求 ID、工具、参数摘要与 5 分钟期限。结果未知时仅核对原请求，不自动重发动作。本机应用白名单为空，未打开应用。

自动化目前仅支持两种明确规则：新公开评论进入分析队列、每日定时生成设备提醒。新规则默认停用；启用评论规则只处理之后的新事件。每日规则按 IANA 时区计算，每日最多一次，启动后补偿当天最近两小时内的提醒；DST 缺失时刻顺延、重复时刻按日期去重。停用会取消尚未开始的任务，已开始的工作可能完成。“已加入队列”不是 AI 完成、设备已展示或用户已读。规则没有任意脚本、邮件发送或高风险工具自动执行能力。

规则停用且没有正在入队的任务后可编辑；保存核对原版本并保持停用，历史与原创建键保留。页面支持草稿保留、原版本重试和只读核对，不自动覆盖并发修改或启用规则；同一本地日期已生成的提醒不因编辑而重发。接口及限制见[规则编辑](docs/console-automation-editing.md)。删除、测试运行和通用条件/动作仍未提供。

成长页提供 1～180 分钟专注、取消和到时结算；服务端验证时间，页面关闭/服务重启不会丢失。每分钟 1 经验，单次最多 120、UTC 日最多 240，每 100 经验升一级。金币为本次经验/5 取整、最少 1、最多 20；有经验奖励时亲密度增加 1，上限 100。重复/并发结算只记一笔，取消不奖励。可选设备成长只读镜像显示各设备状态，两个档案独立结算；设备姿态、人设、商店和虚拟空间尚未联动。

## API 与验证

### 公开记忆语音工具

独立 `/mcp/memory` 默认关闭。开启 `NEXUS_MEMORY_MCP_ENABLED=true` 时，配置 32–256 个可见 ASCII 字符的独立 `NEXUS_MEMORY_MCP_TOKEN`；复用管理员令牌或无效值会使启动失败。受限令牌只进入该路径，不能访问以下管理 API，管理员令牌也不能代用。实际检索还需 Halo/OpenViking 已配置，结果仍执行当前公开状态和哈希复核，以及既有失效删除政策。

`tools/memory_mcp_stdio.py` 只依赖 Python 标准库，从受保护环境读取 `NEXUS_MEMORY_MCP_URL` 和工具令牌；可交给常在线服务器的供应商桥接运行，设备不保存 CORE 或上游服务密钥。它不是私人聊天存档或音频代理。精确的 MCP 2025-06-18 子集见[契约](../04-ai-desktop-pet/integration/contracts/memory-tools-v1/README.md)，Linux 模板、令牌轮换、故障排查与真实验收见 [16](../04-ai-desktop-pet/docs/16-voice-memory-tools.md)。本轮仅验证隔离 HTTP/数据库与 stdio 子进程，没有接入真实语音云端。

### 管理 API

所有 `/api/v1` 请求均需 `Authorization: Bearer <admin-token>`。静态 GET 页面可公开读取，其余路径默认鉴权；管理请求体限制 32 KiB，错误码不含上游正文或密钥。

| 路径 | 用途 |
| --- | --- |
| `GET /overview` | 只读运行统计，固定 UTC 今日窗口 |
| `GET/POST /messages`，`POST /messages/{id}/read` | 消息列表、接入、已读 |
| `GET/POST /devices`，`PUT /devices/{id}/enabled`，`PATCH /devices/{id}/name` | 设备登记、新投递开关及名称 CAS |
| `GET /devices/{id}/management`，`POST .../config-requests`，`GET .../config-requests/{requestId}/audit` | 遥测/设置、CAS 请求与审计 |
| `GET /devices/{id}/telemetry?from&to&bucket` | 只读遥测历史；原始点或 `1m`–`1d` 聚合桶 |
| `GET /deliveries`，`GET /deliveries/{id}/history`，`POST /deliveries/{id}/retry`、`.../cancel` | 投递状态/历史、失败重试与受限取消 |
| `GET /connectors`，`POST /connectors/halo/poll` | 接入健康与同步 |
| `POST /connectors/halo/comments/{name}/import` | 显式导入单条历史公开评论 |
| `GET /ai`，`GET/POST /messages/{id}/analysis`，`POST .../analysis/retry` | 模型状态和分析 |
| `GET /memory`，`POST /messages/{id}/memory`，`POST /memory/search` | 记忆状态/删除计数、显式保存和受限检索 |
| `GET /tools`，`GET/POST /tool-jobs`，`GET /tool-jobs/{id}` | 工具能力和执行请求 |
| `POST /tool-jobs/{id}/{confirm,cancel,reconcile}`，`GET .../audit` | 确认、取消、核对、审计 |
| `GET/POST /automation/rules`，`GET/PUT .../{id}`，`PUT .../{id}/enabled`，`GET /automation/runs`，`POST .../{id}/retry` | 自动化与停用后编辑；新页面启停附带版本 |
| `GET /pet`，`GET/POST /pet/focus`，`POST /pet/focus/{id}/{complete,cancel}` | 成长与专注 |
| `GET /pet/devices`，`GET /devices/{id}/growth` | 设备成长只读镜像、存储状态与新鲜度；默认关闭 |
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

## 远程固件升级

默认 `NEXUS_OTA_ENABLED=false`；设备远程开关也默认关闭。配套启用需 V11、OTA 专用 ACL、已配置生产 OTA 根/公钥的固件。登记版本只保存签名 manifest 的元数据，设备安装时才验签；CORE 不托管固件文件。

- `GET/POST /api/v1/ota/releases` 读取/登记 `{releaseId,manifest}`，同目录不可换包。
- `GET /api/v1/devices/<deviceId>/ota` 返回新鲜回读、动作门控及最近部署/命令。
- `POST .../ota/deployments` 提交 `{requestId,releaseId,bootId}`，安装完成后等待独立重启确认。
- `POST .../ota/deployments/<requestId>/reboot` 提交 `{requestId:独立命令ID,bootId,confirm:"REBOOT_DEVICE"}`。
- `GET .../ota/deployments/<requestId>/audit` 查询状态审计。以上均沿用管理员鉴权。

命令持久化后发布，结果未知时保留 ID、阻止新部署；设备 ACK 不等于镜像健康。完整回读绑定随机启动标识、目标与序号，旧启动不能覆盖新状态。`devices.enabled` 继续仅控制消息。

`python3 tools/upgrade_broker_policy.py` 默认只预览；显式 `--apply` 才备份为 `.remote-ota-v1-backup` 并写已知模板，不覆盖旧 `.management-v1-backup`，不自动重启 Broker，自定义 ACL 拒绝自动修改。未对真实数据库/Broker 执行本轮迁移。

管理台设备卡片提供“固件升级”，可选已兼容版本、查看记录、单独确认重启；提交断线可重试原请求。细节、契约与成套启用步骤见配套 [04 项目远程 OTA 文档](../04-ai-desktop-pet/docs/19-remote-ota.md)。

验证：`python3 tools/run_local.py build`、`python3 tools/test_broker_policy.py`；配套 04 的 `tools/ci/test_remote_ota_contract.py` 运行实际 Java/数据库/MQTT 与 C 事务回环，Flash/SDK/重启为托管边界，不等于实机 OTA。

本项本地完整构建为 50 条测试（46 通过、4 条环境条件跳过），随后新增的默认关闭 HTTP 用例单独通过并重新打包。OTA 专项实际 PostgreSQL/MQTT 5 条通过，配套实际 C peer 另行通过；浏览器 OTA 22 项及既有管理 38 项通过。完整验收边界在配套文档，真实部署与实机升级仍未执行。

## 平滑 Bearer 轮换

可选 `NEXUS_AUTH_TOKEN_FILE` 使用受保护 current/previous 文件，保留环境模式；文件模式拒绝旧环境后备，按请求重载，损坏返回固定 503。管理与 `/mcp/memory` 范围始终独立，工具任务归属保留初始化时的稳定身份；在途请求不被撤销中断。stdio 使用 `NEXUS_MEMORY_MCP_TOKEN_FILE` 独立客户端副本，每次 RPC 重读，不自动重试。初次切换模式需一次正常重启，后续文件更新不重启。

`tools/credential_store.py` 提供 init/status/rotate/revoke/export-memory，使用本地文件锁/修订/原子写入、不输出秘密。格式、权限、命令与已执行验证见 [本仓文档](docs/credential-rotation.md)和配套 [20](../04-ai-desktop-pet/docs/20-credential-rotation.md)。仅验证隔离文件与服务，没有轮换真实凭据或改变设备/上游服务的凭据维护流程。
