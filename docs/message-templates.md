# 消息模板、变量预览与能力约束 · CONSOLE-08

V19 提供独立服务端模板管理和路由挂接，默认路由/消息不使用模板。模板只作用于新投递；来源原消息保持原文，设备收到的标题/摘要/卡片类型由创建投递时的模板版本确定。TEMPLATE-DISPLAY-01 起，非缺省的声音/时长随 payload 的可选 `display` 发送；缺省仍等价于 `sound:none`、`durationMs:8000`，不改变旧模板的线格式。

## 模板与接口

定义 `{name,title,summary,icon,sound,durationSeconds}`，变量为 `{source}`、`{eventType}`、`{title}`、`{content}`、`{summary}`、`{sender}`。严格验证未知变量/残缺花括号；只单次替换，不把消息内容再解析为模板，不支持表达式/脚本/HTML。渲染中间缓冲有界，标题 96、摘要 180 UTF-8 字节，控制字符与截断沿用 `DeviceText.fit`。

设备软件契约现支持 `sound ∈ {none,chime,alert}` 与 `durationSeconds ∈ [5,30]` 整数秒；五类卡片 text/comment/alert/growth/system 仍映射为既有 eventType 前缀。设备把配置 `message.durationMs` 作为展示时长上限：模板时长在上限内生效，超过上限时按上限显示；短于上限时按模板值显示。缺省组合 none/8 秒不发送 `display`，旧固件收到新旧模板的既有消息仍可解析；使用非缺省声音/时长的投递要求设备先升级到支持 `display` 的固件，否则该投递会被拒收。服务端只记录并在投递时发送这些值，不探测已连接设备的实际能力或固件版本。

- `GET /api/v1/templates`：模板列表、变量清单和当前能力约束。
- `PUT /templates/{UUID}`：`{expectedVersion,definition}`；版本 0 固定 UUID 创建，更新使用观察版本，相同原版本和定义重试可确认，冲突 409。
- `GET /templates/{UUID}`：单条读回，删除标记仍可核对。
- `DELETE /templates/{UUID}?expectedVersion=…`：标记删除并加版本，原版本重复删除幂等。被未删除路由引用时 409 `template_in_use`，须先解除引用。
- `POST /templates/preview`：`{definition,message:MessageInput}`，返回符合设备软件能力约束的 `{title,summary,eventType,sound,durationMs,version:0}`，无消息/投递/外部动作。

管理员和 operator 可维护，viewer 只读/预览，服务端权限与页面一致。路由定义增加可空 `templateId`，省略保留原行为；相同目标被多条规则命中时仍由第一条匹配决定模板与优先级。定向测试 `/routing/test` 也支持可空模板 ID，纳入固定请求身份比对。

## 版本、删除与正文保留

投递持久化模板 ID/版本、渲染后的标题/摘要/事件类型快照，以及声音/时长快照（V28 可空列）；修改或删除规则/模板不重新渲染旧投递，定向测试的幂等重放也保留首次快照。引用检查使用模板行锁与路由事务，不能删除仍被引用的定义。删除保留身份，不复活 UUID。

渲染快照属于源消息正文，V19 数据库触发器在 `content_purged_at` 写入同一事务清除标题/摘要快照；元数据删除时随投递删除。不会通过模板副本延长已清理消息的正文保留。模板本身的用户输入/固定文案作为配置维护，不视作逐消息的额外副本。

## 页面与验证

页面提供 CRUD、变量说明/示例预览、能力限制和路由模板选择；删除输入名称确认。草稿、固定 UUID/原版本及未知结果在 App 当前内存跨导航保留；可只读核对/原版本重试，退出/401 清空，不存浏览器持久存储。预览按文本呈现，不执行模板变量内容。

`MessageTemplateFlowTest`、`ConsoleRoutingFlowTest`、`RetentionFlowTest` 共 13 项真实 HTTP/隔离 PostgreSQL 检查通过，覆盖变量/非法能力（含非法声音与 4/31 秒）/字节上限、路由挂接、快照不随模板更新、定向测试声音/时长快照、引用删除保护和源正文清理时快照清除。实际 Vue 的 `test_templates_ui.sh` 验证导航/保存断线/重试/提示音与时长/预览/路由选择/删除/401/窄屏，具体通过数、完整构建与 CI 另记任务台账。

生产模板、真实消息/设备和数据库未操作；声音在真实喇叭上的音量/音色与设备实际展示时长未验收，服务端也不探测旧固件能力。实现契约在本文与可执行测试维护，无需修改共享 Skill。
