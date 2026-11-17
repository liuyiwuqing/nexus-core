# 独立角色令牌与统一操作审计 · CONSOLE-10

按用户确认的首版方案保留现有管理员 Bearer/文件轮换，新增服务端维护的 operator/viewer 令牌，不引入账号密码或 SSO。V18 仅在隔离测试库验证，真实管理员、来源与设备凭据不自动更换。

## 权限

| 范围 | admin | operator | viewer |
| --- | --- | --- | --- |
| 列表、状态、消息内容、历史、元数据导出 | 允许 | 允许 | 允许 |
| 消息创建/已读/分析、投递取消/重试 | 允许 | 允许 | 拒绝 |
| 自动化与路由/模板规则 | 允许 | 允许 | 只读与无动作预演 |
| 设备、OTA、工具、成长写入、Halo 同步、来源维护/凭据 | 允许 | 拒绝 | 拒绝 |
| 角色签发/撤销、统一审计、清理 | 允许 | 拒绝 | 拒绝 |
| Memory MCP、来源签名写入 | 原有独立作用域 | 不能代替 | 不能代替 |

服务端过滤器逐请求校验，403 不终止有效会话；撤销、到期或无效凭据 401。POST 只读会话/记忆检索与明确定义的预演属于只读白名单；不因方法是 POST 自动开放。未知新写接口对非管理员默认拒绝。管理员文件损坏仍 fail closed 为 503，不以角色令牌绕过文件配置错误。角色令牌不能创建另一个管理员令牌，也不拥有对真实设备的隐式控制权。

`POST /api/v1/session` 验证当前 Bearer 并返回 `{actor,role}`，用于显式登录审计；`GET /session` 只读当前身份。页面导航和写入口根据同一角色隐藏/禁用；服务器仍是最终校验。CONSOLE-26 的浏览器会话与维护契约如下。

## 角色令牌维护

- 管理员 `GET /api/v1/access/tokens` 只返回 ID/名称/角色/版本/到期/撤销时间，无令牌/hash。
- `POST /access/tokens`：`{name,role:operator|viewer,expiresAt}`，到期必须未来且不超过 366 天；服务端生成 32 随机字节的 URL-safe token，数据库仅存 SHA-256，响应只显示一次。
- `POST /access/tokens/{id}/revoke`：`{expectedVersion}`；立即撤销未来请求，原版本重复撤销 204，冲突 409。不会中断已经鉴权并开始的在途动作。
- `PUT /access/tokens/{id}`：`{expectedVersion,name,role,expiresAt}`，只允许编辑仍有效的角色令牌；保持 ID/hash/创建时间，版本加一。名称最多 80 字符、角色仅 operator/viewer、到期未来且最多 366 天；权限变更在后续请求立即生效。
- `POST /access/tokens/{id}/rotate`：`{expectedVersion}`，保留 ID/角色/有效期/名称，仅更换服务端随机凭据和 hash，版本加一；旧 Bearer 和依赖它的会话失效，新值仅在本次响应出现。过期/撤销令牌不能复活，缺失 404、版本冲突/失效 409。重新生成响应未知时保留待核对状态，切页不会解除；先刷新核对版本，再显式继续，不能自动重放。
- 页面撤销和重新生成须输入名称。签发响应未知时先核对列表、撤销无法取得的令牌，再显式开始新签发，不自动重放创建。角色令牌默认不签发、无全局通用密钥。

## 浏览器登录与管理员令牌 · CONSOLE-26

登录默认选中“保持登录 8 小时”。`POST /session {remember:true}` 签发服务端内存会话，响应在身份之外包含 `{persisted:true,csrfToken,expiresAt}`；原始长期令牌不写入 Cookie/localStorage/sessionStorage，浏览器通过 host-only `nexus_console_session` HttpOnly/SameSite=Strict Cookie（Path=/api/v1）关联 32 随机字节的会话 ID。非回环主机始终加 Secure；本机 HTTP 回环用于开发，外部浏览器使用 HTTPS。无 remember 或 false 保持原 Bearer 接口兼容，清除本浏览器旧 Cookie，页面关闭/刷新后重新输入。

刷新先调用 GET /session，确认身份后恢复页面和 SSE；所有 Cookie 请求都重新核验原始管理员/角色凭据，角色变更、撤销、轮换和到期不会沿用旧权限。会话绝对期限 8 h，不按访问续期；单进程最多 1024 个，过期懒清理；CORE 重启会清空会话，需要重新登录，不引入数据库迁移或分布式会话。配置暂不可用 503 保留 Cookie，登录页提供重试；无效会话 401 清除 Cookie。读取不增加显式登录审计记录。

