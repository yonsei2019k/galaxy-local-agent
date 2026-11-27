import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "voice-guard"))
import fixed_sentences as fs


class NoFixedChatSentencesTest(unittest.TestCase):
    def test_new_fixed_sentences_are_not_added(self):
        allow = fs.load_allowlist()
        new = [(p, ln, t) for p, ln, t, _ in fs.scan() if (p, t.replace("\t", " ")) not in allow]
        self.assertEqual([], new, "새 고정 채팅 문장입니다. 문장은 모델이 쓰게 하고, 호스트는 상태 라벨과 버튼만 보여 주세요. "
                                  "꼭 필요하면 docs/voice/fixed-sentence-allowlist.txt에 이유와 함께 추가하세요.")

    def test_labels_have_no_technical_tokens(self):
        allow = fs.load_allowlist()
        new = [m for k, m in fs.label_offenders() if ("LABEL", f"{k}: {m}") not in allow]
        self.assertEqual([], new)

    def test_allowlist_has_no_stale_entries(self):
        live = {(p, t.replace("\t", " ")) for p, _l, t, _f in fs.scan()}
        live |= {("LABEL", f"{k}: {m}") for k, m in fs.label_offenders()}
        stale = [k for k in fs.load_allowlist() if k not in live]
        self.assertEqual([], stale, "더 이상 없는 항목입니다. 허용 목록에서 지워 주세요.")

    def test_scanner_detects_sample(self):
        lits = [t for _l, t in fs.literals('fun go(){ answer("작업이 끝났습니다.") }')]
        self.assertTrue(fs.is_sentence(lits[0]))


if __name__ == "__main__":
    unittest.main()
