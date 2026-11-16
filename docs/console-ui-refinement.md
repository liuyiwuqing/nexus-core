# 管理台 UI 细节优化：诊断、方案与首批落地

2026-11-16 · CONSOLE-15。本文记录 nexus-core 管理台（`frontend/`，Vue 3 + Naive UI 2.45.3）在本轮 UI 细化的诊断结论、优化项与优先级、首批发版内容。产品功能、权限、请求契约与整体设计方向不变。

## 一、方法与证据

在真实构建产物上做只读验收，不接数据库、Broker 与设备：

```sh
npm --prefix frontend run build                     # 产物写入 target/frontend
python3 /tmp/nexus_ui_probe.py --frontend target/frontend --port 18841   # 环回 RAM 夹具
```

浏览器取 1440×900、1024×1000、390×844 三档真视口，采集截图、元素盒模型与 network 条目。结论均来自实测，未使用估算。

边界：夹具返回固定数据，不验证 CORE 排序、分页上限与权限分支；本轮的远程 CI、真实部署与实机视觉终验分别记录，不能互相替代。

## 二、现状主要问题

### P0 布局与可用性

1. **消息双栏高度失控**：33 条消息使 `.inbox-layout` 高 3482px、页面 3956px；分页按钮在 y≈3900，翻页需滚过 30 行空白；未选中时 `.detail-placeholder` 垂直居中在 y≈2000，首屏看不到"选择一条消息"。
2. **移动端表格操作列不可达**：`.table-shell > table` 固定 `min-width:540px`，390 视口下"操作"列被推出可视区，容器可横滑但无任何提示。
3. **错误文案泄漏技术细节**：未知错误码回退为 `请求失败（HTTP 404）`，出现在自动化页与权限页；错误横幅在内容区顶部，长页面滚动后不可见。
4. **页头三段式**：顶栏 68px + 设备视图工具栏 56px + 页面标题，移动端上方固定占用近 200px；页面主操作（如"发送测试消息"）在标题右侧，次级操作（接入引导/刷新）在另一条工具栏，动作层级不稳定。
5. **状态表达不一致**：消息页有骨架屏，投递/设备/概览只有一行灰字；空态区块高度 320~340px 只有一行字；危险操作与普通操作同为绿色主按钮。

### P1 视觉规范

6. **分隔线 5 套色**（#dce5e0 / #e7ede8 ×6 / #e8eee9 / #e5ece7 / #edf1ee），控件描边 2 套（原生 #cbd8d0、Naive #dce5e0）。
7. **字号 15 种、圆角 11 种**，无级差；12px 占 29 处，11px 5 处，小字偏多。
8. **`dl` 两端对齐在大宽度下失效**：`.memory-metrics` 标签与数值间距 400~850px，`.connector-data` 同问题。
9. **指标数字不统一**：概览条 30px、卡片内 20px、记忆页沿用列表排版。
10. **正文灰对比度偏低**：`--muted` #64756f 在 canvas 上 4.75:1。
11. **焦点环不达标**：Naive 默认 20% alpha，叠白底仅 1.24:1，低于焦点指示 3:1。
12. **危险操作无视觉区分**（删除规则、撤销令牌、删除路由/模板、轮换凭据）。

### P2 性能与流程

13. 单 chunk JS 470.20 kB（gzip 142.94 kB），12 个面板全部同步 import。
14. 空闲请求过多：停在概览页 16 秒产生 37 次 `/api/v1/*`（messages/devices/connectors/deliveries/status 各 6 次），与该页无关。
15. 每次刷新清空列表造成闪烁；按钮文案在"刷新/刷新中…"间切换导致宽度抖动。
16. 操作结果只出现在内容区顶部，深层操作（设备开关、重试、导出）看不到反馈。

## 三、优化项与优先级

