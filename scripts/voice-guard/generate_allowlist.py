"""Regenerate the allowlist seed and the offender report. Run from anywhere."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import fixed_sentences as fs

REASON = "TODO remove (persona-copy branch)"
hits = fs.scan()
labels = fs.label_offenders()
seen = set()
lines = [
    "# path<TAB>text<TAB>reason. Every entry must carry a reason; delete entries as the sentence is removed.",
    "# Label entries use the pseudo path LABEL.",
]
for path, _ln, text, _fn in sorted(hits):
    key = (path, text.replace("\t", " "))
    if key in seen:
        continue
    seen.add(key)
    lines.append("\t".join([key[0], key[1], REASON]))
for kind, msg in labels:
    lines.append("\t".join(["LABEL", f"{kind}: {msg}", REASON]))
fs.ALLOWLIST.write_text("\n".join(lines) + "\n", encoding="utf-8")

rep = fs.ROOT / ".local/sdd/2026-11-28-voice-harness/fixed-sentences.md"
rep.parent.mkdir(parents=True, exist_ok=True)
out = ["# 고정 채팅 문장 점검 결과 (45bdb4b)\n",
       f"- 문장 {len(hits)}건 (고유 {len(seen)}건), 라벨 문제 {len(labels)}건\n", "## 문장\n"]
for path, ln, text, fn in sorted(hits):
    out.append(f"- `{path}:{ln}` ({fn}) {text}")
out.append("\n## 라벨\n")
for kind, msg in labels:
    out.append(f"- {kind}: {msg}")
rep.write_text("\n".join(out) + "\n", encoding="utf-8")
print(len(hits), len(seen), len(labels))
