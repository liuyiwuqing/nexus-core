#!/usr/bin/env python3
"""Create a local Bridge identity with an explicit project/application whitelist."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", action="append", default=[], metavar="ID=/absolute/path")
    parser.add_argument("--allow-app", action="append", choices=["codex", "idea", "clion", "webstorm"], default=[])
    args = parser.parse_args()
    projects = {}
    for value in args.project:
        name, separator, path = value.partition("=")
        root = Path(path)
        if not separator or not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", name) or not root.is_absolute() or not root.is_dir():
            parser.error("项目必须为 ID=/absolute/path，并且目录存在。")
        projects[name] = str(root.resolve())
    os.umask(0o077)
    data = ROOT / "data"
    data.mkdir(exist_ok=True, mode=0o700)
    path = data / "bridge.json"
    if path.exists():
        print("保留现有 Bridge 配置和白名单；未覆盖凭据。")
        return
    path.write_text(json.dumps({"token": secrets.token_urlsafe(36), "projects": projects, "applications": args.allow_app}, indent=2) + "\n")
    path.chmod(0o600)
    env = data / "local.env"
    if not env.exists():
        parser.error("请先执行 tools/init_local.py。Bridge 配置已生成，尚未启用。")
    text = env.read_text()
    keys = {line.partition("=")[0] for line in text.splitlines() if "=" in line}
    additions = {"NEXUS_BRIDGE_ENABLED": "false", "NEXUS_BRIDGE_URL": "http://127.0.0.1:8091/", "NEXUS_BRIDGE_CONFIG_FILE": str(path)}
    env.write_text(text + "\n# 本机工具 Bridge，凭据仅在 bridge.json 中保存。\n" + "\n".join(key + "=" + value for key, value in additions.items() if key not in keys) + "\n")
    env.chmod(0o600)
    print("已生成 data/bridge.json；未输出凭据，尚未启用。")


if __name__ == "__main__":
    main()
