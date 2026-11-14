"""One native compute operation at a time; no durable personal-data or result store."""
import base64
import ctypes
import hashlib
import json
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.request
import uuid
import zlib
from pathlib import Path
from security import HOME,TOOLS
from windows_budget import constrain
from native_memory_image import NativeMemoryImage

FLAGS=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS
from image_profiles import DEFAULT_ID,profile,public_models,reference_models
MODEL_ID=DEFAULT_ID
MODEL_SHA=profile(MODEL_ID)['sha256']
GEMMA_ROOT=TOOLS/'models' if (TOOLS/'models/gemma-e2b.gguf').is_file() else TOOLS.parent/'gemma-replacement'
GEMMAS={
 'gemma-e2b':(GEMMA_ROOT/'gemma-e2b.gguf','0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b'),
 'gemma-e4b':(GEMMA_ROOT/'gemma-e4b.gguf','d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96'),
 'pc-text':(Path(r'D:\ZipHap\Compute\Models\Qwen3.5-9B-Q4_K_M.gguf'),'03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8'),
}
THINKING_ALLOWANCE=3500
_verified={}
def verify_file(path,expected):
 identity=(path.stat().st_size,path.stat().st_mtime_ns,expected)
 if _verified.get(path)==identity:return
 digest=hashlib.sha256()
 with path.open('rb') as source:
  while data:=source.read(4*1024*1024):digest.update(data)
 if digest.hexdigest()!=expected:raise ValueError('MODEL_INTEGRITY')
 _verified[path]=identity
def clean_scratch(path):
 scratch=(HOME/'scratch').resolve();target=path.resolve()
 if target.parent!=scratch or not re.fullmatch('[0-9a-f-]{36}',target.name):raise ValueError('INVALID_SCRATCH')
 if target.exists():shutil.rmtree(target)
def validate_png(data,expected_size=None):
 if not 44<=len(data)<=32*1024*1024 or data[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('INVALID_PNG')
 offset=8;size=None;ended=False
 while offset<len(data):
  if offset+12>len(data):raise ValueError('PNG_BOUNDS')
  length=struct.unpack_from('>I',data,offset)[0];kind=data[offset+4:offset+8];end=offset+12+length
  if end>len(data):raise ValueError('PNG_BOUNDS')
  if zlib.crc32(data[offset+4:end-4])&0xffffffff!=struct.unpack_from('>I',data,end-4)[0]:raise ValueError('PNG_CRC')
  if kind==b'IHDR':
   if offset!=8 or length!=13:raise ValueError('PNG_HEADER')
   width,height,depth,colour,compression,filtering,interlacing=struct.unpack('>IIBBBBB',data[offset+8:end-4])
   if width not in (512,1024) or height!=width or depth!=8 or colour not in (2,6) or compression or filtering or interlacing:raise ValueError('PNG_PROFILE')
   size=width
  if kind==b'IEND':
   ended=True
   if end!=len(data) or length:raise ValueError('PNG_TRAILING')
  offset=end
 if not ended or size is None or expected_size and size!=expected_size:raise ValueError('PNG_INCOMPLETE')
 return size
def available_memory():
 class Memory(ctypes.Structure):
  _fields_=[('length',ctypes.c_uint32),('load',ctypes.c_uint32)]+[(v,ctypes.c_uint64) for v in ['total','available','page_total','page_available','virtual_total','virtual_available','extended']]
 info=Memory();info.length=ctypes.sizeof(info)
 if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(info)):raise ctypes.WinError()
 return info.available
