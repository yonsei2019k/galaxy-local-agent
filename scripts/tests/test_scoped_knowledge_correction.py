import re
import sqlite3
import unittest
from pathlib import Path


RECORDS = Path(__file__).resolve().parents[2] / "app/src/main/java/com/user/localagent/data/ChatRecords.kt"


class KnowledgeCorrectionQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE knowledge_claims (
                id TEXT PRIMARY KEY, subject TEXT, objectSubject TEXT, sourceKind TEXT,
                sourceId TEXT, updatedAt INTEGER, status TEXT, tags TEXT
            );
            CREATE TABLE knowledge_scopes (claimId TEXT PRIMARY KEY, scopeKey TEXT);
        """)
        self.add("older", 1, tags='["record_semantic","stated"]')

    def add(self, name, time, *, target="일정", group="mail", origin="source-1",
            scope="project:a", tags='["record_semantic","changed"]', status="observed"):
        self.db.execute("INSERT INTO knowledge_claims VALUES (?,?,?,?,?,?,?,?)",
                        (name, "record:" + name, target, group, origin, time, status, tags))
        if scope is not None:
            self.db.execute("INSERT INTO knowledge_scopes VALUES (?,?)", (name, scope))

    def get_correction(self, scope="project:a", owner=False):
        source = RECORDS.read_text(encoding="utf-8")
        sql = re.search(r'@Query\("""([^;]*?)"""\) suspend fun laterKnowledgeCorrection\b',
                        source)[1].rsplit('@Query("""', 1)[-1]
        return self.db.execute(sql, {"anchorId": "older", "scopeKey": scope,
                                     "includeOwner": int(owner)}).fetchone()

    def test_newest_verified_change_bypasses_the_generic_related_limit(self):
        self.add("first-change", 2)
        self.add("newest-cancel", 4, tags='["record_semantic","cancelled"]')
        for i in range(12):
            self.add(f"generic-{i}", i + 10, tags='["record_semantic","stated"]',
                     status="confirmed")
        self.assertEqual("newest-cancel", self.get_correction()[0])

    def test_other_targets_sources_and_project_scopes_do_not_override(self):
        self.add("different-project", 9, scope="project:b")
        self.add("owner", 10, scope=None)
        self.add("different-target", 11, target="다른 일정")
        self.add("different-origin", 12, origin="source-2")
        self.add("different-source-kind", 13, group="sms")
        self.add("proposed", 14, status="proposed")
        self.add("unrelated-tag", 15, tags='["record_semantic","not_cancelled"]')
        self.assertIsNone(self.get_correction(owner=True))
        self.add("accepted", 3)
        self.assertEqual("accepted", self.get_correction(owner=True)[0])
        self.assertIsNone(self.get_correction(scope="project:b", owner=True))


if __name__ == "__main__":
    unittest.main()
