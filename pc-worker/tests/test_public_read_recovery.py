"""Failed public GET recovery retains actual failure, never resolves a write as harmless."""
import pathlib
import sys
import unittest
import uuid
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'deployed'))
from agent_bridge import AgentBridge

def uid():return str(uuid.uuid4())

class PublicReadRecoveryTest(unittest.TestCase):
    def setup_bridge(self,tool,plan=False):
        self.bridge=AgentBridge();self.owner=uid()
        schema={'name':tool,'description':'Synthetic contract','inputSchema':{'type':'object','properties':{},'required':[],'additionalProperties':False}}
        body={'version':1,'parentRunId':self.owner,'projectBinding':'synthetic','authorityRevision':1,
              'allowExternalAgent':True,'expiresAfterMs':600000,'phoneTools':[schema],
              'pcWorkspaceProfile':None,'requestDigest':'a'*64,'requirePlan':plan}
        code,s=self.bridge.dispatch_authenticated('POST','/v1/agent/sessions',self.owner,body)
        self.assertEqual(201,code);self.url='/v1/agent/sessions/'+s['sessionId'];self.phone=s['phoneCapability']
        self.request('GET',role='phone')
        self.agent=self.bridge.bootstrap_agent(self.owner,s['sessionId'],local_authenticated=True)['agentCapability']
    def request(self,method,suffix='',body=None,role='agent'):
        return self.bridge.dispatch_authenticated(method,self.url+suffix,self.owner,body,self.phone if role=='phone' else self.agent)
    def fail_call(self,tool):
        cid=uid();code,s=self.request('POST','/call',{'callId':cid,'tool':tool,'arguments':{}})
        self.assertEqual(202,code)
        _,s=self.request('GET',role='phone')
        pending=s['pendingCall']
        code,claimed=self.request('POST','/claim',{'callId':cid,'sequence':pending['sequence'],'revision':s['revision']},role='phone')
        self.assertEqual(200,code)
        failed={'success':False,'content':'synthetic public connection failed','verification':'FAILED','effectNotApplied':False}
        code,result=self.request('POST','/result',{'callId':cid,'sequence':pending['sequence'],
            'revision':claimed['pendingCall']['claimRevision'],'result':failed},role='phone')
        self.assertEqual(200,code)
        return cid,result,failed
    def test_public_read_failure_allows_a_new_attempt_without_faking_success(self):
        for tool in ('read_webpage','search_web','search_shopping'):
            self.setup_bridge(tool)
            cid,result,failed=self.fail_call(tool)
            self.assertEqual('ACTIVE',result['state']);self.assertIsNone(result['pendingCall'])
            code,record=self.request('GET','/calls/'+cid)
            self.assertEqual(200,code);self.assertEqual('COMPLETED',record['state']);self.assertEqual(failed,record['result'])
            code,again=self.request('POST','/call',{'callId':uid(),'tool':tool,'arguments':{}})
            self.assertEqual(202,code);self.assertEqual(2,again['sequence'])
    def test_write_and_unknown_read_named_extensions_remain_unresolved(self):
        for tool in ('save_note','read_fake_mutation'):
            self.setup_bridge(tool);_,result,_=self.fail_call(tool)
            self.assertEqual('UNKNOWN_EFFECT',result['pendingCall']['state'])
            self.assertEqual(409,self.request('POST','/call',{'callId':uid(),'tool':tool,'arguments':{}})[0])
    def test_private_local_image_tool_cannot_be_planned_or_called(self):
        for planning in (False,True):
            self.setup_bridge('generate_image',plan=planning)
            suffix='/plan' if planning else '/call'
            body={'goal':'synthetic','requiredTools':['generate_image'],'constraints':[]} if planning else {'callId':uid(),'tool':'generate_image','arguments':{}}
            code,result=self.request('POST',suffix,body)
            self.assertEqual(403,code);self.assertEqual('PRIVATE_IMAGE_WORKROOM',result['error'])
            self.assertEqual(0,self.request('GET',role='phone')[1]['lastSequence'])

if __name__=='__main__':unittest.main()
