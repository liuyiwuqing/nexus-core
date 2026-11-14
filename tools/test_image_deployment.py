#!/usr/bin/env python3
"""Verify image-only initialization and 1Panel Compose in an isolated installation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def command(arguments, *, env=None, check=True):
    result = subprocess.run(arguments, capture_output=True, text=True, env=env, timeout=240)
    if check and result.returncode:
        raise RuntimeError("deployment command failed; inspect private diagnostics")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Already loaded or pulled CORE image")
    parser.add_argument("--init-image", required=True, help="Matching initializer image")
    parser.add_argument("--report", type=Path, default=ROOT / "target/image-smoke.json")
    args = parser.parse_args()
    (ROOT / "target").mkdir(exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="image-smoke-", dir=ROOT / "target"))
    data = temporary / "private deployment $TOKEN"
    project = "nexus-image-" + uuid.uuid4().hex[:12]
    env = {key: value for key, value in os.environ.items() if not key.startswith(("NEXUS_", "COMPOSE_"))}
    env.update({"NEXUS_CONSOLE_PORT": "0", "NEXUS_MQTT_PORT": "0", "NEXUS_MQTT_BIND_ADDRESS": "127.0.0.1"})
    initialize = ["docker", "run", "--rm", "--read-only", "--cap-drop", "ALL",
                  "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp",
                  "--user", f"{os.getuid()}:{os.getgid()}",
                  "--mount", f"type=bind,source={temporary},target={temporary}", args.init_image,
                  "--data-dir", str(data), "--domain", "console.example.invalid", "--mqtt-host", "localhost",
                  "--device", "image-test", "--image", args.image]
    compose = ["docker", "compose", "-p", project, "-f", str(data / "compose.1panel.yml")]
    stage = "image-only initialization"
    checks = []
    started = False
    success = False
    report = {"image": args.image, "initImage": args.init_image, "project": project, "checks": checks,
              "boundary": "isolated image-only Linux containers; no target server, real providers or devices"}

    def passed(name):
        checks.append(name)
        print("PASS " + name, flush=True)

    def fingerprint():
        return {str(path.relative_to(data)): (hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_mode & 0o777)
                for path in data.rglob("*") if path.is_file()}

    def container(service):
        return command(compose + ["ps", "-q", service], env=env).stdout.strip()

    def port(service, number):
        return int(command(compose + ["port", service, str(number)], env=env).stdout.strip().rsplit(":", 1)[1])

    try:
        initialized = command(initialize)
        private = dict(line.split("=", 1) for line in (data / "core.env").read_text().splitlines()
                       if line and not line.startswith("#"))
        for key in ("NEXUS_ADMIN_TOKEN", "NEXUS_DB_PASSWORD", "NEXUS_MQTT_PASSWORD"):
            assert private[key] not in initialized.stdout + initialized.stderr
        assert data.stat().st_mode & 0o777 == 0o700
        for name in ("core.env", "postgres.env", "device.json", "pki/ca.key"):
            assert (data / name).stat().st_mode & 0o777 == 0o600
        assert "$7$" in (data / "mosquitto/config/passwd").read_text()
        passed("initializer image generates private TLS, hashed credentials and Compose without source/socket")
        stage = "repeat initialization refusal"
        before = fingerprint()
        repeated = command(initialize, check=False)
        assert repeated.returncode == 2
        assert before == fingerprint()
        passed("repeat initialization refuses to overwrite any credential, certificate or configuration")
        stage = "1Panel configuration"
        with (data / "core.env").open("a") as stream:
            stream.write('NEXUS_AI_API_KEY=\'literal$TOKEN#"quoted"\'\n')
        rendered = json.loads(command(compose + ["config", "--format", "json"], env=env).stdout)
        services = rendered["services"]
        assert set(services) == {"core", "postgres", "mqtt"}
        assert all("build" not in service for service in services.values())
        assert services["core"]["image"] == args.image
        assert services["core"]["ports"][0]["host_ip"] == "127.0.0.1"
        assert not services["postgres"].get("ports")
        assert "format:" not in (data / "compose.1panel.yml").read_text()
        passed("1Panel compose parses without source, build, Caddy, raw env format or external compose.env")
        stage = "image-only Compose startup"
        started = True
        command(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"], env=env)
        core_id = container("core")
        core = json.loads(command(["docker", "inspect", core_id]).stdout)[0]
        assert core["Config"]["User"] == "65534:65534" and core["HostConfig"]["ReadonlyRootfs"]
        assert 'NEXUS_AI_API_KEY=literal$TOKEN#"quoted"' in core["Config"]["Env"]
        report["imageId"] = core["Image"]
        report["initImageId"] = json.loads(command(["docker", "image", "inspect", args.init_image]).stdout)[0]["Id"]
        passed("CORE starts non-root/read-only with authenticated readiness and quoted literal env values")
        stage = "console and authentication"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, body=None, auth=True):
            headers = {"Authorization": "Bearer " + private["NEXUS_ADMIN_TOKEN"]} if auth else {}
            if body is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(f"http://127.0.0.1:{port('core', 8088)}" + path,
                                         data=None if body is None else json.dumps(body).encode(), headers=headers)
            try:
                with opener.open(req, timeout=10) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as error:
                return error.code, error.read()

        assert request("/api/v1/status", auth=False)[0] == 401
        status, body = request("/api/v1/status")
        assert status == 200 and json.loads(body)["database"] == "ready" and json.loads(body)["mqtt"] == "ready"
        status, home = request("/", auth=False)
        assert status == 200
        assets = re.findall(r'(?:src|href)="(/assets/[^\"]+)"', home.decode())
        assert assets
        jar = temporary / "core.jar"
        command(["docker", "cp", core_id + ":/app/core.jar", str(jar)])
        with zipfile.ZipFile(jar) as archive:
            for asset in assets:
                status, body = request(asset, auth=False)
                assert status == 200 and body == archive.read("BOOT-INF/classes/static" + asset)
        passed("loopback console serves actual JAR assets and preserves API authorization")
        stage = "MQTT TLS"
        context = ssl.create_default_context(cafile=str(data / "mosquitto/certs/ca.crt"))
        with socket.create_connection(("127.0.0.1", port("mqtt", 8883)), timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname="localhost") as tls:
                assert tls.version() in ("TLSv1.2", "TLSv1.3")
        passed("MQTT TLS validates the initializer image's CA and hostname")
        stage = "container recreation"
        status, body = request("/api/v1/messages", {"connectorId": "image-smoke", "source": "test",
            "eventId": "one", "eventType": "smoke", "title": "isolated image deployment", "content": "temporary only"})
        assert status == 201
        message_id = json.loads(body)["messageId"]
        before = fingerprint()
        command(compose + ["down"], env=env)
        command(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"], env=env)
        status, body = request("/api/v1/messages")
        assert status == 200 and json.loads(body)["items"][0]["messageId"] == message_id
        assert before == fingerprint()
        passed("database records and original credentials survive container recreation")
        success = True
    except Exception as error:
        report.update({"failedStage": stage, "errorType": type(error).__name__})
        print("FAIL " + stage + " (" + type(error).__name__ + ")", file=sys.stderr)
    finally:
        cleanup = True
        if started:
            logs = command(compose + ["logs", "--no-color"], env=env, check=False)
            log = temporary / "containers.log"
            log.write_text(logs.stdout + logs.stderr)
            log.chmod(0o600)
            cleanup = command(compose + ["down", "-v", "--remove-orphans"], env=env, check=False).returncode == 0
        report.update({"cleanup": cleanup, "passed": success and cleanup})
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        if report["passed"]:
            shutil.rmtree(temporary)
        else:
            print("Private diagnostic directory: " + str(temporary), file=sys.stderr)
    if not report["passed"]:
        raise SystemExit(1)
    print("Image-only deployment passed; isolated resources removed.")


if __name__ == "__main__":
    main()
