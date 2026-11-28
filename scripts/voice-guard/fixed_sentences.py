"""Static scan for host-fixed Korean chat sentences (deterministic, stdlib only).

A literal is an offender when it looks like a Korean sentence AND the function
that contains it also calls a chat-message sink. Function-level matching is a
deliberate approximation: it is stable, has no false negatives for the usual
`val msg = if (...) "..." else "..."; publishX(msg)` shape, and the allowlist
absorbs the rest.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_DIRS = ["app/src/main/java", "android-runtime/src/main", "core/src/main/kotlin"]
ALLOWLIST = ROOT / "docs/voice/fixed-sentence-allowlist.txt"

# Functions/calls that create text the owner reads as a chat message.
SINKS = [
    "answer(", "publishActionFinal(", "publishProviderQuestion(", "publishOwnerQuestion(",
    "publishCloudTextAnswer(", "publishCheckedAnswer(", "publishVerifiedCloudAnswer(",
    "publishIncompleteCloudResult(", "publishMissingProviderReport(", "publishManagedReceiptReview(",
    "publishNoteReceiptFinal(", "publishPrivateCallFollowUpReport(", "publishOfficeReply(",
    "addMessage(", "appendAssistant", "finalAnswer", "ActionCompletionReport.render(",
    "ActionCompletionReport.compose(", "publishReceipts(",
]
# Files whose sentence literals all become chat text without a sink call in the same function
# (the host's managed-action report: its text part is posted as the answer).
CHAT_TEXT_FILES = {"core/src/main/kotlin/com/user/localagent/core/ActionCompletionReport.kt"}
HANGUL = re.compile(r"[가-힣]")
SENTENCE_END = re.compile(r"(요|다|죠|까|니다)\s*[.!?…]\s*$")
FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(")
LOGGING = re.compile(r"\b(FailureLog|Log\.[a-z]|println|require|check|error|throw|IllegalState|IllegalArgument|Regex|testTag|contentDescription|assert)\w*\s*\(?")


def iter_files():
    for d in SCAN_DIRS:
        base = ROOT / d
        if base.exists():
            for p in sorted(base.rglob("*.kt")):
                yield p


def literals(src: str):
    """Yield (line_no, text) for each string literal; ${...} templates become a NUL marker."""
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j
            yield line, src[i + 3:j]
            line += src.count("\n", i, j + 3)
            i = j + 3
        elif c == '"':
            j, buf, depth = i + 1, [], 0
            while j < n:
                ch = src[j]
                if depth == 0 and ch == "\\":
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if depth == 0 and ch == "$" and src.startswith("${", j):
                    depth = 1
                    buf.append("\x00")
                    j += 2
                    continue
                if depth > 0:
                    if ch == "{":
                        depth += 1
                    elif ch == "}":
                        depth -= 1
                    j += 1
                    continue
                if ch == '"' or ch == "\n":
                    break
                buf.append(ch)
                j += 1
            yield line, "".join(buf)
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'" and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
        else:
            i += 1


def is_sentence(text: str) -> bool:
    t = text.replace("\x00", " ").strip()
    h = len(HANGUL.findall(t))
    if h < 4:
        return False
    return bool(SENTENCE_END.search(t)) or h >= 15


def scan():
    """Return list of (relpath, line, text, func) for literals flowing to chat sinks."""
    out = []
    for p in iter_files():
        src = p.read_text(encoding="utf-8", errors="replace")
        rel = str(p.relative_to(ROOT)).replace("\\", "/")
        whole_file = rel in CHAT_TEXT_FILES
        lines = src.split("\n")
        funcs = [(i + 1, m.group(1)) for i, l in enumerate(lines) if (m := FUN.search(l))]
        if not funcs:
            funcs = [(1, "<file>")]
        starts = [f[0] for f in funcs]
        ends = starts[1:] + [len(lines) + 1]
        sink_in = []
        for s, e in zip(starts, ends):
            body = "\n".join(lines[s - 1:e - 1])
            sink_in.append([x for x in SINKS if x in body] or (["<chat-text file>"] if whole_file else []))
        for ln, text in literals(src):
            if not is_sentence(text):
                continue
            line_text = lines[ln - 1]
            if LOGGING.search(line_text) and "answer(" not in line_text:
                continue
            k = max((i for i, s in enumerate(starts) if s <= ln), default=0)
            if not sink_in[k]:
                continue
            out.append((rel, ln, text.replace("\x00", "${...}").strip(), funcs[k][1]))
    return out


TOOL_DEF = re.compile(r'ToolDefinition\(\s*"([a-z][a-z0-9_]+)"')
TOOL_CONST = re.compile(r'const val [A-Z_]+\s*=\s*"([a-z]+_[a-z0-9_]+)"')


def label_offenders():
    """ASCII technical tokens in UI label tables, and tools lacking a Korean label."""
    nav = ROOT / "app/src/main/java/com/user/localagent/ui/ChatNavigator.kt"
    src = nav.read_text(encoding="utf-8")
    m = re.search(r"internal fun toolLabel\(name:String\)=when\(name\)\{(.*?)\n\}", src, re.S)
    body = m.group(1) if m else ""
    keys, issues = set(), []
    for part in re.finditer(r'((?:"[a-z0-9_]+"\s*,?\s*)+)->\s*"([^"]*)"', body):
        keys.update(re.findall(r'"([a-z0-9_]+)"', part.group(1)))
        label = part.group(2)
        for tok in re.findall(r"[A-Za-z0-9_]+", label):
            if "_" in tok or re.search(r"[a-z][A-Z]", tok):
                issues.append(("label-token", f"toolLabel value '{label}' has technical token {tok}"))
    names = set()
    for d in SCAN_DIRS:
        base = ROOT / d
        if base.exists():
            for p in base.rglob("*.kt"):
                text = p.read_text(encoding="utf-8", errors="replace")
                names.update(TOOL_DEF.findall(text))
                if p.name.endswith("ToolCatalog.kt"):
                    names.update(TOOL_CONST.findall(text))
    for n in sorted(names - keys):
        issues.append(("label-missing", f"tool '{n}' has no Korean label in toolLabel (UI shows the raw id)"))
    return issues


def load_allowlist():
    entries = {}
    if not ALLOWLIST.exists():
        return entries
    for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 3 or not parts[2].strip():
            raise ValueError(f"allowlist entry needs path<TAB>text<TAB>reason: {raw[:80]}")
        entries[(parts[0], parts[1])] = parts[2]
    return entries
