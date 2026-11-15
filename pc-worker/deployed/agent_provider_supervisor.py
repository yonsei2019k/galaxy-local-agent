"""RAM-only cloud child lifecycle. A provider report is never effect evidence."""
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import threading

from agent_bridge import BridgeError
from codex_agent_provider import ProviderConfig, ProviderError, run


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
    cancel: threading.Event = field(default_factory=threading.Event)
    state: str = "RUNNING"
    report: dict | None = field(default=None, repr=False)
    error: str | None = None


class AgentProviderSupervisor:
    def __init__(self, bridge, config, runner=run):
        self.bridge, self.config, self.runner = bridge, config, runner
        self.lock = threading.RLock()
        self.children = {}

    def _authorize(self, owner, sid, capability, request=None):
        return self.bridge.authenticate_phone(owner, sid, capability, owner_request=request)

    def health(self):
        with self.lock:
            return {"providerConfigured": self.config is not None,
                    "activeProviders": sum(child.state != "FINISHED" for child in self.children.values())}

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
        return {"state": child.state, "report": None if child.report is None else dict(child.report),
                "errorCode": child.error}

    def launch(self, owner, sid, capability, request):
        if type(request) is not str or not request.strip() or len(request.encode("utf-8")) > 48000:
            raise BridgeError("PROVIDER_REQUEST", 400)
        self._authorize(owner, sid, capability, request)
        digest = hashlib.sha256(request.encode("utf-8")).hexdigest()
        with self.lock:
            existing = self.children.get(sid)
            if existing:
                if existing.digest != digest:
                    raise BridgeError("PROVIDER_REQUEST_CHANGED", 409)
                return self._public(existing)
            if self.config is None:
                raise BridgeError("PROVIDER_NOT_CONFIGURED", 503)
            if len(self.children) >= 64 or any(c.state != "FINISHED" for c in self.children.values()):
                raise BridgeError("PROVIDER_BUSY", 409)
            bootstrap = self.bridge.bootstrap_agent(owner, sid, local_authenticated=True)
            child = _Child(digest)
            self.children[sid] = child
            threading.Thread(target=self._run, args=(child, owner, sid, capability, request, bootstrap),
                             name="ziphap-cloud-child", daemon=True).start()
            return self._public(child)

    def _run(self, child, owner, sid, capability, request, bootstrap):
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
            result = self.runner(self.config, bootstrap, request, external_allowed=True,
                                 cancelled=AuthorityCancellation(), timeout=300)
            with self.lock:
                child.report = {"state": result.state, "answer": result.answer, "question": result.question,
                                "elapsedMs": result.elapsed_ms, "mcpCalls": result.mcp_calls}
        except ProviderError as error:
            child.cancel.set()
            revoked = True
            try:
                self._revoke(owner, sid, capability)
            except BridgeError:
                revoked = False
            with self.lock:
                child.error = error.code if revoked else "PROVIDER_REVOCATION_UNCONFIRMED"
        except Exception:
            child.cancel.set()
            try:
                self._revoke(owner, sid, capability)
            except BridgeError:
                pass
            with self.lock:
                child.error = "PROVIDER_FAILED"
        finally:
            with self.lock:
                child.state = "FINISHED"

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
            if child.state != "FINISHED":
                child.state = "CANCELLING"
            self._revoke(owner, sid, capability)  # Before cancellation ACK, revoke unclaimed effects too.
            return self._public(child)