| 编号 | 优化项 | 预期效果 | 优先级 | 状态 |
|------|--------|----------|--------|------|
| O1 | 消息双栏改为定高、列表与详情各自滚动，分页吸底 | 页面 3956→928px；分页进首屏；空态可见 | P0 | 已完成 |
| O2 | ≤760 表格卡片化，`data-label` 作开关 | 操作列回到视口内；无标签表格保持滚动 | P0 | 已完成 |
| O3 | 顶栏/工具栏吸顶，三行共用 `--gutter` 栅格 | 长列表滚动不丢上下文；左缘对齐 | P0 | 已完成 |
| O4 | 统一分隔线、控件描边、焦点环 | 分隔线 5→1；控件一套语言；焦点 4.88:1 | P0 | 已完成 |
| O5 | `dl` 改两列网格，指标数字收敛 | 标签与数值间距 400~850px→16px | P0 | 已完成 |
| O6 | 危险操作改 `type="error"` | 删除/撤销/轮换与普通提交可区分 | P0 | 已完成 |
| O7 | 字阶 9 档、圆角 5 档、卡片内边距与间距归一 | 视觉节奏统一，去掉无意义级差 | P0 | 已完成 |
| O8 | `--muted` 提到 #57675f | 正文灰对比度 4.75→5.55（canvas）/5.98（白底） | P0 | 已完成 |
| O9 | 失败文案统一出口 `failure.ts`：未知码给处置建议并保留 `HTTP 状态 · 码`，网络层 `TypeError` 不再原样显示 | 不再出现裸 `HTTP 404` 或 `Failed to fetch` | P1 | 已完成 |
| O10 | 反馈吸顶（顶栏下），操作后 1 屏内可见 | 深层操作有结果感 | P1 | 未开始 |
| O11 | 统一 `StatusNote` 组件（info/warn/danger/busy/action 五态） | 5 个面板的未确认提示收敛为一种表达 | P1 | 未开始 |
| O12 | 分级刷新：按当前页请求 + SSE 补齐外壳依赖 + 可见性暂停 | 概览页 16s 15→3 次；消息页 20→8 次 | P1 | 已完成 |
| O13 | 面板异步加载 + 导航意图预取 + 骨架占位 | 首屏 JS 471.07→323.88 kB（−31%），其余按页 2.8–49.3 kB | P1 | 已完成 |
| O14 | 设备设置面板内联到卡片下方 | 触发点与结果同屏 | P1 | 未开始 |
| O15 | OTA 安装按钮带设备名与目标摘要 | 减少误装版本 | P1 | 未开始 |
| O16 | token 守卫脚本接入 `run_local.py build` | 私有 hex 不再回潮 | P2 | 未开始 |
| O17 | 清理 `.primary/.secondary/.text-button/.compact` 空挂类（24 处） | 样式意图与实际一致 | P2 | 未开始 |
| O18 | 横向滚动表格补 `tabindex="0" role="region" aria-label` | 键盘可进入并横滚全部列 | P0 | 已完成 |

## 四、首批落地内容

`style.css` 建立 token 层并把全部硬编码值收敛进来，`theme.ts` 与之一一对应：

- 颜色：surface / surface-subtle / surface-hover / surface-disabled、ink / ink-2 / muted / placeholder / faint、border / border-strong / border-hover / divider、accent 四态 + soft、success / warning / danger / info 各带 soft/border。
- 圆角 5 档（6/8/12/16/pill），字阶 9 档（11/12/13/14/16/18/20/24/30）+ 正文阅读 15 + 计时器 56。
- 层级：`--z-sticky-topbar:5` / `--z-sticky-feedback:7` / `--z-dropdown:10`。
- 输入框静止态 `--border-strong`、悬停 `--border-hover`、焦点 `--focus` 不透明环；Naive 的 `Select` 走 `peers.InternalSelection`，`DatePicker` 走 `peers.Input`（顶层 `Select.border` 是空操作，会静默保持默认主题）。
- 危险态：`common.errorColor` 系列 + 5 处确认按钮改 `type="error"`（删除规则、撤销令牌、删除路由、删除模板、轮换来源凭据）。

度量对照（1440×900，夹具 33 条消息）：

| 指标 | 改前 | 改后 |
|------|------|------|
| 页面总高 | 3956px | 928px |
| 分页位置 | y≈3900（不可达） | y=844（首屏） |
| 空详情提示 | y≈2000 | 视口居中可见 |
| 分隔线色种数 | 5 | 1 |
| 硬编码 hex（除装饰） | 40+ | 0 |
| 圆角种类 | 11 | 5 |
| 字号种类 | 15 | 9（+2 专职） |
| 390 投递表 | 540px，操作列出屏 | 356px 卡片式，操作可见 |
| CSS / JS | 21.66 / 470.20 kB | 26.70 / 181.62 kB |
| 首屏 JS（含共享 `Button` chunk） | 470.20 kB | 323.88 kB |

