import re
import sqlite3
import unittest
from pathlib import Path


CHAT_RECORDS = (Path(__file__).resolve().parents[2] /
                "app/src/main/java/com/user/localagent/data/ChatRecords.kt")


def dao_query():
    source = CHAT_RECORDS.read_text(encoding="utf-8")
    return re.search(r'@Query\("""(?P<sql>[^"]+?)"""\)\s*'
                     r'suspend fun messagesBeforeUserAnchor\b', source)["sql"]


class ContextAnchorQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE messages (id TEXT PRIMARY KEY, threadId TEXT NOT NULL, role TEXT NOT NULL);
            CREATE TABLE chat_branch_messages (
                branchId TEXT NOT NULL, messageId TEXT NOT NULL, position INTEGER NOT NULL,
                PRIMARY KEY (branchId, messageId),
                UNIQUE (branchId, position)
            );
        """)

    def row(self, identifier, position, *, branch="current", thread="room", role="user"):
        self.db.execute("INSERT INTO messages VALUES (?,?,?)", (identifier, thread, role))
        self.db.execute("INSERT INTO chat_branch_messages VALUES (?,?,?)", (branch, identifier, position))

    def load(self, anchor="queued", branch="current", thread="room"):
        result = self.db.execute(dao_query(), {
            "anchorMessageId": anchor, "branchId": branch, "threadId": thread, "limit": 32,
        })
        # This is the order supplied to captureCloudContext by ChatStore.
        return [row[0] for row in reversed(result.fetchall())]

    def test_later_queued_requests_and_placeholders_do_not_consume_the_window(self):
        self.row("before-user", 1)
        self.row("before-answer", 2, role="assistant")
        self.row("queued", 3)
        self.row("queued-answer", 4, role="assistant")
        self.db.commit()
        for queued_count in (0, 15, 16, 32):
            with self.subTest(queued_count=queued_count):
                try:
                    for i in range(queued_count):
                        self.row(f"future-user-{i}", 5 + 2 * i)
                        self.row(f"future-answer-{i}", 6 + 2 * i, role="assistant")
                    self.assertEqual(["before-user", "before-answer"], self.load())
                finally:
                    self.db.rollback()

    def test_only_the_nearest_32_earlier_rows_are_returned_in_branch_order(self):
        for i in range(40):
            self.row(f"before-{i}", i)
        self.row("queued", 40)
        self.row("after", 41)
        self.row("other-branch", 39, branch="fork")
        self.row("other-room", -1, thread="other")
        self.assertEqual([f"before-{i}" for i in range(8, 40)], self.load())

    def test_summary_page_excludes_future_rows_and_keeps_40_before_recent_six(self):
        for i in range(120):
            self.row(f"past-{i}", i)
        self.row("request", 120)
        self.row("future", 121)
        self.row("fork-only", 119, branch="fork")
        source = CHAT_RECORDS.read_text(encoding="utf-8")
        query = re.search(r'@Query\("([^"]+)"\) suspend fun messagePage\b', source)[1]
        rows = list(self.db.execute(query, {"threadId": "room", "branchId": "current", "before": 120, "limit": 46}))
        chronological = [row[0] for row in reversed(rows)]
        self.assertEqual([f"past-{i}" for i in range(74, 114)], chronological[:-6])
        self.assertEqual([f"past-{i}" for i in range(114, 120)], chronological[-6:])

    def test_missing_wrong_branch_wrong_room_and_assistant_anchors_fail_closed(self):
        self.row("before", 0)
        self.row("fork-anchor", 1, branch="fork")
        self.row("wrong-room-anchor", 2, thread="other")
        self.row("assistant-anchor", 3, role="assistant")
        for anchor in ("missing", "fork-anchor", "wrong-room-anchor", "assistant-anchor"):
            with self.subTest(anchor=anchor):
                self.assertEqual([], self.load(anchor))


if __name__ == "__main__":
    unittest.main()
