"""Experimental authenticated device bridge, independent of orchestration/v1.

Root's HTTPS Handler MUST authenticate the existing paired bearer before calling
dispatch_authenticated. Credentials never come from a model tool argument. The
two session capabilities distinguish agent proposals from phone execution reports.
All personal arguments/results are RAM-only. A new process revokes every session;
there is deliberately no deserializer that can resume or replay a claimed write.
No server/config/environment/process/model/ADB integration is performed on import.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, field
import copy
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import struct
import threading
import time
import uuid


RESTART_POLICY = "RAM_ONLY_RESTART_REVOKES"
MAX_CALLS = 18
MAX_TTL_MS = 900_000
PHONE_LEASE_MS = 30_000
MAX_ARGUMENT_BYTES = 32_768
MAX_RESULT_BYTES = 131_072
MAX_WIRE_BYTES = 262_144
PC_TOOLS = (
    {"name": "pc_inspect_workspace", "description": "Read one bound workspace directory; no recursion or writes.",
     "inputSchema": {"type": "object", "additionalProperties": False, "properties": {
         "path": {"type": "string", "maxLength": 512}, "limit": {"type": "integer", "minimum": 1, "maximum": 100}}}},
    {"name": "pc_read_file", "description": "Read a complete UTF-8 file within the bound workspace, at most 32768 bytes; no truncation or writes.",
     "inputSchema": {"type": "object", "additionalProperties": False, "properties": {
         "path": {"type": "string", "minLength": 1, "maxLength": 512},
         "maxBytes": {"type": "integer", "minimum": 1, "maximum": 32768}}, "required": ["path"]}},
)
_PATTERNS = frozenset((r"^[a-f0-9]{64}$", r"^[a-fA-F0-9]{64}$", r"^[0-9]{1,19}$",
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))


class BridgeError(Exception):
    """Only fixed error codes; never stringify arguments, capabilities or file paths."""
    def __init__(self, code, status=400):
        self.code, self.status = code, status
        super().__init__(code)


def _require(condition, code, status=400):
    if not condition:
        raise BridgeError(code, status)


def canonical(value):
    """Public resultAck digest format: sorted keys, compact JSON, strict UTF-8."""
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False,
                          separators=(",", ":")).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise BridgeError("JSON_VALUE") from None


def _bounded_json(value, maximum):
    count = 0
    def visit(item, depth=0):
        nonlocal count
        count += 1
        _require(depth <= 24 and count <= 8192, "JSON_BOUND")
        if isinstance(item, dict):
            _require(all(type(k) is str for k in item), "JSON_KEY")
            for child in item.values():
                visit(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                visit(child, depth + 1)
        else:
            _require(item is None or type(item) in (str, bool, int, float), "JSON_VALUE")
            if type(item) is float:
                _require(math.isfinite(item), "JSON_NUMBER")
    visit(value)
    raw = canonical(value)
    _require(len(raw) <= maximum, "JSON_BOUND")
    return raw


def parse_wire(raw):
    """Optional adapter helper. Existing Handler.body already rejects duplicate keys."""
    _require(type(raw) in (bytes, str), "JSON_OBJECT")
    if isinstance(raw, bytes):
        _require(len(raw) <= MAX_WIRE_BYTES, "JSON_BOUND")
        try:
            raw = raw.decode("utf-8", "strict")
        except UnicodeError:
            raise BridgeError("JSON_UTF8") from None
    def pairs(items):
        result = {}
        for key, value in items:
            _require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    def invalid_constant(_):
        raise BridgeError("JSON_NUMBER")
    try:
        value = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, RecursionError, UnicodeError):
        raise BridgeError("JSON_FORMAT") from None
    _require(type(value) is dict, "JSON_OBJECT")
    _bounded_json(value, MAX_WIRE_BYTES)
    return value


def _shape(value, required, optional=()):
    _require(type(value) is dict and set(required) <= value.keys()
             and value.keys() <= set(required) | set(optional), "BODY_FIELDS")


def _integer(value, low, high):
    _require(type(value) is int and low <= value <= high, "INTEGER_BOUND")
    return value


def _text(value, maximum, blank=False):
    _require(type(value) is str and len(value) <= maximum and "\x00" not in value
             and (blank or bool(value.strip())), "TEXT_BOUND")
    canonical(value)
    return value


def _uuid(value):
    try:
        _require(type(value) is str and str(uuid.UUID(value)) == value, "UUID_REQUIRED")
    except (ValueError, AttributeError):
        raise BridgeError("UUID_REQUIRED") from None
    return value


def _sha(value):
    _require(type(value) is str and re.fullmatch("[a-f0-9]{64}", value) is not None, "SHA_REQUIRED")
    return value


def _schema(schema, depth=0):
    """Small fail-closed subset actually used by the offered Android tool schema.

    Unsupported schema keywords decline registration; they are never silently ignored.
    Patterns are the fixed bounded identifier patterns, not a regex supplied by a model.
    """
    _require(depth <= 12 and type(schema) is dict, "SCHEMA_BOUND")
    allowed = {"type", "properties", "required", "additionalProperties", "oneOf", "items", "enum",
               "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems", "uniqueItems",
               "pattern", "format", "description"}
    _require(schema.keys() <= allowed, "SCHEMA_UNSUPPORTED")
    if "description" in schema:
        _text(schema["description"], 2048, True)
    if "type" in schema:
        _require(schema["type"] in ("object", "array", "string", "integer", "boolean", "number"), "SCHEMA_TYPE")
    if "oneOf" in schema:
        _require(type(schema["oneOf"]) is list and 1 <= len(schema["oneOf"]) <= 8, "SCHEMA_BOUND")
        for variant in schema["oneOf"]:
            _schema(variant, depth + 1)
    if "properties" in schema:
        _require(type(schema["properties"]) is dict and len(schema["properties"]) <= 40, "SCHEMA_BOUND")
        for key, spec in schema["properties"].items():
            _text(key, 80)
            _schema(spec, depth + 1)
    if schema.get("type") == "object":
        _require(schema.get("additionalProperties") is False, "SCHEMA_OPEN_OBJECT")
    if "required" in schema:
        required = schema["required"]
        _require(type(required) is list and len(required) <= 40 and all(type(x) is str for x in required)
                 and len(set(required)) == len(required), "SCHEMA_REQUIRED")
        if "properties" in schema:
            _require(set(required) <= schema["properties"].keys(), "SCHEMA_REQUIRED")
    if "pattern" in schema:
        _require(type(schema["pattern"]) is str and schema["pattern"] in _PATTERNS, "SCHEMA_PATTERN")
    if "format" in schema:
        _require(schema["format"] == "date-time", "SCHEMA_FORMAT")
    if "enum" in schema:
        _require(type(schema["enum"]) is list and 1 <= len(schema["enum"]) <= 64
                 and all(type(x) in (str, int, bool) for x in schema["enum"]), "SCHEMA_ENUM")
    for key in ("minLength", "maxLength", "minItems", "maxItems"):
        if key in schema:
            _integer(schema[key], 0, 131072)
    for key in ("minimum", "maximum"):
        if key in schema:
            _require(type(schema[key]) in (int, float) and math.isfinite(schema[key]), "SCHEMA_NUMBER")
    for key in ("additionalProperties", "uniqueItems"):
        if key in schema:
            _require(type(schema[key]) is bool, "SCHEMA_BOOLEAN")
    if "items" in schema:
        _schema(schema["items"], depth + 1)


def _validate(value, schema):
    kind = schema.get("type")
    if kind:
        valid = {"object": type(value) is dict, "array": type(value) is list,
                 "string": type(value) is str, "integer": type(value) is int,
                 "boolean": type(value) is bool,
                 "number": type(value) in (int, float) and math.isfinite(value)}[kind]
        _require(valid, "ARGUMENT_TYPE")
    if type(value) is dict:
        _require(set(schema.get("required", ())) <= value.keys(), "ARGUMENT_REQUIRED")
        if schema.get("additionalProperties") is False:
            _require(value.keys() <= schema.get("properties", {}).keys(), "ARGUMENT_UNKNOWN")
        if "properties" in schema:
            props = schema["properties"]
            for key in value.keys() & props.keys():
                _validate(value[key], props[key])
    if "oneOf" in schema:
        matches = 0
        for variant in schema["oneOf"]:
            try:
                _validate(value, variant)
                matches += 1
            except BridgeError:
                pass
        _require(matches == 1, "ARGUMENT_ONE_OF")
    if "enum" in schema:
        _require(any(type(value) is type(x) and value == x for x in schema["enum"]), "ARGUMENT_ENUM")
    if type(value) is str:
        _require(schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", MAX_ARGUMENT_BYTES), "ARGUMENT_LENGTH")
        if "pattern" in schema:
            _require(re.fullmatch(schema["pattern"], value) is not None, "ARGUMENT_PATTERN")
        if "format" in schema:
            from datetime import datetime
            try:
                parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
                _require(parsed.tzinfo is not None, "ARGUMENT_DATETIME")
            except ValueError:
                raise BridgeError("ARGUMENT_DATETIME") from None
    if type(value) in (int, float):
        _require(schema.get("minimum", -float("inf")) <= value <= schema.get("maximum", float("inf")), "ARGUMENT_RANGE")
    if type(value) is list:
        _require(schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", 128), "ARGUMENT_LENGTH")
        if schema.get("uniqueItems"):
            _require(len({canonical(x) for x in value}) == len(value), "ARGUMENT_UNIQUE")
        if "items" in schema:
            for item in value:
                _validate(item, schema["items"])


def result_digest(result):
    _shape(result, ("success", "content", "verification", "effectNotApplied"))
    _require(type(result["success"]) is bool and type(result["effectNotApplied"]) is bool, "RESULT_BOOLEAN")
    _text(result["content"], MAX_RESULT_BYTES, True)
    _require(result["verification"] in ("VERIFIED", "PREPARED", "UNVERIFIED", "FAILED"), "RESULT_VERIFICATION")
    _require(result["verification"] not in ("VERIFIED", "PREPARED") or result["success"], "RESULT_EFFECT_CONFLICT")
    _require(not result["effectNotApplied"] or
             not result["success"] and result["verification"] == "FAILED", "RESULT_EFFECT_CONFLICT")
    return hashlib.sha256(_bounded_json(result, MAX_RESULT_BYTES)).hexdigest()


@dataclass(repr=False)
class _Call:
    call_id: str
    sequence: int
    tool: str
    arguments: dict = field(repr=False)
    payload_digest: str
    state: str = "QUEUED"
    claim_revision: int | None = None
    result: dict | None = field(default=None, repr=False)
    result_hash: str | None = None
    source: str | None = None


@dataclass(repr=False)
class _Session:
    session_id: str
    owner: str
    project: str
    authority: int
    request_digest: str
    profile: str | None
    tools: dict = field(repr=False)
    phone_hash: str = field(repr=False)
    agent_key: str = field(repr=False)
    deadline: int
    expires_at: int
    phone_seen: int
    confirmed: bool = False
    revision: int = 1
    state: str = "ACTIVE"
    calls: dict = field(default_factory=dict, repr=False)
    active: str | None = None
    last_ack: dict | None = None
    require_plan: bool = False
    plan: dict | None = field(default=None, repr=False)


class AgentBridge:
    """Thread-safe RAM broker; only Root's authenticated HTTP adapter calls this.

    pc_executor(profile,tool,args) is a trusted read-only injection for tests/host
    integration. Default executes only the two fixed WorkspaceExecutor operations.
    No configuration is read automatically and no credentials are logged.
    """
    def __init__(self, workspace_profiles=None, *, clock_ms=None, epoch_ms=None,
                 pc_executor=None, max_sessions=64):
        self._clock = clock_ms or (lambda: int(time.monotonic() * 1000))
        self._epoch = epoch_ms or (lambda: int(time.time() * 1000))
        _require("phone-only" not in (workspace_profiles or {}), "WORKSPACE_PROFILE_RESERVED")
        self._profiles = {name: WorkspaceExecutor(path) for name, path in (workspace_profiles or {}).items()}
        self._pc_executor = pc_executor
        self._limit = _integer(max_sessions, 1, 128)
        self._sessions = {}
        self._owners = set()
        self._lock = threading.RLock()
        self._last_clock = None

    def __repr__(self):
        return "AgentBridge(private RAM sessions; restart revokes)"

    def _now(self):
        now = self._clock()
        _integer(now, 0, 2**63 - 1)
        if self._last_clock is not None and now < self._last_clock:
            for s in self._sessions.values():
                self._revoke(s, "REVOKED")
            raise BridgeError("CLOCK_ROLLBACK_REVOKED", 410)
        self._last_clock = now
        return now

    def _revoke(self, session, target):
        if session.state in ("CLOSED", "EXPIRED", "REVOKED"):
            return
        session.state = target
        session.revision += 1
        if session.active:
            call = session.calls[session.active]
            if call.state == "QUEUED":
                call.state = "CANCELLED"
                session.active = None
            elif call.state in ("CLAIMED", "RUNNING"):
                call.state = "UNKNOWN_EFFECT"
                if target == "REVOKED":
                    session.state = "CANCELLING"

    def _session(self, owner, sid):
        _uuid(owner); _uuid(sid)
        session = self._sessions.get(sid)
        _require(session is not None, "SESSION_MISSING_OR_RESTART_REVOKED", 410)
        _require(hmac.compare_digest(session.owner, owner), "SESSION_AUTHORITY", 403)
        now = self._now()
        if session.state == "ACTIVE" and now >= session.deadline:
            self._revoke(session, "EXPIRED")
        if session.state == "ACTIVE" and now - session.phone_seen >= PHONE_LEASE_MS:
            self._revoke(session, "REVOKED")
        return session, now

    @staticmethod
    def _role(session, capability):
        _require(type(capability) is str and re.fullmatch(r"[A-Za-z0-9_-]{32,512}", capability) is not None,
                 "SESSION_CAPABILITY", 403)
        digest = hashlib.sha256(capability.encode("ascii")).hexdigest()
        if hmac.compare_digest(digest, session.phone_hash):
            return "phone"
        if hmac.compare_digest(capability, session.agent_key):
            return "agent"
        raise BridgeError("SESSION_CAPABILITY", 403)

    @staticmethod
    def _active(session):
        _require(session.state == "ACTIVE", "SESSION_NOT_ACTIVE", 410)
        _require(session.confirmed, "PHONE_NOT_CONFIRMED", 409)

    def _pending(self, s):
        if not s.active:
            return None
        c = s.calls[s.active]
        if c.tool.startswith("pc_"):
            return None
        value = {"callId": c.call_id, "sequence": c.sequence, "state": c.state, "tool": c.tool,
                 "arguments": copy.deepcopy(c.arguments), "requestDigest": s.request_digest,
                 "projectBinding": s.project, "authorityRevision": s.authority}
        if c.claim_revision is not None:
            value["claimRevision"] = c.claim_revision
        return value

    def _snapshot(self, s):
        value = {"sessionId": s.session_id, "revision": s.revision, "state": s.state,
                 "expiresAtEpochMs": s.expires_at, "lastSequence": len(s.calls),
                 "allowExternalAgent": True, "tools": copy.deepcopy(list(s.tools.values())),
                 "pendingCall": self._pending(s), "restartPolicy": RESTART_POLICY}
        if s.last_ack:
            value["resultAck"] = copy.deepcopy(s.last_ack)
        if s.require_plan:
            value["requiresPlan"] = True
            value["plan"] = copy.deepcopy(s.plan)
        return value

    @staticmethod
    def _call_view(c):
        value = {"callId": c.call_id, "sequence": c.sequence, "state": c.state}
        if c.result is not None:
            value["result"] = copy.deepcopy(c.result)
            value["resultSource"] = c.source
        return value

    def _create(self, owner, body):
        _uuid(owner)
        _shape(body, ("version", "parentRunId", "projectBinding", "authorityRevision", "expiresAfterMs",
                      "phoneTools", "pcWorkspaceProfile", "requestDigest"), ("allowExternalAgent", "requirePlan"))
        _require(body.get("allowExternalAgent") is True, "EXTERNAL_AGENT_NOT_ALLOWED", 403)
        require_plan = body.get("requirePlan", False)
        _require(type(require_plan) is bool, "PLAN_REQUIRED_BOOLEAN")
        _require(type(body["version"]) is int and body["version"] == 1 and body["parentRunId"] == owner, "GRANT_BINDING")
        project = _text(body["projectBinding"], 256)
        authority = _integer(body["authorityRevision"], 0, 2**63 - 1)
        ttl = _integer(body["expiresAfterMs"], 1000, MAX_TTL_MS)
        digest = _sha(body["requestDigest"])
        profile = body["pcWorkspaceProfile"]
        _require(profile is None or type(profile) is str and
                 (profile == "phone-only" or profile in self._profiles), "WORKSPACE_PROFILE")
        offered = body["phoneTools"]
        _require(type(offered) is list and len(offered) <= 32, "TOOL_CATALOG_BOUND")
        catalog = {}
        for spec in offered:
            _shape(spec, ("name", "description", "inputSchema"))
            name = spec["name"]
            _require(type(name) is str and re.fullmatch(r"[a-z][a-z0-9_]{0,63}", name) is not None
                     and not name.startswith("pc_") and name not in catalog, "TOOL_NAME")
            _text(spec["description"], 2048, True)
            _schema(spec["inputSchema"])
            _require(spec["inputSchema"].get("type") == "object", "TOOL_SCHEMA_OBJECT")
            catalog[name] = copy.deepcopy(spec)
        if profile is not None and profile != "phone-only":
            catalog.update({item["name"]: copy.deepcopy(item) for item in PC_TOOLS})
        _require(not require_plan or "plan_task" not in catalog, "PLAN_TOOL_RESERVED")
        _require(bool(catalog), "EMPTY_TOOL_CATALOG")
        _bounded_json(list(catalog.values()), 65_536)
        _require(owner not in self._owners, "PARENT_ALREADY_REGISTERED", 409)
        _require(len(self._sessions) < self._limit, "SESSION_CAPACITY", 429)
        now = self._now()
        phone, agent = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        s = _Session(str(uuid.uuid4()), owner, project, authority, digest, profile, catalog,
                     hashlib.sha256(phone.encode("ascii")).hexdigest(), agent, now + ttl,
                     self._epoch() + ttl, now)
        s.require_plan = require_plan
        self._sessions[s.session_id] = s
        self._owners.add(owner)  # Never reset an 18-call budget under the same parent run.
        return self._snapshot(s) | {"phoneCapability": phone}

    def bootstrap_agent(self, owner, session_id, *, local_authenticated=False):
        """Root-only loopback+paired-auth function, not a public agent/MCP route.

        Root may adapt this only through its authenticated loopback control IPC;
        never expose it through public agent routes or MCP tools. Root passes the
        result to private RAM launcher state, not model arguments/logs/Git. No
        phone capability is returned.
        """
        _require(local_authenticated is True, "LOCAL_BOOTSTRAP_REQUIRED", 403)
        with self._lock:
            s, _ = self._session(owner, session_id)
            self._active(s)
            return {"sessionId": s.session_id, "parentRunId": s.owner,
                    "agentCapability": s.agent_key, "revision": s.revision}

    def authenticate_phone(self, owner, session_id, capability, owner_request=None):
        """Root host adapter: role-only for status/cancel; exact owner text for launch.

        Does not launch a provider or return secrets. Missing text means role-only,
        NOT a successful launch check. Request text is hashed transiently, never
        stored here. Handler must still perform its existing paired authentication.
        """
        with self._lock:
            s, _ = self._session(owner, session_id)
            _require(self._role(s, capability) == "phone", "CAPABILITY_ROLE", 403)
            if owner_request is not None:
                self._active(s)
                _text(owner_request, 12000)
                digest = hashlib.sha256(owner_request.encode("utf-8", "strict")).hexdigest()
                _require(hmac.compare_digest(digest, s.request_digest), "OWNER_REQUEST_DIGEST", 403)
            return self._snapshot(s)

    @staticmethod
    def _plan_text(value, maximum):
        _text(value, maximum)
        _require(len(value.encode("utf-16-le", "strict")) // 2 <= maximum, "PLAN_TEXT_BOUND")

    def _propose_plan(self, s, body):
        self._active(s)
        _require(s.require_plan, "PLAN_NOT_REQUIRED", 409)
        _shape(body, ("goal", "requiredTools", "constraints"))
        self._plan_text(body["goal"], 1000)
        names, constraints = body["requiredTools"], body["constraints"]
        _require(type(names) is list and len(names) <= 12 and
                 all(type(name) is str and name in s.tools for name in names), "PLAN_TOOLS")
        _require(len(set(names)) == len(names), "PLAN_TOOLS")
        _require(type(constraints) is list and len(constraints) <= 6, "PLAN_CONSTRAINTS")
        for item in constraints:
            self._plan_text(item, 240)
        bound = {"version": 1, "parentRunId": s.owner, "projectBinding": s.project,
                 "authorityRevision": s.authority, "requestDigest": s.request_digest,
                 "goal": body["goal"], "requiredTools": names, "constraints": constraints}
        digest = hashlib.sha256(canonical(bound)).hexdigest()
        if s.plan is not None:
            _require(hmac.compare_digest(s.plan["planDigest"], digest), "PLAN_PAYLOAD_CONFLICT", 409)
            return self._snapshot(s)
        _require(not s.calls, "PLAN_AFTER_EFFECTS", 409)
        s.plan = {"state": "AWAITING_HOST", "planDigest": digest, "requestDigest": s.request_digest,
                  "goal": body["goal"], "requiredTools": copy.deepcopy(names), "constraints": copy.deepcopy(constraints)}
        s.revision += 1
        return self._snapshot(s)

    @staticmethod
    def _complete_pc_observation(c):
        """Operation-level current host evidence; never phone or whole-goal evidence."""
        if c.source != "PC_WORKSPACE" or c.state != "COMPLETED" or c.result is None:
            return False
        r = c.result
        if not r["success"] or r["verification"] != "VERIFIED" or r["effectNotApplied"]:
            return False
        try:
            if result_digest(r) != c.result_hash:
                return False
            data = parse_wire(r["content"])
            if data.get("path") != c.arguments.get("path", "") or data.get("readOnly") is not True or data.get("filesChanged") is not False:
                return False
            if data.get("partial") is not False:
                return False
            if c.tool == "pc_inspect_workspace":
                entries = data.get("entries")
                return (data.get("enumerationComplete") is True and data.get("recursive") is False
                        and type(entries) is list and len(entries) <= c.arguments.get("limit", 50))
            if c.tool == "pc_read_file":
                text = data.get("text")
                if type(text) is not str or data.get("complete") is not True:
                    return False
                raw = text.encode("utf-8", "strict")
                return (type(data.get("bytes")) is int and data["bytes"] == len(raw)
                        and len(raw) <= c.arguments.get("maxBytes", 32768)
                        and data.get("sha256") == hashlib.sha256(raw).hexdigest())
            return False
        except (BridgeError, UnicodeError, ValueError):
            return False

    def task_evidence(self, owner, session_id, capability):
        """Phone-authenticated metadata/PC evidence only, not AgentReport completion.

        Phone results are deliberately not exported as Android effects. Root must
        join the actual Android callback ledger/TaskHarness/readback independently.
        Call before close drops private PC result bodies; no digest-only repair.
        """
        with self._lock:
            s, _ = self._session(owner, session_id)
            _require(self._role(s, capability) == "phone", "CAPABILITY_ROLE", 403)
            accepted = s.plan if s.require_plan and s.plan and s.plan["state"] == "ACCEPTED" else None
            required = copy.deepcopy(accepted["requiredTools"]) if accepted else []
            pc_calls, phone_calls, verified_pc, unresolved, prepared = [], [], set(), [], []
            for c in s.calls.values():
                metadata = {"callId": c.call_id, "sequence": c.sequence, "tool": c.tool, "state": c.state}
                if c.state in ("QUEUED", "CLAIMED", "RUNNING", "UNKNOWN_EFFECT"):
                    unresolved.append(copy.deepcopy(metadata))
                if c.result is not None and c.result["verification"] == "PREPARED":
                    prepared.append(copy.deepcopy(metadata))
                if c.tool.startswith("pc_"):
                    complete = self._complete_pc_observation(c)
                    row = metadata | {"evidenceSource": "BROKER_PC", "verifiedComplete": complete}
                    if c.result is not None:
                        row |= {"arguments": copy.deepcopy(c.arguments), "result": copy.deepcopy(c.result), "resultDigest": c.result_hash}
                    pc_calls.append(row)
                    if complete:
                        verified_pc.add(c.tool)
                else:
                    phone_calls.append(metadata)  # No server phone content/verification is promoted.
            value = {"sessionId": s.session_id, "parentRunId": s.owner, "revision": s.revision, "state": s.state,
                     "projectBinding": s.project, "authorityRevision": s.authority, "requestDigest": s.request_digest,
                     "acceptedPlan": copy.deepcopy(accepted), "requiredTools": required,
                     "verifiedPcTools": sorted(verified_pc), "pcCalls": pc_calls, "phoneCalls": phone_calls,
                     "phoneToolsRequiringLocalEvidence": [name for name in required if not name.startswith("pc_")],
                     "unresolvedCalls": unresolved, "preparedCalls": prepared,
                     "phoneEvidenceImported": False, "restartPolicy": RESTART_POLICY}
            _bounded_json(value, MAX_WIRE_BYTES)  # Never silently clip evidence and call it complete.
            return value

    def dispatch_authenticated(self, method, path, owner, request=None, capability=None):
        """Only call AFTER existing paired-token HTTPS Handler authentication.

        Returns (HTTP status, JSON object), with no variable exception details.
        Duplicate-key raw bodies must be rejected by Handler.body/parse_wire.
        """
        try:
            _uuid(owner)
            _require(type(path) is str and "?" not in path and "%" not in path, "ROUTE")
            if method == "POST" and path == "/v1/agent/sessions":
                _bounded_json(request, MAX_WIRE_BYTES)
                with self._lock:
                    return 201, self._create(owner, request)
            match = re.fullmatch(r"/v1/agent/sessions/([0-9a-f-]{36})(?:/(call|claim|result|cancel|close|plan|plan-ack)|/calls/([0-9a-f-]{36}))?", path)
            _require(match is not None, "ROUTE", 404)
            sid, operation, call_id = match.groups()
            if method == "POST":
                _bounded_json(request, MAX_WIRE_BYTES)
            with self._lock:
                s, now = self._session(owner, sid)
                role = self._role(s, capability)
                if method == "GET" and operation is None and call_id is None:
                    if role == "phone" and s.state == "ACTIVE":
                        s.phone_seen, s.confirmed = now, True
                    elif role == "agent":
                        self._active(s)
                    return 200, self._snapshot(s)
                if method == "GET" and call_id:
                    _require(role == "agent", "CAPABILITY_ROLE", 403)
                    self._active(s)
                    _uuid(call_id)
                    _require(call_id in s.calls, "CALL_MISSING", 404)
                    return 200, self._call_view(s.calls[call_id])
                _require(method == "POST" and operation is not None, "METHOD", 405)
                if operation == "plan":
                    _require(role == "agent", "CAPABILITY_ROLE", 403)
                    return 200, self._propose_plan(s, request)
                if operation == "call":
                    _require(role == "agent", "CAPABILITY_ROLE", 403)
                    self._active(s)
                    _shape(request, ("callId", "tool", "arguments"))
                    cid = _uuid(request["callId"])
                    tool, arguments = request["tool"], request["arguments"]
                    _require(type(tool) is str, "TOOL_NOT_OFFERED", 403)
                    if s.require_plan:
                        _require(s.plan is not None and s.plan["state"] == "ACCEPTED", "PLAN_NOT_ACCEPTED", 409)
                        _require(tool in s.plan["requiredTools"], "PLAN_TOOL_NOT_SELECTED", 403)
                    _require(type(arguments) is dict, "ARGUMENT_OBJECT")
                    payload = hashlib.sha256(_bounded_json({"tool": tool, "arguments": arguments}, MAX_ARGUMENT_BYTES)).hexdigest()
                    if cid in s.calls:
                        c = s.calls[cid]
                        _require(hmac.compare_digest(c.payload_digest, payload), "CALL_PAYLOAD_CONFLICT", 409)
                        return 200, self._call_view(c)  # Never dispatch the same call twice.
                    _require(tool in s.tools, "TOOL_NOT_OFFERED", 403)
                    _require(not tool.startswith("pc_") or s.profile in self._profiles,
                             "WORKSPACE_PROFILE", 403)
                    _require(s.active is None, "CALL_ALREADY_ACTIVE", 409)
                    _require(len(s.calls) < MAX_CALLS, "CALL_BUDGET", 429)
                    _validate(arguments, s.tools[tool]["inputSchema"])
                    c = _Call(cid, len(s.calls) + 1, tool, copy.deepcopy(arguments), payload)
                    s.calls[cid], s.active = c, cid
                    s.revision += 1
                    if not tool.startswith("pc_"):
                        return 202, self._call_view(c)
                    c.state, c.source = "RUNNING", "PC_WORKSPACE"
                    profile = s.profile
                else:
                    _require(role == "phone", "CAPABILITY_ROLE", 403)
                    return self._phone(s, operation, request)
            # Only the fixed two read-only PC tools execute outside the session lock.
            try:
                if self._pc_executor:
                    result = self._pc_executor(profile, tool, copy.deepcopy(arguments))
                else:
                    result = self._profiles[profile].execute(tool, arguments)
                digest = result_digest(result)
            except Exception:
                result = {"success": False, "content": "PC_WORKSPACE_READ_FAILED", "verification": "FAILED", "effectNotApplied": True}
                digest = result_digest(result)
            with self._lock:
                s, _ = self._session(owner, sid)
                c = s.calls[cid]
                if s.state != "ACTIVE":
                    c.state, s.active = "CANCELLED", None
                    if s.state == "CANCELLING":
                        s.state = "REVOKED"
                else:
                    c.state, c.result, c.result_hash, s.active = "COMPLETED", copy.deepcopy(result), digest, None
                s.revision += 1
                return 200, self._call_view(c)
        except BridgeError as error:
            return error.status, {"error": error.code}

    def _phone(self, s, operation, body):
        if operation == "plan-ack":
            self._active(s)
            _require(s.require_plan and s.plan is not None, "PLAN_MISSING", 409)
            _shape(body, ("planDigest", "accepted"))
            _require(type(body["accepted"]) is bool, "PLAN_ACK_FIELDS")
            _sha(body["planDigest"])
            _require(hmac.compare_digest(s.plan["planDigest"], body["planDigest"]), "PLAN_DIGEST_CONFLICT", 409)
            target = "ACCEPTED" if body["accepted"] else "REJECTED"
            if s.plan["state"] != "AWAITING_HOST":
                _require(s.plan["state"] == target, "PLAN_ACK_CONFLICT", 409)
                return 200, self._snapshot(s)
            s.plan["state"] = target
            s.revision += 1
            return 200, self._snapshot(s)
        if operation == "claim":
            self._active(s)
            _shape(body, ("callId", "sequence", "revision"))
            cid = _uuid(body["callId"])
            _require(cid in s.calls and cid == s.active, "CLAIM_NOT_CURRENT", 409)
            c = s.calls[cid]
            _require(body["sequence"] == c.sequence and type(body["sequence"]) is int, "CALL_SEQUENCE", 409)
            _require(type(body["revision"]) is int, "REVISION_CONFLICT", 409)
            if c.state == "CLAIMED":
                _require(body["revision"] == c.claim_revision - 1, "REVISION_CONFLICT", 409)
                return 200, self._snapshot(s)  # Receipt replay, never permission to execute again.
            _require(c.state == "QUEUED", "CALL_NOT_QUEUED", 409)
            _require(type(body["revision"]) is int and body["revision"] == s.revision, "REVISION_CONFLICT", 409)
            s.revision += 1
            c.state, c.claim_revision = "CLAIMED", s.revision
            return 200, self._snapshot(s)
        if operation == "result":
            _shape(body, ("callId", "sequence", "revision", "result"))
            cid = _uuid(body["callId"])
            _require(cid in s.calls, "CALL_MISSING", 404)
            c = s.calls[cid]
            _require(type(body["sequence"]) is int and body["sequence"] == c.sequence
                     and type(body["revision"]) is int and body["revision"] == c.claim_revision, "RESULT_CLAIM_BINDING", 409)
            digest = result_digest(body["result"])
            if c.result_hash is not None:
                _require(hmac.compare_digest(c.result_hash, digest), "RESULT_PAYLOAD_CONFLICT", 409)
                value = self._snapshot(s)
                value["resultAck"] = {"callId": cid, "sequence": c.sequence, "resultDigest": digest}
                return 200, value
            _require(s.active == cid and c.state in ("CLAIMED", "UNKNOWN_EFFECT"), "RESULT_NOT_CLAIMED", 409)
            c.result, c.result_hash, c.source = copy.deepcopy(body["result"]), digest, "PHONE_EXECUTOR"
            known = c.result["verification"] in ("VERIFIED", "PREPARED") or c.result["effectNotApplied"]
            c.state = "COMPLETED" if known else "UNKNOWN_EFFECT"
            if known:
                s.active = None
                if s.state == "CANCELLING":
                    s.state = "REVOKED"
            s.last_ack = {"callId": cid, "sequence": c.sequence, "resultDigest": digest}
            s.revision += 1
            return 200, self._snapshot(s)
        _shape(body, ("revision",))
        revision = _integer(body["revision"], 1, s.revision)
        if operation == "cancel":
            self._revoke(s, "REVOKED")
            return 200, self._snapshot(s)
        _require(operation == "close", "ROUTE", 404)
        _require(revision == s.revision, "REVISION_CONFLICT", 409)
        _require(s.active is None, "UNRESOLVED_CALL", 409)
        s.state, s.revision = "CLOSED", s.revision + 1
        for c in s.calls.values():
            c.arguments, c.result = {}, None  # Drop private bodies, not a fake persistent completion.
        return 200, self._snapshot(s)


class WorkspaceExecutor:
    """Bounded read-only host-configured workspace. No shell, ADB, URL or file writes.

    POSIX walks with directory handles/O_NOFOLLOW. Windows opens and retains every
    component with OPEN_REPARSE_POINT and share-read only, rejects reparse handles,
    and enumerates through the verified directory handle, not a raced path string.
    """
    def __init__(self, root):
        self._root = Path(os.path.abspath(os.fspath(root)))
        _require(self._root.is_absolute(), "WORKSPACE_ROOT")
        info = self._root.lstat()
        _require(stat.S_ISDIR(info.st_mode) and not self._linked(info), "WORKSPACE_ROOT")
        _require(self._root.resolve(strict=True) == self._root, "WORKSPACE_ROOT_REPARSE")
        if os.name == "nt":
            fd = self._win_open(self._root)
        else:
            _require(hasattr(os, "O_NOFOLLOW"), "WORKSPACE_PLATFORM")
            fd = os.open(self._root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY)
        try:
            # Use the same handle-stat API on every operation. Path and handle
            # stat can expose different-width file IDs on Windows/Python versions.
            opened = os.fstat(fd)
            _require(stat.S_ISDIR(opened.st_mode) and opened.st_ino != 0, "WORKSPACE_IDENTITY_UNAVAILABLE")
            self._identity = (opened.st_dev, opened.st_ino)
        finally:
            os.close(fd)

    def __repr__(self):
        return "WorkspaceExecutor(private configured root)"

    @staticmethod
    def _linked(info):
        return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)

    @staticmethod
    def _parts(path):
        _text(path, 512, True)
        _require(not any(ch in path for ch in ("\\", ":", "\x00")) and not path.startswith("/"), "WORKSPACE_PATH")
        parts = path.split("/") if path else []
        _require(len(parts) <= 16, "WORKSPACE_PATH")
        for item in parts:
            _require(item not in ("", ".", "..") and item[-1:] not in (" ", ".")
                     and not any(ord(c) < 32 for c in item), "WORKSPACE_PATH")
            name = item.split(".")[0].upper()
            _require(name not in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"} and re.fullmatch(r"(?:COM|LPT)[0-9]", name) is None, "WORKSPACE_PATH")
        return parts

    @staticmethod
    def _win_open(path):
        import ctypes
        from ctypes import wintypes
        import msvcrt
        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.CreateFileW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                                    wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE)
        api.CreateFileW.restype = wintypes.HANDLE
        api.CloseHandle.argtypes = (wintypes.HANDLE,)
        api.GetFileInformationByHandleEx.argtypes = (wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD)
        handle = api.CreateFileW(str(path), 0x80000000, 1, None, 3, 0x00200000 | 0x02000000, None)
        _require(handle != ctypes.c_void_p(-1).value, "WORKSPACE_OPEN")
        try:
            attrs = ctypes.create_string_buffer(8)
            _require(api.GetFileInformationByHandleEx(handle, 9, attrs, 8), "WORKSPACE_ATTRIBUTES")
            _require(not (struct.unpack_from("<I", attrs.raw)[0] & 0x400), "WORKSPACE_REPARSE")
            fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
            handle = None  # fd owns the native handle now.
            return fd
        finally:
            if handle is not None:
                api.CloseHandle(handle)

    @contextmanager
    def _open(self, parts, directory=False):
        handles = []
        try:
            if os.name == "nt":
                handles.append(self._win_open(self._root))
            else:
                _require(hasattr(os, "O_NOFOLLOW") and os.open in os.supports_dir_fd, "WORKSPACE_PLATFORM")
                handles.append(os.open(self._root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY))
            root_info = os.fstat(handles[0])
            _require((root_info.st_dev, root_info.st_ino) == self._identity, "WORKSPACE_CHANGED")
            for index, item in enumerate(parts):
                is_dir = index < len(parts) - 1 or directory
                if os.name == "nt":
                    fd = self._win_open(self._root.joinpath(*parts[:index + 1]))
                else:
                    flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0)
                    if is_dir:
                        flags |= os.O_DIRECTORY
                    fd = os.open(item, flags, dir_fd=handles[-1])
                handles.append(fd)
                info = os.fstat(fd)
                _require(not self._linked(info) and info.st_dev == root_info.st_dev, "WORKSPACE_REPARSE")
                _require(stat.S_ISDIR(info.st_mode) if is_dir else stat.S_ISREG(info.st_mode), "WORKSPACE_FILE_TYPE")
            yield handles[-1]
        finally:
            for fd in reversed(handles):
                os.close(fd)

    @staticmethod
    def _win_entries(fd):
        import ctypes
        from ctypes import wintypes
        import msvcrt
        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.GetFileInformationByHandleEx.argtypes = (wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD)
        handle = msvcrt.get_osfhandle(fd)
        first = True
        while True:
            data = ctypes.create_string_buffer(65536)
            if not api.GetFileInformationByHandleEx(handle, 11 if first else 10, data, len(data)):
                if ctypes.get_last_error() == 18:  # ERROR_NO_MORE_FILES
                    return
                raise BridgeError("WORKSPACE_ENUMERATION")
            first = False
            at = 0
            while True:
                _require(at + 104 <= len(data), "WORKSPACE_ENUMERATION")
                next_offset = struct.unpack_from("<I", data.raw, at)[0]
                attrs, length = struct.unpack_from("<II", data.raw, at + 56)
                size = struct.unpack_from("<q", data.raw, at + 40)[0]
                _require(length % 2 == 0 and at + 104 + length <= len(data), "WORKSPACE_ENUMERATION")
                name = data.raw[at + 104:at + 104 + length].decode("utf-16-le", "strict")
                if name not in (".", ".."):
                    yield name, bool(attrs & 0x10), bool(attrs & 0x400), max(0, size)
                if next_offset == 0:
                    break
                _require(next_offset >= 104 and next_offset % 8 == 0, "WORKSPACE_ENUMERATION")
                at += next_offset

    def execute(self, tool, arguments):
        _require(tool in {x["name"] for x in PC_TOOLS}, "PC_TOOL")
        _validate(arguments, next(x["inputSchema"] for x in PC_TOOLS if x["name"] == tool))
        parts = self._parts(arguments.get("path", ""))
        if tool == "pc_read_file":
            _require(bool(parts), "WORKSPACE_FILE_REQUIRED")
            maximum = arguments.get("maxBytes", 32768)
            with self._open(parts) as fd:
                before = os.fstat(fd)
                _require(before.st_nlink == 1 and before.st_size <= maximum, "WORKSPACE_READ_BOUND")
                data = os.read(fd, maximum + 1)
                after = os.fstat(fd)
                _require(len(data) == before.st_size and len(data) <= maximum and
                         (before.st_ino, before.st_size, before.st_mtime_ns) ==
                         (after.st_ino, after.st_size, after.st_mtime_ns), "WORKSPACE_CHANGED")
                text = data.decode("utf-8", "strict")
                value = {"path": "/".join(parts), "text": text, "bytes": len(data),
                         "sha256": hashlib.sha256(data).hexdigest(), "complete": True, "partial": False,
                         "readOnly": True, "filesChanged": False}
        else:
            limit, entries = arguments.get("limit", 50), []
            with self._open(parts, directory=True) as fd:
                if os.name == "nt":
                    iterator = self._win_entries(fd)
                    for name, folder, linked, size in iterator:
                        entries.append({"name": name, "directory": folder, "readable": not linked, "reparse": linked, "bytes": None if folder else size})
                        if len(entries) > limit:
                            break
                else:
                    with os.scandir(fd) as iterator:
                        for entry in iterator:
                            info = entry.stat(follow_symlinks=False)
                            linked, folder = self._linked(info), stat.S_ISDIR(info.st_mode)
                            entries.append({"name": entry.name, "directory": folder, "readable": not linked, "reparse": linked, "bytes": None if folder else info.st_size})
                            if len(entries) > limit:
                                break
            value = {"path": "/".join(parts), "entries": entries[:limit], "partial": len(entries) > limit,
                     "enumerationComplete": len(entries) <= limit, "readOnly": True, "filesChanged": False,
                     "recursive": False}
        return {"success": True, "content": canonical(value).decode("utf-8"), "verification": "VERIFIED", "effectNotApplied": False}