JS 由主题键增加 0.87 kB，再由代码分割降到 181.62 kB；CSS 增加 5.04 kB 来自 token 定义与响应式规则，gzip 后 6.08 kB。

## 五、第二批：按页请求与代码分割（O12/O13）

### 刷新分三档，不再"一次全站拉通"

`refresh(clearFeedback, resources?)` 改为按需集合，`resources` 缺省取当前标签页的 `tabResources`：

| 触发源 | 取的集合 | 说明 |
|--------|----------|------|
| 5 秒轮询、页面可见、切换标签页、操作后回读 | `tabResources[tab]` | 只取当前页渲染的接口 |
| SSE `invalidate` | `tabResources[tab]` + `devices` | 事件不说明变了什么，设备清单是外壳级依赖（顶栏"设备视图"），必须一并补齐 |
| 顶栏刷新、错误重试、登录 | 全量 5 个接口 | 用户主动要求，允许贵 |

`loading` 只在实际取数据时置位：纯 `status` 探测不占用它，否则顶栏每 5 秒闪一次"刷新中"。返回体按需赋值，未请求的集合保持上一次的值；分页纠正改为两个列表分别判断、统一重取一次。

筛选与搜索的守卫从 `loading.value || busy || exporting` 收窄为 `busy || exporting`：`loading` 由 `readGeneration` 独占竞态保护，原来的写法会让 5 秒窗口内的点击被静默吞掉。

实测（概览夹具，单钩子统计 `fetch`，16000ms 窗口）：

| 页面 | 每次刷新请求数 | 16 秒内 refresh 请求数 |
|------|----------------|------------------------|
| 概览 | 5 → 1（仅 status） | 15 → 3 |
| 消息 | 5 → 2（messages + status） | 20 → 8 |
| 投递 | 5 → 2 | 15 → 6 |
| 设备 | 5 → 2 | 20 → 8 |

概览页自身的数据由 `OverviewPanel` 自己每 5 秒取一次，不计入上表。

### 面板按需加载

- `src/panels.ts` 集中存放 14 个 `() => import()` 加载器，`asyncPanel(name)` 统一产出 `defineAsyncComponent`（`delay: 200` 内不闪骨架、`timeout: 20000` 给出失败态而不是永久空白）。`App.vue` 只保留 `ConsoleShell` 与默认页 `InboxPanel` 同步加载。
- 预取走"意图"而非"后台全量"：导航项 `@pointerenter` / `@focus` 触发 `prefetchTab`，`watch(tab)` 再补一次（键盘与程序化切换）。悬停预取与页面加载共用同一份 `import()`，打包器按模块 id 去重。
- `PanelSkeleton.vue` 只在 chunk 慢于 200ms 时出现，占位高度贴近真实页面，`aria-busy="true"` + `.sr-only` 说明，`prefers-reduced-motion` 下停止脉冲。
- `ConsoleShell.focusContent()` 改为有限次轮询等待 `.content h1`（最多 60×20ms）并用 token 让后一次导航作废前一次：异步面板挂载晚于一次 `nextTick`，原来"登录后焦点进入 h1"的约定会静默失效。

### 跨仓浏览器断言的调整

面板异步化后，"点导航 → 下一句就找按钮"不再成立。三处调整只改等待方式，不改断言内容：

1. 20 个 UI 脚本的 `expect()` 统一改成 `js(expr).catch(() => false)`：表达式在元素尚未渲染时抛 `TypeError`（如 `.find(...).disabled`），此前会直接中止脚本，现在按"尚未就绪"重试，超时后仍以原 label 失败。
2. 3 个脚本里单发的 `tap()` 改为有限次重试（`test_management_ui.sh`、`test_message_compose_ui.sh`、`test_list_filters_ui.sh`）。
3. `test_roles_ui.sh` 的 `nav()` 增加"页面已挂载"门（`.content h1` 存在）：该脚本有一串"只读角色看不到某按钮"的否定断言，页面没渲染时会假通过。

回归结果：20 个控制台 UI 脚本、779 条断言全部通过。

### 已知取舍

