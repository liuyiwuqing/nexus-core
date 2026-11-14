#!/usr/bin/env python3
"""Exercise the deployment initializer through its public CLI; no real configuration."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DockerInitializationTest(unittest.TestCase):
    def command(self, data, *options):
        return subprocess.run([sys.executable, str(ROOT / "tools/init_docker.py"), "--data-dir", str(data),
                               "--domain", "console.example.invalid", "--mqtt-host", "mqtt.example.invalid", *options],
                              capture_output=True, text=True)

    def test_existing_or_symlink_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            existing = Path(temporary) / "existing"
            existing.mkdir()
            marker = existing / "core.env"
            marker.write_text("private original")
            for destination in (existing, Path(temporary) / "link"):
                if destination != existing:
                    destination.symlink_to(existing, target_is_directory=True)
                result = self.command(destination)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(marker.read_text(), "private original")
                self.assertEqual(list(existing.iterdir()), [marker])

    def test_invalid_inputs_create_nothing(self):
        with tempfile.TemporaryDirectory() as temporary:
            for options in (("--domain", "https://console.example.com"), ("--mqtt-host", "a/b"),
                            ("--device", "nexus-core"), ("--image", "bad\nimage")):
                with self.subTest(options=options):
                    data = Path(temporary) / "new"
                    result = self.command(data, *options)
                    self.assertEqual(result.returncode, 2)
                    self.assertFalse(data.exists())

    def test_new_installation_is_private_and_ready_for_compose(self):
        with tempfile.TemporaryDirectory(prefix="nexus-docker-cli-") as temporary:
            data = Path(temporary) / "private deployment $TOKEN"
            result = subprocess.run([
                sys.executable, str(ROOT / "tools/init_docker.py"),
                "--data-dir", str(data), "--domain", "console.example.invalid",
                "--mqtt-host", "mqtt.example.invalid", "--device", "docker-test",
                "--image", "ghcr.io/example/core@sha256:" + "a" * 64,
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            env = dict(line.split("=", 1) for line in (data / "core.env").read_text().splitlines()
                       if line and not line.startswith("#"))
            self.assertEqual(env["NEXUS_DB_URL"], "jdbc:postgresql://postgres:5432/nexus")
            self.assertEqual(env["NEXUS_MQTT_URI"], "ssl://mqtt:8883")
            self.assertEqual(env["NEXUS_BIND_ADDRESS"], "0.0.0.0")
            self.assertEqual(env["NEXUS_PULL_FEEDS_JSON"], "[]")
            for name in ("HALO", "AI", "MEMORY", "MEMORY_MCP", "BRIDGE", "RETENTION", "OTA", "GROWTH_SYNC"):
                self.assertEqual(env[f"NEXUS_{name}_ENABLED"], "false")
            self.assertEqual(data.stat().st_mode & 0o777, 0o700)
            for name in ("core.env", "postgres.env", "compose.env", "device.json", "pki/ca.key"):
                self.assertEqual((data / name).stat().st_mode & 0o777, 0o600)
            self.assertNotIn(env["NEXUS_ADMIN_TOKEN"], result.stdout + result.stderr)
            self.assertNotIn(env["NEXUS_DB_PASSWORD"], result.stdout + result.stderr)
            self.assertEqual(json.loads((data / "device.json").read_text())["brokerUri"], "mqtts://mqtt.example.invalid:8883")
            certificate = subprocess.run(["openssl", "x509", "-in", str(data / "mosquitto/certs/server.crt"),
                                          "-noout", "-ext", "subjectAltName"], capture_output=True, text=True, check=True)
            self.assertIn("DNS:mqtt", certificate.stdout)
            self.assertIn("DNS:mqtt.example.invalid", certificate.stdout)
            strict = subprocess.run(["openssl", "verify", "-x509_strict", "-purpose", "sslserver",
                                     "-verify_hostname", "mqtt.example.invalid", "-CAfile", str(data / "pki/ca.crt"),
                                     str(data / "mosquitto/certs/server.crt")], capture_output=True, text=True)
            self.assertEqual(strict.returncode, 0, strict.stderr)
            rendered = subprocess.run(["docker", "compose", "--env-file", str(data / "compose.env"),
                                       "-f", str(ROOT / "deploy/docker/compose.yml"), "config", "--format", "json"],
                                      capture_output=True, text=True)
            self.assertEqual(rendered.returncode, 0, "compose configuration rejected")
            services = json.loads(rendered.stdout)["services"]
            self.assertFalse(services["core"].get("ports"))
            self.assertFalse(services["postgres"].get("ports"))
            self.assertEqual(services["core"]["environment"]["NEXUS_ADMIN_TOKEN"], env["NEXUS_ADMIN_TOKEN"])
            panel_file = data / "compose.1panel.yml"
            panel = subprocess.run(["docker", "compose", "-f", str(panel_file), "config", "--format", "json"],
                                   capture_output=True, text=True)
            self.assertEqual(panel.returncode, 0, "generated 1Panel compose must work without source or compose.env")
            panel_services = json.loads(panel.stdout)["services"]
            self.assertEqual(set(panel_services), {"core", "postgres", "mqtt"})
            self.assertNotIn("build", panel_services["core"])
            self.assertEqual(panel_services["core"]["image"], "ghcr.io/example/core@sha256:" + "a" * 64)
            self.assertEqual(panel_services["core"]["ports"][0]["host_ip"], "127.0.0.1")
            self.assertFalse(panel_services["postgres"].get("ports"))
            self.assertEqual(panel_services["core"]["environment"]["NEXUS_DB_PASSWORD"], env["NEXUS_DB_PASSWORD"])
            self.assertNotIn("format:", panel_file.read_text())
            self.assertNotIn("${NEXUS_DOCKER_DATA", panel_file.read_text())
            self.assertNotIn("${NEXUS_CORE_IMAGE", panel_file.read_text())


if __name__ == "__main__":
    unittest.main()
