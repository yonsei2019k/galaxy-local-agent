import hashlib
import hmac
import json
import re
import socket
import ssl
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from security import HOME,TOOLS,load,private_v4
from engines import Busy,ReferenceUnavailable
from owned_engines import OwnedEngines,OwnershipMismatch,RequestNotActive,ComputeCancelled,owner_value
from pairing import Pairing
from agent_bridge import AgentBridge,BridgeError
from agent_provider_supervisor import AgentProviderSupervisor,load_config as load_agent_provider
from pc_openclaw_agent import PcAgentError,PcOpenClawAgent,load_config as load_pc_openclaw
from provider_conversation_context import ConversationContextError,MAX_CONTEXT_BYTES,validate_conversation_context
from pathlib import Path
from collab_bridge import CollabBridge,load_config as load_collab_config

from recovery_engines import RecoveryEngines
from overlay_endpoint import overlay_endpoint
from remote_test import RemoteTestQueue,RemoteTestError
REMOTE_TESTS=RemoteTestQueue()
from browser_agent import BrowserAgent,BrowserError
BROWSER=BrowserAgent()
def overlay():
 return overlay_endpoint(CONFIG['port'])
LOG=r'D:\ZipHap\Compute\requests.log';LOG_LIMIT=2*1024*1024
LOG_LOCK=threading.Lock()
POLL=re.compile(r'^/v1/images/[0-9a-f-]{36}$')
def resident(header):
 # Which text model is loaded right now, and whether it belongs to the asking owner; no IDs are exposed.
 try:
  process=ENGINE.llm
  if not process or process.poll() is not None or not ENGINE.llm_alias:return {'llmResident':None}
  mine=(not ENGINE.owner_set) or ENGINE.owner==owner_value(header)
  return {'llmResident':{'modelId':ENGINE.llm_alias,'contextSize':ENGINE.llm_ctx,'ownerMatches':bool(mine)}}
 except Exception:return {'llmResident':None}
def note(path,status,error):
 # Session locators, tool data and capabilities never enter the persistent request log.
 if path.startswith('/v1/pc-agent/'):
  path='/v1/pc-agent'
 if path.startswith('/v1/agent/'):
  if status in (200,201) and not error:return
  path='/v1/agent'
 if path.startswith('/v1/collab/'):
  if status in (200,201) and not error:return
  path='/v1/collab'
 # Remote test polls repeat every few seconds; request IDs and texts never enter the log.
 if path.startswith('/v1/remote/'):
  if status in (200,201) and not error:return
  path='/v1/remote'
 # Browser pages, typed text and URLs never enter the log.
 if path.startswith('/v1/browser/'):
  if status in (200,201) and not error:return
  path='/v1/browser'
 # Image requests/results remain private to their phone channel, including every suffix.
 if path.startswith('/v1/images'):
  if 200<=status<300:return
  path='/v1/images';error='IMAGE_REQUEST_FAILED'
 try:
  import os
  with LOG_LOCK:
   if os.path.exists(LOG) and os.path.getsize(LOG)>LOG_LIMIT:os.replace(LOG,LOG[:-4]+'.1.log')
   with open(LOG,'a',encoding='utf-8') as out:out.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {path} {status} {error}\n")
 except OSError:pass
CONFIG=load();TOKEN=CONFIG['token']
COLLAB=CollabBridge(load_collab_config(HOME))
def agent_workspace_profiles():
 # Host configuration only. Missing/invalid profiles never widen a workspace scope.
 try:
  source=HOME/'agent-workspaces.json'
  if not source.is_file() or source.stat().st_size>16384:return {}
  def unique(items):
   value={}
   for key,item in items:
    if key in value:raise ValueError('DUPLICATE_PROFILE')
    value[key]=item
   return value
  profiles=json.loads(source.read_text(encoding='utf-8'),object_pairs_hook=unique)
  if not isinstance(profiles,dict) or len(profiles)>8:raise ValueError('PROFILE_CONFIG')
  if not all(isinstance(key,str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,63}',key) and
             isinstance(value,str) and len(value)<=4096 and Path(value).is_absolute() and Path(value).is_dir() for key,value in profiles.items()):raise ValueError('PROFILE_CONFIG')
  return profiles
 except (OSError,ValueError,UnicodeError):return {}
