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
from security import HOME,load,private_v4
from engines import Busy,ReferenceUnavailable
from owned_engines import OwnedEngines,OwnershipMismatch,RequestNotActive,ComputeCancelled,owner_value
from pairing import Pairing

from recovery_engines import RecoveryEngines
def overlay():
 import ipaddress
 try:
  for value in socket.gethostbyname_ex(socket.gethostname())[2]:
   if ipaddress.ip_address(value) in ipaddress.ip_network('100.64.0.0/10'):return {'overlayEndpoint':f"https://{value}:{CONFIG['port']}"}
 except OSError:pass
 return {}
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
 # Successful image progress polls are ~95% of traffic and carry no diagnostic value.
 if status==200 and not error and POLL.match(path.split('?')[0]):return
 try:
  import os
  with LOG_LOCK:
   if os.path.exists(LOG) and os.path.getsize(LOG)>LOG_LIMIT:os.replace(LOG,LOG[:-4]+'.1.log')
   with open(LOG,'a',encoding='utf-8') as out:out.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {path} {status} {error}\n")
 except OSError:pass
CONFIG=load();TOKEN=CONFIG['token']
ENGINE=RecoveryEngines(hashlib.sha256(TOKEN.encode()).hexdigest());PAIRING=Pairing(CONFIG)
from media_engine import MediaEngine
from engines import gpu_busy
MEDIA=MediaEngine(lambda:not (ENGINE.llm and ENGINE.llm.poll() is None) and not gpu_busy())
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args):pass
 def reply(self,value,status=200):
  note(self.path,status,value.get('error') if isinstance(value,dict) else None)
  data=json.dumps(value,separators=(',',':')).encode()
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
  value=json.loads(raw,object_pairs_hook=pairs)
  if not isinstance(value,dict):raise ValueError('JSON_OBJECT_REQUIRED')
  return value
 def dispatch(self):
  self.connection.settimeout(60)
  path=self.path
  if '?' in path or '%' in path or not path.startswith('/v1/'):raise ValueError('ROUTE')
  if self.command=='POST' and path in ('/v1/pair/start','/v1/pair/finish') and private_v4(self.client_address[0]):
   self.connection.settimeout(5)
   request=self.body(4096)
   self.reply(PAIRING.start(self.client_address[0],request) if path.endswith('/start') else PAIRING.finish(self.client_address[0],request));return
  if not self.authorized():self.reply({'error':'UNAUTHORIZED'},401);return
  if self.command=='GET' and path=='/v1/health':self.reply(MEDIA.health(ENGINE.health())|{'serverId':CONFIG['server_id']}|overlay()|resident(self.headers.get('X-ZipHap-Compute-Owner')));return
  owners=self.headers.get_all('X-ZipHap-Compute-Owner',[])
  if len(owners)>1:raise ValueError('DUPLICATE_OWNER')
  owner=owner_value(owners[0] if owners else None)
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
    if self.body():raise ValueError('RECOVERY_BODY_MUST_BE_EMPTY')
    result=ENGINE.resume_image(key,owner) if suffix=='/resume' else ENGINE.suspend_image(key,owner)
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
   note('failure '+self.path,0,type(error).__name__+(f' at {tb.tb_frame.f_code.co_filename.rsplit(chr(92),1)[-1]}:{tb.tb_lineno}' if tb else ''))
   try:self.reply({'error':'PC_COMPUTE_FAILED'},503)
   except Exception:self.close_connection=True
 do_GET=do_POST=do_DELETE=handle_request

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
 server=ThreadingHTTPServer(('0.0.0.0',CONFIG['port']),Handler);server.daemon_threads=True
 server.socket=context.wrap_socket(server.socket,server_side=True)
 (HOME/'worker.pid').write_text(str(__import__('os').getpid()))
 threading.Thread(target=discovery,daemon=True).start()
 try:server.serve_forever(poll_interval=.5)
 finally:server.server_close();ENGINE.close()
