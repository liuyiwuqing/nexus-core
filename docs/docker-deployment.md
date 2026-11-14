# Docker 线上部署

使用 1Panel、希望直接拉取镜像时，请使用 [1Panel 镜像部署](1panel-deployment.md)。本页保留自行构建与独立 Caddy 的方案；其中 `format: raw` 不适用于用户已报告的旧版 1Panel 解析器。

管理台随 CORE 镜像提供，页面和 `/api/v1` 使用同一 HTTPS 域名。服务器运行 CORE、PostgreSQL、Mosquitto 和 Caddy 四个容器，ESP32 仍直接连接语音供应商；Mac 不参与日常运行。

本方案用于**新安装**。已有数据库/设备凭据不会自动导入，也不能用初始化工具覆盖。当前没有目标服务器，本页不代表已经上线。

## 准备

- Linux、Docker Engine、Docker Compose **2.30+**、Python **3.11+**、OpenSSL、Git。宿主无需安装 Java、Maven 或 Node。
- 一个 HTTPS 域名，例如 `pet.example.com`，以及设备 MQTT 主机名，例如 `mqtt.example.com`；两者可以指向同一服务器。DNS、80/443 TCP 和 8883 TCP 由部署者配置。80/443 用于 Caddy 自动 HTTPS；数据库 5432 和 CORE 8088 不公开。
- 将本仓受控版本放在 `/opt/nexus/src`。以下命令在该目录执行；仓库是私有的，访问权限由部署者提供。
- CORE 默认限制 1 GiB 内存；构建还需要 Node/Maven 的临时资源和网络。按目标机实际容量评估，未给出生产容量/负载结论。

