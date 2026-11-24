"""Public maintenance CLI; only temporary files are changed."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LEGACY = """user nexus-core
topic write nexus/v1/devices/+/inbox
topic read nexus/v1/devices/+/acks
topic read nexus/v1/devices/+/state
pattern read nexus/v1/devices/%u/inbox
pattern write nexus/v1/devices/%u/acks
pattern write nexus/v1/devices/%u/state
"""

class BrokerPolicyTest(unittest.TestCase):
    def command(self, directory, *args):
        return subprocess.run([sys.executable, str(ROOT / "tools/upgrade_broker_policy.py"), "--directory", str(directory), *args], capture_output=True, text=True)

    def test_preview_changes_nothing_then_apply_preserves_backup_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            conf = "listener 8883\nallow_anonymous false\nmessage_size_limit 1024\nmax_packet_size 2048\nmax_queued_messages 32\n"
            (directory / "acl").write_text(LEGACY)
            (directory / "mosquitto.conf").write_text(conf)
            result = self.command(directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(json.loads(result.stdout)["applied"])
            self.assertEqual((directory / "acl").read_text(), LEGACY)
            self.assertEqual((directory / "mosquitto.conf").read_text(), conf)
            result = self.command(directory, "--apply")
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads(result.stdout)
            self.assertTrue(summary["applied"])
            backup = Path(summary["backup"])
            self.assertEqual((backup / "acl").read_text(), LEGACY)
            self.assertEqual((backup / "mosquitto.conf").read_text(), conf)
            updated = (directory / "acl").read_text()
            self.assertIn("pattern read nexus/v1/devices/%u/config\n", updated)
            self.assertIn("pattern write nexus/v1/devices/%u/config_acks\n", updated)
            self.assertIn("topic write nexus/v1/devices/+/config\n", updated)
            self.assertIn("pattern read nexus/v1/devices/%u/ota\n", updated)
            self.assertIn("pattern write nexus/v1/devices/%u/ota_status\n", updated)
            self.assertIn("pattern write nexus/v1/devices/%u/growth_status\n", updated)
            self.assertIn("topic read nexus/v1/devices/+/growth_status\n", updated)
            self.assertIn("pattern read nexus/v1/devices/%u/appearance\n",updated)
            self.assertIn("pattern write nexus/v1/devices/%u/companion_status\n",updated)
            self.assertNotIn("topic read #", updated)
            updated_conf = (directory / "mosquitto.conf").read_text()
            self.assertIn("message_size_limit 3072\n", updated_conf)
            self.assertIn("max_packet_size 4096\n", updated_conf)
            self.assertIn("max_queued_messages 32\n", updated_conf)
            result = self.command(directory, "--apply")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(json.loads(result.stdout)["changed"])
            self.assertEqual((backup / "acl").read_text(), LEGACY)

    def test_management_policy_upgrade_preserves_the_earlier_backup(self):
        from broker_policy import MANAGEMENT_V1_ACL
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp);(directory/'acl').write_text(MANAGEMENT_V1_ACL)
            (directory/'mosquitto.conf').write_text('listener 8883\nallow_anonymous false\nmessage_size_limit 2048\nmax_packet_size 4096\n')
            original_backup=directory/'.management-v1-backup';original_backup.mkdir();(original_backup/'acl').write_text(LEGACY)
            result=self.command(directory,'--apply');self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual((original_backup/'acl').read_text(),LEGACY)
            self.assertEqual((directory/'.growth-sync-v1-backup/acl').read_text(),MANAGEMENT_V1_ACL)
            self.assertIn('pattern write nexus/v1/devices/%u/ota_acks\n',(directory/'acl').read_text())

    def test_custom_acl_is_left_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            custom = LEGACY + "user monitoring\ntopic read metrics/#\n"
            conf = "listener 8883\nallow_anonymous false\nmessage_size_limit 1024\n"
            (directory / "acl").write_text(custom)
            (directory / "mosquitto.conf").write_text(conf)
            result = self.command(directory, "--apply")
            self.assertEqual(result.returncode, 1)
            self.assertIn("custom_acl_requires_manual_merge", result.stderr)
            self.assertEqual((directory / "acl").read_text(), custom)
            self.assertEqual((directory / "mosquitto.conf").read_text(), conf)
            self.assertFalse((directory / ".growth-sync-v1-backup").exists())

    def test_remote_ota_upgrade_keeps_its_original_recovery_snapshot(self):
        from broker_policy import REMOTE_OTA_V1_ACL
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp);(directory/'acl').write_text(REMOTE_OTA_V1_ACL)
            (directory/'mosquitto.conf').write_text('listener 8883\nallow_anonymous false\nmessage_size_limit 2048\nmax_packet_size 4096\n')
            old=directory/'.remote-ota-v1-backup';old.mkdir();(old/'acl').write_text(LEGACY)
            result=self.command(directory,'--apply');self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual((old/'acl').read_text(),LEGACY)
            self.assertEqual((directory/'.growth-sync-v1-backup/acl').read_text(),REMOTE_OTA_V1_ACL)
            self.assertIn('pattern write nexus/v1/devices/%u/growth_status\n',(directory/'acl').read_text())

if __name__ == "__main__":
    unittest.main()
