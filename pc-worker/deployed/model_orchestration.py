"""Choose among models advertised by the same subscription CLI used for execution."""
from pathlib import Path
import json
import os
import subprocess
import threading
import time

LUNA = "gpt-6-luna"
TERRA = "gpt-6.1-terra"
SOL = "gpt-6.2-sol"
MODELS = {LUNA, TERRA, SOL}
BROWSER_READ = "read_app_browser_page"
VISUAL_TOOLS = {BROWSER_READ, "open_app_browser_page", "read_screen", "screen_action"}
PLAN_TOOLS = {"plan_task"}
_cache = {}
_lock = threading.Lock()


def subscription_environment():
    return {key: value for key, value in os.environ.items()
            if key not in {"OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY",
                           "ZIPHAP_DEVICE_BRIDGE_BOOTSTRAP"}}


def model_catalog(executable):
    """The probe receives no task data. Short-lived negative cache avoids repeated timeouts."""
    with _lock:
        cached = _cache.get(executable)
        if cached and cached[0] > time.monotonic():
            return cached[1]
        rows = {}
        try:
            result = subprocess.run([executable, "debug", "models"], env=subscription_environment(),
                                    capture_output=True, timeout=5,
                                    creationflags=0x08000000 if os.name == "nt" else 0)
            if result.returncode == 0:
                rows = {
                    row["slug"]: frozenset(level["effort"] for level in row["supported_reasoning_levels"])
                    for row in json.loads(result.stdout)["models"]
                    if row["slug"] in MODELS and row.get("visibility") == "list"
                }
        except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired):
            pass
        _cache[executable] = (time.monotonic() + (900 if rows else 60), rows)
        return rows


def available_profile(catalog, *candidates):
    return next(((model, effort) for model, effort in candidates if effort in catalog.get(model, ())), None)


def agent_profile(catalog, allowed_tools=(), recovery=None):
    """Use host workflow state. Model output, tool text, and user text cannot set the profile."""
    recovery = recovery or {}
    tools = set(allowed_tools) - PLAN_TOOLS
    if recovery.get("browserObservationComplete") is True:
        return available_profile(catalog, (TERRA, "medium"), (SOL, "medium"))
    if recovery.get("readObservationsComplete") is True:
        observed = recovery.get("observedReads", ())
        if len(observed) == 1 and not recovery.get("failedReads") and not recovery.get("observationsOmitted"):
            return available_profile(catalog, (LUNA, "low"), (SOL, "medium"))
        return available_profile(catalog, (SOL, "medium"))
    remaining_tools = set(recovery.get("acceptedTools", ()))
    if (remaining_tools and remaining_tools <= VISUAL_TOOLS) or (tools and tools <= VISUAL_TOOLS):
        return available_profile(catalog, (TERRA, "medium"), (SOL, "medium"))
    if len(remaining_tools) > 1:
        return available_profile(catalog, (SOL, "high"), (SOL, "medium"))
    return available_profile(catalog, (SOL, "medium"))


def claude_profile(allowed_tools=(), recovery=None):
    """Route by the host's authorized scope and observations, never by prompt text."""
    recovery = recovery or {}
    if recovery.get("browserObservationComplete") is True:
        return "sonnet", "medium"
    if recovery.get("readObservationsComplete") is True:
        if (len(recovery.get("observedReads", ())) == 1 and not recovery.get("failedReads")
                and not recovery.get("observationsOmitted")):
            return "haiku", None
        return "sonnet", "medium"
    remaining = set(recovery.get("acceptedTools", ())) - PLAN_TOOLS
    tools = remaining or (set(allowed_tools) - PLAN_TOOLS)
    if tools and tools <= VISUAL_TOOLS:
        return "sonnet", "medium"
    if len(remaining) >= 3:
        return "opus", "high"
    return "sonnet", "high" if len(remaining) == 2 else "medium"


def briefing_profile(catalog, record_count, recent_count):
    if record_count + recent_count <= 8:
        return available_profile(catalog, (LUNA, "low"), (SOL, "medium"))
    return available_profile(catalog, (SOL, "medium"))


def model_flags(profile):
    if profile is None:
        return []
    model, effort = profile
    return ["--model", model, "-c", "model_reasoning_effort=" + json.dumps(effort)]


def skill_body():
    path = Path(__file__).with_name("skills") / "model-orchestration" / "SKILL.md"
    return path.read_text(encoding="utf-8").split("---", 2)[2].strip()
