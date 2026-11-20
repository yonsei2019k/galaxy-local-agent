import concurrent.futures
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from collab_dispatch import Dispatch, patch_sources, validation_command


class Mailbox:
    def __init__(self, folder):
        self.folder = folder
        self.events = []
        self.seen = set()

    def __call__(self, path, timeout):
        after = int(path.split("after=")[1].split("&")[0])
        for actor in ("codex", "claude"):
            file = self.folder / f"collab-published-{actor}.md"
            if not file.exists():
                continue
            for section in file.read_text(encoding="utf-8").split("## [")[1:]:
                mid, rest = section.split("]", 1)
                if mid in self.seen:
                    continue
                self.seen.add(mid)
                text = (f"[{mid}]" + rest).strip()
                recipient = text.split(" | to: ")[1].splitlines()[0]
                self.events.append({"sequence": len(self.events) + 1, "messageId": mid,
                                    "sender": actor, "recipient": recipient, "text": text,
                                    "dataOnly": True, "executionAuthority": False,
                                    "bodyDigest": hashlib.sha256(recipient.encode() + b"\0" + text.encode()).hexdigest()})
        return {"dataOnly": True, "executionAuthority": False, "messages": self.events[after:after + 50]}


class Jobs:
    def __init__(self):
        self.calls = []

    def submit(self, func, *args):
        future = concurrent.futures.Future()
        self.calls.append((func.__name__, args[1]["id"], future))
        return future


class DispatcherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.mailbox = Mailbox(self.root)
        self.config = {"published": str(self.root), "tasks": [
            {"id": "one", "number": 1, "public": "본문 확인", "reviews": ["empty_body"]},
            {"id": "two", "number": 2, "public": "값 가림", "reviews": ["preserve_value"]},
        ]}
        self.runner = Dispatch(self.root, self.config, self.mailbox)
        self.addCleanup(self.runner.db.close)
        self.pool = Jobs()
        self.pending = {}

    def tick(self):
        self.runner.tick(self.pool, self.pending)

    def test_next_task_and_independent_review_run_without_a_user_turn(self):
        self.tick()
        self.assertEqual([("review_task", "one")], [(a, b) for a, b, _ in self.pool.calls])
        self.pool.calls[0][2].set_result("empty_body")
        self.tick()
        self.assertEqual([("review_task", "two"), ("proof", "one")],
                         [(a, b) for a, b, _ in self.pool.calls[1:]])
        self.pool.calls[1][2].set_result("preserve_value")
        self.pool.calls[2][2].set_result("a" * 40)
        self.tick()
        self.assertEqual(("proof", "two"), self.pool.calls[3][:2])
        self.pool.calls[3][2].set_result("b" * 40)
        self.tick()
        self.tick()
        self.assertEqual(4, len(self.pool.calls))
        self.assertEqual(["done", "done"], [r["state"] for r in self.runner.db.execute("SELECT state FROM jobs ORDER BY number")])
        self.assertEqual(2, (self.root / "collab-published-codex.md").read_text(encoding="utf-8").count("집중 시험을 완료"))

    def test_restart_does_not_repeat_a_claimed_model_run(self):
        self.tick()
        reopened = Dispatch(self.root, self.config, self.mailbox)
        self.addCleanup(reopened.db.close)
        reopened.tick(self.pool, {})
        row = reopened.db.execute("SELECT state,issue FROM jobs WHERE id='one'").fetchone()
        self.assertEqual(("blocked", "INTERRUPTED"), tuple(row))
        self.assertEqual(1, len([c for c in self.pool.calls if c[1] == "one"]))

    def test_missing_login_waits_for_auth_instead_of_repeating_inference(self):
        self.runner.config["claude"] = "fixture"
        self.tick()
        first = self.pool.calls[0][2]
        first.set_exception(RuntimeError("CHILD_AUTH"))
        with patch("collab_dispatch.run", return_value='{"loggedIn":false}') as auth:
            self.tick()
            self.tick()
            auth.assert_called_once()
        self.assertEqual(1, len([call for call in self.pool.calls if call[1] == "one"]))
        self.runner.next_auth_probe = 0
        with patch("collab_dispatch.run", return_value='{"loggedIn":true}'):
            self.pool.calls[1][2].set_result("preserve_value")
            self.tick()
        self.assertEqual(2, len([call for call in self.pool.calls if call[0:2] == ("review_task", "one")]))

    def test_frontier_addition_runs_without_replaying_completed_ticket(self):
        self.runner.update("one", state="done")
        self.runner.update("two", state="done")
        self.tick()
        self.runner.add_tasks([{"id": "three", "number": 3, "public": "새 결함 확인", "reviews": ["empty_body"]}])
        self.tick()
        self.assertEqual([("review_task", "three")], [call[:2] for call in self.pool.calls])

    def test_patch_cannot_write_an_unapproved_file_or_choose_the_command(self):
        files = {"test.rs": "first\nsecond\n"}
        allowed = {"test.rs"}
        self.assertEqual({"test.rs": "first\nchanged\n"}, patch_sources(allowed, files, [
            {"path": "test.rs", "old": "second", "new": "changed"}]))
        for edit in (
            {"path": "../test.rs", "old": "second", "new": "changed"},
            {"path": "test.rs", "old": "absent", "new": "changed"},
            {"path": "test.rs", "old": "second", "new": "std::process::Command::new(\"anything\")"},
            {"path": "test.rs", "old": "second", "new": "changed", "command": "anything"},
        ):
            with self.subTest(edit=edit), self.assertRaises(RuntimeError):
                patch_sources(allowed, files, [edit])

    def test_validator_does_not_depend_on_the_scheduled_tasks_search_path(self):
        task = {"check": ["host-validator", "--fixture"]}
        with self.assertRaisesRegex(RuntimeError, "VALIDATOR_NOT_FOUND"):
            validation_command({}, task)
        config = {"validator_paths": {"host-validator": sys.executable}}
        self.assertEqual([sys.executable, "--fixture"], validation_command(config, task))

    def test_ready_work_can_run_while_mailbox_is_unreachable(self):
        self.runner.update("one", state="apply_ready", review="empty_body")
        self.runner.update("two", state="request_published")
        with patch.object(self.runner, "ingest", side_effect=TimeoutError):
            self.assertEqual("TimeoutError", self.runner.tick(self.pool, self.pending))
        self.assertEqual([("proof", "one")], [item[:2] for item in self.pool.calls])

    def test_no_work_does_not_start_models_or_publish(self):
        other = self.root / "empty"
        other.mkdir()
        dispatcher = Dispatch(other, {"published": str(other), "tasks": []}, Mailbox(other))
        self.addCleanup(dispatcher.db.close)
        dispatcher.tick(self.pool, {})
        self.assertEqual([], self.pool.calls)
        self.assertFalse(any(other.glob("*.md")))


if __name__ == "__main__":
    unittest.main()
