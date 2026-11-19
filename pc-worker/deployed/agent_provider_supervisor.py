"""RAM-only cloud child lifecycle. A provider report is never effect evidence."""
from dataclasses import dataclass, field
import hashlib
import inspect
import json
from pathlib import Path
import threading
import time
import re

_EVENTS = Path(__file__).resolve().parent.parent / "provider-events.log"


def _event(outcome, calls=None, elapsed_ms=None):
    """Operator diagnostics: fixed outcome codes and counts only, never request, answer or tool content."""
    try:
        if _EVENTS.exists() and _EVENTS.stat().st_size > 1024 * 1024:
            _EVENTS.replace(_EVENTS.with_suffix(".log.1"))
        with _EVENTS.open("a", encoding="utf-8") as out:
            out.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {outcome} calls={calls} ms={elapsed_ms}\n")
    except OSError:
        pass

from agent_bridge import BridgeError
from codex_agent_provider import ProviderConfig, ProviderError, run
from provider_conversation_context import (ConversationContextError, conversation_context_digest,
                                           has_conversation_context, validate_conversation_context)


def load_config(home):
    """Operator-pinned local configuration; no remotely supplied executable or path."""
    try:
        source = Path(home) / "agent-provider.json"
        if not source.is_file() or source.stat().st_size > 16384:
            return None
        def unique(items):
            value = {}
            for key, item in items:
                if key in value:
                    raise ValueError("DUPLICATE_CONFIG")
                value[key] = item
            return value
        data = json.loads(source.read_text(encoding="utf-8"), object_pairs_hook=unique)
        required = {"executable", "executableSha256", "python", "mcpScript", "mcpScriptSha256", "runtimeDirectory", "workspace"}
        if type(data) is not dict or not required <= data.keys() or data.keys() - required - {"model"}:
            return None
        if any(type(data[key]) is not str or not Path(data[key]).is_absolute() for key in
               ("executable", "python", "mcpScript", "runtimeDirectory", "workspace")):
            return None
        return ProviderConfig(Path(data["executable"]), data["executableSha256"], Path(data["python"]),
                              Path(data["mcpScript"]), data["mcpScriptSha256"], Path(data["runtimeDirectory"]),
                              Path(data["workspace"]), data.get("model"))
    except (OSError, ValueError, UnicodeError, TypeError):
        return None


@dataclass
class _Child:
    digest: str
    owner: str = field(default="", repr=False)
    capability: str = field(default="", repr=False)
    tool_names: frozenset = field(default_factory=frozenset, repr=False)
    cancel: threading.Event = field(default_factory=threading.Event)
    state: str = "RUNNING"
    report: dict | None = field(default=None, repr=False)
    error: str | None = None
    progress: dict | None = field(default=None, repr=False)
    started: float = field(default_factory=time.monotonic, repr=False)
    progress_updates: int = 0
    context_digest: str = field(default="", repr=False)


