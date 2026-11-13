#!/usr/bin/env python3
"""Prepare an existing local installation for an ESP32 on a private LAN; keep credentials and CA."""
import argparse
import datetime
import ipaddress
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="This Mac's private IPv4 address reachable from ESP32")
    args = parser.parse_args()
    try:
        host = ipaddress.ip_address(args.host)
        if host.version != 4 or not host.is_private or host.is_loopback or host.is_unspecified:
            raise ValueError()
    except ValueError:
        parser.error("host must be a private LAN IPv4 address")
    data = ROOT / "data"
    pki = data / "pki"
    certs = data / "mosquitto/certs"
    env_path = data / "local.env"
    if not env_path.exists() or not (pki / "ca.key").exists():
        parser.error("run tools/init_local.py first")
    os.umask(0o077)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    extension = pki / "server-lan.ext"
    extension.write_text(f"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:{host}\nextendedKeyUsage=serverAuth\n")
    new_cert = pki / "server-lan.crt"
    subprocess.run(["openssl", "x509", "-req", "-in", str(pki / "server.csr"), "-CA", str(pki / "ca.crt"),
                    "-CAkey", str(pki / "ca.key"), "-CAcreateserial", "-days", "825", "-sha256",
                    "-extfile", str(extension), "-out", str(new_cert)], check=True, capture_output=True)
    device_path = data / "device.json"
    device = json.loads(device_path.read_text())
    device["brokerUri"] = f"mqtts://{host}:8883"
    original_env = env_path.read_text()
    values = {"NEXUS_MQTT_URI": f"ssl://{host}:8883", "NEXUS_MQTT_BIND_ADDRESS": str(host)}
    lines = []
    for line in original_env.splitlines():
        key = line.partition("=")[0]
        lines.append(f"{key}={values.pop(key)}" if key in values else line)
    lines.extend(f"{key}={value}" for key, value in values.items())
    # 保留可人工恢复的副本，不轮换管理员、数据库、设备密码或 CA。
    for path in (env_path, device_path, certs / "server.crt"):
        backup = pki / (path.name + "." + stamp + ".bak")
        backup.write_bytes(path.read_bytes())
        backup.chmod(0o600)
    (certs / "server.crt").write_bytes(new_cert.read_bytes())
    (certs / "server.crt").chmod(0o644)
    device_path.write_text(json.dumps(device, ensure_ascii=False, indent=2) + "\n")
    env_path.write_text("\n".join(lines) + "\n")
    device_path.chmod(0o600)
    env_path.chmod(0o600)
    print("已更新局域网地址和服务端证书，保留了原配置副本；未自动重启服务。")
    print("应用配置：docker compose --env-file data/local.env up -d --force-recreate mqtt；随后重启 CORE。")


if __name__ == "__main__":
    main()
