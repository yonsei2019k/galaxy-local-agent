"""Bounded historical data for a fresh provider run, never execution authority.

No history is loaded or persisted here. The authenticated phone selects the
same conversation/provider binding; the provider validates only this wire shape.
"""
import hashlib
import json
import uuid

MAX_CONTEXT_BYTES = 12000
_CONTEXT_KEYS = {"version", "recentTurns", "previousTask"}
_TURN_KEYS = {"role", "text", "sourceRunId"}
_TASK_KEYS = {"sourceRunId", "status", "ownerRequest", "goal", "unresolvedEffects"}
_STATUSES = {"completed", "paused", "failed", "cancelled", "interrupted"}


class ConversationContextError(ValueError):
    def __init__(self):
        super().__init__("INVALID_CONVERSATION_CONTEXT")


def _unique(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ConversationContextError()
        result[key] = value
    return result


def _text(value, limit):
    if type(value) is not str or len(value) > limit:
        raise ConversationContextError()
    value.encode("utf-8", errors="strict")
    return value


def _source(value):
    if type(value) is not str or str(uuid.UUID(value)) != value:
        raise ConversationContextError()
    return value


def _encoded(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False,
                      sort_keys=True, separators=(",", ":")).encode("utf-8", errors="strict")


def validate_conversation_context(value):
    """Return a detached exact-shape context or None; never truncate or coerce."""
    if value is None:
        return None
    try:
        if type(value) is not dict or set(value) != _CONTEXT_KEYS:
            raise ConversationContextError()
        if type(value["version"]) is not int or value["version"] != 1:
            raise ConversationContextError()
        turns = value["recentTurns"]
        if type(turns) is not list or len(turns) > 8:
            raise ConversationContextError()
        recent = []
        for turn in turns:
            if (type(turn) is not dict or set(turn) != _TURN_KEYS or type(turn["role"]) is not str or
                    turn["role"] not in ("user", "assistant")):
                raise ConversationContextError()
            recent.append({"role": turn["role"], "text": _text(turn["text"], 1500),
                           "sourceRunId": _source(turn["sourceRunId"])})
        task = value["previousTask"]
        previous = None
        if task is not None:
            if (type(task) is not dict or set(task) != _TASK_KEYS or
                    type(task["status"]) is not str or task["status"] not in _STATUSES or
                    type(task["unresolvedEffects"]) is not bool):
                raise ConversationContextError()
            previous = {"sourceRunId": _source(task["sourceRunId"]), "status": task["status"],
                        "ownerRequest": _text(task["ownerRequest"], 3000), "goal": _text(task["goal"], 1500),
                        "unresolvedEffects": task["unresolvedEffects"]}
        result = {"version": 1, "recentTurns": recent, "previousTask": previous}
        if len(_encoded(result)) > MAX_CONTEXT_BYTES:
            raise ConversationContextError()
        return result
    except (ValueError, TypeError, UnicodeError, AttributeError, RecursionError):
        raise ConversationContextError() from None


def parse_conversation_context(raw):
    """Strict JSON boundary for adapters; the HTTP parser must also reject duplicates."""
    try:
        if type(raw) is bytes:
            if len(raw) > MAX_CONTEXT_BYTES:
                raise ConversationContextError()
            raw = raw.decode("utf-8", errors="strict")
        elif type(raw) is not str:
            raise ConversationContextError()
        if len(raw.encode("utf-8", errors="strict")) > MAX_CONTEXT_BYTES:
            raise ConversationContextError()
        value = json.loads(raw, object_pairs_hook=_unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(ConversationContextError()))
        return validate_conversation_context(value)
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ConversationContextError() from None


def conversation_context_digest(value):
    """Canonical RAM-only retry binding; does not authenticate the historical data."""
    return hashlib.sha256(_encoded(validate_conversation_context(value))).hexdigest()


def has_conversation_context(value):
    return value is not None and bool(value["recentTurns"] or value["previousTask"] is not None)


def provider_input(owner_request, context):
    """Keep legacy stdin byte-for-byte when absent; context never changes owner digest."""
    if context is None:
        return owner_request
    return json.dumps({"currentOwnerRequest": owner_request, "conversationContext": context},
                      ensure_ascii=False, allow_nan=False, separators=(",", ":"))
