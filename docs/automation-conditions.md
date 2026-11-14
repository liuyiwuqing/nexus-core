# 自动化条件与动作 · CONSOLE-09-C

自动化提供三个明确组合：`message.received → analyze_public_comment`、`message.received → notify_device`、`schedule.daily → notify_device`。前两种可同时限制来源、事件类型、关键词和优先级；不接受任意脚本、网络 URL 或工具动作。

## 契约与兼容性

创建和停用后编辑的原 API 增加可选对象 `conditions`，示例：

```json
{"source":"wh.demo","eventType":"alert.created","keyword":"紧急","priority":"high"}
```

键只能是 `source/eventType/keyword/priority`，最多四项；空对象或空字符串不限。来源与类型精确匹配，关键词为标题及正文的区分大小写包含匹配，全部条件为 AND。来源最长 32、类型最长 64，二者限字母/数字/下划线/点/短横线；关键词最长 160；优先级只接受 low/normal/high/critical。每日规则不能附带非空条件对象。响应仍在 `arguments` 中返回对应 `sourceFilter/eventTypeFilter/keywordFilter/priorityFilter`，旧定义和无条件调用兼容，无新增迁移。

新消息提醒要求固定标题/正文，分别最多 160/8192 字符。规则默认停用；启用后只处理新事件。候选消息必须未过期且正文未清理；自动化产生的消息不会再次触发消息提醒，避免相互循环。公开评论分析仍只接受 Halo `public_comment`，动作入队继续执行原公开性与授权检查，条件不能放宽它。

同一规则版本/事件只生成一个运行，提醒用运行身份入库去重；原创建键、版本保护、停用/在途编辑删除限制、未知结果核对和执行历史保持原契约。条件参与创建指纹与编辑相等判断。规则预演仍是当前定义/设备候选检查，不消费示例事件，不执行动作，不承诺条件已经命中真实消息；路由仍独立决定设备范围。

## 使用与验证

自动化页选择“新消息条件 → 设备提醒”，填写固定提醒和所需条件；留空的条件不限制。保存后核对列表再启用。评论规则也可增加条件；每日规则只显示时间和时区。编辑保持原触发器/动作。断线后保留同一版本和参数，可显式重试或只读核对；导航保留，退出清空，整页刷新不会恢复草稿。

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp \
  -Dtest=AutomationConditionsFlowTest,ConsoleAutomationEditFlowTest,ConsoleOperationSubmissionFlowTest verify
# 同级 04：实际 Vue、RAM HTTP
bash tools/ci/test_automation_conditions_ui.sh
bash tools/ci/test_automation_edit_ui.sh
bash tools/ci/test_automation_lifecycle_ui.sh
```

新增真实 HTTP/隔离 PostgreSQL/调度 3 项，验证组合条件、同事件重放、停用编辑和原版本重试、未知字段/动作拒绝、私有内容不进入公开分析、无条件提醒不处理自身输出。早期测试按全局标题计数，新增第二规则后暴露跨例污染，改为本规则运行身份关联计数；一次误用数据库列名的检查失败后已按实际模式修正。没有调整生产行为来迎合测试。

新增 3 项与原创建/编辑共 17 项 HTTP/隔离库检查、Maven verify 退出 0；前端类型检查和构建通过，新增实际 Vue 检查 17 次，原编辑 99 次、删除预演 52 次回归通过，390px 截图已查看。

规范沉淀于此契约和公开行为测试；无共享 Skill 变更。真实消息、供应商、设备及生产库未操作，完整构建与远程 CI 另记当前台账。
