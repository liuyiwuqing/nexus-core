#!/usr/bin/env python3
"""Real isolated Compose/HTTPS/DB/MQTT smoke; removes only its own random project."""
import argparse
import hashlib
import http.cookiejar
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
import time
import traceback
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HEALTH = ["java", "-Dloader.main=dev.nexus.core.runtime.ContainerHealthcheck", "-cp", "/app/core.jar",
          "org.springframework.boot.loader.launch.PropertiesLauncher"]


def command(args, *, check=True, env=None):
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, timeout=240)
    if check and result.returncode:
        raise RuntimeError("command failed: " + " ".join(map(str, args[:5])))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Already built local CORE image")
    parser.add_argument("--report", type=Path, default=ROOT / "target/docker-smoke.json")
    args = parser.parse_args()
    (ROOT / "target").mkdir(exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="docker-smoke-", dir=ROOT / "target"))
    project = "nexus-smoke-" + uuid.uuid4().hex[:12]
    data = temporary / "private"
    env = os.environ.copy()
    # Host overrides cannot accidentally change this test's ports or credentials.
    env = {key: value for key, value in env.items() if not key.startswith(("NEXUS_", "COMPOSE_"))}
    env.update({"NEXUS_HTTP_BIND_ADDRESS": "127.0.0.1", "NEXUS_MQTT_BIND_ADDRESS": "127.0.0.1",
                "NEXUS_HTTP_PORT": "0", "NEXUS_HTTPS_PORT": "0", "NEXUS_MQTT_PORT": "0"})
    compose = ["docker", "compose", "-p", project, "--env-file", str(data / "compose.env"),
               "-f", str(ROOT / "deploy/docker/compose.yml")]
    stage = "initialize"
    checks = []
    started = False
    success = False
    report = {"project": project, "image": args.image, "checks": checks,
              "boundary": "isolated Linux containers; localhost private CA; no production/real providers/devices"}

    def passed(name):
        checks.append(name)
        print("PASS " + name, flush=True)

    def container(service):
        return command(compose + ["ps", "-q", service], env=env).stdout.strip()

    def port(service, number):
        return int(command(compose + ["port", service, str(number)], env=env).stdout.strip().rsplit(":", 1)[1])

    def wait_ready():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            probe = command(compose + ["exec", "-T", "core", *HEALTH], env=env, check=False)
            if probe.returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError("authenticated health did not recover")

    try:
        command([sys.executable, str(ROOT / "tools/init_docker.py"), "--data-dir", str(data),
                 "--domain", "localhost", "--mqtt-host", "localhost", "--device", "smoke-pet", "--image", args.image])
        private = dict(line.split("=", 1) for line in (data / "core.env").read_text().splitlines()
                       if line and not line.startswith("#"))
        with (data / "core.env").open("a") as stream:
            stream.write('NEXUS_AI_API_KEY=literal$TOKEN#"quoted"\n')
        stage = "compose startup"
        started = True
        command(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"], env=env)
        passed("compose startup and authenticated readiness")
        core_id = container("core")
        caddy_id = container("caddy")
        core = json.loads(command(["docker", "inspect", core_id]).stdout)[0]
        assert core["Config"]["User"] == "65534:65534"
        assert core["HostConfig"]["ReadonlyRootfs"]
        assert not core["HostConfig"].get("PortBindings")
        assert "NEXUS_AI_API_KEY=literal$TOKEN#\"quoted\"" in core["Config"]["Env"]
        assert not json.loads(command(["docker", "inspect", container("postgres")]).stdout)[0]["HostConfig"].get("PortBindings")
        passed("non-root read-only CORE, private database/API, literal env values")
        stage = "TLS and console"
        ca = temporary / "https-ca.crt"
        deadline = time.monotonic() + 30
        while command(["docker", "cp", caddy_id + ":/data/caddy/pki/authorities/local/root.crt", str(ca)], check=False).returncode:
            if time.monotonic() >= deadline:
                raise RuntimeError("local HTTPS CA did not become ready")
            time.sleep(0.5)
        context = ssl.create_default_context(cafile=str(ca))
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))

        session_cookies = http.cookiejar.CookieJar()
        session_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(session_cookies))

        def request(path, body=None, auth=True, *, session=False, csrf=None, cookie=None):
            # Re-query ports after container recreation; Docker chooses new host ports.
            headers = {"Content-Type": "application/json"}
            if csrf:
                headers["X-Nexus-CSRF"] = csrf
            if cookie:
                headers["Cookie"] = cookie
            if auth:
                headers["Authorization"] = "Bearer " + private["NEXUS_ADMIN_TOKEN"]
            req = urllib.request.Request(f"https://localhost:{port('caddy', 443)}{path}",
                                         data=None if body is None else json.dumps(body).encode(), headers=headers)
            try:
                with (session_opener if session else opener).open(req, timeout=15) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as error:
                return error.code, error.read()

        def wait_https():
            deadline = time.monotonic() + 30
            while True:
                try:
                    if request("/api/v1/status", auth=False)[0] == 401:
                        return
                except (OSError, urllib.error.URLError):
                    pass
                if time.monotonic() >= deadline:
                    raise RuntimeError("HTTPS proxy did not become ready")
                time.sleep(0.5)

        wait_https()
        status, response = request("/api/v1/status")
        assert status == 200 and json.loads(response)["database"] == "ready" and json.loads(response)["mqtt"] == "ready"
        status, home = request("/", auth=False)
        assert status == 200
        assets = re.findall(r'(?:src|href)="(/assets/[^\"]+)"', home.decode())
        assert assets
        jar = temporary / "core.jar"
        command(["docker", "cp", core_id + ":/app/core.jar", str(jar)])
        with zipfile.ZipFile(jar) as archive:
            for asset in assets:
                status, content = request(asset, auth=False)
                assert status == 200 and content == archive.read("BOOT-INF/classes/static" + asset)
        report["jarSha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
        report["imageId"] = core["Image"]
        passed("trusted HTTPS, admin auth and actual JAR console assets")
        stage = "durable browser session"
        status, logged_in = request("/api/v1/session", {"remember": True}, session=True)
        report["browserLoginStatus"] = status
        assert status == 200, "browser_session_login_failed"
        logged_in = json.loads(logged_in)
        assert logged_in["persisted"] and logged_in["role"] == "admin"
        session_cookie = next(c for c in session_cookies if c.name == "nexus_console_session")
        report["browserCookieFlags"] = {"secure": session_cookie.secure, "httpOnly": session_cookie.has_nonstandard_attr("HttpOnly")}
        assert session_cookie.secure and session_cookie.has_nonstandard_attr("HttpOnly"), "browser_cookie_flags"
        replay_cookie = "nexus_console_session=" + session_cookie.value
        assert request("/api/v1/session/logout", {}, auth=False, session=True)[0] == 403
        assert any(mount["Destination"] == "/var/lib/nexus/sessions" and mount["Type"] == "volume" for mount in core["Mounts"])
        mode = command(compose + ["exec", "-T", "core", "stat", "-c", "%u:%g:%a", "/var/lib/nexus/sessions"], env=env).stdout.strip()
        assert mode == "65534:65534:700"
        passed("secure browser session uses private non-root persistent volume and CSRF")
        stage = "MQTT TLS"
        mqtt_context = ssl.create_default_context(cafile=str(data / "mosquitto/certs/ca.crt"))
        with socket.create_connection(("127.0.0.1", port("mqtt", 8883)), timeout=5) as raw:
            with mqtt_context.wrap_socket(raw, server_hostname="localhost") as tls:
                assert tls.version() in ("TLSv1.2", "TLSv1.3")
        passed("published MQTT port with verified private-CA TLS")
        stage = "persist and restore"
        status, created = request("/api/v1/messages", {"connectorId": "docker-smoke", "source": "test",
            "eventId": "one", "eventType": "smoke", "title": "isolated persistence check", "content": "temporary test only"})
        assert status == 201
        message_id = json.loads(created)["messageId"]
        command(compose + ["down"], env=env)
        command(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"], env=env)
        wait_https()
        status, messages = request("/api/v1/messages")
        assert status == 200 and json.loads(messages)["total"] == 1
        assert json.loads(messages)["items"][0]["messageId"] == message_id
        passed("message and TLS state survive full container recreation")
        status, restored_session = request("/api/v1/session", auth=False, session=True)
        assert status == 200 and json.loads(restored_session) == logged_in
        assert request("/api/v1/session/logout", {}, auth=False, session=True, csrf=logged_in["csrfToken"])[0] == 204
        command(compose + ["restart", "core"], env=env)
        wait_ready()
        wait_https()
        assert request("/api/v1/session", auth=False, cookie=replay_cookie)[0] == 401
        passed("browser session survives recreation and committed logout survives restart")
        stage = "broker failure and recovery"
        core_before_recovery = json.loads(command(["docker", "inspect", container("core")]).stdout)[0]
        command(compose + ["stop", "mqtt"], env=env)
        deadline = time.monotonic() + 20
        while True:
            status, response = request("/api/v1/status")
            if status == 200 and json.loads(response)["mqtt"] == "unavailable":
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("broker failure was not visible")
            time.sleep(1)
        assert command(compose + ["exec", "-T", "core", *HEALTH], env=env, check=False).returncode != 0
        command(compose + ["start", "mqtt"], env=env)
        wait_ready()
        core_after_recovery = json.loads(command(["docker", "inspect", container("core")]).stdout)[0]
        assert core_after_recovery["Id"] == core_before_recovery["Id"]
        assert core_after_recovery["State"]["StartedAt"] == core_before_recovery["State"]["StartedAt"]
        assert core_after_recovery["RestartCount"] == core_before_recovery["RestartCount"]
        passed("broker loss fails health and recovers without restarting CORE")
        success = True
    except Exception as error:
        report["failedStage"] = stage
        report["errorType"] = type(error).__name__
        report["errorLine"] = traceback.extract_tb(error.__traceback__)[-1].lineno
        print("FAIL " + stage + " (" + type(error).__name__ + ")", file=sys.stderr)
    finally:
        cleanup = True
        if started:
            logs = command(compose + ["logs", "--no-color"], env=env, check=False)
            log = temporary / "containers.log"
            log.write_text(logs.stdout + logs.stderr)
            log.chmod(0o600)
            # This generated project never shares a name, volume or config with an installation.
            cleanup = command(compose + ["down", "-v", "--remove-orphans"], env=env, check=False).returncode == 0
        report["cleanup"] = cleanup
        report["passed"] = success and cleanup
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        if success and cleanup:
            shutil.rmtree(temporary)
        else:
            print("Private diagnostic directory: " + str(temporary), file=sys.stderr)
    if not report["passed"]:
        raise SystemExit(1)
    print("Docker deployment smoke passed; isolated resources removed.")


if __name__ == "__main__":
    main()