Cookie 的 POST/PUT/PATCH/DELETE 请求必须提供该会话独立的 `X-Nexus-CSRF`；缺失、重复或不匹配 403。明确提供 Bearer 时以该 header 为准，不能靠 Cookie 绕过错误/重复 header。Webhook 和 `/mcp/memory` 从不接受 Cookie；原机器 Bearer、记忆工具独立凭据与来源签名边界保留。SSE 定期检查原凭据、会话期限和撤销；CSV 导出同样支持会话。设计参考 [OWASP 会话管理](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) 和 [CSRF 同步令牌](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。

显式 `POST /session/logout` 立即使当前服务端会话失效并清除 Cookie。退出/401 清除当前页数据和草稿；读响应含 epoch 检查，过时响应不能带回身份。浏览器 storage 只保存无秘密的退出意图，网络中断时也不在刷新后擅自恢复；退出结果未知明确提示。BroadcastChannel 只传登录/退出事件，不传令牌，其他同源标签页清空旧数据并重新验证身份。草稿、幂等 ID、一次性令牌不会跨刷新持久化。

管理员 `GET /access/admin-token` 仅返回 `{mode,editable,revision?}`。环境变量模式明确提示服务端配置，不能假装改写环境；不可写文件禁止修改。`PUT /access/admin-token {expectedRevision,currentToken,newToken}` 要求最新 revision、当前 current 管理员令牌、新值 32–256 位无空白 ASCII 且不与任何现有 admin/memory/角色凭据（包括失效角色）复用。页面可生成随机新值、重复确认，并要求先保存；不在响应/日志/审计中返回新旧管理员秘密。持有 previous 过渡令牌不等于通过 current 证明。

更新沿用 [受保护凭据文件](credential-rotation.md)，与 CLI 共享 POSIX 记录锁、修订校验和同目录原子替换，保留 owner/group、0600、稳定 adminActor 和完整 memory scope。零过渡替换 admin 并移除其 previous，其他管理员凭据/会话立即失效；当前 Cookie 会话换发新的 ID/CSRF，当前页面内存登录则更换内存 Bearer。编辑组件被切走后仍由 App 接管成功凭据；失联不自动重放，使用已保存的新值核对。修改不影响已经鉴权开始的业务动作。

## 审计与保留

统一记录显式登录、API 写入/预演请求与导出，包括被拒绝的尝试。字段只有随机审计 ID、稳定服务端 actor、角色、HTTP 方法、过滤后的路径、开始/完成时间和 HTTP 结果；不记录请求头、令牌、Cookie、正文、query、参数或响应。密钥、数据库 URL 与消息文本不进入审计，依据 [OWASP 日志数据排除建议](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#data-to-exclude)。

写动作前先插入审计开始记录，插入失败返回 503 且不执行动作；响应完成后再记录状态。动作与审计完成并非同一数据库事务；崩溃或完成写入失败会保留未完成记录，表示需要核对结果，不能把它当作动作失败或自动重试。记录 HTTP 状态不等于设备已展示、上游已采用或真实执行成功。现有设备/OTA/工具的业务审计仍保留，不被本表替代。

管理员 `GET /api/v1/audit?page=1&role=…` 每页 100，按开始时间降序。`GET /audit/retention` 只读预览 90 天以前已完成记录；显式 `POST /audit/retention {confirm:"DELETE_EXPIRED_AUDIT"}` 每次最多删除 200 行，未完成记录保留。不启用自动清理，不触碰真实数据库。本表是运维审计，不宣称防篡改或账号实名归责；持有同一管理员令牌的人共享管理员身份。

## 验证

真实 HTTP/独立 PostgreSQL 的角色、撤销/到期、审计正文/凭据排除、保留预览/确认和批量边界检查，配合既有管理员轮换/Webhook 专项；实际 Vue + RAM HTTP 的 `test_roles_ui.sh` 覆盖三种角色的入口、只读保留、一次性凭据/名称确认、审计、390px。完整构建、通过数量和 CI 以 04 台账为准。

完整构建曾复现过滤器被 `@Transactional` 代理导致 GenericFilterBean 初始化 logger 为空；改用显式事务，不对 servlet Filter 创建代理后重跑。登录 401 后提示被 epoch 清理吞掉也已由原管理页回归复现修复。生产库、真实角色签发/撤销、清理和设备均未操作。项目授权契约沉淀在本文、迁移与检查，未改共享 Skill。

CONSOLE-26 本地最终验证：完整 verify 128 项（123 通过/5 条件跳过），鉴权/会话/文件轮换/SSE/CSP 专项 21 项、CLI 4 项、真实非空稳定 actor 复核单项通过；实际 Vue 23 组 1098 条，新增登录/维护 60 条。真实本地 Vite/CORE 已验证会话恢复、CSRF 拒绝、Cookie SSE、管理员编辑状态及退出，未修改真实秘密或角色令牌。配套脚本/证据边界/提交与 CI 见 04 的 CONSOLE-26 台账。
