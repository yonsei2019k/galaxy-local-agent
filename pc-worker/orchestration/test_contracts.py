import ast
from dataclasses import replace
import json
from pathlib import Path
import unittest

import contracts
from contracts import (ContractError, Control, HostObservation, MAX_BYTES, MAX_DEPTH,
                       MAX_INTEGER, MAX_NODES, TaskRequest, parse_message, strict_json)

ROOT = Path(__file__).resolve().parents[2]
PROTOCOL = ROOT / "protocol" / "orchestration" / "v1"
GOLDEN = json.loads((PROTOCOL / "fixtures" / "messages.json").read_text(encoding="utf-8"))


def task_wire():
    return json.loads(next(c["raw"] for c in GOLDEN["cases"] if c["id"] == "task-local-read"))


def control_wire():
    return json.loads(next(c["raw"] for c in GOLDEN["cases"] if c["id"] == "control-start"))


class ContractTests(unittest.TestCase):
    def error(self, code, raw):
        with self.assertRaises(ContractError) as caught:
            parse_message(raw)
        self.assertEqual(code, caught.exception.code)
        self.assertEqual(code, str(caught.exception))

    def test_every_published_wire_golden(self):
        self.assertEqual(1, GOLDEN["fixture_version"])
        self.assertEqual(len(GOLDEN["cases"]), len({c["id"] for c in GOLDEN["cases"]}))
        kinds = set()
        for case in GOLDEN["cases"]:
            with self.subTest(case=case["id"]):
                if case["accepted"]:
                    parsed = parse_message(case["raw"])
                    self.assertEqual(case["kind"], parsed.wire()["kind"])
                    self.assertEqual(parsed, parse_message(json.dumps(parsed.wire())))
                    kinds.add(type(parsed))
                else:
                    self.error(case["error"], case["raw"])
        self.assertEqual({TaskRequest, Control, HostObservation}, kinds)

    def test_published_schemas_match_runtime_subset_without_remote_resolution(self):
        expected = contracts.schema_documents()
        self.assertEqual(3, len(expected))
        for filename, schema in expected.items():
            with self.subTest(schema=filename):
                actual = json.loads((PROTOCOL / filename).read_text(encoding="utf-8"))
                self.assertEqual(schema, actual)
                self.assertFalse(actual["additionalProperties"])
                self.assertEqual(set(actual["properties"]), set(actual["required"]))

    def test_schema_export_cannot_change_runtime_rules(self):
        exported = contracts.schema_documents()
        exported["task-request.schema.json"]["additionalProperties"] = True
        bad = task_wire() | {"grant": True}
        self.error("UNKNOWN_FIELD", json.dumps(bad))

    def test_duplicate_keys_at_multiple_depths(self):
        for raw in ('{"kind":"control","kind":"control"}',
                    '{"x":{"a":1,"a":2}}', '{"x":[{"a":1,"a":2}]}'):
            with self.subTest(raw=raw):
                self.error("DUPLICATE_KEY", raw)

    def test_non_finite_and_non_integer_lexemes(self):
        for number, code in (("NaN", "NON_FINITE"), ("Infinity", "NON_FINITE"),
                             ("-Infinity", "NON_FINITE"), ("1e999", "INTEGER_REQUIRED"),
                             ("1.0", "INTEGER_REQUIRED"), ("1e0", "INTEGER_REQUIRED")):
            with self.subTest(number=number):
                self.error(code, '{"kind":"control","epoch":' + number + '}')

    def test_bool_negative_and_unsafe_integer_revisions(self):
        for field in ("epoch", "expected_revision"):
            for value, code in ((True, "TYPE"), (False, "TYPE"), (-1, "INTEGER_BOUND"),
                                (MAX_INTEGER + 1, "INTEGER_BOUND")):
                with self.subTest(field=field, value=value):
                    self.error(code, json.dumps(control_wire() | {field: value}))
        parsed = parse_message(json.dumps(control_wire() | {"epoch": MAX_INTEGER}))
        self.assertEqual(MAX_INTEGER, parsed.epoch)

    def test_raw_type_and_utf8_are_checked_before_shape(self):
        for raw in (None, True, {}, [], bytearray(b"{}")):
            with self.subTest(type=type(raw).__name__):
                self.error("RAW_TYPE", raw)
        self.error("UTF8", b'"\xff"')
        self.error("UTF8", '"\ud800"')
        self.error("UNICODE_SCALAR", '"\\ud800"')

    def test_byte_limit_uses_utf8_bytes_and_has_an_exact_boundary(self):
        self.assertIsNone(strict_json("null" + " " * (MAX_BYTES - 4)))
        self.error("BYTE_BOUND", "null" + " " * (MAX_BYTES - 3))
        self.error("BYTE_BOUND", '"' + "한" * (MAX_BYTES // 3) + '"')

    def test_depth_and_node_budgets_reject_before_recursive_parsing(self):
        self.assertEqual([[]], strict_json("[[]]"))
        self.error("DEPTH_BOUND", "[" * (MAX_DEPTH + 1) + "]" * (MAX_DEPTH + 1))
        self.error("NODE_BOUND", json.dumps([0] * MAX_NODES))
        # Brackets inside escaped strings do not consume structural depth.
        self.assertEqual("[" * 20 + '"', strict_json(json.dumps("[" * 20 + '"')))

    def test_unknown_fields_in_every_wire_boundary(self):
        for raw in (task_wire() | {"binding": {}}, control_wire() | {"source": "owner"},
                    task_wire() | {"intent": task_wire()["intent"] | {"allow_external_agent": True}}):
            self.error("UNKNOWN_FIELD", json.dumps(raw))

    def test_missing_version_or_fields_do_not_infer_legacy_authority(self):
        for field in ("version", "request_id", "intent"):
            raw = task_wire()
            del raw[field]
            self.error("MISSING_FIELD", json.dumps(raw))

    def test_opaque_ids_digests_and_capability_bounds(self):
        for request_id, code in (("", "STRING_BOUND"), ("x" * 65, "STRING_BOUND"),
                                 ("../outside", "VALUE"), ("한글", "VALUE"), ("x\n", "VALUE")):
            self.error(code, json.dumps(task_wire() | {"request_id": request_id}))
        self.assertEqual("x" * 64, parse_message(json.dumps(task_wire() | {"request_id": "x" * 64})).request_id)
        for capabilities, code in (([], "ARRAY_BOUND"), (["unknown"], "VALUE"), ([True], "TYPE"),
                                   (["workspace.read"] * 4, "ARRAY_BOUND")):
            raw = task_wire()
            raw["intent"]["capabilities"] = capabilities
            self.error(code, json.dumps(raw))
        for digest, code in (("A" * 64, "VALUE"), ("a" * 63, "STRING_BOUND")):
            raw = task_wire()
            raw["intent"]["input_digest"] = digest
            self.error(code, json.dumps(raw))

    def test_canonical_task_digest_ignores_whitespace_key_order_and_capability_order(self):
        raw = task_wire()
        raw["intent"]["capabilities"] = ["workspace.read", "artifact.propose"]
        first = parse_message(json.dumps(raw, indent=2))
        raw["intent"]["capabilities"].reverse()
        second = parse_message(json.dumps(dict(reversed(list(raw.items())))))
        self.assertEqual(first.digest, second.digest)
        self.assertNotEqual(first.digest, replace(first, input_digest="b" * 64).digest)

    def test_direct_construction_cannot_skip_shape_checks(self):
        task = parse_message(json.dumps(task_wire()))
        for changes in ({"capabilities": frozenset({1, "workspace.read"})},
                        {"scope": "../escape"}, {"provider": "cloud"}):
            with self.subTest(changes=changes):
                with self.assertRaises(ContractError):
                    replace(task, **changes)

    def test_errors_do_not_echo_untrusted_text(self):
        marker = "synthetic-private-marker"
        with self.assertRaises(ContractError) as caught:
            parse_message('{"kind":"control","secret":"' + marker)
        self.assertNotIn(marker, str(caught.exception))

    def test_implementation_modules_have_no_execution_or_io_imports(self):
        allowed = {"__future__", "copy", "dataclasses", "hashlib", "json", "re", "enum", "threading", "contracts"}
        for name in ("contracts.py", "state.py"):
            tree = ast.parse(Path(__file__).with_name(name).read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    self.assertTrue(all(alias.name in allowed for alias in node.names))
                elif isinstance(node, ast.ImportFrom):
                    self.assertIn(node.module, allowed)
                elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                    self.assertNotIn(node.func.id, {"open", "eval", "exec", "compile", "__import__"})


if __name__ == "__main__":
    unittest.main()