try:
 coordination=Path(COLLAB.config['coordination']).resolve() if COLLAB.config else HOME.resolve()
 private_coordination=next((parent for parent in coordination.parents if parent.name=='.local'),coordination)
 AGENT_BRIDGE=AgentBridge(workspace_profiles=agent_workspace_profiles(),private_roots=(HOME,TOOLS,Path(LOG).parent,private_coordination))
except (BridgeError,OSError,RuntimeError):AGENT_BRIDGE=AgentBridge(workspace_profiles={})
AGENT_PROVIDER=AgentProviderSupervisor(AGENT_BRIDGE,load_agent_provider(HOME),claude_config=load_agent_provider(HOME,"claude"))
PC_OPENCLAW=PcOpenClawAgent(load_pc_openclaw(HOME))
ENGINE=RecoveryEngines(hashlib.sha256(TOKEN.encode()).hexdigest());PAIRING=Pairing(CONFIG)
from media_engine import MediaEngine
from engines import gpu_busy
MEDIA=MediaEngine(lambda:not (ENGINE.llm and ENGINE.llm.poll() is None) and not gpu_busy())
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args):pass
 def reply(self,value,status=200):
  note(self.path,status,value.get('error') if isinstance(value,dict) else None)
  data=json.dumps(value,separators=(',',':'),ensure_ascii=False).encode('utf-8')
  self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
 def authorized(self):
  return private_v4(self.client_address[0]) and hmac.compare_digest(self.headers.get('Authorization','')[:200],'Bearer '+TOKEN)
 def body(self,limit=12*1024*1024):
  if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length',[]))!=1:raise ValueError('BODY_FORMAT')
  length=int(self.headers['Content-Length'])
  if not 2<=length<=limit or self.headers.get_content_type()!='application/json':raise ValueError('BODY_BOUND')
  raw=self.rfile.read(length)
  if len(raw)!=length:raise ValueError('BODY_INCOMPLETE')
  def pairs(items):
   result={}
   for key,value in items:
    if key in result:raise ValueError('DUPLICATE_JSON_KEY')
    result[key]=value
   return result
  value=json.loads(raw.decode('utf-8',errors='strict'),object_pairs_hook=pairs)
  if not isinstance(value,dict):raise ValueError('JSON_OBJECT_REQUIRED')
  return value
 def dispatch(self):
  self.connection.settimeout(60)
  path=self.path
  query=''
  if '?' in path:
   path,_,query=path.partition('?')
   if path!='/v1/collab/messages' or self.command!='GET' or len(query)>80:raise ValueError('ROUTE')
  if '%' in query:raise ValueError('ROUTE')
  if '?' in path or '%' in path or not path.startswith('/v1/'):raise ValueError('ROUTE')
  if self.command=='POST' and path in ('/v1/pair/start','/v1/pair/finish') and private_v4(self.client_address[0]):
   self.connection.settimeout(5)
   request=self.body(4096)
   self.reply(PAIRING.start(self.client_address[0],request) if path.endswith('/start') else PAIRING.finish(self.client_address[0],request));return
  if not self.authorized():self.reply({'error':'UNAUTHORIZED'},401);return
  if (path=='/v1/images' or path.startswith('/v1/images/')) and self.headers.get_all('X-ZipHap-Agent-Capability',[]):
   self.reply({'error':'IMAGE_PHONE_ONLY'},403);return
  if path.startswith('/v1/collab/'):
   if self.headers.get_all('X-ZipHap-Agent-Capability',[]) or self.headers.get_all('X-ZipHap-Phone-Capability',[]):
    self.reply({'error':'COLLAB_PAIR_ONLY'},403);return
   status,value=COLLAB.handle(self.command,path,query,self.body)
   self.reply(value,status);return
  if path=='/v1/health' and self.headers.get_all('X-ZipHap-Agent-Capability',[]):
   self.reply({'error':'HEALTH_HOST_ONLY'},403);return
  if self.command=='GET' and path=='/v1/health':self.reply(MEDIA.health(ENGINE.health())|{'serverId':CONFIG['server_id'],'deviceToolBridge':{'version':1,'publicReadFailureRecovery':1,'restartPolicy':'RAM_ONLY_RESTART_REVOKES','bootstrapLoopbackOnly':True}|AGENT_PROVIDER.health()}|overlay()|resident(self.headers.get('X-ZipHap-Compute-Owner')));return
  owners=self.headers.get_all('X-ZipHap-Compute-Owner',[])
  if len(owners)>1:raise ValueError('DUPLICATE_OWNER')
  owner=owner_value(owners[0] if owners else None)
  if path.startswith('/v1/pc-agent/'):
   if (self.headers.get_all('X-ZipHap-Agent-Capability',[]) or
       self.headers.get_all('X-ZipHap-Phone-Capability',[])):
    self.reply({'error':'PC_AGENT_PAIR_ONLY'},403);return
   try:
    status,value=PC_OPENCLAW.dispatch(self.command,path,owner,self.body)
    self.reply(value,status)
   except PcAgentError as error:self.reply({'error':error.code},error.status)
   return
  if path=='/v1/control/agent-bootstrap':
   # Private host IPC, not a remote phone/MCP capability-minting endpoint.
   if self.command!='POST' or self.client_address[0]!='127.0.0.1':self.reply({'error':'LOCAL_CONTROL_ONLY'},403);return
   if self.headers.get_all('X-ZipHap-Agent-Capability',[]) or self.headers.get_all('X-ZipHap-Phone-Capability',[]):raise ValueError('HOST_CONTROL_ONLY')
   request=self.body(4096)
   if set(request)!={'sessionId'}:raise ValueError('BOOTSTRAP_SHAPE')
   try:self.reply(AGENT_BRIDGE.bootstrap_agent(owner,request['sessionId'],local_authenticated=True))
   except BridgeError as error:self.reply({'error':error.code},error.status)
   return
  if path.startswith('/v1/remote/'):
   try:
    local=self.client_address[0]=='127.0.0.1'
    item=re.fullmatch(r'/v1/remote/requests/([0-9a-f-]{36})(/result)?',path)
    if self.command=='POST' and path=='/v1/remote/requests':
     if not local:self.reply({'error':'LOCAL_CONTROL_ONLY'},403);return
     self.reply(REMOTE_TESTS.enqueue(self.body(8192)),201);return
    if self.command=='POST' and path=='/v1/remote/next':
     # Phone side: the owner's time-limited switch decides whether it polls at all.
     if self.body(64)!={}:raise ValueError('NEXT_SHAPE')
     self.reply(REMOTE_TESTS.claim());return
    if item and self.command=='POST' and item[2]:self.reply(REMOTE_TESTS.report(item[1],self.body(65536)));return
    if item and self.command=='GET' and not item[2]:
     if not local:self.reply({'error':'LOCAL_CONTROL_ONLY'},403);return
     self.reply(REMOTE_TESTS.status(item[1]));return
    raise ValueError('REMOTE_ROUTE')
   except RemoteTestError as error:self.reply({'error':error.code},error.status);return
  if path.startswith('/v1/browser/'):
   # Paired phone or loopback. Policy (public pages only, no sensitive typing, approval for irreversible clicks) lives in BrowserAgent.
   operation={'/v1/browser/open':BROWSER.open,'/v1/browser/read':BROWSER.read,'/v1/browser/act':BROWSER.act,'/v1/browser/close':BROWSER.close,
     '/v1/browser/public-read':BROWSER.public_read}.get(path)
   if path in ('/v1/browser/owner-login-form','/v1/browser/owner-login-submit'):
    if self.client_address[0]=='127.0.0.1':self.reply({'error':'PAIRED_PHONE_ONLY'},403);return
    operation=BROWSER.owner_login_form if path.endswith('-form') else BROWSER.owner_login_submit
   if self.command!='POST' or operation is None:raise ValueError('BROWSER_ROUTE')
   self.connection.settimeout(90)
   request=self.body(8192)
   try:self.reply(operation(request))
   except BrowserError as error:self.reply({'error':error.code},error.status)
   return
  if path.startswith('/v1/agent/'):
   phone_caps=self.headers.get_all('X-ZipHap-Phone-Capability',[])
   agent_caps=self.headers.get_all('X-ZipHap-Agent-Capability',[])
   if len(phone_caps)+len(agent_caps)>1:raise ValueError('DUPLICATE_CAPABILITY')
   values=phone_caps+agent_caps
   capability=values[0] if values else None
   if capability is not None and (len(capability)>512 or not re.fullmatch(r'[A-Za-z0-9_-]{32,512}',capability)):raise ValueError('CAPABILITY_FORMAT')
   request=self.body(262144) if self.command=='POST' else None
   host=re.fullmatch(r'/v1/agent/sessions/([0-9a-f-]{36})/(launch|provider|provider-cancel|evidence|context-capabilities|persona-capabilities)',path)
   if host:
    if len(phone_caps)!=1 or agent_caps:self.reply({'error':'PHONE_CONTROL_ONLY'},403);return
    sid=str(uuid.UUID(host[1]));action=host[2]
    try:
     if self.command=='POST' and action=='launch':
      if type(request) is not dict or 'ownerRequest' not in request or set(request)-{'ownerRequest','conversationContext','provider','officePersona'}:
       raise ValueError('PROVIDER_ROUTE')
      context=None
      if 'conversationContext' in request:
       try:
        if request['conversationContext'] is None:raise ConversationContextError()
        context=validate_conversation_context(request['conversationContext'])
       except ConversationContextError:raise BridgeError('INVALID_CONVERSATION_CONTEXT',400) from None
      options={}
      if context is not None:options['conversation_context']=context
      if 'provider' in request:options['provider']=request['provider']
      if 'officePersona' in request:
       persona=request['officePersona']
       if (type(persona) is not str or not 1<=len(persona)<=6000 or persona!=persona.strip()
           or any(ord(ch)<32 and ch!='\n' or 127<=ord(ch)<160 for ch in persona)):
        raise BridgeError('INVALID_OFFICE_PERSONA',400)
       options['office_persona']=persona
      value=AGENT_PROVIDER.launch(owner,sid,capability,request['ownerRequest'],**options)
     elif self.command=='GET' and action=='provider':value=AGENT_PROVIDER.status(owner,sid,capability)
     elif self.command=='POST' and action=='provider-cancel' and request=={}:value=AGENT_PROVIDER.cancel(owner,sid,capability)
     elif self.command=='GET' and action=='evidence':value=AGENT_BRIDGE.task_evidence(owner,sid,capability)
     elif self.command=='GET' and action=='context-capabilities':
      snapshot=AGENT_BRIDGE.authenticate_phone(owner,sid,capability)
      if snapshot.get('state')!='ACTIVE':raise BridgeError('SESSION_NOT_ACTIVE',410)
      value={'conversationContextVersions':[1],'maxContextBytes':MAX_CONTEXT_BYTES}
     elif self.command=='GET' and action=='persona-capabilities':
      snapshot=AGENT_BRIDGE.authenticate_phone(owner,sid,capability)
      if snapshot.get('state')!='ACTIVE':raise BridgeError('SESSION_NOT_ACTIVE',410)
      value={'officePersonaVersions':[1],'maxOfficePersonaChars':6000}
     else:raise ValueError('PROVIDER_ROUTE')
     # Older phone builds reject unknown response fields. Progress is a display-only negotiated extension.
     if self.headers.get('X-ZipHap-Provider-Progress')!='1' and isinstance(value,dict):
      value=dict(value);value.pop('progress',None)
     self.reply(value)
    except BridgeError as error:self.reply({'error':error.code},error.status)
    return
   status,value=AGENT_BRIDGE.dispatch_authenticated(self.command,path,owner,request,capability)
   self.reply(value,status);return
  if self.command=='POST' and path=='/v1/images':self.reply(ENGINE.image(self.body(),owner),202);return
  match=re.fullmatch(r'/v1/images/([0-9a-f-]{36})(/png|/ack|/resume|/suspend|/reference)?',path)
  if match:
   key=str(uuid.UUID(match[1]));suffix=match[2]
   if self.command=='GET' and suffix is None:self.reply(ENGINE.public_job(key,owner));return
   if self.command=='GET' and suffix=='/reference':
    data=ENGINE.reference_bytes(key,owner)
    self.send_response(200);self.send_header('Content-Type','image/png');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data);return
   if self.command=='GET' and suffix=='/png':
    data=ENGINE.image_bytes(key,owner)
    if data is None:self.reply({'error':'NOT_READY'},409);return
    self.send_response(200);self.send_header('Content-Type','image/png');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-SHA256',hashlib.sha256(data).hexdigest());self.end_headers();self.wfile.write(data);return
   if self.command=='POST' and suffix in ('/resume','/suspend'):
    body=self.body()
    if suffix=='/suspend' and body:raise ValueError('RECOVERY_BODY_MUST_BE_EMPTY')
    if suffix=='/resume' and (set(body)-{'automatic','explicit'} or any(type(v) is not bool for v in body.values())):raise ValueError('INVALID_RESUME_MODE')
    result=ENGINE.resume_image(key,owner,automatic=body.get('automatic',False),explicit=body.get('explicit',False)) if suffix=='/resume' else ENGINE.suspend_image(key,owner)
    self.reply(result,202 if suffix=='/resume' else 200);return
   if self.command=='DELETE' or self.command=='POST' and suffix=='/ack':
    if self.command=='POST':self.body()
    result=ENGINE.cancel_image(key,owner);self.reply(result,200 if result['cleanup_complete'] else 202);return
  if self.command=='POST' and path=='/v1/media/transcribe':self.connection.settimeout(610);self.reply(MEDIA.transcribe(self.body()));return
  if self.command=='POST' and path=='/v1/media/describe':
   # Gemma media shares the existing native admission lock.
   if not ENGINE.lock.acquire(timeout=1):raise Busy()
   try:
    self.connection.settimeout(610);self.reply(MEDIA.describe(self.body()))
   finally:ENGINE.lock.release()
   return
  if self.command=='POST' and path=='/v1/cloud/chat':
   import cloud_engine
   request=self.body();self.connection.settimeout(610)
   try:
    cloud_engine._client()
   except cloud_engine.KeyRequired:self.reply({'error':'CLAUDE_KEY_REQUIRED'},503);return
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers();self.close_connection=True
   def out(data):self.wfile.write(data);self.wfile.flush()
   try:cloud_engine.chat(request,out)
   except Exception as error:note('cloud',0,type(error).__name__);out(b'data: {"error":"CLAUDE_FAILED"}\n\n')
   return
  if self.command=='POST' and path=='/v1/llm/load':self.reply(ENGINE.load_llm(self.body(),owner));return
  if self.command=='POST' and path=='/v1/llm/tokens':self.reply(ENGINE.tokens(self.body(),owner));return
  if self.command=='POST' and path=='/v1/llm/cancel':
   request=self.body()
   if set(request)!={'requestId'}:raise ValueError('CANCEL_FORMAT')
   key=str(uuid.UUID(request['requestId']));result=ENGINE.cancel_chat(key,owner)
   self.reply(result,200 if result['cleanup_complete'] else 202);return
  if self.command=='POST' and path=='/v1/llm/unload':
   result=ENGINE.unload_llm(owner,self.body());self.reply(result,200 if result['cleanup_complete'] else 202);return
  if self.command=='POST' and path=='/v1/llm/chat':
   started=False
   def send(response):
    nonlocal started
    self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers();started=True;self.close_connection=True
    total=0
    while line:=response.readline(256*1024):
     total+=len(line)
     if len(line)>128*1024 or total>4*1024*1024:raise ValueError('STREAM_BOUND')
     self.wfile.write(line);self.wfile.flush()
     if line.strip()==b'data: [DONE]':return
    raise ValueError('STREAM_COMPLETION_MISSING')
   began=time.time()
   try:ENGINE.chat(self.body(),send,owner)
   except Exception as failure:
    if started:note(path,'stream',f"{type(failure).__name__}:{str(failure) if re.fullmatch(r'[A-Z_]{3,40}',str(failure)) else '-'} after {time.time()-began:.1f}s")
    # Before streaming, the outer handler replies with the HTTP status and logs it once.
    if not started:raise
    self.close_connection=True
    return
   note(path,200,f"ok {time.time()-began:.1f}s")
   return
  if self.command=='POST' and path=='/v1/control/stop' and self.client_address[0]=='127.0.0.1':
   self.body();self.reply({'stopping':True})
   threading.Thread(target=self.server.shutdown,daemon=True).start();return
  self.reply({'error':'NOT_FOUND'},404)
 def handle_request(self):
  try:self.dispatch()
  except Busy:self.reply({'error':'PC_BUSY'},423)
  except OwnershipMismatch:self.reply({'error':'COMPUTE_OWNER_MISMATCH'},409)
  except RequestNotActive:self.reply({'error':'COMPUTE_REQUEST_NOT_ACTIVE'},409)
  except ComputeCancelled:self.reply({'error':'COMPUTE_CANCELLED'},409)
  except ReferenceUnavailable:self.reply({'error':'REFERENCE_NOT_AVAILABLE'},422)
  except KeyError:self.reply({'error':'NOT_FOUND'},404)
  except (ValueError,TypeError) as error:note('reason',0,type(error).__name__+':'+str(error)[:40]);self.reply({'error':'INVALID_REQUEST'},400)
  except (BrokenPipeError,ConnectionResetError,socket.timeout):self.close_connection=True
  except Exception as error:
   tb=error.__traceback__
   while tb and tb.tb_next:tb=tb.tb_next
   note('failure',0,type(error).__name__+(f' at {tb.tb_frame.f_code.co_filename.rsplit(chr(92),1)[-1]}:{tb.tb_lineno}' if tb else ''))
   try:self.reply({'error':'PC_COMPUTE_FAILED'},503)
   except Exception:self.close_connection=True
 do_GET=do_POST=do_DELETE=handle_request

