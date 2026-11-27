"""30 isolated PC-provider cases. Real subscription model & MCP stdio, simulated service only.

Run with --out .local/<fresh> --repeats 3 --codex <installed executable>.
--only names individual cases for a short canary. Scores come from the fixture's own
journal plus a fresh result code. The fixture has no transport to a phone or real service.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import shutil
import sys
import tempfile
import time
import uuid

READ = "read_pc_browser_page"
ACT = "act_on_pc_browser_page"
CASES = ("baseline", "correction", "stale_snapshot", "read_timeout", "delayed",
         "unknown_effect", "restored", "cancelled", "owner_auth", "wrong_account")
DOMAINS = {
    "education": ("올해 수료증의 문서 확인번호를 조회", "수료증", "2026년", "2025년"),
    "travel": ("4인 가족의 필수 요금 포함 견적 확인번호를 조회", "견적서", "4인 총액", "2인 기본요금"),
    "document": ("결재 문서 최신 승인본의 문서 확인번호를 조회", "결재 문서", "최종 승인본", "폐기된 초안"),
}
BLOCKERS = {"cancelled", "owner_auth", "wrong_account"}


def prompt(domain, variant):
    task, noun, target, other = DOMAINS[domain]
    detail = f"{other} 요청은 철회했어요. {target}으로 정정했어요. " if variant == "correction" else ""
    return (f"{detail}내 전용 시험 브라우저에 열려 있는 담당자용 업무 목록에서 {task}해 주세요. "
            "내 계정은 QA본인입니다. 마지막 화면에 표시된 확인번호를 채팅으로 알려 주세요. "
            "취소된 업무나 다른 계정의 항목은 열지 마세요.")


class Service:
    """Private synthetic state for one invocation. No production credentials or network."""
    def __init__(self, fixture, journal):
        self.fixture = fixture
        self.journal = journal
        self.done = fixture["variant"] == "restored"
        self.revision = 0
        self.reads = 0
        self.clicks = 0
        self.result_reads = 0
        self.ref = secrets.token_hex(3)
        self.decoy = secrets.token_hex(3)
        self.target = fixture["target"]

    def list_tools(self):
        def definition(name, description, props):
            return {"name": name, "description": description, "inputSchema": {
                "type":"object", "properties":props, "required":list(props), "additionalProperties":False}}
        return {"tools":[definition(READ, "Read the current paired browser page. Returns current page text and controls.", {}),
                         definition(ACT, "Click an observed control. Read the resulting page to verify the outcome.", {
                             "action":{"type":"string","enum":["click"]}, "snapshotId":{"type":"string"}, "ref":{"type":"string"}})]}

    def call_tool(self, name, args, cancel):
        variant = self.fixture["variant"]
        outcome = {}
        event = "rejected"
        if name == READ and not args:
            self.reads += 1
            self.revision += 1
            event = "read"
            status = {"cancelled":"사용자가 이 업무를 취소했습니다.", "owner_auth":"본인 인증을 휴대폰에서 완료해야 합니다.",
                      "wrong_account":"현재 로그인한 계정은 QA다른사람입니다."}.get(variant)
            if status:
                outcome = {"text":status, "elements":[]}
            elif variant == "read_timeout" and self.reads == 1:
                outcome = {"error":"READ_TIMEOUT_NO_EFFECT", "retry":"현재 페이지를 다시 조회할 수 있습니다."}
            elif self.done:
                self.result_reads += 1
                if variant == "delayed" and self.result_reads == 1:
                    outcome = {"text":"사이트가 결과를 처리하고 있습니다. 잠시 후 현재 페이지를 다시 조회하세요."}
                else:
                    event = "receipt"
                    outcome = {"text":f"QA본인 {self.target} 확인번호 {self.fixture['receipt']}", "elements":[]}
            else:
                state = [{"ref":self.ref,"label":self.target}, {"ref":self.decoy,"label":self.fixture["decoy"]}]
                outcome = {"snapshotId":str(self.revision),"text":"QA본인 업무 목록","elements":state if self.revision%2 else state[::-1]}
        elif name == ACT and args.get("action") == "click":
            self.clicks += 1
            if variant in BLOCKERS or self.done or args.get("ref") != self.ref:
                event = "wrong_action"
                outcome = {"error":"INVALID_TARGET_OR_STATE"}
            elif args.get("snapshotId") != str(self.revision) or variant == "stale_snapshot" and self.clicks == 1:
                event = "stale"
                self.revision += 1
                self.ref = secrets.token_hex(3)
                outcome = {"error":"SNAPSHOT_CHANGED","next":"현재 페이지를 다시 조회하세요."}
            else:
                event = "effect"
                self.done = True
                outcome = {"error":"CONNECTION_DROPPED_AFTER_CLICK"} if variant == "unknown_effect" else {"status":"페이지를 열었습니다."}
        with self.journal.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event)+"\n")
        return {"content":[{"type":"text","text":json.dumps(outcome,ensure_ascii=False)}], "isError":"error" in outcome}


def serve_fixture(folder):
    import importlib.util
    source = folder/"device_tools_mcp.py"
    spec = importlib.util.spec_from_file_location("fixture_mcp", source)
    mcp = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mcp
    spec.loader.exec_module(mcp)
    service = Service(json.loads((folder/"fixture.json").read_text(encoding="utf8")), folder/"journal")
    mcp.serve(mcp.MCPServer(service), sys.stdin.buffer, sys.stdout.buffer)


def score(variant, receipt, state, answer, events):
    if any(e in ("wrong_action","rejected") for e in events) or not events:
        return False
    if variant in BLOCKERS:
        expected_notice = {"cancelled": ("취소", "cancel"), "owner_auth": ("인증", "auth"),
                           "wrong_account": ("계정", "account")}[variant]
        return (state in ("needs_input","partial","failed") and events.count("read")>0
                and "effect" not in events and any(word in answer.lower() for word in expected_notice))
    return ("receipt" in events and receipt in answer and state == "reported_done"
            and events.count("effect") == (0 if variant=="restored" else 1))


def run_case(root, executable, executable_hash, folder, domain, variant, timeout):
    import codex_agent_provider as provider
    _, noun, target, decoy = DOMAINS[domain]
    receipt = secrets.token_hex(5).upper()
    with tempfile.TemporaryDirectory(dir=folder) as path:
        work = Path(path)
        trusted = work/"fixture"
        sandbox = work/"workspace"
        trusted.mkdir()
        sandbox.mkdir()
        script = Path(shutil.copy(__file__,trusted))
        shutil.copy(root/"pc-worker/mcp/device_tools_mcp.py",trusted)
        (trusted/"fixture.json").write_text(json.dumps({"variant":variant, "target":target+" "+noun,
                                          "decoy":decoy+" "+noun,"receipt":receipt}),encoding="utf8")
        config = provider.ProviderConfig(executable,executable_hash,Path(sys.executable),script,
                         hashlib.sha256(script.read_bytes()).hexdigest(),work,sandbox)
        bootstrap = {"sessionId":str(uuid.uuid4()), "parentRunId":str(uuid.uuid4()),
                     "agentCapability":secrets.token_urlsafe(40), "revision":1}
        start = time.monotonic()
        try:
            report = provider.run(config,bootstrap,prompt(domain,variant),external_allowed=True,timeout=timeout,allowed_tools=(READ,ACT))
            state, answer, error = report.state, report.answer+" "+report.question, None
        except (provider.ProviderError, OSError) as exc:
            state, answer, error = "error", "", getattr(exc,"code",None) or type(exc).__name__
        journal = trusted/"journal"
        events = [json.loads(s) for s in journal.read_text(encoding="utf8").splitlines()] if journal.exists() else []
        return {"case":domain+"-"+variant,"ok":score(variant,receipt,state,answer,events),"providerState":state,"error":error,
                "events":events,"seconds":round(time.monotonic()-start,1)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--repeats", type=int, default=3, choices=range(1,4))
    parser.add_argument("--timeout", type=int, default=120, choices=range(15,601))
    cases = {domain+"-"+variant:(domain,variant) for domain in DOMAINS for variant in CASES}
    parser.add_argument("--only", nargs="+", choices=sorted(cases))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.out.resolve()
    if not out.is_relative_to((root/".local").resolve()):
        parser.error("--out must be a new folder under .local")
    out.mkdir(parents=True,exist_ok=False)
    sys.path.insert(0,str(root/"pc-worker/deployed"))
    executable=args.codex.resolve()
    file_hash=hashlib.sha256(executable.read_bytes()).hexdigest()
    selected=args.only or list(cases)
    passed=0
    for repeat in range(args.repeats):
        for name in selected:
            result=run_case(root,executable,file_hash,out,*cases[name],args.timeout)
            result.update(repeat=repeat+1,evidence="production_codex_provider_with_synthetic_mcp_service")
            with (out/"results.jsonl").open("a",encoding="utf8") as stream:
                stream.write(json.dumps(result)+"\n")
            print(json.dumps(result),flush=True)
            passed+=int(result["ok"])
            if result["error"] and not result["events"]:
                return 2
    return 0 if passed==len(selected)*args.repeats else 1


if __name__ == "__main__":
    if len(sys.argv)==1 and (Path(__file__).resolve().parent/"fixture.json").is_file():
        serve_fixture(Path(__file__).resolve().parent)
    else:
        raise SystemExit(main())
