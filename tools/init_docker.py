#!/usr/bin/env python3
"""Initialize a NEW private Docker deployment; never overwrite an existing installation."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from init_local import write

ROOT = Path(__file__).resolve().parents[1]


def hostname(value):
    if len(value) > 253 or any(not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", label)
                               for label in value.split(".")):
        raise argparse.ArgumentTypeError("use a hostname without scheme, path or port")
    return value.lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", required=True, type=Path)
    parser.add_argument("--domain", required=True, type=hostname, help="Public HTTPS console domain")
    parser.add_argument("--mqtt-host", required=True, type=hostname, help="Public device-facing MQTT hostname/IP")
    parser.add_argument("--device", default="dodo-1")
    parser.add_argument("--image", default="nexus-core:local", help="Local image tag; no registry upload")
    args = parser.parse_args()
    data = args.data_dir.expanduser().absolute()
    if any(c in str(data) for c in "'\n\r"):
        parser.error("data directory cannot contain apostrophes or line breaks")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", args.device) or args.device == "nexus-core":
        parser.error("invalid device id")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,200}", args.image):
        parser.error("invalid image tag")
    if data.exists() or data.is_symlink():
        parser.error("destination already exists; preserve it and choose a new directory")
    if not data.parent.is_dir():
        parser.error("parent directory must already exist")
    os.umask(0o077)
    data.mkdir(mode=0o700)
    # Reuse the versioned TLS/ACL generator. A failed initialization stays private
    # and is never silently regenerated over partial credentials on a later run.
    try:
        subprocess.run([sys.executable, str(ROOT / "tools/init_local.py"), "--data-dir", str(data),
                        "--host", args.mqtt_host, "--device", args.device, "--broker-dns", "mqtt"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        env = dict(line.split("=", 1) for line in (data / "local.env").read_text().splitlines()
                   if line and not line.startswith("#"))
        env.update({
            "NEXUS_BIND_ADDRESS": "0.0.0.0", "NEXUS_PORT": "8088",
            "NEXUS_DB_URL": "jdbc:postgresql://postgres:5432/nexus",
            "NEXUS_MQTT_URI": "ssl://mqtt:8883", "NEXUS_MQTT_CA_CERT": "/etc/nexus/mqtt-ca.crt",
            "NEXUS_MQTT_ALLOW_INSECURE": "false", "NEXUS_MQTT_CLIENT_ID": "nexus-core",
            "NEXUS_AUTH_TOKEN_FILE": "", "NEXUS_MEMORY_MCP_ENABLED": "false", "NEXUS_MEMORY_MCP_TOKEN": "",
            "NEXUS_RETENTION_ENABLED": "false", "NEXUS_OTA_ENABLED": "false", "NEXUS_GROWTH_SYNC_ENABLED": "false",
            "NEXUS_HALO_URL": "", "NEXUS_BRIDGE_CONFIG_FILE": "",
        })
        for name in ("NEXUS_AI_CONFIG_FILE", "NEXUS_AI_CONFIG_FORMAT", "NEXUS_MQTT_BIND_ADDRESS"):
            env.pop(name, None)
        write(data / "core.env", "# Private container environment; do not source or commit.\n" +
              "\n".join(key + "=" + value for key, value in env.items()) + "\n")
        write(data / "postgres.env", "POSTGRES_DB=nexus\nPOSTGRES_USER=nexus\nPOSTGRES_PASSWORD=" + env["NEXUS_DB_PASSWORD"] + "\n")
        write(data / "compose.env", "# Keep with the matching deployment; no runtime secrets here.\n" +
              f"NEXUS_DOCKER_DATA='{data}'\nNEXUS_CONSOLE_DOMAIN={args.domain}\nNEXUS_CORE_IMAGE={args.image}\n")
        (data / "local.env").unlink()
        (data / "bridge.json").unlink()
    except (OSError, ValueError, subprocess.SubprocessError):
        raise SystemExit("initialization failed; private partial output preserved, do not reuse this directory") from None
    print("已生成私有 Docker 配置、TLS/ACL 和设备配置；未输出凭据。")
    print("使用 deploy/docker/compose.yml 与该目录的 compose.env 启动；操作见 docs/docker-deployment.md。")


if __name__ == "__main__":
    main()
