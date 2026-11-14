# 自动化规则删除与测试预演 · CONSOLE-09-B

自动化页为已停用、无在途入队任务的规则提供删除；同时支持对当前版本执行不产生动作的测试预演。条件与限定动作组合由 [CONSOLE-09-C](automation-conditions.md) 提供。

## API 与状态

- 管理员 `DELETE /api/v1/automation/rules/{id}?expectedVersion=<版本>`：成功 204。要求停用、版本匹配且没有 `executing` 任务。缺失/非法版本 400，未鉴权 401，不存在 404，版本/状态冲突 409。删除后相同原版本重试 204，其他版本写操作 410 `rule_deleted`。
- V15 新增 `deleted_at`。保留原 ID、创建去重键、定义和执行历史；版本加一，取消等待任务并记 `rule_deleted`。正常列表隐藏删除行，单条 GET 仍返回 `deleted: true` 便于核对；不能编辑、重新启用或预演。原创建键重放返回原 ID，不复活或新建规则。
- 删除与编辑/启停共用行锁；不强行撤销入队任务，不撤回已生成的消息、分析或投递。历史名称仍是规则当前名称，不是不可篡改审计。
- `POST /api/v1/automation/rules/{id}/preview`，请求 `{expectedVersion}`：返回 `dryRun: true`、同一快照的当前规则、候选启用设备数、阻塞原因和 `asOf`。不创建消息、事件、执行记录或分析任务，不调用 MQTT/Bridge，不消耗当天调度身份。候选数不代表上屏或已读；离线/免打扰继续由实际投递时的原策略处理。停用可预演，版本冲突 409、已删除 410。

## 页面行为

删除显示具体名称与历史保留影响，输入完全相同名称后提交。请求期间防重复，固定原 ID/版本。未知结果保留 App 内存提交，导航可恢复；支持只读核对和显式原版本重试，不自动更新版本。退出/401 清空，刷新/关闭整页后应先核对列表与历史。

预演明确显示版本和无动作边界，独立错误反馈，离开页面后丢弃迟到响应。编辑/删除互斥，加载/读取失败期间禁止状态动作。390px 确认表单无页面横向溢出。

## 验证

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -s .mvn/settings.xml -B -ntp \
  -Dtest=ConsoleAutomationEditFlowTest,ConsoleOperationSubmissionFlowTest test
python3 tools/run_local.py build
# 同级 04：实际 Vue + 回环 RAM HTTP
bash tools/ci/test_automation_lifecycle_ui.sh
bash tools/ci/test_automation_edit_ui.sh
```

新增 3 项 HTTP/隔离 PostgreSQL 专项先复现缺失路由，再验证版本/停用/在途保护、原创建键/历史保持、重复删除、删除后拒绝写入及预演不创建消息/执行记录。与既有编辑/创建共 14 项通过。页面新增 52 次检查通过，覆盖名称确认、重复点击、前后断线、冲突核对、导航、401、预演边界和 390px；已查看窄屏截图。页面测试只用 RAM HTTP 夹具，不代表真实 CORE 或设备。

规范沉淀：项目状态/保留契约在本文、V15 和测试维护，无需更新共享 Skill。生产数据库未迁移，真实规则、设备和目标机未操作；完整构建、既有页面回归和 CI 另记台账实际结果。
