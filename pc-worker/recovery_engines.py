"""Opt-in recovery layered over the installed ownership checks. Legacy RAM-only jobs are unchanged."""
import base64, hashlib, json, threading, time, uuid
from pathlib import Path
from image_progress import mark,acquire_slot,resources_waiting
from checkpoint_store import CheckpointStore,TTL
from owned_engines import OwnedEngines,owner_value,request_key,terminate,OwnershipMismatch,_UNSET
from engines import HOME,TOOLS,profile,verify_file,available_memory,gpu_busy,validate_png,Busy
from native_memory_image import NativeMemoryImage

def job_control(method):
    def guarded(self,key,owner=_UNSET):
        with self.jobs_lock:control=self._job_owner(key,owner).setdefault('recovery_control',threading.RLock())
        with control:return method(self,key,owner)
    return guarded

class RecoveryEngines(OwnedEngines):
    def __init__(self,pairing_fingerprint):
        self.instance_id=str(uuid.uuid4());self.closing=False;self.store=CheckpointStore(HOME/'image-recovery-v1',pairing_fingerprint)
        super().__init__()
        with self.jobs_lock:
            for key in self.store.keys()[:64]:
                if len(self.jobs)>=4:break
                try:
                    saved=self.store.load(key);request=saved['request'];owner=owner_value(saved['owner'])
                    if request_key(request)!=key or owner is None:raise ValueError('RECOVERY_BINDING')
                    if hashlib.sha256(json.dumps(request,sort_keys=True,separators=(',',':')).encode()).hexdigest()!=saved['requestHash']:raise ValueError('RECOVERY_REQUEST_CHANGED')
                    job=self._restored(saved)
                    if saved['phase']=='completed':job.update(result=self.store.result(key),phase='completed')
                    job['cleanup_done'].set();self.jobs[key]=job
                except Exception:pass # Never interpret damaged state as a new generation request.
    def _restored(self,saved):
        request=saved['request'];now=time.monotonic()
        return dict(id=request['requestId'],modelId=request['modelId'],phase='suspended',step=saved.get('step',0),
            steps=request['steps'],seed=request['seed'],created=now,updated=now,client_seen=now,cancel=threading.Event(),
            process=None,result=None,request_hash=saved['requestHash'],error=None,owner=saved['owner'],thread=None,
            cleanup_done=threading.Event(),recovery=True,request=request,createdWall=saved['createdWall'],
            binding=saved.get('binding'),attempts=saved.get('attempts',1),elapsed_ms=saved.get('elapsedMillis',0),pause=False)
    def _persist(self,job):
        self.store.save(job['id'],dict(request=job['request'],owner=job['owner'],requestHash=job['request_hash'],
            createdWall=job['createdWall'],phase=job['phase'],step=job['step'],binding=job.get('binding'),
            attempts=job.get('attempts',1),elapsedMillis=job.get('elapsed_ms',0)))
    def image(self,request,owner=None):
        if request.get('ioMode')!='encrypted_checkpoint':return super().image(request,owner)
        owner=owner_value(owner)
        if owner is None:raise ValueError('RECOVERY_OWNER_REQUIRED')
        key=request_key(request);clean=request|{'ioMode':'ram_only'}
        with self.jobs_lock:
            existing=self.jobs.get(key)
            if existing and not existing.get('recovery'):raise ValueError('RECOVERY_MODE_CONFLICT')
            super().image(clean,owner)
            job=self.jobs[key]
            if not existing:
                job.update(recovery=True,request=clean,createdWall=time.time(),attempts=1,pause=False)
                try:self._persist(job)
                except Exception:job['cancel'].set();raise
            return self.public_job(key,owner)
    def public_job(self,key,owner=_UNSET):
        with self.jobs_lock:
            result=super().public_job(key,owner);job=self.jobs[key]
            if job.get('recovery'):
                if time.time()>=job['createdWall']+TTL:raise ValueError('RECOVERY_EXPIRED')
                saved=self.store.native_step(key,job['binding'],job['steps']) if job.get('binding') else 0
                result.update(recovery=True,checkpointStep=saved,resumedFrom=job.get('resumedFrom',0),
                    expiresAt=int(job['createdWall']+TTL),attempts=job.get('attempts',1))
            return result
    def _binding(self,job,runtime,selected):
        binaries={name:hashlib.sha256((runtime/name).read_bytes()).hexdigest() for name in
            ('sd-server.exe','stable-diffusion.dll','ggml.dll','ggml-base.dll','ggml-cpu.dll','ggml-vulkan.dll','ggml-cuda.dll')}
        value={'requestHash':job['request_hash'],'model':selected['sha256'],'files':[(str(p),h) for p,h in selected['files']],
            'runtime':binaries,'schema':1,'sampler':'euler','cfg':selected['cfg'],'arguments':selected['arguments']}
        return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    def run_image(self,key,prompt,negative,reference,strength):
        with self.jobs_lock:job=self.jobs[key];recovery=job.get('recovery',False)
        if not recovery:return super().run_image(key,prompt,negative,reference,strength)
        acquired=False;started=time.monotonic()
        try:
            acquired=acquire_slot(self.lock,job)
            self.stop_llm()
            while resources_waiting(job,available_memory(),gpu_busy()):
                if job['cancel'].wait(3):raise InterruptedError()
                if time.time()>job['createdWall']+TTL:raise TimeoutError('RECOVERY_EXPIRED')
            mark(job,'verifying_models')
            selected=profile(job['modelId'])
            for path,sha in selected['files']:verify_file(path,sha)
            mark(job,'preparing_runtime')
            runtime=TOOLS/'runtime-checkpoint-37bc88b-v1'
            binding=self._binding(job,runtime,selected)
            if job.get('binding') not in (None,binding):raise ValueError('RECOVERY_RUNTIME_CHANGED')
            job['binding']=binding;job['resumedFrom']=self.store.native_step(key,binding,job['steps'])
            job['step']=job['resumedFrom'];mark(job,'loading_model');self._persist(job)
            def launched(process):job['process']=process
            def progress(step):
                job.update(step=max(job['step'],min(job['steps'],step)),phase='decoding' if step==job['steps'] else 'sampling',updated=time.monotonic())
            native=NativeMemoryImage(runtime/'sd-server.exe',selected['arguments'],runtime)
            result=native.generate(prompt,negative,job['seed'],job['steps'],1024,selected['cfg'],'euler',reference=reference,
                cancel=job['cancel'],on_process=launched,on_progress=progress,extra_args=('--disable-prefetch',) if reference else (),
                native_environment={'ZIPHAP_EULER_CHECKPOINT':str(self.store.native(key)), 'ZIPHAP_CHECKPOINT_BINDING':binding})
            if job['cancel'].is_set():raise InterruptedError()
            validate_png(result,1024);self.store.result(key,result)
            job.update(result=result,phase='completed',step=job['steps'],error=None)
        except Exception:
            job.update(phase='suspended' if job.get('pause') else 'failed',error='PC_IMAGE_COMPUTE_FAILED')
            try:
                saved=self.store.native_step(key,job['binding'],job['steps']) if job.get('binding') else 0
                if saved:job.update(phase='resumable',step=saved,error='PC_IMAGE_CHECKPOINT_AVAILABLE')
            except Exception:job.update(phase='failed',error='PC_IMAGE_CHECKPOINT_INVALID')
        finally:
            terminate(job.get('process'),wait=True)
            job.update(process=None,updated=time.monotonic(),elapsed_ms=job.get('elapsed_ms',0)+round((time.monotonic()-started)*1000))
            try:
                if job['cancel'].is_set() and not job.get('pause'):
                    job.update(phase='cancelled',result=None,error=None);self.store.remove(key)
                else:self._persist(job)
            finally:
                job['cleanup_done'].set()
                if acquired:self.lock.release()
    @job_control
    def resume_image(self,key,owner):
        with self.jobs_lock:
            if self.closing:raise ValueError('WORKER_CLOSING')
            job=self._job_owner(key,owner)
            if not job.get('recovery'):raise ValueError('NOT_RECOVERABLE')
            if job['phase'] in ('cancelled','acknowledged','failed'):raise ValueError('NO_RECOVERY_STATE')
            self.store.load(key) # expiry and pairing epoch are checked before any launch
            if job['phase']=='completed' or job.get('thread') and job['thread'].is_alive():return self.public_job(key,owner)
            if job['phase'] not in ('resumable','suspended'):raise ValueError('NO_RECOVERY_STATE')
            job.update(cancel=threading.Event(),cleanup_done=threading.Event(),pause=False,phase='queued',error=None,
                attempts=job.get('attempts',1)+1,client_seen=time.monotonic())
            request=job['request'];reference=base64.b64decode(request.get('referencePng',''),validate=True)
            thread=threading.Thread(target=self.run_image,args=(key,request['prompt'],request.get('negativePrompt',''),reference,.65),daemon=True)
            job['thread']=thread;thread.start();return self.public_job(key,owner)
    @job_control
    def suspend_image(self,key,owner):
        with self.jobs_lock:
            job=self._job_owner(key,owner)
            if not job.get('recovery'):raise ValueError('NOT_RECOVERABLE')
            if job['phase'] in ('completed','cancelled','acknowledged'):return {'released':True,'cleanup_complete':True}
            job['pause']=True
        result=super().cancel_image(key,owner)
        if result['cleanup_complete']:
            # Base cancellation clears result but the encrypted checkpoint survives.
            saved=self.store.native_step(key,job['binding'],job['steps']) if job.get('binding') else 0
            job.update(phase='resumable' if saved else 'suspended',step=saved);self._persist(job)
        return result
    @job_control
    def cancel_image(self,key,owner=_UNSET):
        with self.jobs_lock:
            job=self._job_owner(key,owner);recovery=job.get('recovery',False);job['pause']=False
        result=super().cancel_image(key,owner)
        if recovery and result['cleanup_complete']:self.store.remove(key)
        return result
    def sweep(self):
        while True:
            time.sleep(10);now=time.monotonic()
            with self.jobs_lock:jobs=list(self.jobs.items())
            for key,job in jobs:
                try:
                    if job.get('recovery'):
                        if time.time()>job['createdWall']+TTL:self.cancel_image(key,job.get('owner'))
                        elif job.get('thread') and job['thread'].is_alive() and now-job['client_seen']>45:self.suspend_image(key,job.get('owner'))
                    elif now-job['created']>4*3600+120 or job['phase'] not in ('completed','acknowledged','cancelled','failed') and now-job['client_seen']>45:
                        self.cancel_image(key,job.get('owner'))
                except Exception:pass
            with self.jobs_lock:
                for key,job in list(self.jobs.items()):
                    thread=job.get('thread');done=not thread or not thread.is_alive()
                    if done and ((job.get('recovery') and (time.time()>job['createdWall']+TTL or job['phase'] in ('cancelled','acknowledged'))) or
                        not job.get('recovery') and job['phase'] in ('completed','acknowledged','cancelled','failed') and now-job['updated']>300):self.jobs.pop(key,None)
            if now-self.last_used>45 and self.lock.acquire(blocking=False):
                try:self.stop_llm();self._release_owner(self.owner)
                finally:self.lock.release()
    def health(self):
        return super().health()|{'image_recovery':1,'image_recovery_storage':'user_dpapi_24h','image_checkpoint_interval':5,
            'durable_personal_store':True,'durable_personal_store_scope':'explicit_image_recovery_only',
            'image_io_modes':['ram_only','encrypted_checkpoint'],'image_recovery_instance':self.instance_id}
    def image_bytes(self,key,owner=None):
        with self.jobs_lock:
            job=self._job_owner(key,owner)
            if job.get('recovery') and time.time()>=job['createdWall']+TTL:raise ValueError('RECOVERY_EXPIRED')
            return super().image_bytes(key,owner)
    def reference_bytes(self,key,owner):
        with self.jobs_lock:
            job=self._job_owner(key,owner)
            if not job.get('recovery'):raise ValueError('NOT_RECOVERABLE')
            self.store.load(key)
            data=base64.b64decode(job['request'].get('referencePng',''),validate=True)
            if not data:raise ValueError('NO_REFERENCE')
            validate_png(data,1024);return data
    def close(self):
        self.closing=True
        with self.jobs_lock:jobs=list(self.jobs.items())
        for key,job in jobs:
            if job.get('recovery'):self.suspend_image(key,job.get('owner'))
            else:self.cancel_image(key,job.get('owner'))
        if self.lock.acquire(timeout=20):
            try:self.stop_llm()
            finally:self.lock.release()
