"""Check the configured subscription CLIs at Windows sign-in without inference."""
import json
import os
from pathlib import Path
import subprocess
import time

from agent_provider_supervisor import load_config
from claude_agent_provider import STRIPPED_ENV
from codex_agent_provider import ProviderError, _pin
from model_orchestration import subscription_environment


def subscription_ready(config, provider):
    if config is None:
        return False
    try:
        executable = _pin(config.executable, config.executable_sha256)
        if provider == "claude":
            command = [executable, "auth", "status"]
            env = {key: value for key, value in os.environ.items() if key not in STRIPPED_ENV}
            if config.config_dir is not None:
                env["CLAUDE_CONFIG_DIR"] = str(config.config_dir)
        else:
            command = [executable, "login", "status"]
            env = subscription_environment()
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        result = subprocess.run(command, env=env, stdin=subprocess.DEVNULL,
                                capture_output=True, timeout=10, creationflags=flags)
        if result.returncode != 0:
            return False
        if provider == "claude":
            status = json.loads(result.stdout)
            return status.get("loggedIn") is True and status.get("authMethod") == "claude.ai"
        return b"logged in using chatgpt" in (result.stdout + result.stderr).lower()
    except (OSError, ValueError, AttributeError, ProviderError, subprocess.TimeoutExpired):
        return False


def main():
    home = Path(__file__).resolve().parent.parent
    with (home / "provider-startup.log").open("a", encoding="utf-8") as log:
        for provider in ("codex", "claude"):
            ready = subscription_ready(load_config(home, provider), provider)
            log.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {provider} {'READY' if ready else 'UNAVAILABLE'}\n")


if __name__ == "__main__":
    main()
