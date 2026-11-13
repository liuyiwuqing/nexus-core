#!/usr/bin/env python3
"""Preview by default; explicitly apply a backed-up management/OTA ACL/packet-limit update."""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
from broker_policy import upgrade_acl, upgrade_config

ROOT = Path(__file__).resolve().parents[1]

def replace(path, content, mode):
    fd, temporary = tempfile.mkstemp(prefix=".management-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as output:
            os.fchmod(output.fileno(), mode)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        parent = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def upgrade(directory, apply):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("regular_config_directory_required")
    directory = directory.resolve()
    original, candidate, modes = {}, {}, {}
    for name, transform in (("acl", upgrade_acl), ("mosquitto.conf", upgrade_config)):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("regular_broker_config_files_required")
        original[name] = path.read_text()
        modes[name] = stat.S_IMODE(path.stat().st_mode)
        candidate[name] = transform(original[name])
    changed = [name for name in original if original[name] != candidate[name]]
    result = {"changed": changed, "applied": False, "backup": None, "restartRequired": False}
    if not changed or not apply:
        return result
    backup = directory / ".remote-ota-v1-backup"
    backup.mkdir(mode=0o700)  # Never overwrite the original recovery snapshot.
    for name, content in original.items():
        replace(backup / name, content, 0o600)
    if any((directory / name).read_text() != content for name, content in original.items()):
        raise ValueError("broker_configuration_changed_during_preflight")
    written = []
    try:
        for name in changed:
            replace(directory / name, candidate[name], modes[name])
            written.append(name)
        if any((directory / name).read_text() != candidate[name] for name in changed):
            raise ValueError("broker_configuration_readback_failed")
    except (OSError, ValueError):
        for name in written:
            if (directory / name).read_text() == candidate[name]:
                replace(directory / name, original[name], modes[name])
        raise ValueError("broker_upgrade_failed_check_remote_ota_v1_backup") from None
    result.update(applied=True, backup=str(backup), restartRequired=True)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "data/mosquitto/config")
    parser.add_argument("--apply", action="store_true", help="Back up and write templates; does not restart the broker")
    args = parser.parse_args()
    try:
        print(json.dumps(upgrade(args.directory, args.apply), ensure_ascii=False))
    except (OSError, ValueError) as error:
        code = str(error) if isinstance(error, ValueError) else "broker_config_io_error"
        print(json.dumps({"error": code}), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
