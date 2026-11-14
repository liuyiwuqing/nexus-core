# RSS / 天气拉取 · CONSOLE-06-B

RSS 2.0、Atom 1.0 与 Open-Meteo 室外天气接入现有收件箱和设备路由。连接器默认全部停用，地址、坐标、周期与设备范围由服务端配置，管理员可在接入页测试、启停、立即同步及查看最近状态/接收消息。operator/viewer 只读，不获得供应商网络操作或来源配置权限。

## 1Panel 配置

新初始化的 `core.env` 包含 `NEXUS_PULL_FEEDS_JSON=[]`。已有部署只在私有 `core.env` 增加/修改这一行，然后重建 CORE 容器使环境生效；不要重跑初始化或覆盖数据库、TLS、凭据文件。示例是一整行 JSON，Compose env 文件用单引号保留字面值：

```dotenv
NEXUS_PULL_FEEDS_JSON='[{"id":"blog-feed","name":"博客订阅","kind":"rss","url":"https://example.com/feed.xml","intervalMinutes":15,"deviceIds":["dodo-1"]},{"id":"outdoor","name":"室外","kind":"weather","latitude":31.2,"longitude":121.5,"intervalMinutes":60,"deviceIds":["dodo-1"]}]'
```

示例订阅地址需替换成自己确认的来源，坐标按需要修改。配置最多 16 个，id 为 1–24 位小写字母/数字/短横线且唯一；名称不超过 80，设备最多 32 个且启用前必须已登记。RSS 周期 15–1440 分钟，天气 60–1440 分钟。服务端 URL 只接受 HTTPS，不带 userinfo/fragment，最多 2048 字符；网络地址是服务端信任配置，管理 API 不接收任意 URL。不要把不可信输入填入服务端配置。HTTP 仅显式 `nexus.pull.allow-insecure=true` 的隔离测试使用，默认关闭。

天气固定请求 Open-Meteo forecast 的 `current` 温度、相对湿度及 WMO code，使用 UTC Unix 秒；不读取定位、不调用设备传感器，不覆盖 DHT11 或成长。默认免费端点用于个人非商业场景；商用/付费供应商需按其条件另行维护服务端接入。依据：[Open-Meteo API](https://open-meteo.com/en/docs)、[使用条件](https://open-meteo.com/en/terms)、[RSS 2.0](https://www.rssboard.org/rss-specification)、[Atom RFC 4287](https://www.rfc-editor.org/rfc/rfc4287)。消息附 Open-Meteo 来源和数据时间。

## 同步语义

配置内容生成独立指纹，状态按 id+指纹保存。改变地址、坐标、范围或周期后使用新的默认停用状态与首次基线；旧行保留，不能用旧配置的请求改变新配置。名称等配置也参与指纹。管理员确认重新启用后才读取，重启不丢失同一配置的启用/基线/去重状态。

首次成功同步仅记住当前条目，不补发历史；只读测试不建立基线。之后以 RSS guid（缺失时 link）或 Atom id 去重，天气每供应商小时一次。RSS/Atom 最多 100 条，缺失可用身份或格式错误则整批失败；正文 HTML 转纯文本，标题/正文裁剪到既有消息上限，链接只作为数据、不额外抓取。发布时间可解析且早于基线的条目不接收；没有日期的条目只能按“首次出现的身份”判断，不能保证其真实发布时间。订阅窗口之外的漏项不做追溯补齐。

每次最多 2 MiB、连接超时 3 秒/请求超时 6 秒，不跟随重定向。XML 禁止 DOCTYPE、外部实体、外部 DTD/schema、XInclude，限制深度 64。天气校验单位/数值和 24 小时内时间，过时/无效响应不生成新消息。后台单独工作线程每 30 秒选一个到期来源，不阻塞投递调度；数据库 30 秒租约防并发，网络返回后在同一事务复核启用状态/版本再写基线、去重、消息和投递。停用使在途读取结果作废，但不撤回已提交消息。

每来源手动同步至少间隔 60 秒；只读测试独立每分钟一次。失败不推进基线或去重，最近错误只记录固定代码，不记录 URL、正文或凭据；成功与消息/投递同事务。去重只存散列，不自动清理，每配置上限 20000 个身份，达到后停止新入库并提示维护，避免静默删除去重后重放旧内容。绑定设备与既有路由取交集，设备停用/离线/DND 遵循原逻辑。

## API 与页面

- `GET /api/v1/connectors/pull`：列表含版本、配置指纹、host、范围、周期、基线、最近尝试/成功/错误/新增数；不返回完整地址或坐标。
- 管理员 `PUT /{id}/enabled`：`{expectedVersion,configuration,enabled}`。幂等重试保持原状态/原版本；冲突 409，不自动反向切换。
- 管理员 `POST /{id}/probe`：`dryRun:true`、条目数及最多三条纯文本样例；不写消息、投递或状态/基线。该请求会访问已配置供应商。
- 管理员 `POST /{id}/poll`：`{expectedVersion,configuration}`，返回 `baseline/received`；停用/在途/配置冲突 409，过频 429，供应商失败 502。消息有身份去重，但此请求本身不是可重放的固定批次。

接入页启停未知结果保留原配置/版本和目标状态，导航可恢复，支持原请求重试或只读核对。同步未知结果只保留时间/来源提示并读取状态，不自动重试，也不把成功 HTTP 当作设备上屏。退出/401 清空会话。连接测试样例不持久化，离开页面丢弃；“查看接收消息”进入对应来源筛选。

## 验证与边界

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp -Dtest=PullConnectorsFlowTest test
python3 -m unittest tools/test_init_docker.py
python3 tools/run_local.py build
# 同级 04：实际 Vue、回环 RAM HTTP
bash tools/ci/test_pull_ui.sh
bash tools/ci/test_management_ui.sh
bash tools/ci/test_roles_ui.sh
```

公开接口先以 404 建立失败反馈，再验证默认停用、只读测试、基线/新 RSS/Atom、去重和设备交集；天气小时去重/来源标识/过时拒绝；DOCTYPE 不解析、重定向不访问目标、并发租约、角色拒绝以及停用丢弃迟到结果。测试时间仅调整隔离供应商/数据库夹具，不访问真实 RSS 或天气服务。页面专项覆盖断线启停、只读核对不重发、导航、角色、401 和窄屏。首轮完整构建 116 条中 111 通过/5 条条件跳过。随后补测 HTML 去除后空标题，先复现错误接受（200），修正为整批格式拒绝后最终专项 verify 3 项通过；最终前端类型检查/构建、JAR 三份资源逐字节/V20 核对通过；实际 Vue 新增 32 次、管理页 38 次、角色页 27 次回归通过，390px 截图已查看。初始化 CLI 3 项通过。

V20 只在隔离库迁移，生产来源和真实消息未操作。规范沉淀在本文、迁移与公开行为检查，无共享 Skill 变更。
