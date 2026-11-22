"""Exercise the actual HTTP adapter AST without importing/starting the worker."""
import ast
from email.message import Message
import hashlib
import io
import json
from pathlib import Path
import re
import socket
import tempfile
import threading
import types
import unittest
import uuid
from unittest import mock
from agent_bridge import AgentBridge,BridgeError
from agent_provider_supervisor import AgentProviderSupervisor
from codex_agent_provider import AgentReport
from provider_conversation_context import ConversationContextError,MAX_CONTEXT_BYTES,validate_conversation_context
from test_provider_conversation_context import fixture as context_fixture
from pc_openclaw_agent import PcAgentError

SOURCE=Path(__file__).parents[1]/"deployed/server.py"
OWNER="00000000-0000-0000-0000-000000000001"
SESSION="00000000-0000-0000-0000-000000000002"
CAP="synthetic_capability_"+"a"*32


def subject():
    tree=ast.parse(SOURCE.read_text(encoding="utf-8"))
    selected=[node for node in tree.body if isinstance(node,(ast.FunctionDef,ast.ClassDef)) and
              node.name in ("Handler","note","agent_workspace_profiles")]
    bridge=mock.Mock()
    bridge.dispatch_authenticated.return_value=(200,{"synthetic":True})
    bridge.bootstrap_agent.return_value={"sessionId":SESSION,"agentCapability":CAP}
    def owner(value):
        if value is not None and value!=OWNER:raise ValueError("OWNER")
        return value
    namespace=dict(BaseHTTPRequestHandler=object,re=re,json=json,uuid=uuid,Path=Path,AGENT_BRIDGE=bridge,
        BridgeError=BridgeError,ConversationContextError=ConversationContextError,MAX_CONTEXT_BYTES=MAX_CONTEXT_BYTES,
        validate_conversation_context=validate_conversation_context,
        AGENT_PROVIDER=mock.Mock(),PC_OPENCLAW=mock.Mock(),PcAgentError=PcAgentError,owner_value=owner,LOG_LOCK=threading.Lock(),LOG_LIMIT=2097152,LOG="fixture.log",
        time=types.SimpleNamespace(strftime=lambda _:"synthetic-time"))
    exec(compile(ast.Module(body=selected,type_ignores=[]),str(SOURCE),"exec"),namespace)
    return namespace,bridge


