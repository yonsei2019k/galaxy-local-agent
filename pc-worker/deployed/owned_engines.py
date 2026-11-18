"""Request-owned native lifetimes. No request text, IDs, or credentials are persisted."""
import re
import subprocess
import threading
import time
import uuid
from engines import Engines, Busy, GEMMAS, clean_scratch, validate_text_request
from security import HOME

class OwnershipMismatch(Exception): pass
class RequestNotActive(Exception): pass
class ComputeCancelled(Exception): pass
_UNSET=object()
SOFT_CANCEL_WAIT=2
FINAL_CANCEL_WAIT=6

def owner_value(value):
 if value is None:return None
 if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError('COMPUTE_OWNER_FORMAT')
 return value

def request_key(request):
 value=request.get('requestId',str(uuid.uuid4()))
 if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError('REQUEST_ID_FORMAT')
 return value

def terminate(process,wait=False):
 if process is None:return True
 if process.poll() is None:
  try:process.terminate()
  except OSError:pass
 if wait:
  # Never release the native-compute lock while a child might still be computing.
  while process.poll() is None:
   try:process.wait(timeout=1)
   except subprocess.TimeoutExpired:
    try:process.kill()
    except OSError:pass
 return process.poll() is not None

class OwnedEngines(Engines):
 def __init__(self):
  self.owner_lock=threading.RLock();self.owner=None;self.owner_set=False
  self.operation=None;self.records={};self.request_local=threading.local()
  super().__init__()
 def check_owner(self,owner):
  owner=owner_value(owner)
  if self.owner_set and self.owner!=owner:raise OwnershipMismatch()
 def _begin(self,owner,kind,key,handoff=False):
  # Caller holds the native RLock. Only idle load may transfer ownership.
  with self.owner_lock:
   if not handoff:self.check_owner(owner)
   if handoff and owner is None and self.owner_set and self.owner is not None:raise OwnershipMismatch()
   changed=self.owner_set and self.owner!=owner
   if (owner,key) in self.records:raise ValueError('REQUEST_ID_REUSED')
   if handoff:self.owner=owner;self.owner_set=True
   record={'owner':owner,'kind':kind,'key':key,'cancel':threading.Event(),'done':threading.Event(),
           'process':self.llm,'cleanup_verified':False,'slots_cleared':False,'created':time.monotonic()}
   self.operation=record;self.records[(owner,key)]=record
   stale=[k for k,v in self.records.items() if v['done'].is_set()]
   for k in stale[:-64]:self.records.pop(k,None)
   return record,changed
 def _finish(self,record,success):
  # Base methods release only their nested RLock; this outer lock remains held.
  while True:
   with self.owner_lock:
    record['process']=self.llm or record['process']
    stop=not success or record['cancel'].is_set() or record['kind']=='chat' and not record['slots_cleared']
   if stop:self.stop_llm()
   with self.owner_lock:
    if record['cancel'].is_set() and self.llm and self.llm.poll() is None:continue
    record['cleanup_verified']=self.llm is None or self.llm.poll() is not None
    if self.operation is record:self.operation=None
    self.last_used=time.monotonic()
    break
  self.lock.release()
  record['done'].set()
 def stop_llm(self):
  process=self.llm
  terminate(process,wait=True)
  if self.llm is process:
   self.llm=None;self.llm_alias=None;self.llm_ctx=0;self.llm_port=None;self.llm_key=None
 def upstream(self,path,payload,timeout=180):
  with self.owner_lock:record=self.operation
  if record and record['cancel'].is_set():raise ComputeCancelled()
  response=super().upstream(path,payload,timeout)
  if record and path.startswith('/slots/') and getattr(response,'status',200)==200:record['slots_cleared']=True
  return response
 def load_llm(self,request,owner=None):
  owner=owner_value(owner);key=request_key(request)
  body={k:v for k,v in request.items() if k!='requestId'}
  if set(body)-{'modelId','modelShaPrefix','contextSize'}:raise ValueError('UNKNOWN_FIELD')
  alias=body['modelId'];prefix=body['modelShaPrefix'];context=body['contextSize']
  if alias not in GEMMAS or not isinstance(prefix,str) or not re.fullmatch('[a-f0-9]{12,64}',prefix) or type(context)!=int or context not in (4096,8192):raise ValueError('LLM_PROFILE')
  if not GEMMAS[alias][1].startswith(prefix):raise ValueError('MODEL_SHA_MISMATCH')
  if alias=='pc-text-4b' and prefix!=GEMMAS[alias][1]:raise ValueError('MODEL_SHA_MISMATCH')
  if not self.lock.acquire(timeout=30):raise Busy()
  record=None;success=False
  try:
   record,changed=self._begin(owner,'load',key,handoff=True)
   if changed:self.stop_llm()
   if record['cancel'].is_set():raise ComputeCancelled()
   result=super().load_llm(body)
   if record['cancel'].is_set():raise ComputeCancelled()
   success=True
   return result|{'requestId':key}
  finally:
   if record:self._finish(record,success)
   else:self.lock.release()
 def tokens(self,request,owner=None):
  owner=owner_value(owner);key=request_key(request)
  validate_text_request(request,'tokens')
  if not self.lock.acquire(timeout=30):raise Busy()
  record=None;success=False
  try:
   record,_=self._begin(owner,'tokens',key)
   result=super().tokens(request|{'requestId':key})
   if record['cancel'].is_set():raise ComputeCancelled()
   success=True
   return result|{'requestId':key}
  finally:
   if record:self._finish(record,success)
   else:self.lock.release()
 def chat(self,request,send,owner=None):
  owner=owner_value(owner);key=request_key(request)
  validate_text_request(request,'chat')
  if not self.lock.acquire(timeout=30):raise Busy()
  record=None;success=False
  try:
   record,_=self._begin(owner,'chat',key)
   # Registered before urlopen: cancellation works while upstream prefill has no headers.
   result=super().chat(request,send)
   if record['cancel'].is_set():raise ComputeCancelled()
   success=True
   return result
  finally:
   if record:self._finish(record,success)
   else:self.lock.release()
 def _cancel_record(self,record):
  with self.owner_lock:
   if self.operation is record:
    record['cancel'].set();process=self.llm
    record['process']=process or record['process']
   else:process=None
  if process:terminate(process)
  if not record['done'].wait(SOFT_CANCEL_WAIT) and process and process.poll() is None:
   try:process.kill()
   except OSError:pass
  record['done'].wait(FINAL_CANCEL_WAIT)
  complete=record['done'].is_set() and record['cancel'].is_set() and record['cleanup_verified']
  return {'cancelled':record['cancel'].is_set(),'cleanup_complete':complete}
 def cancel_chat(self,key,owner=None):
  owner=owner_value(owner);key=str(uuid.UUID(key))
  with self.owner_lock:
   self.check_owner(owner)
   record=self.records.get((owner,key))
   if record is None:raise RequestNotActive()
   if self.operation is not record and not record['cancel'].is_set():raise RequestNotActive()
  return self._cancel_record(record)
 def unload_llm(self,owner=None,request=None):
  owner=owner_value(owner);request=request or {}
  if set(request)-{'requestId'}:raise ValueError('UNKNOWN_FIELD')
  wanted=request_key(request) if 'requestId' in request else None
  with self.owner_lock:
   self.check_owner(owner);record=self.operation
   if record is not None and wanted is not None and record['key']!=wanted:raise RequestNotActive()
  if record is not None:
   result=self._cancel_record(record)
   if result['cleanup_complete']:self._release_owner(owner)
   return {'released':result['cleanup_complete'],'cleanup_complete':result['cleanup_complete']}
  if not self.lock.acquire(timeout=30):raise Busy()
  try:
   with self.owner_lock:self.check_owner(owner)
   self.stop_llm();self._release_owner(owner)
   return {'released':True,'cleanup_complete':True}
  finally:self.lock.release()
 def _release_owner(self,owner):
  with self.owner_lock:
   if self.owner==owner and self.operation is None and self.llm is None:self.owner=None;self.owner_set=False
 def _job_owner(self,key,owner):
  if owner is _UNSET:owner=getattr(self.request_local,'owner',None)
  owner=owner_value(owner);job=self.jobs[key]
  if 'owner' not in job and hasattr(self.request_local,'owner'):job['owner']=self.request_local.owner
  if job.get('owner')!=owner:raise OwnershipMismatch()
  return job
 def image(self,request,owner=None):
  owner=owner_value(owner);key=request_key(request)
  with self.jobs_lock:
   if key in self.jobs:self._job_owner(key,owner)
   self.request_local.owner=owner
   try:
    result=super().image(request)
    self.jobs[key]['owner']=owner;self.jobs[key].setdefault('cleanup_done',threading.Event())
    return result
   finally:del self.request_local.owner
 def public_job(self,key,owner=_UNSET):
  with self.jobs_lock:
   job=self._job_owner(key,owner)
   result=super().public_job(key)
   thread=job.get('thread')
   result['cleanup_complete']=bool(job.get('cleanup_done') and job['cleanup_done'].is_set() and not(thread and thread.is_alive()))
   return result
 def image_bytes(self,key,owner=None):
  with self.jobs_lock:
   job=self._job_owner(key,owner)
   return job['result'] if job['phase']=='completed' else None
 def run_image(self,key,prompt,negative,reference,strength):
  with self.jobs_lock:
   job=self.jobs[key];job.setdefault('cleanup_done',threading.Event())
  acquired=False
  try:
   while not acquired:
    if job['cancel'].is_set():raise InterruptedError()
    acquired=self.lock.acquire(timeout=.1)
   if job['cancel'].is_set():raise InterruptedError()
   super().run_image(key,prompt,negative,reference,strength)
  except InterruptedError:job.update(phase='cancelled',error=None)
  except Exception as failure:
   with open(r'D:\ZipHap\Compute\requests.log','a',encoding='utf-8') as out:out.write(f"{time.strftime('%H:%M:%S')} image_job failed PC_IMAGE_COMPUTE_FAILED\n")
   job.update(phase='cancelled' if job['cancel'].is_set() else 'failed',error='PC_IMAGE_COMPUTE_FAILED')
  finally:
   terminate(job.get('process'),wait=True)
   clean_scratch(HOME/'scratch'/key)
   with self.jobs_lock:
    job['process']=None
    if job['cancel'].is_set() and job['phase']!='acknowledged':job.update(phase='cancelled',result=None,error=None)
    job['updated']=time.monotonic();job['cleanup_done'].set()
   if acquired:self.lock.release()
 def cancel_image(self,key,owner=_UNSET):
  with self.jobs_lock:
   job=self._job_owner(key,owner);job['cancel'].set();process=job.get('process');thread=job.get('thread')
   job['result']=None
   if job['phase']=='completed':job.update(phase='acknowledged',updated=time.monotonic())
  terminate(process)
  if thread and thread is not threading.current_thread():
   thread.join(timeout=SOFT_CANCEL_WAIT)
   if thread.is_alive() and process and process.poll() is None:
    try:process.kill()
    except OSError:pass
   thread.join(timeout=FINAL_CANCEL_WAIT)
  complete=bool(job.get('cleanup_done') and job['cleanup_done'].is_set() and not(thread and thread.is_alive()) and job.get('process') is None)
  return {'released':complete,'cleanup_complete':complete}
 def health(self):
  value=super().health();free=self.lock.acquire(blocking=False)
  if free:self.lock.release()
  with self.owner_lock:record=self.operation;pending=bool(record and record['cancel'].is_set() and not record['done'].is_set())
  with self.jobs_lock:pending=pending or any(j['cancel'].is_set() and j.get('thread') and j['thread'].is_alive() for j in self.jobs.values())
  return value|{'compute_owner_binding':1,'compute_busy':not free,'cleanup_pending':pending}
 def sweep(self):
  while True:
   time.sleep(10);now=time.monotonic()
   with self.jobs_lock:
    expired=[(k,j.get('owner')) for k,j in self.jobs.items() if now-j['created']>4*3600+120 or j['phase'] not in ('completed','acknowledged','cancelled','failed') and now-j['client_seen']>45]
   for key,owner in expired:
    try:self.cancel_image(key,owner)
    except KeyError:pass
   with self.jobs_lock:
    for key,job in list(self.jobs.items()):
     if job['phase'] in ('completed','acknowledged','cancelled','failed') and not job['thread'].is_alive() and now-job['updated']>300:del self.jobs[key]
   if now-self.last_used>45 and self.lock.acquire(blocking=False):
    try:
     self.stop_llm();self._release_owner(self.owner)
    finally:self.lock.release()
 def close(self):
  with self.owner_lock:record=self.operation
  if record:self._cancel_record(record)
  with self.jobs_lock:jobs=[(key,job.get('owner')) for key,job in self.jobs.items()]
  for key,owner in jobs:self.cancel_image(key,owner)
  if self.lock.acquire(timeout=20):
   try:self.stop_llm()
   finally:self.lock.release()
