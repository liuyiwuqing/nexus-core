# 管理台界面与组件约定

2026-11-16 · CONSOLE-14。保持 Vue 3 / TypeScript / Vite 与随 JAR 发布的方式，使用 Naive UI 2.45.3 和 `@lucide/vue` 1.73.0。字体和图标不依赖外部 CDN，版本由 package-lock 固定。

## 界面与使用

- 十二个入口分为“日常”“消息与自动化”“系统”，管理员、操作员、只读角色的可见范围沿用原约定；默认仍进入消息页。
- 桌面保留侧栏；760px 以下使用可滚动抽屉，支持 Escape、焦点约束和关闭后返回菜单按钮。选择页面后关闭抽屉并聚焦页面标题。
- 消息保留列表/详情联动，手机选择消息后显示详情并提供返回；筛选与导出分开组织，“导出”内保留元数据下载和管理员正文确认。未应用的筛选、读取错误、空结果与导出中继续禁止导出。
- 设备卡片统一在线标记、接收开关、设置和固件入口；登记、重命名的版本核对、禁用新消息和 OTA 确认流程保留。界面状态不代表设备已收到或完成操作。
- 所有页面动作按钮使用 Naive UI；登录和消息编辑使用 NInput。其他字段保留原生校验与日期输入，不改请求字段、时区转换或服务端分页。
- 登录令牌仍仅保存在页面内存。消息、工具、规则和模板的未知结果、原请求身份、草稿与只读核对保持；关闭或刷新页面的恢复边界不变。

## 样式基线（CONSOLE-15）

`style.css` 的 `:root` 为唯一 token 来源，`theme.ts` 与之逐值对应（颜色/圆角/字阶/描边/焦点环）；诊断、度量对照与未落地清单见 [console-ui-refinement.md](console-ui-refinement.md)。

- 按钮层级只由 Naive 的 `type` 与 `quaternary` 决定；`.primary/.secondary/.text-button/.compact` 定义为 0，不新增样式。红色仅用于"删除 / 撤销 / 永久失效"三类不可回退动作，全站 5 处。
- 表格卡片化以 `data-label` 为开关（`:has(td[data-label])`），未标注的表格回退横向滚动。`dl>div` 默认两列网格，指标条用 `display:block` 覆盖。
- 跨仓浏览器断言按行为写，不按条数写。

## 取数与加载（CONSOLE-16）

- 轮询只取当前标签页渲染的接口（`App.vue` 的 `tabResources`）；SSE 失效额外补 `devices`，因为顶栏"设备视图"是外壳级依赖；顶栏刷新、错误重试、登录走 `allResources`。新增页面必须同时登记 `tabResources`，否则该页数据不会轮询。
- `refresh()` 的第二个参数是集合而不是布尔；`loading` 只在实际取数据时置位，纯 `status` 探测不占用它。筛选/搜索的守卫用 `busy || exporting`，竞态统一由 `readGeneration` 负责。
- 面板一律通过 `src/panels.ts` 的 `asyncPanel(name)` 引入，不直接写 `defineAsyncComponent(() => import(...))`；新增面板要同时登记 `loaders` 与 `tabPanels`，否则意图预取拿不到。
- `focusContent()` 按有限次轮询等 `.content h1`：面板是异步挂载的，一次 `nextTick` 不足以拿到标题。

## 样式守卫（CONSOLE-17）

`npm run check` = `vue-tsc --noEmit && node scripts/check-tokens.mjs`（单跑 `npm run check:tokens`）。四条规则：

1. `style.css` 的 hex 只允许出现在 `:root` 定义里；
2. 组件的 `<style>` 块里不允许出现任何 hex；
3. `var(--x)` 必须已在 `:root` 定义（带 fallback 也算）；
4. 定义过的 token 必须有人引用。

失败即退出 1，`run_local.py build` 与 20 个跨仓 UI 脚本都会因此中断。本轮据此删掉 5 个死 token（`--accent-hover`、`--accent-pressed`、`--info`、`--info-soft`、`--radius-pill`），现存 53 个。

`.primary/.secondary/.text-button/.compact` 保持零字节定义：视觉由 Naive 的 `type`/`quaternary` 决定，这几个类只作模板与断言的语义 hook，不做样式扩展。

## 修改位置

`frontend/src/theme.ts` 管理 Naive UI 主题；`style.css` 管理布局与已有原生字段；`navigation.ts` 保存导航分组和图标；`ConsoleNavigation.vue` 复用角色导航；`ConsoleShell.vue` 管理桌面框架和移动抽屉。App 继续拥有请求、权限会话与待确认提交，界面组件不另建 API 或持久化副本。

颜色以现有深绿为强调色，正文使用深色中性色；动作层级、字段边框、面板和间距统一。移动适配改变布局，保留字段、操作后果和错误信息；尊重减少动态效果偏好。

## 静态页 CSP

Naive UI 会生成样式节点。`GET /` 和 `GET /index.html` 为每次响应生成独立 24 字节随机 nonce，写入页面 meta 与 `style-src 'self' 'nonce-…'`，并返回 `Cache-Control: no-store`。原 `default-src 'self'`、禁止嵌套、base-uri/form-action 与 API 鉴权保留，没有增加 unsafe-inline 或外部脚本来源。

`NConfigProvider` 开启 `inlineThemeDisabled`，通过其公开 `styleMountTarget` 仅为 Naive 挂载的样式节点加 nonce；不修改全局 DOM 方法。开发用 Vite 没有服务端 nonce 时使用默认样式目标。正式 JAR 必须同时包含新 index/JS/CSS 与配套过滤器；不能只替换其中一部分。

## 验证

```sh
# CORE：TypeScript、生产前端、Maven/独立 PostgreSQL 与 MQTT、JAR
python3 tools/run_local.py build
# 配套 04 工程：真实 Vue + 回环 RAM HTTP + 与正式页面一致的 CSP
bash tools/ci/test_console_theme_ui.sh
# 异步面板要求断言先等待再判断，20 个控制台脚本需整体回归
```

沿用 04 的消息发送、设备/记忆、筛选/CSV、投递时间线、OTA/成长、角色、SSE/引导、工具/规则、路由、来源和模板等浏览器入口。新增检查覆盖导航完整性、SVG、每次独立 nonce、样式实际生效、导出折叠/键盘关闭、390px 抽屉与焦点、菜单范围和无 CSP 违规；后端 `ConsolePagePolicyTest` 校验静态页 nonce/缓存及资源原策略。

实际结果与配套提交见 04 的 [当前台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)。浏览器样例不接生产数据库、Broker、云端或设备；本地构建与页面检查不能代替目标机部署和用户最终视觉验收。旧镜像与不可变离线包保留原版本。

本轮本地结果：原 19 组浏览器共 750 次、新增主题/导航 21 次检查通过；完整 CORE 118 条中 113 通过、5 条可选环境跳过，CLI 11 项通过。最终类型检查/生产前端与重新打包退出 0；JAR 恰含三份当前前端资源，20 份迁移逐字节匹配，无旧哈希资源混入。远程 CI 与部署按对应提交分别记录。
