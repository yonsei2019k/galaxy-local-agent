"""In-memory transition prototype. No executor, authentication, I/O, TTL, or service."""
from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum
from threading import RLock

from contracts import (CAPABILITIES, OPERATIONS, MAX_INTEGER, ContractError, Control,
                       HostObservation, TaskRequest, _id, _integer, _shape)


def _deny(code):
    raise ContractError(code)


class Source(Enum):
    OWNER = "owner_control"
    HOST = "host_observer"
    AGENT = "untrusted_agent"


class Phase(Enum):
    AWAITING_APPROVAL = "awaiting_approval"
    READY = "ready"
    RUNNING = "running"
    PAUSED = "paused"
    CANCEL_REQUESTED = "cancel_requested"
    CANCELLED = "cancelled"
    COMPLETED = "completed"


class Effect(Enum):
    NONE = "none"
    IN_FLIGHT = "in_flight"
    NOT_APPLIED = "not_applied"
    UNKNOWN = "unknown"
    VERIFIED = "host_attested_verified"


@dataclass(frozen=True)
class HostAuthority:
    """Synthetic trusted input, NOT a credential. Never construct it from message fields."""
    owner: str
    project: str
    branch: str
    scope: str
    target: str
    epoch: int
    revision: int
    capabilities: frozenset[str]
    allow_external_agent: bool = False  # Missing/legacy authority stays local-only.

    def __post_init__(self):
        for value in (self.owner, self.project, self.branch, self.scope):
            _shape(value, _id())
        for value in (self.epoch, self.revision):
            _shape(value, _integer())
        if self.target not in ("phone", "pc") or type(self.target) is not str:
            _deny("TYPE")
        if type(self.allow_external_agent) is not bool or type(self.capabilities) is not frozenset:
            _deny("TYPE")
        if any(type(v) is not str or v not in CAPABILITIES for v in self.capabilities):
            _deny("CAPABILITY")

    @property
    def identity(self):
        return (self.owner, self.project, self.branch, self.scope, self.target, self.epoch, self.revision)


@dataclass(frozen=True)
class ExecutionBinding:
    request: TaskRequest
    captured: HostAuthority


def _bind(request, authority):
    if type(request) is not TaskRequest or type(authority) is not HostAuthority:
        _deny("HOST_TYPE")
    if request.scope != authority.scope or request.target != authority.target:
        _deny("SCOPE")
    if not request.capabilities <= authority.capabilities:
        _deny("CAPABILITY_SUBSET")
    if OPERATIONS[request.operation] not in request.capabilities:
        _deny("REQUIRED_CAPABILITY")
    if request.provider == "external_agent" and not authority.allow_external_agent:
        _deny("EXTERNAL_DENIED")
    return ExecutionBinding(request, authority)


@dataclass(frozen=True)
class Snapshot:
    request_id: str
    request_digest: str
    revision: int
    phase: Phase
    effect: Effect
    attempts: int
    approval_id: str | None = None
    result_digest: str | None = None


@dataclass(frozen=True)
class _Approval:
    request_id: str
    request_digest: str
    identity: tuple
    start_revision: int
    consumed: bool = False


@dataclass(frozen=True)
class _Entry:
    binding: ExecutionBinding
    snapshot: Snapshot


