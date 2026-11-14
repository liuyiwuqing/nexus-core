# 1Panel 镜像部署（DEL-07）

服务器只需要 Docker 和已有的 1Panel，不上传源码，不安装 Java、Node、Maven 或 Python。CORE 镜像包含 Vue 管理台与 API；独立的一次性初始化镜像生成该服务器自己的凭据、MQTT CA/证书和编排文件。现有源码构建/Caddy 方案继续见 [Docker 部署](docker-deployment.md)。

## 发布与读取镜像

在仓库默认分支运行 [Publish deployment images](../.github/workflows/publish-images.yml)：

```sh
gh workflow run publish-images.yml \
  --repo liuyiwuqing/nexus-core --ref codex/core-baseline
```

流水线先运行现有 CLI、源码镜像/HTTPS Compose 和新增的镜像初始化/1Panel 实际容器检查，通过后向 GHCR 发布：

- `ghcr.io/liuyiwuqing/nexus-core:sha-<完整提交号>`：管理台与 CORE。
- `ghcr.io/liuyiwuqing/nexus-core-init:sha-<相同完整提交号>`：一次性初始化工具。

两者均发布 `linux/amd64`、`linux/arm64` manifest。前端和 Java 字节码在构建机架构打包，JRE 与初始化工具按目标架构生成。发布后的独立 job 检查两种架构的 manifest，重新从 GHCR 按 digest 拉取镜像，在 Ubuntu amd64 上运行完整的镜像部署检查。成功后下载该次运行的 `published-image-verification` artifact，内含 `published-images.env`（两个已验证镜像的 digest，无密码）及检查报告。提交标签标识源码，运行时用 digest 固定实际镜像字节，不依赖 `latest`。

发布不会改变仓库或包的可见性。CI 使用该仓库短期 `GITHUB_TOKEN` 的 `packages: write/read`，无需发布 PAT。服务器使用具有对应包读取权限、`read:packages` 权限的 PAT classic 登录 GHCR，见 [GitHub 官方说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-to-the-container-registry)。在密码提示处输入令牌，不把它写进命令、编排或聊天：

```sh
docker login ghcr.io -u liuyiwuqing
```

在 1Panel 的镜像仓库设置中也添加 `ghcr.io` 与该读取账号/令牌，确保面板拉取时能认证。无需给服务器镜像写入或源码读取权限。

## 第一次部署

把已验证的 `published-images.env` 上传到服务器的私有工作目录，或在终端设置其中的 `NEXUS_CORE_IMAGE`、`NEXUS_INIT_IMAGE`。以 root 终端为例，域名改成自己的实际域名：

```sh
(
set -e
. ./published-images.env
install -d -m 700 /etc/nexus
docker pull "$NEXUS_CORE_IMAGE"
docker pull "$NEXUS_INIT_IMAGE"
docker run --rm --read-only --cap-drop ALL \
  --security-opt no-new-privileges:true --tmpfs /tmp \
  --mount type=bind,source=/etc/nexus,target=/etc/nexus \
  "$NEXUS_INIT_IMAGE" \
  --data-dir /etc/nexus/docker \
  --domain pet.example.com \
  --mqtt-host mqtt.example.com \
  --device dodo-1 \
  --image "$NEXUS_CORE_IMAGE"
)
```

初始化容器使用自身的 Python、OpenSSL 和 `mosquitto_passwd`，不挂载 Docker socket、不启动数据库、不连接设备或外部供应商。父目录在容器内外保持相同路径，使编排引用宿主机正确位置。已有目标目录（包括空目录、符号链接、失败残留）会拒绝初始化，不重新生成密码或证书；失败时保留私有输出，不要直接删除重跑。

在 1Panel「容器 → 编排」创建 `nexus-production`，粘贴 `/etc/nexus/docker/compose.1panel.yml` 的内容。该文件只引用私有文件路径，不含密码，可通过面板文件管理读取；不需要源码目录或构建操作。

生成文件是完整的三服务编排，无 `build`、`pull_policy: never`、`env_file.format`、Caddy 或 80/443 端口占用。已写入实际镜像与配置绝对路径，不依赖面板另行加载 `compose.env`。数据库仅在内部网络；CORE 只发布 `127.0.0.1:18088`；MQTT/TLS 默认发布 `8883`，暂不接设备时可删除 MQTT 的 `ports` 映射，之后按需恢复。

生成密码使用 URL-safe 字符，兼容普通 `env_file`。人工填写带 `$`、`#` 的第三方密钥时使用 Compose env 单引号值，例如 `NEXUS_AI_API_KEY='literal$TOKEN#"quoted"'`；不要把该写法复制到保留 `format: raw` 的源码构建配置或 `run_local.py` 配置中。

在 1Panel 为域名配置 HTTPS 反向代理。OpenResty 使用 host 网络时，上游为 `http://127.0.0.1:18088`；使用 bridge 网络时，`127.0.0.1` 指向 OpenResty 自身，需将其加入 CORE 的 edge 网络并代理到 `http://core:8088`，核对实际网络名与服务别名。不要为解决代理可达性直接把管理 API 发布到公网。

通过安全编辑器读取 `core.env` 中的 `NEXUS_ADMIN_TOKEN` 登录。AI、Halo、记忆、Bridge、清理、OTA 与成长同步默认仍关闭。设备配置在 `device.json`，不要把它或 CA 私钥粘贴到聊天。

## 更新与恢复

- 重启、更新和重新创建 CORE 时不再运行初始化镜像。保留原目录、凭据、CA、设备配置、编排项目名和 PostgreSQL 卷。
- 按 [既有备份步骤](docker-deployment.md)备份后，将编排中 CORE 的 `image` 改成下一次通过验证的 digest，拉取并重新创建 CORE。更新 `core.env` 后也需重新创建；单纯 restart 不重新读取 env。
- 不混用新初始化的数据库/MQTT 密码与原卷，不通过重新初始化修复连接。镜像回滚需同时核对数据库 schema 兼容性，更换镜像不等于数据库回滚。

## 验证与边界

本地入口：`python3 -m unittest discover -s tools -p 'test_*.py'`、`tools/test_docker_deployment.py` 和 `tools/test_image_deployment.py --image <CORE> --init-image <INIT>`。新增检查覆盖无源码/socket 初始化、私有权限/密码哈希、重复初始化原样拒绝、普通 env/特殊路径、三服务配置、认证/实际 JAR 资源、MQTT TLS 与重建持久性，仅清理自身随机项目和卷；测试端口随机绑定回环地址。

CI、镜像发布、从仓库拉取后的验证分别以实际运行和报告为准。目标 1Panel 版本、Debian DNS/防火墙、OpenResty 网络、域名 HTTPS、真实供应商及设备仍需目标机验收；流水线不自动连接用户服务器，不修改包的公开可见性。

2026-11-14 本地：CLI 11 项通过；CORE 镜像执行前端类型检查/构建与 Maven package 成功；原 Compose 六组检查、新增镜像部署七组检查全部通过，并清理本轮隔离容器/卷。新增七组使用实际 `linux/arm64` 镜像，包含 `$`/空格路径和单引号 env 原样解析。初始化镜像首次默认构建网络因 Alpine DNS 失败，host 网络构建通过；未修改源码中的软件源或全局网络设置。两份 Actions workflow 的 actionlint 1.7.12 检查通过。远程发布与回拉验证另记实际运行，不把本地镜像当作已发布产物。
