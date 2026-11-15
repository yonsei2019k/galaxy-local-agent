"""Exercise the actual HTTP adapter AST without importing/starting the worker."""
import ast
from email.message import Message
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

SOURCE=Path(__file__).parents[1]/"deployed/server.py"
OWNER="00000000-0000-0000-0000-000000000001"
SESSION="00000000-0000-0000-0000-000000000002"
CAP="synthetic_capability_"+"a"*32


class BridgeError(Exception):pass


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
        BridgeError=BridgeError,AGENT_PROVIDER=mock.Mock(),owner_value=owner,LOG_LOCK=threading.Lock(),LOG_LIMIT=2097152,LOG="fixture.log",
        time=types.SimpleNamespace(strftime=lambda _:"synthetic-time"))
    exec(compile(ast.Module(body=selected,type_ignores=[]),str(SOURCE),"exec"),namespace)
    return namespace,bridge


class ServerAgentAdapterTests(unittest.TestCase):
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
    def test_provider_status_and_task_evidence_remain_phone_authenticated_host_routes(self):
        for suffix in ("provider","evidence"):
            ns,b,c,response,_=self.make("/v1/agent/sessions/"+SESSION+"/"+suffix)
            c.command="GET";c.headers.add_header("X-ZipHap-Phone-Capability",CAP)
            ns["Handler"].dispatch(c)
            if suffix=="provider":ns["AGENT_PROVIDER"].status.assert_called_once_with(OWNER,SESSION,CAP)
            else:b.task_evidence.assert_called_once_with(OWNER,SESSION,CAP)
            b.dispatch_authenticated.assert_not_called()
    def make(self,path,peer="127.0.0.1",authorized=True,body=None):
        ns,bridge=subject();headers=Message();headers.add_header("X-ZipHap-Compute-Owner",OWNER)
        response=[];limits=[]
        def read(limit=0):limits.append(limit);return {} if body is None else body
        call=types.SimpleNamespace(path=path,command="POST",client_address=(peer,1234),headers=headers,
            connection=types.SimpleNamespace(settimeout=lambda _:None),authorized=lambda:authorized,
            reply=lambda data,status=200:response.append((status,data)),body=read)
        return ns,bridge,call,response,limits
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
