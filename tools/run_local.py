#!/usr/bin/env python3
"""Build/run CORE with project-local configuration; no global JDK/Maven edits."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def environment(include_local=True):
    result = os.environ.copy()
    path = ROOT / "data/local.env"
    if include_local and path.exists():
        for line in path.read_text().splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or not key.startswith("NEXUS_"):
                raise ValueError("invalid local.env entry")
            result.setdefault(key, value)
    source = result.get("NEXUS_AI_CONFIG_FILE", "") if include_local else ""
    if source:
        # Reuse the existing protected provider config; never duplicate its key on disk.
        content = Path(source).expanduser().read_text()
        if result.get("NEXUS_AI_CONFIG_FORMAT", "openviking") == "codex":
            provider = tomllib.loads(content).get("model_providers", {}).get(result.get("NEXUS_AI_CONFIG_PROVIDER", "custom"), {})
            model = {"api_base": provider.get("base_url", ""), "api_key": provider.get("experimental_bearer_token", ""), "model": result.get("NEXUS_AI_MODEL", "")}
        else:
            model = json.loads(content).get("vlm", {})
        for target, field in (("NEXUS_AI_BASE_URL", "api_base"), ("NEXUS_AI_API_KEY", "api_key"), ("NEXUS_AI_MODEL", "model")):
            value = model.get(field, "")
            if not isinstance(value, str) or not value.strip():
                raise ValueError("existing AI configuration is incomplete")
            if not result.get(target):
                result[target] = value
    bridge_source = result.get("NEXUS_BRIDGE_CONFIG_FILE", "") if include_local else ""
    if bridge_source and not result.get("NEXUS_BRIDGE_TOKEN"):
        result["NEXUS_BRIDGE_TOKEN"] = json.loads(Path(bridge_source).expanduser().read_text()).get("token", "")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["test", "build", "run", "live-ai-test"], nargs="?", default="run")
    args = parser.parse_args()
    # 测试使用独立容器与测试凭据，避免真实实例配置泄漏到测试环境。
    env = environment(include_local=args.mode in ("run", "live-ai-test"))
    if env.get("NEXUS_JAVA_HOME"):
        env["JAVA_HOME"] = env["NEXUS_JAVA_HOME"]
    elif Path("/usr/libexec/java_home").exists():
        candidate = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True)
        if candidate.returncode == 0:
            env["JAVA_HOME"] = candidate.stdout.strip()
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else shutil.which("java")
    if java is None:
        raise SystemExit("Java 21+ is required")
    version = subprocess.run([java, "-version"], capture_output=True, text=True)
    import re
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match.group(1)) < 21:
        raise SystemExit("Java 21+ is required; set JAVA_HOME for this command")
    os.chdir(ROOT)
    if args.mode == "live-ai-test":
        env["NEXUS_LIVE_AI_TEST"] = "true"
        command = ["mvn", "-s", ".mvn/settings.xml", "-B", "-ntp", "-Dtest=LiveCommentModelTest", "test"]
    elif args.mode in ("test", "build"):
        if args.mode == "build":
            frontend = ROOT / "frontend"
            # Maven resources copy does not remove hashed assets from an older UI build.
            static_output = ROOT / "target/classes/static"
            if static_output.is_dir():
                shutil.rmtree(static_output)
            npm_env = env.copy()
            if npm_env.get("HTTPS_PROXY"):
                npm_env.setdefault("npm_config_https_proxy", npm_env["HTTPS_PROXY"])
            if not (frontend / "node_modules").exists():
                subprocess.run(["npm", "ci"], cwd=frontend, env=npm_env, check=True)
            subprocess.run(["npm", "run", "check"], cwd=frontend, env=npm_env, check=True)
            subprocess.run(["npm", "run", "build"], cwd=frontend, env=npm_env, check=True)
        command = ["mvn", "-s", ".mvn/settings.xml", "-B", "-ntp", "verify" if args.mode == "build" else "test"]
    else:
        jar = ROOT / "target/nexus-core-0.1.0-SNAPSHOT.jar"
        if not jar.exists():
            raise SystemExit("Build first: python3 tools/run_local.py build")
        command = [java, "-jar", str(jar)]
    os.execvpe(command[0], command, env)


if __name__ == "__main__":
    main()
