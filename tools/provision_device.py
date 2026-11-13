#!/usr/bin/env python3
"""Send only the NEXUS namespace configuration over USB; never replace a whole NVS partition."""
import argparse
import json
from pathlib import Path
import re
import time
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Exclusive USB serial port, e.g. /dev/cu.usbmodem...")
    parser.add_argument("--config", type=Path, default=ROOT / "data/device.json")
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    uri = urlparse(config.get("brokerUri", ""))
    if uri.scheme != "mqtts" or not uri.hostname or uri.hostname in ("localhost", "127.0.0.1", "::1"):
        parser.error("设备需要可访问的局域网 Broker 地址，请先运行 tools/configure_lan.py --host <Mac局域网IP>")
    device = config.get("deviceId", "")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", device) or config.get("username") != device:
        parser.error("invalid device identity")
    command = ("NEXUS_PROVISION " + json.dumps(config, ensure_ascii=True, separators=(",", ":")) + "\n").encode()
    if len(command) > 8223:
        parser.error("configuration exceeds USB protocol limit")
    try:
        import serial
    except ImportError:
        raise SystemExit("需要 pyserial；可在已激活的 ESP-IDF Python 环境中运行本脚本。")
    # 不通过进程参数、日志或终端回显传递设备密码。
    port = serial.Serial(baudrate=115200, timeout=0.2, write_timeout=5, exclusive=True)
    port.dtr = False
    port.rts = False
    port.port = args.port
    with port:
        port.reset_input_buffer()
        port.write(command)
        port.flush()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            line = port.readline()
            if b"NEXUS_CONFIG_SAVED" in line:
                print("NEXUS 配置已持久保存，设备将重启。请在管理台核对在线状态；这不代表消息已送达。")
                return
            if b"NEXUS_CONFIG_ERROR" in line:
                raise SystemExit("设备拒绝了配置或 NVS 写入失败，原有数据保留。")
    raise SystemExit("未收到保存确认，结果未知。请检查串口占用、设备固件与管理台状态后再操作。")


if __name__ == "__main__":
    main()
