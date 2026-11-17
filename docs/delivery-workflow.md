# CORE 需求与逐项交付

2026-11-14。CORE 承载消息、Halo 公开评论、可选 AI/公开记忆、工具审计、自动化、独立专注档案及设备管理；ESP32 基础语音直连供应商，CORE 不接管音频。当前需求与任务台账在配套 [04 项目](https://github.com/liuyiwuqing/ai-desktop-pet/blob/main/docs/18-requirements-and-progress.md)，详细能力和实际边界见本仓 [README](../README.md)。

## 已完成与边界

源码基线 `913b3af` 纳入 100 个文件，私有 data、env、密钥和构建目录已排除。该基线的前端类型检查/生产构建及 Maven verify 通过：44 项，41 通过、3 条件跳过、0 失败/错误。真实模型、Java/C 可选回环和可选语音桥接的条件跳过不计作通过。

04 的离线包保留该基线源码与实际 JAR；实际 Java 21/PostgreSQL/Mosquitto/Caddy Linux 容器启动/重启、V1–V10 迁移、鉴权和静态资源检查通过。没有目标服务器，未执行 systemd、生产 TLS、真实库迁移/清理、真实供应商及设备验收。

## 持续推进

每个独立工作项：读取对应需求和真实源码 → 明确公开接口/验收 → 实现与必要契约配套 → 针对性验证 → 更新 README/功能文档/04 台账 → 检查差异与私有数据边界 → 本地提交 → 正常推送并核对远程提交 → 下一项。用户已授权此流程，不重复请求常规确认；推送失败保留本地提交并明确记录。

远程 OTA 软件已随 `2808ed7` 推送：V11 隔离迁移、持久命令与启动回读、管理台、ACL 及回归成套交付。完整构建 50 条中 46 通过、4 条条件跳过；新增默认关闭 HTTP 用例另行通过，实际 C peer 单独通过，浏览器 OTA 22 项与既有管理 38 项通过。设备真实 Flash/掉电/回滚仍未执行。

CORE/记忆工具平滑轮换的软件实现、文档及验证已完成，随本项提交：完整构建 59 条中 55 通过、4 条跳过；真实 HTTP/文件/CLI 7 项、CLI 4、stdio 7、桥接启动 6、TLS 桥接 6、交付 10 项通过，见 [凭据轮换](credential-rotation.md)。设备及上游服务的物理/维护凭据流程不变，未操作真实秘密。

成长只读镜像已完成本地软件闭环，见 [同步说明](growth-sync.md)：V12、HTTP/MQTT、设备隔离、管理台及 ACL 维护同项交付；完整构建 64 条中 59 通过、5 条跳过，实际 C producer 回环另行通过，浏览器 18 项通过。服务端奖励下发仍为二期，不直接合并两个档案。

管理台已补[测试消息断线重试](console-messages.md)与[消息/投递状态筛选](console-list-filters.md)，配套完成度矩阵和后续缺项见 04 的 23 文档。API 新增可选读取参数，不改变默认广播、鉴权、重试或设备状态流转；详细验证与配套提交在任务台账逐项记录。

各项先完成可本地验证的软件闭环；真实环境所需身份、生产签名钥、发布域名/服务器、设备部署和产品验收另行记录，不以夹具或编译冒充。

DEL-07 补齐 [1Panel 镜像交付](1panel-deployment.md)：独立初始化镜像、无源码/无 raw 格式的三服务编排，以及“验证后发布多架构镜像，再从 GHCR 按 digest 回拉验证”的工作流。服务器不再承担源码构建，私有仓库与包可见性不变；目标机验收仍独立记录。该项目交付约定沉淀在功能文档和可执行 CI，无需修改共享团队 Skill。

CONSOLE-05 新增[只读运行概览](console-overview.md)：UTC 收件窗口、既有 90 秒在线规则、当前失败比例与保留投递积压，独立加载/异常/轮询。接口与真实 PostgreSQL 专项、实际 Vue 的回环边界检查及完整构建分别记录，不作为设备或部署证据。

CONSOLE-05 的 CORE `af916aa` 对应 [Docker CI 43408287857](https://github.com/liuyiwuqing/nexus-core/actions/runs/43408287857) 全步骤 success；04 `24df6d9` 对应 [43408341106](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43408341106) 七个 job success。前者运行源码构建/隔离 Compose，后者运行固件/主机及交付检查；HTTP 数据库/Maven 和浏览器专项仍是本地证据。

CONSOLE-04 新增[投递状态时间线与受限取消](console-delivery-history.md)：V13 同事务状态记录、旧行诚实基线、永久取消 guard 与幂等取消，保留原投递/回执契约。真实 HTTP/worker/隔离 SQL、浏览器、Java–C 源码契约与完整构建分别核对，不迁移真实库或发送撤回命令。

CONSOLE-04 的 CORE `109b83f` 已推送并核对远端，对应 [Docker CI 43410896935](https://github.com/liuyiwuqing/nexus-core/actions/runs/43410896935) 完成，源码镜像构建和隔离 Compose 的全部步骤 success。04 配套 `0dec4b6` 对应 [43410974174](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43410974174) 七个 job success，含 full 主机/协议/DSP/LVGL；70 通过/5 跳过的 Maven、253 项浏览器及 Java–C 源码契约仍为本地证据。

CONSOLE-12-A 补齐[工具/规则创建与页面故障处理](console-operations.md)：固定逻辑提交、V14 规则可选幂等键、读取失败隐藏旧操作、独立审计/迟到响应门控，保留现有确认、启停、版本和入队语义。接口/隔离 PostgreSQL、实际 Vue 及完整构建分别核对；不扩大到自动化条件/动作或真实执行验收。

CONSOLE-12-A 的 CORE `28793aa` / 04 `052851f` 均已推送并核对远端。[Docker CI 43414082430 第二次运行](https://github.com/liuyiwuqing/nexus-core/actions/runs/43414082430/attempts/2) 的 CLI 11 项、源码镜像与隔离 Compose 全部通过。第一次运行在 Maven 解析 Spring Boot 父 POM 时报告不存在；随后中央仓库直连 200、内容与本地缓存哈希一致，未改依赖/源码而重跑同一提交后通过，具体网络原因未确认。04 的 [43414283771](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43414283771) 七个 job success，含五种固件预设及 full 主机/协议/DSP/LVGL；74 通过/5 跳过的 Maven 与 349 项浏览器专项仍是本地证据。

CONSOLE-13 新增[来源/设备/收件窗口与元数据 CSV](console-list-filters.md)：列表、计数与导出共享绑定条件；管理员读取、5000 行上限、明确列白名单与 CSV 公式转义。新增 6 项 HTTP/隔离 PostgreSQL、实际 Vue 44 项和完整构建 80 通过/5 跳过；没有新增迁移或正文导出。配套页面回归、JAR 资源、提交与远程 CI 在 04 台账分别核对。

CONSOLE-13 的 CORE `ea9cb77` 已正常推送并核对远端；[Docker CI 43553123583](https://github.com/liuyiwuqing/nexus-core/actions/runs/43553123583) 的初始化 CLI 11 项、源码镜像构建及真实隔离 Compose 全步骤 success。393 项浏览器、80 通过/5 跳过的完整 Maven 与 Java–C 源码契约仍为本地证据，目标机/实机验收未执行。

线上管理台采用独立 [Docker Compose 方案](docker-deployment.md)：源码构建 Vue/JAR 镜像、私有初始化、PostgreSQL/MQTT/Caddy、认证健康检查及隔离部署验证。根目录开发 Compose 保留。当前没有目标服务器，生产 DNS/证书、真实配置迁移、资源和恢复演练仍需另行验收；本地验证结果记入 04 台账 DEL-06。

CONSOLE-09-A 新增[停用后规则编辑](console-automation-editing.md)：固定类型/ID、事务版本校验、在途入队保护、原版本重试及只读核对；新页面启停附带观察到的版本，旧客户端保持兼容。新增 7 项 HTTP/真实隔离 PostgreSQL 与既有创建 4 项通过；不新增迁移，不删除历史，也不执行真实规则/消息。完整构建 87 通过/5 条件跳过，页面新增 99/既有 393 项通过；最终前端检查/资源打包与 JAR 三资源、V1–V14 逐字节一致。逐仓推送和远程 CI 分别记入 04 台账。

CONSOLE-09-A 的 CORE `cb18ba2` 已正常推送并核对远端；[Docker CI 43576059029](https://github.com/liuyiwuqing/nexus-core/actions/runs/43576059029) 全步骤 success，含初始化 CLI 11 项、源码镜像及真实隔离 Compose。492 项浏览器及 87 通过/5 跳过的完整 Maven 为本地证据，不冒充远程专项或目标机/实机验收。

CONSOLE-09-A 后续测试提交 `2ba3a84` 将调度断言限定到本规则的运行/结果消息，原 11 项专项再次通过；实现及最终 JAR 来源未变。该提交已推送并核对远端，对应 [Docker CI 43576489895](https://github.com/liuyiwuqing/nexus-core/actions/runs/43576489895) 全步骤 success；首次实现 CI `43576059029` 仍保留各自提交证据。

TELEMETRY-01 新增[遥测历史与采样](device-telemetry-history.md)：V21 追加式样本表、变化即记（5 秒下限）与 30 秒心跳封顶、30 天分批清理，以及 `GET /devices/{id}/telemetry` 的 `raw`/`1m`–`1d` 查询。窗口、桶数、2000 点上限、空桶不补零、ISO-8601 与 400/404 语义已写入文档和 04 管理契约；快照、MQTT 主题、遥测 Schema、ACL 与管理台现有行为不变。新增 2 项真实 HTTP/PostgreSQL/MQTT 测试；完整构建 120 条中 115 通过、5 条条件跳过，V21 在隔离库成功应用。远程 [Docker CI 43874044017](https://github.com/liuyiwuqing/nexus-core/actions/runs/43874044017) 的 job 未启动，GitHub 返回计费/额度限制；上一提交 `253e467` 的 [43871121175](https://github.com/liuyiwuqing/nexus-core/actions/runs/43871121175) 同样如此，均非代码失败，远程 CI 待额度恢复后补跑。未迁移真实库，未实现图表页面，未做实机长期采样验收。

TELEMETRY-02 在管理台「设置与状态」新增[状态历史](console-ui.md)与实时/历史视觉区分：无依赖内联 SVG 画可用内存（实线）与启动以来最小内存（虚线），1 小时/6 小时/24 小时/7 天分别查询 `1m/5m/15m/1h`，缺失桶按 1.8 倍桶宽断线、不补零；悬停与左右方向键显示采样时间与 KB，历史每 60 秒刷新。实时徽标只读 `management` 的 `fresh`，改为「实时快照/快照已过期」并带 90 秒口径说明，历史区块不标「实时」且脚注声明最后一条不代表在线。04 夹具新增 `/telemetry` 端点与请求记录，`test_management_ui.sh` 新增空态、图例/双序列、默认 `5m`、范围切 `15m`、悬停取值及 390px 无横向溢出断言，46 项通过；`npm run check`（78 token）与构建通过。`python3 tools/run_local.py build` 全量 120 条中 115 通过、5 条条件跳过，V21 在隔离 PostgreSQL 17 应用成功并产出 jar。未改后端、MQTT 主题、遥测 Schema 或 ACL；未迁移真实库、未部署、未烧录，实机长期采样与视觉验收仍待完成。远程 [Docker CI 43877392638](https://github.com/liuyiwuqing/nexus-core/actions/runs/43877392638) 的 `docker-compose` job 在 2 秒内失败、`steps` 为空且无日志，与 TELEMETRY-01 的计费/额度阻断一致；非代码失败，待额度恢复后补跑。

TELEMETRY-03 在管理台[状态历史](console-ui.md)增加内存/音量/未读/音频指标切换：复用既有 `GET .../telemetry` 响应，切换只在前端换序列、不新增请求；音量、未读、音频欠载分别按 `%`、条、次绘制，音频视图用 `--danger` 标记 `audioFaulted=true` 的桶并在图例与悬停提示写明；计数/百分比下界钳 0，避免全 0 时刻度出现负值。无新增 API、迁移、MQTT 主题或遥测 Schema。`npm run check`（78 token）与构建通过；04 `test_management_ui.sh` 由 46 增至 54 项（指标组、单一序列、三种单位、故障点/图例/提示、切回内存），仍覆盖 390px 无横向溢出。`python3 tools/run_local.py build` 120 条中 115 通过、5 条条件跳过，V21 在隔离 PostgreSQL 17 应用成功并产出 jar。CORE `ef98bc8` 与 04 `1dcd617` 已推送并核对远端；推送后的 [CORE Docker CI 43893638155](https://github.com/liuyiwuqing/nexus-core/actions/runs/43893638155) 与 [04 Software checks 43893666019](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43893666019) 均在约 2 秒内失败、`steps` 为空且无日志，仍是 GitHub 计费/额度阻断，非代码失败，待恢复后补跑。未新增图表缩放/导出/游标联动，未迁移真实库、部署、烧录或实机验收。

TELEMETRY-04 为[状态历史](console-ui.md)补图表交互：图内拖拽选区放大时间窗口（只放大已取回桶、不重查，纵轴按窗口内采样自适应，`clipPath` 裁掉窗外线段/故障点/游标，跨度小于 3 小时轴标签到秒），缩放后出现「重置缩放」，双击亦可恢复；「导出 CSV」按当前范围导出全部 18 列桶数据（UTF-8 BOM + CRLF，文件名 `telemetry-<设备>-<范围>-<UTC>.csv`），无点时禁用。无新增 API、迁移、MQTT 主题或遥测 Schema。`npm run check`（78 token）与构建通过；04 `test_management_ui.sh` 由 54 增至 59 项（CSV 下载与内容、拖拽缩放、缩放后悬停、重置），仍覆盖 390px 无横向溢出。`python3 tools/run_local.py build` 120 条中 115 通过、5 条条件跳过，V21 在隔离 PostgreSQL 17 应用成功并产出 jar。CORE `f13c4f5` 与 04 `a7de938` 已推送并核对远端；[CORE Docker CI 43908329517](https://github.com/liuyiwuqing/nexus-core/actions/runs/43908329517) 与 [04 Software checks 43908339990](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43908339990) 仍约 2–3 秒失败、`steps` 为空且无日志，属 GitHub 计费/额度阻断，待恢复后补跑。缩放是已取回桶的放大而非重新采样；未做多序列联动、真实库迁移、部署、烧录与实机验收。

TELEMETRY-05 补齐设备网络事实：固件 `svc_net` 新增只读 `pet_net_local_ip`/`pet_net_rssi`，联网时在遥测 summary 附带 `wifiIp`（IPv4）与 `wifiRssi`（dBm），未关联时省略，假传输返回不支持；`telemetry.schema.json` 增加可选字段与 IPv4/`-130..0` 约束并重新生成契约。V22 为样本表追加 `wifi_ip`/`wifi_rssi`，地址变化立即成条、信号波动随心跳，聚合桶取桶内最新非空地址与平均信号，非法地址或越界信号整条拒绝。管理台[实时快照](console-ui.md)显示「网络 · 地址 · dBm」，[状态历史](device-telemetry-history.md)新增网络指标画 RSSI，CSV 扩至 20 列。`DeviceManagementFlowTest` 新增 1 项真实 HTTP/PostgreSQL/MQTT 用例；完整构建 121 条中 116 通过、5 条条件跳过，V22 在隔离 PostgreSQL 17 应用并产出 jar；前端检查与构建、固件增量 `idf.py build`（0x2d81c0，29% 余量）、`test_host.sh` 与 `check_contracts.py` 通过。构建矩阵用例在中文 locale 下按英文 `dubious ownership` 断言失败，`LC_ALL=C` 重跑通过，属既有环境问题。未迁移真实库、部署或烧录，未验证真实 AP 切换与长期信号质量。CORE `8656303` 与 04 `f8c6610` 已推送并核对远端；[CORE Docker CI 43989886723](https://github.com/liuyiwuqing/nexus-core/actions/runs/43989886723) 与 [04 Software checks 43989890697](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43989890697) 仍在约 2 秒内失败、`steps` 为空且无日志，属 GitHub 计费/额度阻断，非代码失败，待恢复后补跑。

TELEMETRY-06 把网络信息带到设备列表：`GET /devices` 用 `LEFT JOIN LATERAL` 一次取回每台设备最近一条带地址的遥测（`wifiIp`/`wifiRssi`/`telemetryAt`），设备卡片在「最近连接」下显示「Wi-Fi 地址 · dBm · 相对时间」，无地址时整行省略；不新增表或迁移，设备列表仍是一次查询。`DeviceManagementFlowTest` 在既有网络用例中补列表断言，浏览器回归 62→63 项。完整构建 121 条中 116 通过、5 条条件跳过；前端检查与构建通过。未做逐台历史信号对比、掉线原因或列表排序扩展。CORE `2345764` 与 04 `862c466` 已推送并核对远端；[CORE Docker CI 43992251814](https://github.com/liuyiwuqing/nexus-core/actions/runs/43992251814) 与 [04 Software checks 43992263834](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43992263834) 仍在约 2 秒内失败、`steps` 为空且无日志，属 GitHub 计费/额度阻断，非代码失败，待恢复后补跑。

TELEMETRY-07 补齐设备网络质量：固件 `svc_net` 新增只读 `pet_net_ssid`/`pet_net_channel`/`pet_net_last_disconnect`，SSID 空口字节不是可打印 UTF-8 时整项省略，断线只记运行期、未对时只报原因不报时刻；遥测 Schema 增加四个可选字段并重生成契约。V23 为样本表追加 `wifi_ssid`/`wifi_channel`/`wifi_disconnect_reason`/`wifi_disconnected_at`，SSID、信道与断线原因变化立即成条，聚合桶取桶内最新非空网络信息，非法 SSID、越界信道或原因整条拒绝。管理台[实时快照](console-ui.md)显示网络名/地址/信道/信号与「上次断线」原因名称，[设备卡片](console-ui.md)显示 SSID 与地址，[状态历史](device-telemetry-history.md)的 CSV 扩至 24 列。`DeviceManagementFlowTest` 扩展既有网络用例覆盖四个新字段与两类非法值；完整构建 121 条中 116 通过、5 条条件跳过，V23 在隔离 PostgreSQL 17 应用并产出 jar；前端检查与构建、固件 `idf.py build`（0x2d84f0，29% 余量）、`test_host.sh`（`LC_ALL=C`）与 `check_contracts.py` 通过，浏览器回归 63→64 项。未迁移真实库、部署或烧录，未验证真实 AP 漫游、信道干扰与断线原因长期分布。CORE `05255ed` 与 04 `a6f2516` 已推送并核对远端；[CORE Docker CI 43995342794](https://github.com/liuyiwuqing/nexus-core/actions/runs/43995342794) 与 [04 Software checks 43995350148](https://github.com/liuyiwuqing/ai-desktop-pet/actions/runs/43995350148) 均在约 4 秒内失败、`steps` 为空且无日志，属 GitHub 计费/额度阻断，非代码失败，待恢复后补跑。

TELEMETRY-07 部署：2026-11-17 在本机真实环境（`nexus-core` Compose 项目、`nexus-core-postgres-1`/`nexus-core-mqtt-1`、127.0.0.1:8088）执行迁移与部署。依赖容器已不存在但保留 `nexus-core_postgres-data` 卷，先用 `docker compose --env-file data/local.env up -d` 按原卷重建并通过健康检查；迁移前版本为 V20。按升级口径以 `umask 077` 导出 `data/backups/nexus-before-v23-20261117-094358.dump`（86 KB、183 个 TOC 条目、权限 600），在临时库 `nexus_restore_check` 完整恢复并核对（Flyway 最高 V20、37 张表、无 `device_telemetry_samples`）后删除。停止旧 JAR 进程（PID 35397），以新构建的 `target/nexus-core-0.1.0-SNAPSHOT.jar` 启动，Flyway 自动应用 V21–V23，`flyway_schema_history` 三条均 success、schema 到 v23，新增四列与 CHECK 约束存在；`/api/v1/status` 的 database/mqtt 均 ready，管理台返回 200，`GET /devices` 含 `wifiSsid` 键，`GET /devices/dodo-1/telemetry?bucket=1h` 返回 200 与空点集。服务以独立会话后台运行（PID 48119，日志 `data/logs/core-20261117-094505.log`）；备份与日志均在忽略的 `data/`，未输出凭据。该记录只代表本机真实环境，不是目标服务器部署、真实设备上报或实机验收；GitHub CI 因计费/额度阻断的状态未变化。

管理台真实环境浏览器验收（ego-browser，2026-11-17）：管理员令牌登录成功；设备页显示 1 台设备/0 在线/从未连接，无遥测时不渲染 Wi-Fi 行；「设置与状态」显示“尚未收到设备状态”，状态历史各范围空态为“所选范围内没有采样点。”且「导出 CSV」禁用；切换到网络指标无错误；390px 视口无横向溢出；概览显示 0 在线/1 离线、今日收件 0、失败率 `—`。投递页仅有 1 条 2026-11-10 的 `ack_timeout` 历史失败，与本次迁移无关。未做真机上报、漫游与断线原因分布验收。


CONSOLE-21 按用户反馈继续压缩管理台留白并统一 Naive 表单/下拉：208px 侧栏、56px 页头、24px 桌面页边距、16px 卡片内距、34px 控件，设备设置满宽，手机短消息列表随内容收缩。`ConsoleForm`/`ConsoleField`/`formRules` 承接原约束与焦点，日期/分钟/毫秒 API、权限、原身份重试与 manifest 显式登记保留；`vueuc` 浮层/虚拟列表局部适配正式 CSP 的 nonce，不放宽策略。新增表单专项 35 次，21 组浏览器合计 848 次；类型/token/生产前端与完整 CORE 构建通过（121 条中 116 通过、5 条条件跳过），最终资源重打包后 23 份前端/V1–V23 均与源码输出逐字节一致。首轮两组脚本偶发登录连接失败，单独重跑通过，初因未确认；定位、复跑范围与截图入口见 [界面约定](console-ui.md)。配套 04 台账记录对应提交；本次不重启真实 CORE、不迁移库、不做真实消息/设备或部署验收。


CONSOLE-22 继续收紧消息页：默认只显示状态/来源，收件时间折叠并显示生效数量，草稿、UTC 查询、分页、导出与清空保持原契约。导航/消息行/图表缩放重置/历史评论导入/导出入口统一为 `NButton`，清理旧原生按钮视觉覆盖；导出打开时明确焦点归属，保留 Escape/外部点击关闭与正文确认。8 组实际 Vue/环回 RAM 浏览器共 328 次检查通过，含 12 页按钮、折叠保留草稿与 1440px/390px 明暗/CSP；类型/74 token/生产前端与完整 CORE 构建通过（121 条中 116 通过、5 条条件跳过）。最终 UI 经专项复跑并重打静态资源，JAR 的 23 份前端/23 份迁移逐字节一致；Vite 的入口略超 500 kB 为非阻断告警。本地 5173 预览持续运行，不重启真实 8088、不迁移真实库、不进行真实消息或设备验收。04 配套台账记录对应提交与远程 CI，详见 [界面约定](console-ui.md)。
