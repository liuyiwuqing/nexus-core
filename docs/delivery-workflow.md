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
