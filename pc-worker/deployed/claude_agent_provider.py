"""Claude Code subscription CLI as a cloud agent provider (same contract as codex_agent_provider).

Uses the operator's existing `claude` login, never an API key (API/auth-token variables are
removed from the child environment). Only the scoped ziphap_device MCP server is reachable; every
built-in tool is disabled. The bootstrap capability lives solely in a private mcp.json inside the
per-run static temp dir that is deleted with it; never in arguments or the process environment.
"""
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time

from codex_agent_provider import (BOOTSTRAP_ENV, FINAL_SCHEMA, MAX_LINE,
                                  MAX_OUTPUT, MAX_STDERR, AgentReport, ProviderError, _parse, _pin,
                                  _ProcessTree, bootstrap_secret, instructions_for, owner_choices)
from provider_conversation_context import (ConversationContextError, provider_input,
                                           validate_conversation_context)

from model_orchestration import WORKER_ONLY_ENV, claude_profile, skill_body

TOOL_PREFIX = "mcp__ziphap_device__"
# --json-schema makes Claude Code add this built-in tool to return the final object (observed with 2.1.330:
# init tools == ["StructuredOutput"] when no MCP server is configured). It has no effect outside the report.
STRUCTURED_TOOL = "StructuredOutput"
DISALLOWED = "Bash,Edit,Write,MultiEdit,NotebookEdit,Read,Glob,Grep,WebFetch,WebSearch,Task"
STRIPPED_ENV = ("OPENAI_API_KEY", "CODEX_API_KEY", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
                "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR",
                "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", BOOTSTRAP_ENV, *WORKER_ONLY_ENV)