class TLSServer(ThreadingHTTPServer):
 # The TLS handshake runs in each request thread with a deadline. Wrapping the listening socket made
 # accept() handshake on the serve loop, so one stalled client (a dropped mobile/relay link) blocked
 # every later connection until the backlog overflowed and the PC refused all phones.
 daemon_threads=True
 def __init__(self,address,handler,context):
  self.tls=context;super().__init__(address,handler)
 def finish_request(self,request,client_address):
  request.settimeout(15)
  try:secure=self.tls.wrap_socket(request,server_side=True)
  except (ssl.SSLError,OSError):return
  try:super().finish_request(secure,client_address)
  finally:
   try:secure.close()
   except OSError:pass

def discovery():
 last={}
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
  sock.bind(('0.0.0.0',CONFIG['discovery_port']))
  while True:
   data,peer=sock.recvfrom(1024)
   try:
    if not private_v4(peer[0]):continue
    now=time.monotonic()
    if now-last.get(peer[0],0)<.5:continue
    if len(last)>64:last={key:value for key,value in last.items() if now-value<60}
    last[peer[0]]=now
    request=json.loads(data);nonce=request['nonce']
    if request.get('service')!='ZipHapCompute' or not re.fullmatch('[0-9a-f]{64}',nonce):continue
    proof=hmac.new(TOKEN.encode(),f"{CONFIG['server_id']}|{nonce}|{CONFIG['port']}".encode(),hashlib.sha256).hexdigest()
    reply={'service':'ZipHapCompute','version':1,'serverId':CONFIG['server_id'],'nonce':nonce,'port':CONFIG['port'],'proof':proof}
    if request.get('pairing') is True:reply.update(pairingAvailable=True,certificateSha256=CONFIG['certificate_sha256'])
    sock.sendto(json.dumps(reply,separators=(',',':')).encode(),peer)
   except Exception:continue
if __name__=='__main__':
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2
 context.load_cert_chain(HOME/'server-cert.pem',HOME/'server-key.pem')
 server=TLSServer(('0.0.0.0',CONFIG['port']),Handler,context)
 (HOME/'worker.pid').write_text(str(__import__('os').getpid()))
 threading.Thread(target=discovery,daemon=True).start()
 try:server.serve_forever(poll_interval=.5)
 finally:
  try:server.server_close();ENGINE.close()
  finally:BROWSER.dispose()
