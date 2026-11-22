import re
import sqlite3
import unittest
from pathlib import Path

RECORDS = Path(__file__).resolve().parents[2] / "app/src/main/java/com/user/localagent/data/ChatRecords.kt"


class SavedAnalysisTailQueryTest(unittest.TestCase):
    def test_read_is_bounded_to_visible_recent_analysis_tails(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript("""
            CREATE TABLE knowledge_claims (
                id TEXT PRIMARY KEY, value TEXT, sourceKind TEXT, status TEXT, updatedAt INTEGER
            );
            CREATE TABLE knowledge_scopes (claimId TEXT PRIMARY KEY, scopeKey TEXT);
        """)
        source = RECORDS.read_text(encoding="utf-8")
        sql = re.search(r'@Query\("""((?:(?!""").)+)"""\) suspend fun recentAnalysisTails\b',
                        source,re.S)[1]

        def add(item, updated, *, scope="project:s1", kind="analysis_finding", status="observed", value="내용"):
            db.execute("INSERT INTO knowledge_claims VALUES (?,?,?,?,?)", (item, value, kind, status, updated))
            if scope is not None:
                db.execute("INSERT INTO knowledge_scopes VALUES (?,?)", (item, scope))

        tail = "고요한 유리 우편함"
        add("kept", 40, value="앞" * 2000 + tail)
        add("foreign", 99, scope="project:other", value=tail)
        add("owner", 98, scope=None, value=tail)
        add("other-kind", 97, kind="owner", value=tail)
        add("unapproved", 96, status="proposed", value=tail)
        for i in range(30):
            add("other-" + str(i), i)

        before = db.total_changes
        rows = db.execute(sql, {"scopeKey": "project:s1", "includeOwner": 0}).fetchall()
        self.assertEqual(24, len(rows))
        self.assertEqual(("kept", ("앞" * 2000 + tail)[-1536:]), rows[0])
        self.assertTrue(all(item.startswith("other-") for item, _ in rows[1:]))
        self.assertEqual(before, db.total_changes)


if __name__ == "__main__":
    unittest.main()
