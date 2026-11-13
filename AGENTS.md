# CORE 交付约定

- 保持 Java 21 / Spring Boot、PostgreSQL、MQTT/TLS 与现有 Vue 管理台结构；先读 README 及受影响模块源码。
- 用户要求每项工作同步文档、测试/构建、提交和推送后继续下一项，不需逐项确认。交付约定见 `docs/delivery-workflow.md`；产品任务台账位于同级 `04-ai-desktop-pet/docs/18-requirements-and-progress.md`。
- 跨仓契约变更同步固件 Schema、设备实现、ACL、管理 UI 与联调；不得把收到请求当作设备动作已完成。
- 使用 `python3 tools/run_local.py build` 或 `test`，Maven 使用 `.mvn/settings.xml`；测试采用独立数据库/Broker，不迁移真实数据库。
- `data/`、真实配置、凭据、私钥和构建输出不入库。保留未授权的已有修改，不重置/清理工作区，不强推。
- 本地集成、GitHub CI、目标机部署、真实供应商和实机验收分别记录。当前不部署真实服务器、不烧录设备。
