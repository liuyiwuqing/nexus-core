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
| O9 | 错误文案映射：未知状态给中文与处置建议，保留状态码便于报障 | 不再出现裸 `HTTP 404` | P1 | 未开始 |
| O10 | 反馈吸顶（顶栏下），操作后 1 屏内可见 | 深层操作有结果感 | P1 | 未开始 |
| O11 | 统一 `StatusNote` 组件（info/warn/danger/busy/action 五态） | 5 个面板的未确认提示收敛为一种表达 | P1 | 未开始 |
| O12 | 分级刷新：按当前页请求 + SSE 驱动 + 可见性暂停 | 概览页 16s 37 次→约 4 次 | P1 | 未开始 |
| O13 | 面板异步加载（`defineAsyncComponent`） | 首屏 JS 471→约 300 kB | P1 | 未开始 |
| O14 | 设备设置面板内联到卡片下方 | 触发点与结果同屏 | P1 | 未开始 |
| O15 | OTA 安装按钮带设备名与目标摘要 | 减少误装版本 | P1 | 未开始 |
| O16 | token 守卫脚本接入 `run_local.py build` | 私有 hex 不再回潮 | P2 | 未开始 |
| O17 | 清理 `.primary/.secondary/.text-button/.compact` 空挂类（24 处） | 样式意图与实际一致 | P2 | 未开始 |

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
| CSS / JS | 21.66 / 470.20 kB | 26.60 / 471.07 kB |

JS 增加 0.87 kB 来自主题键；CSS 增加 4.94 kB 来自 token 定义与响应式规则，gzip 后 6.04 kB。

## 五、未落地与原因

- **代码分割与按页请求**（O12/O13）：会改动 `App.vue` 的请求时序、`readGeneration` 与分页纠正逻辑，需与跨仓浏览器断言一起回归，单独立项。
- **错误文案与反馈吸顶**（O9/O10）：`mutate()` 的未确认分支被 750 次跨仓断言覆盖，不能与样式批次混做。
- **`StatusNote`**（O11）：需在 5 个面板加 `app-error` 传参，属模板契约变更。
- **`confirm()` 二次确认**（OTA、成长结算）：本轮不加。OTA 重启保留复选框确认，成长"完成并结算"属正向完成，不用红色。
- **token 守卫脚本**（O16）：会给 build 增加失败面，下一批单独提交并接 `run_local.py`。

## 六、规范约定

1. **按钮层级**：主操作 `type="primary"`；次级 `class="secondary"`；文本动作 `quaternary + class="text-button"`。红色只给"删除 / 撤销 / 永久失效"三类不可回退动作，全站预算 5 处，新增需在此登记。
2. **空挂类禁令**：`.primary` / `.secondary` / `.text-button` / `.compact` 不新增任何样式定义（定义数应为 0），层级只由 Naive 的 `type` 与 `quaternary` 决定，避免红色按钮被 `.primary` 背景染回主色。
3. **表格**：需要卡片化的表格给中间列加 `data-label`，`:has(td[data-label])` 才启用；未标注的表格自动回退横向滚动。不要把"三张表全卡片化"当默认。
4. **dl**：默认两列网格（`minmax(96px,max-content) 1fr`），指标条与统计单元用 `display:block` 覆盖；不要再给 `dl>div` 加 `justify-content:space-between`。
5. **验收方式**：跨仓浏览器断言按"行为"写，不按条数写（12 个 `[data-tab]` 改文案会红），资源体积按 gzip 且留 10 kB 余量。

## 七、验证

```sh
npm --prefix frontend run check     # vue-tsc，退出 0
npm --prefix frontend run build     # 退出 0，CSS 26.60 kB / JS 471.07 kB
```

浏览器实测通过：12 个导航项 / 3 个分组完整；导出折叠与 Escape 归还焦点；390px 抽屉可开合并把焦点交给 `h1`；三档视口 `document.documentElement.scrollWidth` 均等于视口宽（无横向溢出）；Naive 样式节点带本页 nonce。

未执行：`tools/run_local.py build`（需独立 PostgreSQL/MQTT 与 Maven）、远程 CI、真实部署。上述结果仅为本地前端与环回夹具，不代表目标机与实机验收。