class StateMachine:
    MAX_JOBS = 64

    def __init__(self, max_attempts=2):
        if type(max_attempts) is not int or not 1 <= max_attempts <= 3:
            _deny("ATTEMPT_BOUND")
        self._max_attempts = max_attempts
        self._jobs = {}
        self._keys = {}
        self._approvals = {}
        self._lock = RLock()

    @staticmethod
    def _current(entry, authority):
        if type(authority) is not HostAuthority:
            _deny("HOST_TYPE")
        captured, request = entry.binding.captured, entry.binding.request
        if captured.identity != authority.identity:
            _deny("STALE_BINDING")
        if not request.capabilities <= authority.capabilities:
            _deny("CAPABILITY_SUBSET")
        if request.provider == "external_agent" and not authority.allow_external_agent:
            _deny("EXTERNAL_DENIED")

    def _entry(self, request_id, authority):
        entry = self._jobs.get(request_id)
        if entry is None:
            _deny("UNKNOWN_REQUEST")
        self._current(entry, authority)
        return entry

    @staticmethod
    def _message(entry, message):
        view = entry.snapshot
        if message.scope != entry.binding.request.scope:
            _deny("SCOPE")
        if message.epoch != entry.binding.captured.epoch:
            _deny("STALE_EPOCH")
        if message.request_digest != view.request_digest:
            _deny("REQUEST_DIGEST")
        if message.expected_revision != view.revision:
            _deny("STALE_REVISION")
        if view.revision >= MAX_INTEGER:
            _deny("INTEGER_BOUND")

    def _store(self, entry, **changes):
        view = replace(entry.snapshot, revision=entry.snapshot.revision + 1, **changes)
        self._jobs[view.request_id] = replace(entry, snapshot=view)
        return view

    def submit(self, request, authority):
        binding = _bind(request, authority)
        with self._lock:
            key = (authority.owner, request.idempotency_key)
            previous_id = self._keys.get(key)
            if previous_id is not None:
                previous = self._entry(previous_id, authority)
                if previous.snapshot.request_digest != request.digest:
                    _deny("IDEMPOTENCY_CONFLICT")
                return previous.snapshot
            if request.request_id in self._jobs:
                _deny("REQUEST_ID_CONFLICT")
            if len(self._jobs) >= self.MAX_JOBS:
                _deny("JOB_BOUND")
            phase = Phase.AWAITING_APPROVAL if request.operation == "write_artifact" else Phase.READY
            view = Snapshot(request.request_id, request.digest, 0, phase, Effect.NONE, 0)
            self._jobs[request.request_id] = _Entry(binding, view)
            self._keys[key] = request.request_id
            return view

    def snapshot(self, request_id, authority):
        with self._lock:
            return self._entry(request_id, authority).snapshot

    def control(self, message, authority, *, source):
        if source is not Source.OWNER:
            _deny("UNTRUSTED_SOURCE")
        if type(message) is not Control:
            _deny("MESSAGE_TYPE")
        with self._lock:
            entry = self._entry(message.request_id, authority)
            self._message(entry, message)
            view = entry.snapshot
            approval_key = (authority.owner, message.approval_id)
            if message.action == "approve":
                if view.phase is not Phase.AWAITING_APPROVAL:
                    _deny("PHASE")
                if approval_key in self._approvals:
                    _deny("APPROVAL_REUSED")
                self._approvals[approval_key] = _Approval(view.request_id, view.request_digest,
                                                         authority.identity, view.revision + 1)
                return self._store(entry, phase=Phase.READY, approval_id=message.approval_id)
            if message.action == "start":
                if view.phase is not Phase.READY:
                    _deny("PHASE")
                if view.attempts >= self._max_attempts:
                    _deny("ATTEMPT_BOUND")
                if entry.binding.request.operation == "write_artifact":
                    key = (authority.owner, view.approval_id)
                    approval = self._approvals.get(key)
                    if approval is None or approval.consumed or (
                        approval.request_id, approval.request_digest, approval.identity, approval.start_revision
                    ) != (view.request_id, view.request_digest, authority.identity, view.revision):
                        _deny("APPROVAL_BINDING")
                    self._approvals[key] = replace(approval, consumed=True)
                return self._store(entry, phase=Phase.RUNNING, effect=Effect.IN_FLIGHT,
                                   attempts=view.attempts + 1, result_digest=None)
            if message.action == "cancel":
                if view.phase in (Phase.COMPLETED, Phase.CANCELLED, Phase.CANCEL_REQUESTED):
                    _deny("PHASE")
                return self._store(entry, phase=Phase.CANCEL_REQUESTED)
            if message.action == "retry":
                if view.effect in (Effect.UNKNOWN, Effect.IN_FLIGHT, Effect.VERIFIED):
                    _deny("EFFECT_UNCERTAIN_OR_APPLIED")
                if view.phase is not Phase.PAUSED or view.effect is not Effect.NOT_APPLIED:
                    _deny("PHASE")
                if view.attempts >= self._max_attempts:
                    _deny("ATTEMPT_BOUND")
                phase = Phase.AWAITING_APPROVAL if entry.binding.request.operation == "write_artifact" else Phase.READY
                return self._store(entry, phase=phase, approval_id=None)
            _deny("ACTION")

    def observe(self, message, authority, *, source):
        # Wire field names, JSON 'source', model prose, or exit status cannot select HOST.
        if source is not Source.HOST:
            _deny("UNTRUSTED_SOURCE")
        if type(message) is not HostObservation:
            _deny("MESSAGE_TYPE")
        with self._lock:
            entry = self._entry(message.request_id, authority)
            self._message(entry, message)
            view = entry.snapshot
            if message.event == "cancel_ack":
                if view.phase is not Phase.CANCEL_REQUESTED:
                    _deny("PHASE")
                effect = Effect.UNKNOWN if view.effect is Effect.IN_FLIGHT else view.effect
                return self._store(entry, phase=Phase.CANCELLED, effect=effect)
            if view.phase not in (Phase.RUNNING, Phase.PAUSED, Phase.CANCEL_REQUESTED, Phase.CANCELLED):
                _deny("PHASE")
            if view.phase is Phase.PAUSED and view.effect is not Effect.UNKNOWN:
                _deny("PHASE")
            if view.phase is Phase.CANCELLED and view.effect not in (Effect.UNKNOWN, Effect.IN_FLIGHT):
                _deny("PHASE")
            if message.event == "effect_verified":
                phase = Phase.COMPLETED if view.phase in (Phase.RUNNING, Phase.PAUSED) else view.phase
                return self._store(entry, phase=phase, effect=Effect.VERIFIED,
                                   result_digest=message.result_digest)
            if message.event in ("effect_unknown", "not_applied"):
                if view.effect is Effect.VERIFIED:
                    _deny("EFFECT_ALREADY_VERIFIED")
                effect = Effect.UNKNOWN if message.event == "effect_unknown" else Effect.NOT_APPLIED
                phase = Phase.PAUSED if view.phase is Phase.RUNNING else view.phase
                return self._store(entry, phase=phase, effect=effect)
            _deny("EVENT")