- 非设备类页面（消息/投递/概览等）的设备下拉依赖 SSE 失效或进入设备页才会更新；顶栏下拉只显示设备名，不含在线态，因此不构成状态误导。
- `EmptyOutDir` 每次构建会删掉上一轮的 hashed 资产，本机沙箱对单轮批量删除有阈值，连续跑第三个脚本才构建时会被拦下；这是环境限制，不是构建失败。

## 六、未落地与原因

- **错误文案与反馈吸顶**（O9/O10）：`mutate()` 的未确认分支被 750 次跨仓断言覆盖，不能与样式批次混做。
- **`StatusNote`**（O11）：需在 5 个面板加 `app-error` 传参，属模板契约变更。
- **`confirm()` 二次确认**（OTA、成长结算）：本轮不加。OTA 重启保留复选框确认，成长"完成并结算"属正向完成，不用红色。
- **token 守卫脚本**（O16）：会给 build 增加失败面，下一批单独提交并接 `run_local.py`。

## 七、规范约定

1. **按钮层级**：主操作 `type="primary"`；次级 `class="secondary"`；文本动作 `quaternary + class="text-button"`。红色只给"删除 / 撤销 / 永久失效"三类不可回退动作，全站预算 5 处，新增需在此登记。
2. **空挂类禁令**：`.primary` / `.secondary` / `.text-button` / `.compact` 不新增任何样式定义（定义数应为 0），层级只由 Naive 的 `type` 与 `quaternary` 决定，避免红色按钮被 `.primary` 背景染回主色。
3. **表格**：需要卡片化的表格给中间列加 `data-label`，`:has(td[data-label])` 才启用；未标注的表格自动回退横向滚动。不要把"三张表全卡片化"当默认。
4. **dl**：默认两列网格（`minmax(96px,max-content) 1fr`），指标条与统计单元用 `display:block` 覆盖；不要再给 `dl>div` 加 `justify-content:space-between`。
5. **验收方式**：跨仓浏览器断言按"行为"写，不按条数写（12 个 `[data-tab]` 改文案会红），资源体积按 gzip 且留 10 kB 余量。
6. **卡片化覆盖**：三张表（投递、最近执行、操作审计）已给中间列加 `data-label`，`≤760` 全部转卡片行；新增表格若不标注 `data-label`，将自动回退横向滚动并由 O18 的 `role="region"` 提供键盘入口。
7. **焦点与标题**：抽屉关闭后 `focusContent()` 只取 `.content h1`，不得放宽为 `.content h1, .content h2`（会把焦点退到区块标题）；`.section-heading` 上不再叠加 `:focus-visible` 轮廓（全局 `button/a/input/textarea/select` 已覆盖，重复样式会在非交互容器上画出焦点框）。

## 八、验证

```sh
npm --prefix frontend run check     # vue-tsc，退出 0
npm --prefix frontend run build     # 退出 0，CSS 26.70 kB / 首屏 JS 181.62 + 共享 Button 142.26 kB，另按页 14 个 chunk
```

浏览器实测通过：12 个导航项 / 3 个分组完整；导出折叠与 Escape 归还焦点；390px 抽屉可开合并把焦点交给 `h1`；三档视口 `document.documentElement.scrollWidth` 均等于视口宽（无横向溢出）；Naive 样式节点带本页 nonce；三张表格容器具备 `tabindex="0"`、`role="region"` 与 `aria-label`。

下一轮建议加一条"标题随手写就能成立"的指纹断言（按 `tabId → h1 文本` 对 12 页逐项核对），这样标题回退到 `h2` 或文案漂移会立刻红，而不是等用户发现；`focusContent()` 只认 `h1`，该断言是它的兜底。

跨仓回归（`04-ai-desktop-pet/tools/ci`）20 个控制台脚本 / 779 条断言全部通过，含 `test_console_theme_ui.sh`、`test_overview_ui.sh`、`test_events_ui.sh`、`test_list_filters_ui.sh`、`test_management_ui.sh`、`test_roles_ui.sh` 与 4 个自动化脚本。

未执行：`tools/run_local.py build`（需独立 PostgreSQL/MQTT 与 Maven）、远程 CI、真实部署。上述结果仅为本地前端与环回夹具，不代表目标机与实机验收。