def gpu_busy():
 try:
  result=subprocess.run(['nvidia-smi','--query-gpu=utilization.gpu,memory.free','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=4,creationflags=FLAGS)
  used,free=[int(v.strip()) for v in result.stdout.splitlines()[0].split(',')]
  return used>65 or free<1300
 except Exception:return True

class Busy(Exception):pass
class ReferenceUnavailable(Exception):pass
class Engines:
 def __init__(self):
  self.lock=threading.RLock();self.jobs_lock=threading.RLock();self.jobs={};self.llm=None;self.llm_alias=None;self.llm_ctx=0;self.llm_port=None;self.llm_key=None;self.last_used=time.monotonic();self.upstreams={};self.upstream_lock=threading.Lock()
  scratch=HOME/'scratch';scratch.mkdir(exist_ok=True)
  for folder in scratch.iterdir():
   if folder.is_dir() and re.fullmatch('[0-9a-f-]{36}',folder.name):clean_scratch(folder)
  threading.Thread(target=self.sweep,daemon=True).start()
 def stop_llm(self):
  if self.llm:
   self.llm.terminate()
   try:self.llm.wait(timeout=10)
   except subprocess.TimeoutExpired:self.llm.kill();self.llm.wait(timeout=10)
  self.llm=None;self.llm_alias=None;self.llm_ctx=0
 def health(self):
  return {'service':'ZipHapCompute','version':1,'image_io':'ram_only','image_reference':True,'image_reference_models':reference_models(),'pairing_protocol':1,'image_model':MODEL_ID,'image_model_sha256':MODEL_SHA,'image_models':public_models(),'image_steps':[20,32,50],'image_size':1024,'llm_models':{key:value[1] for key,value in GEMMAS.items() if value[0].is_file()},'structured_output':True,'text_contexts':[4096,8192],'durable_personal_store':False}
 def image(self,request):
  allowed={'modelId','requestId','prompt','negativePrompt','seed','steps','referencePng','strength','ioMode'}
  if set(request)-allowed:raise ValueError('UNKNOWN_FIELD')
  if request.get('ioMode','ram_only')!='ram_only':raise ValueError('IMAGE_IO_PROFILE')
  model_id=request.get('modelId',MODEL_ID);profile(model_id)
  key=str(uuid.UUID(request['requestId']))
  prompt=request['prompt'];negative=request.get('negativePrompt','')
  if not isinstance(prompt,str) or not 1<=len(prompt)<=4000 or '\x00' in prompt or not isinstance(negative,str) or len(negative)>2000 or '\x00' in negative:raise ValueError('PROMPT_BOUND')
  steps=request.get('steps',50);seed=request.get('seed',-1)
  if type(steps)!=int or not 12<=steps<=50 or type(seed)!=int or not -1<=seed<=9223372036854775807:raise ValueError('IMAGE_OPTIONS')
  if seed<0:seed=uuid.uuid4().int&0x7fffffff
  strength=request.get('strength',.65)
  if not isinstance(strength,(int,float)) or not .4<=strength<=.85:raise ValueError('REFERENCE_STRENGTH')
  reference=base64.b64decode(request.get('referencePng',''),validate=True)
  if len(reference)>8*1024*1024:raise ValueError('REFERENCE_BOUND')
  if reference:
   validate_png(reference,1024)
   if not profile(model_id).get('supports_reference'):raise ReferenceUnavailable()
  elif profile(model_id).get('requires_reference'):raise ValueError('REFERENCE_REQUIRED')
  request_hash=hashlib.sha256(json.dumps(request,sort_keys=True,separators=(',',':')).encode()).hexdigest()
  with self.jobs_lock:
   if key in self.jobs:
    if self.jobs[key]['request_hash']!=request_hash:raise ValueError('REQUEST_ID_CONFLICT')
    return self.public_job(key)
   if len(self.jobs)>=4:raise Busy()
   self.jobs[key]={'id':key,'modelId':model_id,'phase':'queued','step':0,'steps':steps,'seed':seed,'created':time.monotonic(),'updated':time.monotonic(),'client_seen':time.monotonic(),'cancel':threading.Event(),'process':None,'result':None,'request_hash':request_hash,'error':None}
  thread=threading.Thread(target=self.run_image,args=(key,prompt,negative,reference,strength),daemon=True)
  self.jobs[key]['thread']=thread;thread.start()
  return self.public_job(key)
 def public_job(self,key):
  with self.jobs_lock:
   job=self.jobs[key]
   job['client_seen']=time.monotonic()
   result={k:job[k] for k in ['id','phase','step','steps','seed','error']}
   if job['result'] is not None:result.update({'sha256':hashlib.sha256(job['result']).hexdigest(),'bytes':len(job['result']),'width':1024,'height':1024,'modelId':job.get('modelId',MODEL_ID),'modelSha256':profile(job.get('modelId',MODEL_ID))['sha256'],'elapsedMillis':job['elapsed_ms']})
   return result
 def run_image(self,key,prompt,negative,reference,strength):
  job=self.jobs[key];folder=HOME/'scratch'/key;process=None
  try:
   with self.lock:
    self.stop_llm()
    deadline=time.monotonic()+1800
    while available_memory()<4*1024**3 or gpu_busy():
     job['phase']='waiting_resources'
     if job['cancel'].wait(3) or time.monotonic()>deadline:raise InterruptedError()
    if job['cancel'].is_set():raise InterruptedError()
    selected=profile(job.get('modelId',MODEL_ID))
    for path,sha in selected['files']:
     verify_file(path,sha)
     if job['cancel'].is_set():raise InterruptedError()
    arguments=selected['arguments']
    runtime=TOOLS/selected.get('runtime_dir','runtime')
    native=NativeMemoryImage(runtime/'sd-server.exe',arguments,runtime)
    def launched(child):
     nonlocal process
     process=child;job['process']=child;job['gpu_priority_lowered']=child.ziphap_gpu_priority_lowered
    def progress(step):
     job['step']=max(job['step'],min(job['steps'],step));job['phase']='decoding' if job['step']==job['steps'] else 'sampling';job['updated']=time.monotonic()
    started=time.monotonic();job['phase']='loading'
    result=native.generate(prompt,negative,job['seed'],job['steps'],1024,selected['cfg'],'euler',reference=reference,cancel=job['cancel'],on_process=launched,on_progress=progress,extra_args=('--disable-prefetch',) if reference else ())
    if job['cancel'].is_set():raise InterruptedError()
    validate_png(result,1024)
    job.update(result=result,phase='completed',step=job['steps'],elapsed_ms=round((time.monotonic()-started)*1000),updated=time.monotonic())
  except InterruptedError:job.update(phase='cancelled',error=None,updated=time.monotonic())
  except Exception as error:
   known={'NATIVE_MEMORY_SERVER_LOAD_FAILED','NATIVE_MEMORY_SERVER_LOAD_TIMEOUT','NATIVE_MEMORY_SERVER_EXITED','NATIVE_MEMORY_GENERATION_FAILED','NATIVE_MEMORY_GENERATION_TIMEOUT'}
   code=str(error) if str(error) in known else 'PC_IMAGE_COMPUTE_FAILED'
   job.update(phase='cancelled' if job['cancel'].is_set() else 'failed',error=None if job['cancel'].is_set() else code,updated=time.monotonic())
  finally:
   if process and process.poll() is None:process.terminate();process.wait(timeout=15)
   job['process']=None;clean_scratch(folder)
 def cancel_image(self,key):
  with self.jobs_lock:
   job=self.jobs[key];job['cancel'].set();process=job['process']
   if process and process.poll() is None:process.terminate()
   job['result']=None
   if job['phase']=='completed':job['phase']='acknowledged';job['updated']=time.monotonic()
 def load_llm(self,request):
  if set(request)-{'modelId','modelShaPrefix','contextSize'}:raise ValueError('UNKNOWN_FIELD')
  alias=request['modelId'];prefix=request['modelShaPrefix'];context=request['contextSize']
  if alias not in GEMMAS or not isinstance(prefix,str) or not re.fullmatch('[a-f0-9]{12,64}',prefix) or context not in (4096,8192):raise ValueError('LLM_PROFILE')
  path,sha=GEMMAS[alias]
  if not sha.startswith(prefix):raise ValueError('MODEL_SHA_MISMATCH')
  if not self.lock.acquire(timeout=30):raise Busy()
  try:
   self.last_used=time.monotonic()
   if self.llm and self.llm.poll() is None and self.llm_alias==alias and self.llm_ctx==context:return {'modelId':alias,'modelSha256':sha,'contextSize':context,'structuredOutput':True}
   self.stop_llm()
   if available_memory()<3*1024**3:raise Busy()
   verify_file(path,sha)
   ladder=(30,20,0) if alias=='pc-text' else (24,0)
   for attempt,layers in enumerate(ladder):
    try:
     self.start_llm(path,layers);break
    except (RuntimeError,TimeoutError):
     self.stop_llm()
     if attempt==len(ladder)-1:raise
   self.llm_alias=alias;self.llm_ctx=context;self.llm_layers=layers
   return {'modelId':alias,'modelSha256':sha,'contextSize':context,'structuredOutput':True}
  except Exception:self.stop_llm();raise
  finally:self.lock.release()
 def start_llm(self,path,layers):
   with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
   self.llm_port=port;self.llm_key=uuid.uuid4().hex
   env=dict(__import__('os').environ,LLAMA_API_KEY=self.llm_key)
   command=[str(TOOLS/'llama-runtime/llama-server.exe'),'-m',str(path),'--host','127.0.0.1','--port',str(port),'--ctx-size','8192','--parallel','1','--n-gpu-layers',str(layers),'--threads','6','--threads-batch','6','--batch-size','512','--ubatch-size','256','--jinja','--flash-attn','on','--cache-type-k','q8_0','--cache-type-v','q8_0','--cache-ram','0','--no-context-shift','--no-webui','--slots','--slot-save-path',str(HOME/'scratch'),'--log-disable','--reasoning-format','deepseek']
   self.llm=subprocess.Popen(command,cwd=TOOLS/'llama-runtime',env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=FLAGS)
   constrain(self.llm)
   deadline=time.monotonic()+90
   while time.monotonic()<deadline:
    if self.llm.poll() is not None:raise RuntimeError('LLM_LOAD_FAILED')
    try:
     with self.upstream('/health',None,timeout=1) as reply:
      if reply.status==200:break
    except Exception:time.sleep(.3)
   else:raise TimeoutError('LLM_LOAD_TIMEOUT')
 def upstream(self,path,payload,timeout=180):
  raw=json.dumps(payload,separators=(',',':')).encode() if payload is not None else None
  request=urllib.request.Request(f'http://127.0.0.1:{self.llm_port}'+path,data=raw,headers={'Authorization':'Bearer '+str(self.llm_key),'Content-Type':'application/json'})
  return urllib.request.urlopen(request,timeout=timeout)
 def require_llm(self,request):
  if not self.llm or self.llm.poll() is not None or request.get('modelId')!=self.llm_alias:raise ValueError('LLM_NOT_READY')
  messages=request['messages']
  if not isinstance(messages,list) or not 1<=len(messages)<=81:raise ValueError('MESSAGE_BOUND')
  for message in messages:
   if set(message)!={'role','content'} or message['role'] not in ('system','user','assistant') or not isinstance(message['content'],str):raise ValueError('MESSAGE_FORMAT')
  if len(json.dumps(messages,ensure_ascii=False).encode())>256*1024:raise ValueError('INPUT_BOUND')
  self.last_used=time.monotonic()
 def tokens(self,request):
  if not self.lock.acquire(timeout=30):raise Busy()
  try:
   self.require_llm(request)
   with self.upstream('/apply-template',{'messages':request['messages'],'add_generation_prompt':True}) as response:prompt=json.loads(response.read(2*1024*1024))['prompt']
   with self.upstream('/tokenize',{'content':prompt,'add_special':False,'parse_special':True}) as response:tokens=json.loads(response.read(2*1024*1024))['tokens']
   return {'count':len(tokens),'modelId':self.llm_alias}
  finally:self.lock.release()
 def chat(self,request,send):
  if set(request)-{'requestId','modelId','messages','maxTokens','thinking','schema'}:raise ValueError('UNKNOWN_FIELD')
  key=str(uuid.UUID(request['requestId']));maximum=request['maxTokens']
  if type(maximum)!=int or not 1<=maximum<=4096:raise ValueError('OUTPUT_BOUND')
  if not self.lock.acquire(timeout=30):raise Busy()
  try:
   self.require_llm(request)
   think=self.llm_alias=='pc-text' and request.get('schema') is None and maximum>512
   messages=request['messages']
   if self.llm_alias=='pc-text':
    rule='모든 생각과 답변은 반드시 한국어로 작성한다. 중국어 문장을 쓰지 않는다.'
    messages=[dict(m,content=rule+'\n'+m['content']) if m['role']=='system' else m for m in messages]
    if not any(m['role']=='system' for m in messages):messages=[{'role':'system','content':rule}]+messages
   payload={'messages':messages,'max_tokens':maximum+(THINKING_ALLOWANCE if think else 0),'stream':True,'stream_options':{'include_usage':True},'cache_prompt':False,'temperature':.7,'top_p':.8,'chat_template_kwargs':{'enable_thinking':think},'reasoning_format':'deepseek'}
   if request.get('schema') is not None:
    schema=request['schema']
    if not isinstance(schema,dict) or len(json.dumps(schema))>65536:raise ValueError('SCHEMA_BOUND')
    payload['response_format']={'type':'json_schema','json_schema':{'name':'ziphap_output','strict':True,'schema':schema}}
   with self.upstream('/v1/chat/completions',payload,timeout=300) as response:
    with self.upstream_lock:self.upstreams[key]=response
    send(response)
  finally:
   with self.upstream_lock:self.upstreams.pop(key,None)
   if self.llm and self.llm.poll() is None:
    try:
     with self.upstream('/slots/0?action=erase',{},timeout=3) as response:response.read(4096)
    except Exception:pass
   self.last_used=time.monotonic();self.lock.release()
 def cancel_chat(self,key):
  with self.upstream_lock:response=self.upstreams.get(key)
  if response:
   self.stop_llm()
   response.close()
 def sweep(self):
  while True:
   time.sleep(10)
   with self.jobs_lock:
    for key,job in list(self.jobs.items()):
     if time.monotonic()-job['created']>3600 or job['phase'] not in ('completed','acknowledged','cancelled','failed') and time.monotonic()-job['client_seen']>45:self.cancel_image(key)
     if job['phase'] in ('completed','acknowledged','cancelled','failed') and time.monotonic()-job['updated']>300:del self.jobs[key]
   if time.monotonic()-self.last_used>300 and self.lock.acquire(blocking=False):
    try:self.stop_llm()
    finally:self.lock.release()
 def close(self):
  with self.jobs_lock:
   jobs=list(self.jobs.values())
   for job in jobs:
    job['cancel'].set();process=job['process']
    if process and process.poll() is None:process.terminate()
    job['result']=None
  self.stop_llm()
  for job in jobs:
   thread=job.get('thread')
   if thread and thread is not threading.current_thread():thread.join(timeout=20)