class ServerAgentAdapterTests(unittest.TestCase):
    def test_pc_agent_start_requires_pairing_and_rejects_delegated_capabilities(self):
        path = "/v1/pc-agent/jobs"
        for authorized, header, expected in (
            (False, None, 401),
            (True, "X-ZipHap-Agent-Capability", 403),
            (True, "X-ZipHap-Phone-Capability", 403),
            (True, None, 202),
        ):
            with self.subTest(authorized=authorized, header=header):
                ns, bridge, call, response, _ = self.make(path, authorized=authorized)
                if header:
                    call.headers.add_header(header, CAP)
                agent = ns["PC_OPENCLAW"]
                agent.dispatch.return_value = (202, {"state": "RUNNING"})
                ns["Handler"].dispatch(call)
                self.assertEqual(response[0][0], expected)
                if expected == 202:
                    agent.dispatch.assert_called_once_with("POST", path, OWNER, call.body)
                else:
                    agent.dispatch.assert_not_called()
                bridge.dispatch_authenticated.assert_not_called()

    def test_provider_progress_is_returned_only_to_opted_in_phone_builds(self):
        for opt_in in (False,True):
            ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",body={"ownerRequest":"synthetic"})
            c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            if opt_in:c.headers.add_header("X-ZipHap-Provider-Progress","1")
            ns["AGENT_PROVIDER"].launch.return_value={"state":"RUNNING","report":None,"errorCode":None,"progress":{"stage":"starting"}}
            ns["Handler"].dispatch(c)
            self.assertEqual(200,response[0][0]);self.assertEqual(opt_in,"progress" in response[0][1])
    def test_provider_host_routes_require_phone_header_not_agent_header(self):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",body={"ownerRequest":"synthetic"})
        c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
        ns["Handler"].dispatch(c)
        self.assertEqual(403,response[0][0]);ns["AGENT_PROVIDER"].launch.assert_not_called()
        b.dispatch_authenticated.assert_not_called()
    def test_provider_launch_forwards_exact_text_and_owner_without_agent_bootstrap_response(self):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",body={"ownerRequest":"합성\n원문"})
        c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
        ns["AGENT_PROVIDER"].launch.return_value={"state":"RUNNING","report":None,"errorCode":None}
        ns["Handler"].dispatch(c)
        ns["AGENT_PROVIDER"].launch.assert_called_once_with(OWNER,SESSION,CAP,"합성\n원문")
        self.assertEqual(200,response[0][0]);self.assertNotIn("agentCapability",response[0][1])
        b.bootstrap_agent.assert_not_called();b.dispatch_authenticated.assert_not_called()
    def test_explicit_claude_launch_preserves_phone_authority_and_provider(self):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",
            body={"ownerRequest":"synthetic", "provider":"claude"})
        c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
        ns["AGENT_PROVIDER"].launch.return_value={"state":"RUNNING","report":None,"errorCode":None}
        ns["Handler"].dispatch(c)
        ns["AGENT_PROVIDER"].launch.assert_called_once_with(OWNER,SESSION,CAP,"synthetic",provider="claude")
        self.assertEqual(200,response[0][0])
        b.bootstrap_agent.assert_not_called()

    def test_provider_launch_forwards_detached_v1_context_as_keyword_and_preserves_exact_request(self):
        context=context_fixture();request="  그 작업\n계속 확인해  "
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",
            body={"ownerRequest":request,"conversationContext":context})
        c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
        ns["AGENT_PROVIDER"].launch.return_value={"state":"RUNNING","report":None,"errorCode":None}
        ns["Handler"].dispatch(c)
        ns["AGENT_PROVIDER"].launch.assert_called_once_with(OWNER,SESSION,CAP,request,conversation_context=context)
        forwarded=ns["AGENT_PROVIDER"].launch.call_args.kwargs["conversation_context"]
        context["recentTurns"][0]["text"]="caller mutated after capture"
        self.assertNotEqual(context,forwarded)
        self.assertEqual(200,response[0][0]);b.bootstrap_agent.assert_not_called();b.dispatch_authenticated.assert_not_called()
        self.assertNotIn("recentTurns",json.dumps(response[0][1]))
    def test_invalid_context_types_versions_and_unknown_fields_do_not_launch(self):
        invalid=[None,"{}",[],{},context_fixture()|{"version":True},context_fixture()|{"version":2},
                 context_fixture()|{"authority":True},context_fixture()|{"recentTurns":[{"role":"system"}]}]
        for context in invalid:
            ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",
                body={"ownerRequest":"current","conversationContext":context})
            c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            ns["Handler"].dispatch(c)
            self.assertEqual([(400,{"error":"INVALID_CONVERSATION_CONTEXT"})],response)
            ns["AGENT_PROVIDER"].launch.assert_not_called();b.bootstrap_agent.assert_not_called()
    def test_launch_envelope_unknown_or_missing_fields_do_not_launch(self):
        for body in ({"ownerRequest":"current","context":context_fixture()},
                     {"ownerRequest":"current","conversationContext":context_fixture(),"allowExternalAgent":True},
                     {"conversationContext":context_fixture()}):
            ns,b,c,_,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",body=body)
            c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            with self.assertRaisesRegex(ValueError,"^PROVIDER_ROUTE$"):ns["Handler"].dispatch(c)
            ns["AGENT_PROVIDER"].launch.assert_not_called();b.bootstrap_agent.assert_not_called()
    def test_agent_header_cannot_launch_even_with_valid_history(self):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch",
            body={"ownerRequest":"current","conversationContext":context_fixture()})
        c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
        ns["Handler"].dispatch(c)
        self.assertEqual(403,response[0][0]);ns["AGENT_PROVIDER"].launch.assert_not_called()
    def test_provider_status_and_task_evidence_remain_phone_authenticated_host_routes(self):
        for suffix in ("provider","evidence"):
            ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/"+suffix)
            c.command="GET";c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            ns["Handler"].dispatch(c)
            if suffix=="provider":ns["AGENT_PROVIDER"].status.assert_called_once_with(OWNER,SESSION,CAP)
            else:b.task_evidence.assert_called_once_with(OWNER,SESSION,CAP)
            b.dispatch_authenticated.assert_not_called()
    def context_session(self):
        now=[1000]
        bridge=AgentBridge(clock_ms=lambda:now[0],epoch_ms=lambda:1800000000000+now[0])
        status,created=bridge.dispatch_authenticated("POST","/v1/agent/sessions",OWNER,{
            "version":1,"parentRunId":OWNER,"projectBinding":"synthetic-context-project","authorityRevision":1,
            "allowExternalAgent":True,"expiresAfterMs":60000,"pcWorkspaceProfile":"phone-only",
            "requestDigest":hashlib.sha256(b"synthetic current request").hexdigest(),
            "phoneTools":[{"name":"phone_clock","description":"synthetic read","inputSchema":{
                "type":"object","properties":{},"additionalProperties":False}}]})
        self.assertEqual(201,status)
        sid,phone=created["sessionId"],created["phoneCapability"]
        _,snapshot=bridge.dispatch_authenticated("GET","/v1/agent/sessions/"+sid,OWNER,capability=phone)
        agent=bridge.bootstrap_agent(OWNER,sid,local_authenticated=True)["agentCapability"]
        return bridge,now,sid,phone,agent,snapshot
    def context_capabilities(self,bridge,sid,cap,header="X-ZipHap-Phone-Capability"):
        ns,_,c,response,limits=self.make("/v1/agent/sessions/"+sid+"/context-capabilities")
        c.command="GET";c.headers.add_header(header,cap);ns["AGENT_BRIDGE"]=bridge
        ns["Handler"].dispatch(c)
        ns["AGENT_PROVIDER"].launch.assert_not_called();self.assertEqual([],limits)
        return response
    def test_context_capabilities_authenticates_active_phone_and_returns_static_limits_only(self):
        bridge,_,sid,phone,_,_=self.context_session()
        self.assertEqual([(200,{"conversationContextVersions":[1],"maxContextBytes":12000})],
                         self.context_capabilities(bridge,sid,phone))
    def test_context_capabilities_rejects_agent_header_and_agent_in_phone_header(self):
        bridge,_,sid,_,agent,_=self.context_session()
        for header,code in (("X-ZipHap-Agent-Capability","PHONE_CONTROL_ONLY"),
                            ("X-ZipHap-Phone-Capability","CAPABILITY_ROLE")):
            self.assertEqual([(403,{"error":code})],self.context_capabilities(bridge,sid,agent,header))
    def test_context_capabilities_rejects_closed_revoked_expired_and_lapsed_phone_lease(self):
        for state in ("closed","revoked","expired","phone_lease_lapsed"):
            bridge,now,sid,phone,_,snapshot=self.context_session()
            if state in ("closed","revoked"):
                suffix="close" if state=="closed" else "cancel"
                self.assertEqual(200,bridge.dispatch_authenticated("POST","/v1/agent/sessions/"+sid+"/"+suffix,
                    OWNER,{"revision":snapshot["revision"]},phone)[0])
            else:now[0]+=60001 if state=="expired" else 30000
            self.assertEqual([(410,{"error":"SESSION_NOT_ACTIVE"})],self.context_capabilities(bridge,sid,phone))
    def test_context_capabilities_rejects_missing_or_restart_revoked_session(self):
        self.assertEqual([(410,{"error":"SESSION_MISSING_OR_RESTART_REVOKED"})],
                         self.context_capabilities(AgentBridge(),SESSION,CAP))
    def make(self,path,peer="127.0.0.1",authorized=True,body=None):
        ns,bridge=subject();headers=Message();headers.add_header("X-ZipHap-Compute-Owner",OWNER)
        response=[];limits=[]
        def read(limit=0):limits.append(limit);return {} if body is None else body
        call=types.SimpleNamespace(path=path,command="POST",client_address=(peer,1234),headers=headers,
            connection=types.SimpleNamespace(settimeout=lambda _:None),authorized=lambda:authorized,
            reply=lambda data,status=200:response.append((status,data)),body=read)
        return ns,bridge,call,response,limits
    def wire(self,raw):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch")
        c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
        c.headers.add_header("Content-Type","application/json")
        c.headers.add_header("Content-Length",str(len(raw)))
        c.rfile=io.BytesIO(raw)
        c.body=lambda limit=0:ns["Handler"].body(c,limit)
        return ns,b,c,response
    def test_actual_body_accepts_utf8_korean_context_without_altering_request(self):
        body={"ownerRequest":"  현재\n원문  ","conversationContext":context_fixture()}
        ns,b,c,response=self.wire(json.dumps(body,ensure_ascii=False).encode("utf-8"))
        ns["AGENT_PROVIDER"].launch.return_value={"state":"RUNNING"}
        ns["Handler"].dispatch(c)
        ns["AGENT_PROVIDER"].launch.assert_called_once_with(OWNER,SESSION,CAP,body["ownerRequest"],
            conversation_context=body["conversationContext"])
        self.assertEqual(200,response[0][0])
    def test_actual_body_rejects_utf16_utf32_invalid_utf8_and_nested_duplicates(self):
        raw=json.dumps({"ownerRequest":"current","conversationContext":context_fixture()},separators=(",",":"))
        bad=[raw.encode(encoding) for encoding in ("utf-16","utf-16-le","utf-16-be","utf-32","utf-32-le","utf-32-be")]
        bad+=[b'\xff{}',raw.replace('"ownerRequest":"current"','"ownerRequest":"current","ownerRequest":"other"').encode(),
              raw.replace('"version":1','"version":1,"version":1').encode(),
              raw.replace('"role":"user"','"role":"user","role":"assistant"').encode(),
              raw.replace('"status":"paused"','"status":"paused","status":"completed"').encode()]
        for data in bad:
            ns,b,c,_=self.wire(data)
            with self.assertRaises((ValueError,UnicodeError)):ns["Handler"].dispatch(c)
            ns["AGENT_PROVIDER"].launch.assert_not_called();b.bootstrap_agent.assert_not_called()
    def test_actual_body_escaped_surrogate_context_is_rejected_before_launch(self):
        context=context_fixture();context["recentTurns"][0]["text"]="synthetic\ud800"
        ns,b,c,response=self.wire(json.dumps({"ownerRequest":"current","conversationContext":context}).encode())
        ns["Handler"].dispatch(c)
        self.assertEqual([(400,{"error":"INVALID_CONVERSATION_CONTEXT"})],response)
        ns["AGENT_PROVIDER"].launch.assert_not_called()
    def test_http_to_actual_supervisor_keeps_current_request_digest_and_retry_context_binding(self):
        request="그 화면에서 조건만 확인해"
        bridge=AgentBridge()
        status,created=bridge.dispatch_authenticated("POST","/v1/agent/sessions",OWNER,{
            "version":1,"parentRunId":OWNER,"projectBinding":"synthetic-project","authorityRevision":1,
            "allowExternalAgent":True,"expiresAfterMs":60000,"pcWorkspaceProfile":"phone-only",
            "requestDigest":hashlib.sha256(request.encode()).hexdigest(),
            "phoneTools":[{"name":"phone_clock","description":"synthetic read","inputSchema":{
                "type":"object","properties":{},"additionalProperties":False}}]})
        self.assertEqual(201,status)
        sid,cap=created["sessionId"],created["phoneCapability"]
        bridge.dispatch_authenticated("GET","/v1/agent/sessions/"+sid,OWNER,capability=cap)
        seen=[];complete=threading.Event()
        def runner(config,bootstrap,owner_request,*,conversation_context=None,**kwargs):
            seen.append((owner_request,conversation_context));complete.set()
            return AgentReport("partial","synthetic fresh result","",1,0)
        supervisor=AgentProviderSupervisor(bridge,object(),runner)
        context=context_fixture()
        def dispatch(history):
            ns,_,c,response,_=self.make("/v1/agent/sessions/"+sid+"/launch",
                body={"ownerRequest":request,"conversationContext":history})
            ns["AGENT_PROVIDER"]=supervisor
            c.headers.add_header("X-ZipHap-Phone-Capability",cap)
            ns["Handler"].dispatch(c)
            return response
        self.addCleanup(lambda:supervisor.cancel(OWNER,sid,cap))
        self.assertEqual(200,dispatch(context)[0][0]);self.assertTrue(complete.wait(1))
        self.assertEqual([(request,context)],seen)
        self.assertEqual(hashlib.sha256(request.encode()).hexdigest(),supervisor.children[sid].digest)
        self.assertEqual(200,dispatch(dict(reversed(list(context.items()))))[0][0])
        self.assertEqual([(409,{"error":"PROVIDER_CONTEXT_CHANGED"})],dispatch(context|{"previousTask":None}))
        self.assertEqual(1,len(seen))
    def test_no_pair_authority_never_reaches_broker_or_reads_body(self):
        ns,b,c,response,limits=self.make("/v1/agent/sessions",authorized=False)
        ns["Handler"].dispatch(c);self.assertEqual(401,response[0][0]);self.assertEqual([],limits)
        b.dispatch_authenticated.assert_not_called()
    def test_remote_bootstrap_rejected_even_with_spoofed_forwarded_header(self):
        ns,b,c,response,_=self.make("/v1/control/agent-bootstrap",peer="192.0.2.5",body={"sessionId":SESSION})
        c.headers.add_header("X-Forwarded-For","127.0.0.1")
        ns["Handler"].dispatch(c);self.assertEqual(403,response[0][0]);b.bootstrap_agent.assert_not_called()
    def test_local_authenticated_control_bootstraps_only_fixed_session(self):
        ns,b,c,response,limits=self.make("/v1/control/agent-bootstrap",body={"sessionId":SESSION})
        ns["Handler"].dispatch(c)
        b.bootstrap_agent.assert_called_once_with(OWNER,SESSION,local_authenticated=True)
        self.assertEqual([4096],limits);self.assertEqual(200,response[0][0])
    def test_agent_credential_cannot_be_used_for_host_bootstrap(self):
        ns,b,c,_,_=self.make("/v1/control/agent-bootstrap",body={"sessionId":SESSION})
        c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
        with self.assertRaises(ValueError):ns["Handler"].dispatch(c)
        b.bootstrap_agent.assert_not_called()
    def test_duplicate_owner_and_multiple_capabilities_rejected(self):
        for kind in ("owner","caps"):
            ns,b,c,_,_=self.make("/v1/agent/sessions/"+SESSION+"/call")
            if kind=="owner":c.headers.add_header("X-ZipHap-Compute-Owner",OWNER)
            else:
                c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
                c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            with self.assertRaises(ValueError):ns["Handler"].dispatch(c)
            b.dispatch_authenticated.assert_not_called()
    def test_valid_call_passes_authenticated_owner_and_bounded_body(self):
        path="/v1/agent/sessions/"+SESSION+"/call";body={"callId":SESSION,"tool":"phone_clock","arguments":{}}
        ns,b,c,response,limits=self.make(path,body=body);c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
        ns["Handler"].dispatch(c)
        b.dispatch_authenticated.assert_called_once_with("POST",path,OWNER,body,CAP)
        self.assertEqual([262144],limits);self.assertEqual([(200,{"synthetic":True})],response)
    def test_bootstrap_cannot_carry_new_authority_fields(self):
        ns,b,c,_,_=self.make("/v1/control/agent-bootstrap",body={"sessionId":SESSION,"allowExternalAgent":True})
        with self.assertRaises(ValueError):ns["Handler"].dispatch(c)
        b.bootstrap_agent.assert_not_called()
    def test_request_log_never_persists_session_locators(self):
        ns,_=subject();path="/v1/agent/sessions/"+SESSION
        with mock.patch("builtins.open",mock.mock_open()) as opened:
            ns["note"](path,200,None);opened.assert_not_called()
            with mock.patch("os.path.exists",return_value=False):ns["note"](path,403,"CAPABILITY_ROLE")
            written=opened().write.call_args.args[0]
            self.assertNotIn(SESSION,written);self.assertIn("/v1/agent 403",written)
    def test_agent_capability_is_denied_on_every_image_route_without_reading_payload(self):
        cases=[("POST","/v1/images")]
        cases += [(method,"/v1/images/"+SESSION+suffix) for method,suffix in
                  (("GET",""),("GET","/png"),("GET","/reference"),("POST","/ack"),
                   ("POST","/resume"),("POST","/suspend"),("DELETE",""))]
        for method,path in cases:
            ns,_,c,response,limits=self.make(path,body={"prompt":"synthetic private image prompt"})
            ns["ENGINE"]=mock.Mock();c.command=method
            c.headers.add_header("X-ZipHap-Agent-Capability",CAP)
            ns["Handler"].dispatch(c)
            self.assertEqual([(403,{"error":"IMAGE_PHONE_ONLY"})],response)
            self.assertEqual([],limits);self.assertEqual([],ns["ENGINE"].mock_calls)
    def test_image_phone_compute_and_status_contracts_remain_unchanged(self):
        body={"requestId":SESSION,"prompt":"synthetic private image prompt"}
        for method,path in (("POST","/v1/images"),("GET","/v1/images/"+SESSION)):
            ns,_,c,response,_=self.make(path,body=body);c.command=method
            engine=ns["ENGINE"]=mock.Mock()
            engine.image.return_value={"phase":"queued"};engine.public_job.return_value={"phase":"loading"}
            ns["Handler"].dispatch(c)
            if method=="POST":
                engine.image.assert_called_once_with(body,OWNER)
                self.assertEqual([(202,{"phase":"queued"})],response)
            else:
                engine.public_job.assert_called_once_with(SESSION,OWNER)
                self.assertEqual([(200,{"phase":"loading"})],response)
    def test_agent_health_lookup_cannot_expose_image_runtime_metadata(self):
        ns,_,c,response,limits=self.make('/v1/health')
        c.command='GET';c.headers.add_header('X-ZipHap-Agent-Capability',CAP)
        engine=ns['ENGINE']=mock.Mock()
        ns['Handler'].dispatch(c)
        self.assertEqual([(403,{'error':'HEALTH_HOST_ONLY'})],response)
        self.assertEqual([],limits);self.assertEqual([],engine.mock_calls)
    def test_image_logs_suppress_all_successes_and_redact_all_failure_suffixes(self):
        ns,_=subject()
        for suffix in ("","/png","/reference","/ack","/resume","/suspend","?private=synthetic"):
            path="/v1/images/"+SESSION+suffix
            with mock.patch("builtins.open",mock.mock_open()) as opened:
                for status in (200,201,202,204,206):ns["note"](path,status,None)
                opened.assert_not_called()
                for status in (400,403,404,409,500):
                    with mock.patch("os.path.exists",return_value=False):ns["note"](path,status,"synthetic private prompt")
                written="".join(call.args[0] for call in opened().write.call_args_list)
                self.assertNotIn(SESSION,written);self.assertNotIn("synthetic",written.replace("synthetic-time",""))
                self.assertNotIn("/png",written);self.assertNotIn("/reference",written)
                self.assertEqual(5,written.count("/v1/images "));self.assertEqual(5,written.count("IMAGE_REQUEST_FAILED"))
    def test_unexpected_agent_failure_never_logs_request_path_or_private_error_text(self):
        ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/launch")
        for name in ("Busy","OwnershipMismatch","RequestNotActive","ComputeCancelled","ReferenceUnavailable"):
            ns[name]=type(name,(Exception,),{})
        ns["socket"]=socket
        ns["note"]=mock.Mock()
        def fail():raise RecursionError("synthetic private request body")
        c.dispatch=fail
        ns["Handler"].handle_request(c)
        logged=repr(ns["note"].call_args_list)
        self.assertNotIn(SESSION,logged);self.assertNotIn("private request body",logged)
        self.assertEqual("failure",ns["note"].call_args.args[0])
        self.assertEqual(503,response[0][0])
    def test_host_profiles_have_no_relative_path_or_duplicate_fallback(self):
        ns,_=subject()
        with tempfile.TemporaryDirectory(prefix="ziphap-server-profile-test-") as folder:
            root=Path(folder).resolve();ns["HOME"]=root
            target=root/"agent-workspaces.json"
            for raw in ('{"qa":"relative"}','{"qa":"a","qa":"b"}','[]'):
                target.write_text(raw,encoding="utf-8");self.assertEqual({},ns["agent_workspace_profiles"]())
            target.write_text(json.dumps({"qa":str(root/"missing")}),encoding="utf-8")
            self.assertEqual({},ns["agent_workspace_profiles"]())
            target.write_text(json.dumps({"qa":str(root)}),encoding="utf-8")
            self.assertEqual({"qa":str(root)},ns["agent_workspace_profiles"]())


if __name__=="__main__":unittest.main()