def build_launch(config, bootstrap, static, external_allowed=False, *, allowed_tools=(), read_recovery=None):
    """Write static instructions/mcp.json into `static`; return (args, environment)."""
    if external_allowed is not True:
        raise ProviderError("EXTERNAL_AGENT_NOT_ALLOWED")
    secret = bootstrap_secret(bootstrap)
    executable = _pin(config.executable, config.executable_sha256)
    script = _pin(config.mcp_script, config.mcp_script_sha256)
    python = Path(config.python)
    workspace = Path(config.workspace)
    if not python.is_absolute() or not python.is_file() or not workspace.is_absolute() or not workspace.is_dir():
        raise ProviderError("PROVIDER_CONFIGURATION")
    if config.model is not None and (not isinstance(config.model, str) or not 1 <= len(config.model) <= 100
                                     or any(c.isspace() for c in config.model)):
        raise ProviderError("PROVIDER_CONFIGURATION")
    config_dir = None
    if config.config_dir is not None:
        config_dir = Path(config.config_dir)
        if not config_dir.is_absolute() or not config_dir.is_dir():
            raise ProviderError("PROVIDER_CONFIGURATION")
    mcp = Path(static) / "mcp.json"
    document = {"mcpServers": {"ziphap_device": {"type": "stdio", "command": str(python),
                                                 "args": ["-I", "-B", script], "env": {BOOTSTRAP_ENV: secret}}}}
    descriptor = os.open(mcp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as target:
        target.write(json.dumps(document, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    args = [executable, "-p", "--output-format", "stream-json", "--verbose", "--input-format", "text",
            "--no-session-persistence", "--strict-mcp-config", "--mcp-config", str(mcp),
            "--tools", "", "--allowedTools", "mcp__ziphap_device", "--disallowedTools", DISALLOWED,
            "--permission-mode", "dontAsk", "--setting-sources", "", "--disable-slash-commands",
            "--system-prompt-file", str(Path(static) / "instructions.txt"),
            "--json-schema", json.dumps(FINAL_SCHEMA, separators=(",", ":"))]
    if config.model is not None:
        args += ["--model", config.model]
    elif config.orchestrate:
        model, effort = claude_profile(allowed_tools, read_recovery)
        args += ["--model", model]
        if effort is not None:
            args += ["--effort", effort]
    environment = {k: v for k, v in os.environ.items() if k not in STRIPPED_ENV
                   and k not in {"ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL", "CLAUDE_CODE_EFFORT_LEVEL"}
                   and not (k.startswith("ANTHROPIC_DEFAULT_") and k.endswith("_MODEL"))}
    if config_dir is not None:
        environment["CLAUDE_CONFIG_DIR"] = str(config_dir)
    return args, environment


def _valid_final(value):
    return (type(value) is dict and set(value) in ({"state", "answer", "question"}, set(FINAL_SCHEMA["required"])) and
            value["state"] in FINAL_SCHEMA["properties"]["state"]["enum"] and
            isinstance(value["answer"], str) and len(value["answer"]) <= 12000 and
            isinstance(value["question"], str) and len(value["question"]) <= 300)


def run(config, bootstrap, owner_request, *, external_allowed=False, cancelled=None, timeout=300, progress=None,
        observe_tool=None, conversation_context=None, read_recovery=None, allowed_tools=(), office_persona=None):
    """Blocking owned child. Same guarantees as codex_agent_provider.run."""
    try:
        context = validate_conversation_context(conversation_context)
    except ConversationContextError:
        raise ProviderError("INVALID_CONVERSATION_CONTEXT") from None
    try:
        valid_request = type(owner_request) is str and owner_request.strip() and len(owner_request.encode("utf-8")) <= 65536
    except UnicodeError:
        valid_request = False
    if not valid_request:
        raise ProviderError("INVALID_OWNER_REQUEST")
    prompt = provider_input(owner_request, context)
    if read_recovery is not None:
        prompt = json.dumps({"currentOwnerRequest": owner_request, "conversationContext": context,
                             "readRecovery": read_recovery}, ensure_ascii=False, allow_nan=False)
    if type(timeout) is not int or not 1 <= timeout <= 600:
        raise ProviderError("PROVIDER_CONFIGURATION")
    stop = cancelled if cancelled is not None else threading.Event()
    if stop.is_set():
        raise ProviderError("PROVIDER_CANCELLED")
    root = Path(config.runtime_directory)
    if not root.is_absolute() or not root.is_dir():
        raise ProviderError("PROVIDER_CONFIGURATION")
    started = time.monotonic()
    progress_count = 0

    def emit(stage, calls=0, tool=None):
        nonlocal progress_count
        if progress is None or progress_count >= 512:
            return
        progress_count += 1
        value = {"stage": stage, "calls": calls}
        if type(tool) is str and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,79}", tool):
            value["toolName"] = tool
        try:
            progress(value)
        except Exception:
            pass

    emit("starting")
    with tempfile.TemporaryDirectory(prefix="ziphap-agent-static-", dir=root) as folder:
        static = Path(folder)
        instructions = instructions_for(context, read_recovery, office_persona)
        if config.orchestrate and external_allowed is True:
            instructions += "\n\n" + skill_body()
        (static / "instructions.txt").write_text(instructions, encoding="utf-8")
        args, environment = build_launch(config, bootstrap, static, external_allowed,
                                         allowed_tools=allowed_tools, read_recovery=read_recovery)
        readers_stop = threading.Event()
        threads = []
        tree = None
        child = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 cwd=str(config.workspace), env=environment,
                                 creationflags=0x08000000 if os.name == "nt" else 0,
                                 start_new_session=os.name != "nt")
        try:
            tree = _ProcessTree(child)
            emit("planning")
            incoming = queue.Queue(maxsize=32)

            def reader(stream, kind):
                try:
                    while not readers_stop.is_set():
                        line = stream.readline(MAX_LINE + 1)
                        if not line:
                            break
                        while not readers_stop.is_set():
                            try:
                                incoming.put((kind, line), timeout=.1)
                                break
                            except queue.Full:
                                pass
                except (OSError, ValueError):
                    pass
                finally:
                    while not readers_stop.is_set():
                        try:
                            incoming.put(("eof", kind), timeout=.1)
                            break
                        except queue.Full:
                            pass
            threads = [threading.Thread(target=reader, args=(child.stdout, "out"), daemon=True),
                       threading.Thread(target=reader, args=(child.stderr, "err"), daemon=True)]

            def writer():
                try:
                    child.stdin.write(prompt.encode("utf-8")); child.stdin.close()
                except (OSError, ValueError):
                    pass
            threads.append(threading.Thread(target=writer, daemon=True))
            for thread in threads:
                thread.start()
            total = {"out": 0, "err": 0}; ended = set(); calls = 0
            initialized = False; result_event = None
            pending = {}  # tool_use_id -> unprefixed tool name (None for the StructuredOutput report)
            structured = None
            while len(ended) < 2:
                if stop.is_set():
                    raise ProviderError("PROVIDER_CANCELLED")
                if time.monotonic() - started > timeout:
                    raise ProviderError("PROVIDER_TIMEOUT")
                try:
                    kind, line = incoming.get(timeout=.1)
                except queue.Empty:
                    continue
                if kind == "eof":
                    ended.add(line); continue
                total[kind] += len(line)
                if len(line) > MAX_LINE or total[kind] > (MAX_OUTPUT if kind == "out" else MAX_STDERR):
                    raise ProviderError("PROVIDER_OUTPUT_BOUND")
                if kind == "err":
                    continue
                try:
                    event = _parse(line)
                except (ValueError, UnicodeError, RecursionError):
                    raise ProviderError("PROVIDER_INVALID_STREAM") from None
                if type(event) is not dict:
                    raise ProviderError("PROVIDER_INVALID_STREAM")
                etype = event.get("type")
                if etype == "system":
                    if event.get("subtype") != "init":
                        continue
                    tools = event.get("tools")
                    if type(tools) is not list or any(type(t) is not str or not (t.startswith(TOOL_PREFIX) or t == STRUCTURED_TOOL)
                                                       for t in tools):
                        raise ProviderError("UNEXPECTED_AGENT_TOOL")
                    servers = event.get("mcp_servers")
                    if type(servers) is not list:
                        raise ProviderError("PROVIDER_RUN_FAILED")
                    mine = [s for s in servers if type(s) is dict and s.get("name") == "ziphap_device"]
                    if (len(mine) != 1 or mine[0].get("status") != "connected" or
                            any(type(s) is not dict or s.get("name") != "ziphap_device" for s in servers)):
                        raise ProviderError("PROVIDER_RUN_FAILED")
                    initialized = True
                elif etype == "assistant":
                    message = event.get("message")
                    content = message.get("content") if type(message) is dict else None
                    if type(content) is not list:
                        continue
                    for item in content:
                        if type(item) is not dict:
                            continue
                        if item.get("type") == "tool_use":
                            name = item.get("name")
                            if name == STRUCTURED_TOOL:
                                if type(item.get("id")) is str:
                                    pending[item["id"]] = None  # Final report, not a device call.
                                structured = item.get("input")
                                continue
                            if type(name) is not str or not name.startswith(TOOL_PREFIX):
                                raise ProviderError("UNEXPECTED_AGENT_TOOL")
                            short = name[len(TOOL_PREFIX):]
                            if type(item.get("id")) is str:
                                pending[item["id"]] = short
                            emit("tool_running", calls, short)
                        elif item.get("type") == "text":
                            emit("answering", calls)
                elif etype == "user":
                    message = event.get("message")
                    content = message.get("content") if type(message) is dict else None
                    if type(content) is not list:
                        continue
                    for item in content:
                        if type(item) is dict and item.get("type") == "tool_result":
                            key = item.get("tool_use_id")
                            if key not in pending:
                                raise ProviderError("UNEXPECTED_AGENT_TOOL")
                            short = pending.pop(key)
                            if short is None:
                                continue
                            calls += 1
                            if observe_tool is not None:
                                observe_tool(short, item.get("content"))
                            emit("tool_returned", calls, short)
                elif etype == "result":
                    if result_event is not None:
                        raise ProviderError("PROVIDER_INVALID_STREAM")
                    if event.get("subtype") != "success" or event.get("is_error") is not False:
                        raise ProviderError("PROVIDER_RUN_FAILED")
                    result_event = event
                    emit("verifying", calls)
                # stream_event, rate_limit_event and anything else: ignored, never echoed.
            code = child.wait(timeout=5)
            if stop.is_set():
                raise ProviderError("PROVIDER_CANCELLED")
            if code != 0 or not initialized or result_event is None:
                raise ProviderError("PROVIDER_RUN_FAILED")
            try:
                if result_event.get("structured_output") is not None:
                    value = result_event["structured_output"]
                elif structured is not None:
                    value = structured
                else:
                    final = result_event.get("result")
                    if not isinstance(final, str):
                        raise ValueError()
                    value = _parse(final)
                if not _valid_final(value):
                    raise ValueError()
            except (ValueError, TypeError, UnicodeError, RecursionError):
                raise ProviderError("PROVIDER_FINAL_FORMAT") from None
            return AgentReport(value["state"], value["answer"], value["question"],
                               round((time.monotonic() - started) * 1000), calls,
                               owner_choices(value["state"], value.get("choices")))
        finally:
            readers_stop.set()
            if tree is not None:
                tree.terminate()
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait(timeout=5)
            for stream in (child.stdin, child.stdout, child.stderr):
                try:
                    stream.close()
                except (OSError, ValueError):
                    pass
            for thread in threads:
                if thread.ident is not None:
                    thread.join(timeout=1)
            if tree is not None:
                tree.close()