Compose 位于 [deploy/docker/compose.yml](../deploy/docker/compose.yml)，与根目录的开发 Compose 独立。基础镜像均绑定实际镜像摘要；更新依赖时需要显式核对摘要并重跑验证。[启动依赖](https://docs.docker.com/compose/how-tos/startup-order/)使用数据库及 CORE 的健康条件；私有运行时 env 使用 [raw 格式](https://docs.docker.com/reference/compose-file/services/#env_file)，保留密钥中的 `$`、`#` 和引号，不能给值添加 shell 引号。

## 新安装

```sh
sudo install -d -m 700 /etc/nexus
sudo python3 tools/init_docker.py \
  --data-dir /etc/nexus/docker \
  --domain pet.example.com \
  --mqtt-host mqtt.example.com \
  --device dodo-1 \
  --image nexus-core:20261113
```

初始化会生成随机管理员、数据库、Broker 和设备凭据，创建 Broker 私有 CA/服务证书及设备作用域 ACL。目录为 700，env/设备配置/CA 私钥为 600；Broker 读取的证书/密钥仅在私有父目录中可读。服务证书同时包含外部 MQTT 主机名和容器 DNS `mqtt`。已有目录（含空目录、符号链接或失败留下的部分配置）一律拒绝，不旋转凭据。若初始化失败，先检查私有输出，再自行选择新的目录。

为后续操作定义同一 Compose 命令，避免误切项目导致数据库卷变化：

```sh
dc() {
  sudo docker compose -p nexus-production \
    --env-file /etc/nexus/docker/compose.env \
    -f /opt/nexus/src/deploy/docker/compose.yml "$@"
}
dc config --quiet
dc build core
dc up -d --no-build --wait --wait-timeout 180
dc ps
```

`Dockerfile` 在 Node 阶段执行 `npm ci`、类型检查与 Vue 构建，在 Maven/Java 21 阶段打包 JAR，最终仅保留 JRE/JAR。镜像打包跳过集成测试，不能把构建成功写成测试成功；独立验证见后文。`.dockerignore` 采用源码白名单，排除宿主 data/env、密钥、Git 历史及已有产物。镜像只在本机生成，本工具不上传镜像仓库。

访问 `https://pet.example.com`，用安全编辑器读取 `/etc/nexus/docker/core.env` 的 `NEXUS_ADMIN_TOKEN` 登录，不把文件内容打印到聊天或日志。令牌仍只保存在当前页面内存。Caddy 的公网证书申请需要正确 DNS 和可达端口，自动证书及账户状态持久化在专用卷；见 [Caddy 自动 HTTPS](https://caddyserver.com/docs/automatic-https)。

CORE 使用 UID/GID `65534:65534`、只读根文件系统和临时 `/tmp`；数据库只有私有容器网络入口，CORE 8088 仅供 Caddy 访问。Broker 只公开认证后的 TLS 8883，CORE 通过私有 CA 校验 `mqtt`。Docker 宿主管理员仍能读取容器环境，访问 Docker 权限应与这些凭据一起管理。

容器日志按每份 10 MiB、最多 3 份轮换；CORE 停止等待 30 秒。需要延长保留期或调整内存时由部署者按目标机监测结果修改，不把这些默认值当作生产容量保证。

Halo、AI、长期记忆、Memory MCP、Mac Bridge、消息清理、OTA 和成长镜像默认关闭。按对应功能文档配置后，执行 `dc up -d --no-build --force-recreate core` 使 env 生效；`restart` 不重新读取 env。容器内的 `localhost` 指向自身，外部服务必须使用服务器可达地址。`run_local.py` 专用 `*_CONFIG_FILE` 引用不会由 JAR 自动展开。

设备文件 `/etc/nexus/docker/device.json` 包含设备密码及 Broker CA，只通过既有受控配置流程交给对应设备；它不是固件镜像。登记同名设备不等于创建新 Broker 凭据。不要给所有设备共享 `nexus-core` 账号，不把 CA 私钥或管理员令牌交给设备。

## 健康、停止与故障

```sh
dc ps
dc logs --tail 100 core mqtt caddy
dc stop
dc up -d --no-build --wait --wait-timeout 180
```

CORE 健康检查在容器内调用受管理员鉴权的 `/api/v1/status`，只有 PostgreSQL 和 MQTT 同时 `ready` 才成功；凭据不进入命令参数/输出。检查支持标准管理员 env 及已有凭据文件的当前令牌。Broker 故障会使健康失败，恢复后 CORE 重连。`unhealthy` 本身不会触发 `unless-stopped` 重启；该策略处理进程退出/宿主重启，需由部署者监测健康状态。

使用 [凭据文件轮换](credential-rotation.md)时，额外以只读目录挂载该文件的父目录，再设置容器内绝对路径 `NEXUS_AUTH_TOKEN_FILE`。文件须由 UID `65534` 可读、权限 `600`，父目录不能被其他用户写；不能仅挂载单个文件，否则宿主原子替换可能不会反映到容器。默认方案使用 env，不会自动创建或更改既有凭据文件。

`depends_on` 只负责初始启动依赖，不能代替运行时恢复。CORE 故障时先核对数据库、Broker、证书、私有配置权限和容器日志；不要用删除卷解决启动问题。普通停止/容器重建保留 PostgreSQL、Caddy 数据卷。**生产环境不要执行 `down -v`。**

## 升级、备份与恢复

1. 记录当前 Git 提交、`NEXUS_CORE_IMAGE`、容器镜像 ID、Compose 项目名及三份卷名。保留旧镜像；本地 tag 不是不可变发布标识。
2. 在维护窗口先 `dc stop caddy core`，随后备份数据库。备份目录须预先存在且仅管理员可读：

   ```sh
   umask 077
   dc exec -T postgres pg_dump -U nexus -d nexus -Fc > /secure-backups/nexus-before-upgrade.dump
   ```

   同时备份 `/etc/nexus/docker`（含 CA、设备配置与凭据）、Caddy 数据/配置卷及版本信息；加密保存且不提交 Git。保留相同 Compose 项目名，避免误用新卷。
3. 在**独立测试库**恢复并验证备份后再升级。检出审阅过的目标提交，把 `compose.env` 的 `NEXUS_CORE_IMAGE` 改为新 tag，执行 `dc build core`、`dc up -d --no-build --wait --wait-timeout 180`，核对鉴权、列表、设备连接和日志。新 JAR 首次连接会执行 Flyway，禁止跳过已有迁移或自动清库。
4. 回滚前核对 schema 兼容性；涉及不兼容迁移时，需在停写状态将备份恢复到新的隔离卷/实例，再切换整套匹配版本。不能只切旧镜像并假定数据库可回退。恢复须同时保留原设备密码、CA 和 Broker ACL，重新初始化会让现有设备失联。

跨 CPU 架构时优先在目标 Linux 服务器构建。Apple Silicon 本机默认生成 `linux/arm64`；不能将它直接当作 `linux/amd64` 镜像。需要离线运送时先确定目标架构，再用对应构建与 `docker image save/load`，单独记录实际镜像 ID/哈希；本次没有发布 registry 镜像或构建生产离线镜像包。

## 本地验证

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/run_local.py build
docker build -t nexus-core:docker-validation .
python3 tools/test_docker_deployment.py \
  --image nexus-core:docker-validation --report target/docker-smoke.json
```

最后一条使用随机 `nexus-smoke-*` 项目、全新临时凭据/卷、127.0.0.1 随机端口和 `localhost` 私有 CA，检查真实 HTTPS/静态资源/鉴权、MQTT TLS、私有端口、非 root 只读进程、字面 env、容器重建保留消息、Broker 中断与健康恢复。没有注册设备，测试消息只留在隔离库。结束时仅删除该随机项目的卷/网络和自己的私有输出；失败诊断留在忽略的 `target/`，不读取真实部署配置。

[Docker workflow](../.github/workflows/docker-deployment.yml)在 Ubuntu 24.04 构建同一源码镜像并执行 CLI/真实 Compose 检查；只使用临时测试凭据，不发布镜像，也不连接目标服务器。远程状态需以具体运行记录核对，不能由本地结果推断。

实际执行结果及配套提交在 04 的 [任务台账](../../04-ai-desktop-pet/docs/18-requirements-and-progress.md)记录。源码构建、本地 Docker、远程 CI、公网 DNS/证书、目标机资源、真实供应商和设备验收分别判断。

2026-11-13 本地：CLI 11 项通过，前端类型检查/构建与 Maven verify 退出 0（67 条中 62 通过、5 条可选环境跳过，0 失败/错误）；实际源码 Docker 镜像为 `linux/arm64`，隔离 Compose 六组检查通过。报告和日志保留在忽略目录 `target/docker-smoke.json`、`target/docker-*.log`。初次 MQTT 严格校验发现新 CA 缺少 keyUsage，先由 `openssl verify -x509_strict` 复现，补齐新证书约束后通过；已有凭据/CA 未变。随后修正验证脚本读取列表主键为既有 `messageId`，重跑整套检查通过，没有修改业务 API。

CORE `9026e41` 已推送并核对远端；对应 [Docker CI 43398869944](https://github.com/liuyiwuqing/nexus-core/actions/runs/43398869944) 已完成且 `docker-compose` job/全部步骤 success，远程 CLI 11 项、源码镜像及六组实际 Compose 检查通过。该 workflow 不运行宿主完整 Maven 集成套件，62 通过/5 跳过仍是上述本地证据。04 配套部署索引/台账 `5f9c15c` 已推送；本段仅补 CI 记录，不改变已验证的实现或配置。
