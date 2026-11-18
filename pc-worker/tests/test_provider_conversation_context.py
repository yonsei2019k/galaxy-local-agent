"""Synthetic history data only; no CLI, account, network, model or device."""
import json
import unittest

from provider_conversation_context import (ConversationContextError, conversation_context_digest,
    has_conversation_context, parse_conversation_context, provider_input, validate_conversation_context)

SOURCE = "30000000-0000-4000-8000-000000000003"


def fixture():
    return {"version": 1, "recentTurns": [
        {"role": "user", "text": "테스트 서비스 탈퇴 방법을 찾아줘", "sourceRunId": SOURCE},
        {"role": "assistant", "text": "설정의 계정 관리 화면까지 확인했습니다.", "sourceRunId": SOURCE}],
        "previousTask": {"sourceRunId": SOURCE, "status": "paused", "ownerRequest": "테스트 서비스 탈퇴",
                         "goal": "탈퇴 전 조건 확인", "unresolvedEffects": False}}


class ConversationContextTests(unittest.TestCase):
    def test_absence_and_explicit_empty_are_distinct_retry_bindings(self):
        empty = {"version": 1, "recentTurns": [], "previousTask": None}
        self.assertIsNone(validate_conversation_context(None))
        self.assertFalse(has_conversation_context(empty))
        self.assertEqual("현재 요청", provider_input("현재 요청", None))
        self.assertNotEqual(conversation_context_digest(None), conversation_context_digest(empty))

    def test_context_is_detached_and_canonical_digest_ignores_key_order(self):
        original = fixture(); validated = validate_conversation_context(original)
        reordered = dict(reversed(list(original.items())))
        self.assertEqual(conversation_context_digest(original), conversation_context_digest(reordered))
        original["recentTurns"][0]["text"] = "changed"
        original["previousTask"]["goal"] = "changed"
        self.assertNotEqual(original, validated)
        self.assertEqual(validated, parse_conversation_context(json.dumps(validated, ensure_ascii=False).encode()))

    def test_exact_shapes_reject_unknown_missing_and_wrong_types(self):
        invalid = [[], "history", {}, fixture() | {"tools": []}, fixture() | {"version": True},
                   fixture() | {"version": 2}, fixture() | {"recentTurns": {}},
                   fixture() | {"previousTask": []}]
        for key in ("version", "recentTurns", "previousTask"):
            invalid.append({k: v for k, v in fixture().items() if k != key})
        for key, replacement in (("role", "system"), ("role", None), ("text", 1),
                                 ("sourceRunId", "not-a-run")):
            value = fixture(); value["recentTurns"][0][key] = replacement; invalid.append(value)
        for key, replacement in (("status", "running"), ("status", 1), ("unresolvedEffects", 1),
                                 ("ownerRequest", None), ("goal", []), ("sourceRunId", SOURCE.upper())):
            value = fixture(); value["previousTask"][key] = replacement
            # SOURCE has digits only; use a noncanonical spelling instead.
            if key == "sourceRunId": value["previousTask"][key] = "{" + SOURCE + "}"
            invalid.append(value)
        for nested in ("recentTurns", "previousTask"):
            value = fixture(); target = value[nested][0] if nested == "recentTurns" else value[nested]
            target["approval"] = True; invalid.append(value)
        for value in invalid:
            with self.subTest(case=invalid.index(value)):
                with self.assertRaisesRegex(ConversationContextError, "^INVALID_CONVERSATION_CONTEXT$"):
                    validate_conversation_context(value)

    def test_each_text_limit_and_turn_limit_is_enforced_without_truncation(self):
        good = fixture(); good["recentTurns"] = [dict(good["recentTurns"][0], text="x" * 1500)]
        good["previousTask"]["ownerRequest"] = "x" * 3000
        good["previousTask"]["goal"] = "x" * 1500
        self.assertEqual(good, validate_conversation_context(good))
        invalid = []
        for field, limit in (("ownerRequest", 3000), ("goal", 1500)):
            value = fixture(); value["previousTask"][field] = "x" * (limit + 1); invalid.append(value)
        value = fixture(); value["recentTurns"][0]["text"] = "x" * 1501; invalid.append(value)
        value = fixture(); value["recentTurns"] *= 5; invalid.append(value)
        for value in invalid:
            with self.assertRaises(ConversationContextError): validate_conversation_context(value)

    def test_utf8_total_bound_is_bytes_and_invalid_unicode_is_rejected(self):
        # Each field is individually in bounds, but the combined UTF-8 budget is not.
        value = fixture(); value["recentTurns"] = [dict(value["recentTurns"][0], text="가" * 1500)] * 3
        with self.assertRaises(ConversationContextError): validate_conversation_context(value)
        for field in ("text", "ownerRequest", "goal"):
            value = fixture()
            target = value["recentTurns"][0] if field == "text" else value["previousTask"]
            target[field] = "private-synthetic\ud800"
            with self.assertRaises(ConversationContextError): validate_conversation_context(value)
        for raw in (b"\xff", '"\ud800"', b" " * 12001):
            with self.assertRaises(ConversationContextError): parse_conversation_context(raw)
        escaped_value = fixture(); escaped_value["recentTurns"][0]["text"] = "\ud800"
        with self.assertRaises(ConversationContextError): parse_conversation_context(json.dumps(escaped_value))

    def test_exact_12000_utf8_bytes_pass_and_one_extra_byte_fails(self):
        value = {"version": 1, "recentTurns": [
            {"role": "user", "text": "x" * 1400, "sourceRunId": SOURCE} for _ in range(8)], "previousTask": None}
        encode = lambda item: json.dumps(item, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        needed = 12000 - len(encode(value))
        self.assertTrue(0 <= needed <= 800)
        for turn in reversed(value["recentTurns"]):
            added = min(100, needed); turn["text"] += "x" * added; needed -= added
        self.assertEqual(0, needed)
        self.assertEqual(12000, len(encode(value)))
        self.assertEqual(value, validate_conversation_context(value))
        value["recentTurns"][0]["text"] += "x"
        with self.assertRaises(ConversationContextError): validate_conversation_context(value)

    def test_duplicate_keys_at_each_json_level_and_nonfinite_values_fail_closed(self):
        raw = json.dumps(fixture(), separators=(",", ":"))
        cases = [raw.replace('"version":1', '"version":1,"version":1'),
                 raw.replace('"role":"user"', '"role":"user","role":"assistant"'),
                 raw.replace('"status":"paused"', '"status":"paused","status":"completed"'),
                 raw.replace('"version":1', '"version":NaN'),
                 '[' * 1500 + '0' + ']' * 1500]
        for value in cases:
            with self.assertRaises(ConversationContextError): parse_conversation_context(value)

    def test_all_terminal_history_states_remain_data_and_do_not_alter_current_request(self):
        for state in ("completed", "paused", "failed", "cancelled", "interrupted"):
            value = fixture(); value["previousTask"].update(status=state, unresolvedEffects=True)
            payload = json.loads(provider_input("그 화면에서 조건만 다시 확인해", validate_conversation_context(value)))
            self.assertEqual({"currentOwnerRequest", "conversationContext"}, set(payload))
            self.assertEqual("그 화면에서 조건만 다시 확인해", payload["currentOwnerRequest"])
            self.assertTrue(payload["conversationContext"]["previousTask"]["unresolvedEffects"])
            self.assertNotIn("executionAuthority", payload)


if __name__ == "__main__": unittest.main()
