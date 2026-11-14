"""Small, offline v1 shapes. A parsed message is never authentication or authority."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import hashlib
import json
import re

MAX_BYTES = 16 * 1024
MAX_DEPTH = 8
MAX_NODES = 512
MAX_INTEGER = 2**53 - 1
CAPABILITIES = ("workspace.read", "artifact.propose", "workspace.write")
OPERATIONS = {
    "inspect_workspace": "workspace.read",
    "propose_artifact": "artifact.propose",
    "write_artifact": "workspace.write",
}


class ContractError(ValueError):
    """Stable code only: never echo input, paths, credentials, or exception text."""
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _fail(code: str):
    raise ContractError(code)


def _id():
    return {"type": "string", "minLength": 1, "maxLength": 64,
            "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"}


def _digest():
    return {"type": "string", "pattern": "^[a-f0-9]{64}$", "minLength": 64, "maxLength": 64}


def _integer():
    return {"type": "integer", "minimum": 0, "maximum": MAX_INTEGER}


def _object(properties):
    return {"type": "object", "additionalProperties": False,
            "required": list(properties), "properties": properties}


def _schemas():
    intent = _object({
        "operation": {"type": "string", "enum": list(OPERATIONS)},
        "scope": _id(), "target": {"type": "string", "enum": ["phone", "pc"]},
        "provider": {"type": "string", "enum": ["local", "external_agent"]},
        "input_digest": _digest(),
        "capabilities": {"type": "array", "minItems": 1, "maxItems": 3,
                         "uniqueItems": True, "items": {"type": "string", "enum": list(CAPABILITIES)}},
    })
    base = lambda kind: {"version": {"type": "integer", "const": 1},
                         "kind": {"type": "string", "const": kind}}
    task = _object(base("task_request") | {
        "request_id": _id(), "idempotency_key": _id(), "intent": intent,
    })
    control = _object(base("control") | {
        "request_id": _id(), "scope": _id(), "epoch": _integer(),
        "expected_revision": _integer(), "request_digest": _digest(),
        "action": {"type": "string", "enum": ["approve", "start", "cancel", "retry"]},
        "approval_id": {"oneOf": [_id(), {"type": "null"}]},
    })
    control["allOf"] = [{
        "if": {"properties": {"action": {"const": "approve"}}},
        "then": {"properties": {"approval_id": _id()}},
        "else": {"properties": {"approval_id": {"type": "null"}}},
    }]
    observation = _object(base("host_observation") | {
        "request_id": _id(), "scope": _id(), "epoch": _integer(),
        "expected_revision": _integer(), "request_digest": _digest(),
        "event": {"type": "string", "enum": ["effect_verified", "effect_unknown", "not_applied", "cancel_ack"]},
        "result_digest": {"oneOf": [_digest(), {"type": "null"}]},
    })
    observation["allOf"] = [{
        "if": {"properties": {"event": {"const": "effect_verified"}}},
        "then": {"properties": {"result_digest": _digest()}},
        "else": {"properties": {"result_digest": {"type": "null"}}},
    }]
    return {"task-request.schema.json": task, "control.schema.json": control,
            "host-observation.schema.json": observation}


_SHAPES = _schemas()


def schema_documents():
    """Public schema files must match these small shapes; not a general schema engine."""
    return {name: {"$schema": "https://json-schema.org/draft/2020-12/schema",
                   "title": "ZipHap offline prototype " + name} | deepcopy(shape)
            for name, shape in _SHAPES.items()}


def _shape(value, schema):
    expected = schema.get("type")
    types = {"object": dict, "array": list, "string": str, "integer": int, "null": type(None)}
    if expected and type(value) is not types[expected]:
        _fail("TYPE")  # bool is deliberately not an integer.
    if "const" in schema and (type(value) is not type(schema["const"]) or value != schema["const"]):
        _fail("VALUE")
    if "enum" in schema and value not in schema["enum"]:
        _fail("VALUE")
    if type(value) is dict:
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False and set(value) - set(properties):
            _fail("UNKNOWN_FIELD")
        if set(schema.get("required", [])) - set(value):
            _fail("MISSING_FIELD")
        for key, rule in properties.items():
            if key in value:
                _shape(value[key], rule)
    if type(value) is str:
        if not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", MAX_BYTES):
            _fail("STRING_BOUND")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            _fail("VALUE")
    if type(value) is int and not schema.get("minimum", -MAX_INTEGER) <= value <= schema.get("maximum", MAX_INTEGER):
        _fail("INTEGER_BOUND")
    if type(value) is list:
        if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", MAX_NODES):
            _fail("ARRAY_BOUND")
        for item in value:
            _shape(item, schema.get("items", {}))
        if schema.get("uniqueItems") and len({json.dumps(v, sort_keys=True) for v in value}) != len(value):
            _fail("DUPLICATE_ITEM")
    if "oneOf" in schema:
        matched = 0
        for branch in schema["oneOf"]:
            try:
                _shape(value, branch)
                matched += 1
            except ContractError:
                pass
        if matched != 1:
            _fail("SHAPE_BRANCH")
    for clause in schema.get("allOf", []):
        if "if" not in clause:
            _shape(value, clause)
            continue
        try:
            _shape(value, clause["if"])
            branch = "then"
        except ContractError:
            branch = "else"
        _shape(value, clause.get(branch, {}))


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail("DUPLICATE_KEY")
        result[key] = value
    return result


def _int(text):
    if len(text.lstrip("-")) > 16:
        _fail("INTEGER_BOUND")
    value = int(text)
    if abs(value) > MAX_INTEGER:
        _fail("INTEGER_BOUND")
    return value


def _depth(text):
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > MAX_DEPTH:
                _fail("DEPTH_BOUND")
        elif char in "]}":
            depth -= 1
            if depth < 0:
                _fail("JSON_SYNTAX")


def strict_json(raw):
    if type(raw) not in (str, bytes):
        _fail("RAW_TYPE")
    try:
        encoded = raw.encode("utf-8") if type(raw) is str else raw
        if len(encoded) > MAX_BYTES:
            _fail("BYTE_BOUND")
        text = encoded.decode("utf-8")
    except UnicodeError:
        _fail("UTF8")
    _depth(text)
    try:
        value = json.loads(text, object_pairs_hook=_pairs, parse_int=_int,
                           parse_float=lambda _: _fail("INTEGER_REQUIRED"),
                           parse_constant=lambda _: _fail("NON_FINITE"))
    except (json.JSONDecodeError, RecursionError, ValueError) as error:
        if isinstance(error, ContractError):
            raise
        _fail("JSON_SYNTAX")
    stack, nodes = [value], 0
    while stack:
        item = stack.pop()
        nodes += 1
        if nodes > MAX_NODES:
            _fail("NODE_BOUND")
        if type(item) is dict:
            stack.extend(item.keys())
            stack.extend(item.values())
        elif type(item) is list:
            stack.extend(item)
        elif type(item) is str and any(0xD800 <= ord(c) <= 0xDFFF for c in item):
            _fail("UNICODE_SCALAR")
    return value


def canonical_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=True, allow_nan=False).encode("ascii")).hexdigest()


@dataclass(frozen=True)
class TaskRequest:
    request_id: str
    idempotency_key: str
    operation: str
    scope: str
    target: str
    provider: str
    input_digest: str
    capabilities: frozenset[str]

    def __post_init__(self):
        if type(self.capabilities) is not frozenset:
            _fail("TYPE")
        if any(type(item) is not str for item in self.capabilities):
            _fail("TYPE")
        _shape(self.wire(), _SHAPES["task-request.schema.json"])

    def wire(self):
        return {"version": 1, "kind": "task_request", "request_id": self.request_id,
                "idempotency_key": self.idempotency_key,
                "intent": {"operation": self.operation, "scope": self.scope, "target": self.target,
                           "provider": self.provider, "input_digest": self.input_digest,
                           "capabilities": sorted(self.capabilities)}}

    @property
    def digest(self):
        return canonical_digest(self.wire())


@dataclass(frozen=True)
class Control:
    request_id: str
    scope: str
    epoch: int
    expected_revision: int
    request_digest: str
    action: str
    approval_id: str | None

    def __post_init__(self):
        _shape(self.wire(), _SHAPES["control.schema.json"])

    def wire(self):
        return {"version": 1, "kind": "control", **self.__dict__}


@dataclass(frozen=True)
class HostObservation:
    request_id: str
    scope: str
    epoch: int
    expected_revision: int
    request_digest: str
    event: str
    result_digest: str | None

    def __post_init__(self):
        _shape(self.wire(), _SHAPES["host-observation.schema.json"])

    def wire(self):
        return {"version": 1, "kind": "host_observation", **self.__dict__}


def parse_message(raw):
    message = strict_json(raw)
    if type(message) is not dict:
        _fail("TYPE")
    names = {"task_request": "task-request.schema.json", "control": "control.schema.json",
             "host_observation": "host-observation.schema.json"}
    kind = message.get("kind")
    if type(kind) is not str or kind not in names:
        _fail("KIND")
    _shape(message, _SHAPES[names[kind]])
    if kind == "task_request":
        return TaskRequest(message["request_id"], message["idempotency_key"],
                           **(message["intent"] | {"capabilities": frozenset(message["intent"]["capabilities"])}))
    fields = {key: value for key, value in message.items() if key not in ("version", "kind")}
    return (Control if kind == "control" else HostObservation)(**fields)
