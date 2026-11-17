# 本地凭据轮换

2026-11-13。可选 `NEXUS_AUTH_TOKEN_FILE` 启用管理员/记忆工具独立的 current + previous，旧值 UTC 到期，最长过渡 24 h。每次授权重读受保护文件；损坏或不安全更新返回固定 503，不使用旧缓存/环境后备。环境模式保留，初次切换文件模式需要正常重启一次，后续更新不重启。

完整格式、部署权限、命令、撤销与验收边界见配套 [04 文档 20](../../04-ai-desktop-pet/docs/20-credential-rotation.md)。本项目入口：

```sh
python3 tools/credential_store.py init --file /private/absolute/tokens.json
python3 tools/credential_store.py status --file /private/absolute/tokens.json
python3 tools/credential_store.py rotate --file /private/absolute/tokens.json --scope memory --expected-revision 1 --grace-seconds 300
python3 tools/credential_store.py export-memory --file /private/absolute/tokens.json --output /private/client-directory/memory.json
```

初始化读取受保护环境中的原管理员/可选 memory token，并保存原管理员身份哈希。工具任务使用该固定身份，轮换与服务重启不改变既有任务归属或幂等范围。CLI 不接受秘密参数、不打印秘密，拒绝覆盖已有 store、使用文件锁/修订检查与原子写入；不要手改身份或恢复旧秘密文件来“回滚”。

stdio 可选 `NEXUS_MEMORY_MCP_TOKEN_FILE`，只读导出的独立客户端文件；每次请求读取，不自动重试或重放。桥接用户不得读取整个 CORE 凭据文件。真正撤销当前泄露值用零过渡 rotate；revoke 仅移除 previous。现有任务已通过授权的请求不会因撤销而被强行取消。

验证：`python3 tools/test_credential_store.py` 4 项；标准 `python3 tools/run_local.py build` 59 条中 55 通过、4 条条件跳过，含 7 项真实 HTTP/PostgreSQL/文件/CLI 流程与第二服务实例的任务归属检查。配套 stdio 7、启动 6、TLS 桥接 6、交付 10 项通过；真实配置、部署和设备凭据没有更新。

## 管理台修改管理员令牌 · CONSOLE-26

“权限 → 修改管理员令牌”支持已有受保护文件模式；环境变量不会被网页直接改写。首次启用仍由服务端用上方 init 命令保留原凭据/身份，并配置 NEXUS_AUTH_TOKEN_FILE 后正常重启。要使用网页修改，文件和父目录需要 CORE 运行用户可写；read-only 挂载继续通过 CLI 维护。不要因此放宽 0600、目录权限或向浏览器暴露文件。

网页要求 current 证明和 expectedRevision，零过渡替换 admin 并移除 previous，保留完整 memory scope 和稳定身份；当前浏览器会话自动换发，其他旧凭据后续请求失败。新值只能由用户输入或在本地页面生成并保存；响应和审计不返回秘密。CLI 与 Java FileChannel 统一使用 POSIX record lock（fcntl.lockf），跨进程互斥，不将 flock 与 record lock 叠加；部署时同步更新 CLI，不混用旧版本并发写同一文件。API、会话寿命、未知结果与验证入口见[权限契约](roles-and-audit.md#浏览器登录与管理员令牌--console-26)。
