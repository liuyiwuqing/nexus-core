#!/usr/bin/env python3
"""Create isolated local credentials and a TLS broker configuration; never print secrets."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
from broker_policy import MANAGEMENT_ACL

ROOT = Path(__file__).resolve().parents[1]


def run(*command):
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def write(path, text, mode=0o600):
    path.write_text(text)
    path.chmod(mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="localhost", help="Broker hostname/IP reachable from the ESP32; included in its certificate")
    parser.add_argument("--device", default="dodo-1")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", args.device) or args.device == "nexus-core":
        parser.error("invalid device id")
    if not re.fullmatch(r"[a-zA-Z0-9.-]{1,253}", args.host):
        parser.error("invalid host")
    os.umask(0o077)
    data = ROOT / "data"
    data.mkdir(exist_ok=True, mode=0o700)
    env_file = data / "local.env"
    if env_file.exists():
        print("保留现有 data/local.env 和凭据；未覆盖配置。")
        return
    pki = data / "pki"
    config = data / "mosquitto/config"
    certs = data / "mosquitto/certs"
    for directory in (pki, config, certs):
        directory.mkdir(parents=True, exist_ok=True)
    config.chmod(0o755)
    certs.chmod(0o755)
    try:
        ipaddress.ip_address(args.host)
        extra_san = "IP:" + args.host
    except ValueError:
        extra_san = "DNS:" + args.host
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "3650",
        "-subj", "/CN=NEXUS Local CA", "-keyout", str(pki / "ca.key"), "-out", str(pki / "ca.crt"))
    run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + args.host,
        "-keyout", str(certs / "server.key"), "-out", str(pki / "server.csr"))
    extensions = pki / "server.ext"
    write(extensions, "subjectAltName=DNS:localhost,IP:127.0.0.1," + extra_san + "\nextendedKeyUsage=serverAuth\n")
    run("openssl", "x509", "-req", "-in", str(pki / "server.csr"), "-CA", str(pki / "ca.crt"),
        "-CAkey", str(pki / "ca.key"), "-CAcreateserial", "-days", "825", "-sha256",
        "-extfile", str(extensions), "-out", str(certs / "server.crt"))
    write(certs / "ca.crt", (pki / "ca.crt").read_text(), 0o644)
    for name in ("server.key", "server.crt"):
        (certs / name).chmod(0o644)  # Host data/ is 0700; broker UID 1883 reads this bind mount.
    core_password, device_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    passwd = config / "passwd"
    write(passwd, f"nexus-core:{core_password}\n{args.device}:{device_password}\n")
    # Encrypt the file in place; secrets are not passed in process arguments.
    run("docker", "run", "--rm", "--entrypoint", "mosquitto_passwd", "-v", f"{config}:/config",
        "eclipse-mosquitto:2.0.22", "-U", "/config/passwd")
    passwd.chmod(0o644)
    write(config / "acl", MANAGEMENT_ACL, 0o644)
    write(config / "mosquitto.conf", """listener 8883
allow_anonymous false
password_file /mosquitto/config/passwd
acl_file /mosquitto/config/acl
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
tls_version tlsv1.2
max_packet_size 4096
message_size_limit 2048
max_queued_messages 32
persistence false
""", 0o644)
    write(data / "device.json", json.dumps({
        "schemaVersion": 1, "deviceId": args.device, "brokerUri": "mqtts://" + args.host + ":8883",
        "username": args.device, "password": device_password, "caCert": (pki / "ca.crt").read_text(),
    }, ensure_ascii=False, indent=2) + "\n")
    write(data / "bridge.json", json.dumps({"token": secrets.token_urlsafe(36), "projects": {}, "applications": []}, indent=2) + "\n")
    env = {
        "NEXUS_ADMIN_TOKEN": secrets.token_urlsafe(36), "NEXUS_DB_PASSWORD": secrets.token_urlsafe(32),
        "NEXUS_DB_URL": "jdbc:postgresql://127.0.0.1:54329/nexus", "NEXUS_DB_USER": "nexus",
        "NEXUS_MQTT_URI": "ssl://localhost:8883", "NEXUS_MQTT_USERNAME": "nexus-core",
        "NEXUS_MQTT_PASSWORD": core_password, "NEXUS_MQTT_CA_CERT": "data/mosquitto/certs/ca.crt",
        "NEXUS_MQTT_BIND_ADDRESS": "127.0.0.1",
        "NEXUS_HALO_ENABLED": "false", "NEXUS_HALO_URL": "https://blog.muyin.site",
        "NEXUS_HALO_TOKEN": "", "NEXUS_HALO_CONNECTOR_ID": "halo-muyin",
        "NEXUS_AI_ENABLED": "false", "NEXUS_AI_AUTO_ENABLED": "false", "NEXUS_AI_BASE_URL": "",
        "NEXUS_AI_API_KEY": "", "NEXUS_AI_MODEL": "", "NEXUS_MEMORY_ENABLED": "false",
        "NEXUS_MEMORY_URL": "http://127.0.0.1:1933/", "NEXUS_MEMORY_API_KEY": "",
        "NEXUS_MEMORY_ACCOUNT": "default", "NEXUS_MEMORY_USER": "default", "NEXUS_MEMORY_PEER": "nexus-pet",
        "NEXUS_BRIDGE_ENABLED": "false", "NEXUS_BRIDGE_URL": "http://127.0.0.1:8091/",
        "NEXUS_BRIDGE_CONFIG_FILE": str(data / "bridge.json"),
        "NEXUS_AI_CONFIG_FILE": "", "NEXUS_AI_CONFIG_FORMAT": "openviking", "NEXUS_AI_PROXY": "",
    }
    write(env_file, "# 本机私有配置；不要提交或复制到聊天。\n" + "\n".join(k + "=" + v for k,v in env.items()) + "\n")
    print("已生成 data/local.env、TLS 证书、Broker ACL 和 data/device.json；未输出任何凭据。")
    print("启动依赖：docker compose --env-file data/local.env up -d")


if __name__ == "__main__":
    main()