class AgentProviderSupervisor:
    def __init__(self, bridge, config, runner=run):
        self.bridge, self.config, self.runner = bridge, config, runner
        self.lock = threading.RLock()
        self.children = {}

    def _authorize(self, owner, sid, capability, request=None):
        return self.bridge.authenticate_phone(owner, sid, capability, owner_request=request)

    def health(self):
        with self.lock:
            self._reconcile_revocations()
            return {"providerConfigured": self.config is not None,
                    "activeProviders": sum(child.state != "FINISHED" for child in self.children.values())}

    def _reconcile_revocations(self):
        for sid, child in self.children.items():
            if child.state != "REVOCATION_UNCONFIRMED":
                continue
            try:
                self._revoke(child.owner, sid, child.capability)
            except BridgeError:
                continue
            child.state = "FINISHED"
            child.error = "PROVIDER_FAILED"

    def _revoke(self, owner, sid, capability):
        try:
            snapshot = self._authorize(owner, sid, capability)
            status, result = self.bridge.dispatch_authenticated("POST", "/v1/agent/sessions/" + sid + "/cancel",
                                                               owner, {"revision": snapshot["revision"]}, capability)
            if status >= 400:
                raise BridgeError("PROVIDER_REVOCATION_UNCONFIRMED", status)
            return result
        except BridgeError as error:
            if error.status != 410:  # Expiry/restart already revokes the original session.
                raise

    @staticmethod
    def _public(child):
        result = {"state": child.state, "report": None if child.report is None else dict(child.report),
                  "errorCode": child.error}
        if child.progress is not None:
            result["progress"] = dict(child.progress)
        return result

    def _collect_finished(self):
        """Bound terminal RAM without making an evicted session replayable."""
        for sid, child in list(self.children.items()):
            if len(self.children) < 64:
                break
            if child.state != "FINISHED":
                continue
            # Revoke before dropping the idempotency receipt. An old launch can no longer
            # obtain fresh agent authority, even while its original phone session exists.
            self._revoke(child.owner, sid, child.capability)
            del self.children[sid]

    def _progress(self, child, value):
        if type(value) is not dict or value.get("stage") not in {
                "starting", "planning", "tool_running", "tool_returned", "verifying", "answering"}:
            return
        calls = value.get("calls", 0)
        tool = value.get("toolName")
        if type(calls) is not int or not 0 <= calls <= 128:
            return
        if tool is not None and (type(tool) is not str or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,79}", tool) is None):
            return
        with self.lock:
            if child.state != "RUNNING" or child.cancel.is_set() or child.progress_updates >= 512:
                return
            # Timings and sequence belong to this supervisor, not model data/callback keys.
            child.progress_updates += 1
            child.progress = {"sequence": child.progress_updates, "stage": value["stage"],
                              "elapsedMs": min(600000, max(0, round((time.monotonic() - child.started) * 1000))),
                              "calls": max(calls, (child.progress or {}).get("calls", 0))}
            if tool in child.tool_names:
                child.progress["toolName"] = tool

    def _supports_keyword(self, name, *, variadic=True):
        try:
            return any((p.name == name and p.kind in (inspect.Parameter.POSITIONAL_OR_KEYWORD,
                                                     inspect.Parameter.KEYWORD_ONLY)) or
                       (variadic and p.kind == inspect.Parameter.VAR_KEYWORD)
                       for p in inspect.signature(self.runner).parameters.values())
        except (TypeError, ValueError):
            return False

    def launch(self, owner, sid, capability, request, conversation_context=None):
        try:
            valid_request = type(request) is str and request.strip() and len(request.encode("utf-8")) <= 48000
        except UnicodeError:
            valid_request = False
        if not valid_request:
            raise BridgeError("PROVIDER_REQUEST", 400)
        try:
            context = validate_conversation_context(conversation_context)
            context_digest = conversation_context_digest(context)
        except ConversationContextError:
            raise BridgeError("INVALID_CONVERSATION_CONTEXT", 400) from None
        snapshot = self._authorize(owner, sid, capability, request)
        digest = hashlib.sha256(request.encode("utf-8")).hexdigest()
        with self.lock:
            self._reconcile_revocations()
            existing = self.children.get(sid)
            if existing:
                if existing.digest != digest:
                    raise BridgeError("PROVIDER_REQUEST_CHANGED", 409)
                if existing.context_digest != context_digest:
                    raise BridgeError("PROVIDER_CONTEXT_CHANGED", 409)
                return self._public(existing)
            if self.config is None:
                raise BridgeError("PROVIDER_NOT_CONFIGURED", 503)
            # An explicit keyword declaration acknowledges the new data contract.
            # A generic **kwargs legacy stub may silently discard it, so is insufficient.
            if has_conversation_context(context) and not self._supports_keyword("conversation_context", variadic=False):
                raise BridgeError("PROVIDER_CONTEXT_UNSUPPORTED", 503)
            if any(c.state != "FINISHED" for c in self.children.values()):
                raise BridgeError("PROVIDER_BUSY", 409)
            self._collect_finished()
            if len(self.children) >= 64:
                raise BridgeError("PROVIDER_BUSY", 409)
            bootstrap = self.bridge.bootstrap_agent(owner, sid, local_authenticated=True)
            tools = frozenset(item.get("name") for item in snapshot.get("tools", [])
                              if type(item) is dict and type(item.get("name")) is str)
            child = _Child(digest, owner, capability, tools, context_digest=context_digest)
            self.children[sid] = child
            threading.Thread(target=self._run, args=(child, owner, sid, capability, request, bootstrap, context),
                             name="ziphap-cloud-child", daemon=True).start()
            return self._public(child)

    def _run(self, child, owner, sid, capability, request, bootstrap, conversation_context):
        def revoked():
            if child.cancel.is_set():
                return True
            try:
                self._authorize(owner, sid, capability, request)
                return False
            except BridgeError:
                child.cancel.set()
                return True
        class AuthorityCancellation:
            def is_set(self):
                return revoked()
        try:
            kwargs = {"external_allowed": True, "cancelled": AuthorityCancellation(), "timeout": 300}
            if self._supports_keyword("progress"):
                kwargs["progress"] = lambda value: self._progress(child, value)
            if conversation_context is not None and self._supports_keyword("conversation_context", variadic=False):
                kwargs["conversation_context"] = conversation_context
            total_calls = total_ms = 0
            for attempt in range(2):
                result = self.runner(self.config, bootstrap, request, **kwargs)
                if (result.state not in {"reported_done","needs_input","partial","failed"} or
                        type(result.answer) is not str or len(result.answer)>12000 or
                        type(result.question) is not str or len(result.question)>300 or
                        type(result.elapsed_ms) is not int or not 0<=result.elapsed_ms<=600000 or
                        type(result.mcp_calls) is not int or not 0<=result.mcp_calls<=128):
                    raise ProviderError("PROVIDER_FINAL_FORMAT")
                total_calls += result.mcp_calls
                total_ms += result.elapsed_ms
                if attempt or not self._supports_keyword("read_recovery", variadic=False) or revoked():
                    break
                remaining = int(300 - (time.monotonic() - child.started))
                if remaining <= 0:
                    break
                recovery = self.bridge.read_recovery_context(owner, sid, capability, before_plan=result.state != "reported_done")
                if recovery is None:
                    break
                kwargs.update(read_recovery=recovery, timeout=remaining)
                _event("RECONSIDER_READ", total_calls, total_ms)
            with self.lock:
                # Cancellation ACK and publication share this gate. Recheck the broker's
                # exact current request too; a pre-lock check leaves a revocation race.
                if revoked():
                    raise ProviderError("PROVIDER_CANCELLED")
                child.report = {"state": result.state, "answer": result.answer, "question": result.question,
                                "elapsedMs": total_ms, "mcpCalls": total_calls}
            _event("REPORT_" + str(result.state).upper()[:24], total_calls, total_ms)
        except ProviderError as error:
            child.cancel.set()
            revoked = True
            try:
                self._revoke(owner, sid, capability)
            except BridgeError:
                revoked = False
            with self.lock:
                child.error = error.code if revoked else "PROVIDER_REVOCATION_UNCONFIRMED"
            _event(child.error)
        except Exception:
            child.cancel.set()
            confirmed = True
            try:
                self._revoke(owner, sid, capability)
            except BridgeError:
                confirmed = False
            with self.lock:
                child.error = "PROVIDER_FAILED" if confirmed else "PROVIDER_REVOCATION_UNCONFIRMED"
            _event(child.error)
        finally:
            with self.lock:
                child.state = "REVOCATION_UNCONFIRMED" if child.error == "PROVIDER_REVOCATION_UNCONFIRMED" else "FINISHED"

    def status(self, owner, sid, capability):
        self._authorize(owner, sid, capability)
        with self.lock:
            child = self.children.get(sid)
            if child is None:
                raise BridgeError("PROVIDER_NOT_STARTED", 404)
            return self._public(child)

    def cancel(self, owner, sid, capability):
        self._authorize(owner, sid, capability)
        with self.lock:
            child = self.children.get(sid)
            if child is None:
                raise BridgeError("PROVIDER_NOT_STARTED", 404)
            child.cancel.set()
            previously_unconfirmed = child.state == "REVOCATION_UNCONFIRMED"
            if child.state != "FINISHED":
                child.state = "CANCELLING"
            try:
                self._revoke(owner, sid, capability)  # Before cancellation ACK, revoke unclaimed effects too.
            except BridgeError:
                if previously_unconfirmed:
                    child.state = "REVOCATION_UNCONFIRMED"
                raise
            if previously_unconfirmed:
                child.state = "FINISHED"
            return self._public(child)
