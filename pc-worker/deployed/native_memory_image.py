"""Native diffusion HTTP in RAM only: no prompt/reference/output files or user log files."""
import base64
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.request
import uuid
from windows_budget import constrain,refresh_resident_budget

class NativeMemoryImage:
    def __init__(self,executable,model_args,cwd):
        self.executable=executable;self.model_args=model_args;self.cwd=cwd;self.last_native_output=''
    @staticmethod
    def thread_count(requested):
        if type(requested) is not int or not 1<=requested<=4:raise ValueError('NATIVE_THREAD_BUDGET')
        return min(requested,os.cpu_count() or 2)
    def generate(self,prompt,negative,seed,steps,size,cfg,sampler,reference=b'',cancel=None,on_process=None,on_progress=None,extra_args=(),native_environment=None,on_stage=None,cpu_threads=2):
        if any('<sd_cpp_extra_args' in text.lower() or '</sd_cpp_extra_args' in text.lower() for text in (prompt,negative)):
            raise ValueError('PROMPT_CONTROL_TAG_NOT_ALLOWED')
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        command=[str(self.executable),'--listen-ip','127.0.0.1','--listen-port',str(port),*map(str,self.model_args),
                 '--width',str(size),'--height',str(size),'--steps',str(steps),'--cfg-scale',str(cfg),'--sampling-method',sampler,
                 '--seed',str(seed),'--threads',str(self.thread_count(cpu_threads)),'--mmap','--vae-tiling','--flash-attn','on','--conditioning-cache-size','0',
                 '--disable-image-metadata','--log-level','error',*extra_args]
        reader=None;progress_reader=None;finished=threading.Event()
        process=subprocess.Popen(command,cwd=self.cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS,
                                 env={**{k:v for k,v in __import__("os").environ.items() if not k.startswith("ZIPHAP_CHECKPOINT") and k!="ZIPHAP_EULER_CHECKPOINT"},**(native_environment or {})})
        try:
            process.ziphap_gpu_priority_lowered=constrain(process)
            if on_process:on_process(process)
            def drain():
                buffer=''
                while chunk:=process.stdout.read(256):
                    buffer=(buffer+chunk.decode('utf-8',errors='replace'))[-16384:]
                    self.last_native_output=buffer
            reader=threading.Thread(target=drain,daemon=True);reader.start()
            base=f'http://127.0.0.1:{port}'
            deadline=time.monotonic()+180
            while time.monotonic()<deadline:
                if cancel is not None and cancel.is_set():raise InterruptedError()
                if process.poll() is not None:raise RuntimeError('NATIVE_MEMORY_SERVER_LOAD_FAILED')
                try:
                    with urllib.request.urlopen(base+'/v1/models',timeout=1) as response:
                        if response.status==200:break
                except Exception:time.sleep(.2)
            else:raise TimeoutError('NATIVE_MEMORY_SERVER_LOAD_TIMEOUT')
            # Native async jobs expose request-scoped numerical preview progress. The OpenAI
            # synchronous API does not populate /sdapi/v1/progress in runtime37bc88b.
            payload={'prompt':prompt,'negative_prompt':negative,'seed':seed,'width':size,'height':size,
                     'batch_count':1,'embed_image_metadata':False,'preview':'proj','preview_interval':1,
                     'output_format':'png','output_compression':100}
            if reference:payload['ref_images']=[base64.b64encode(reference).decode('ascii')]
            def read_json(request,timeout=10):
                with urllib.request.urlopen(request,timeout=timeout) as response:
                    data=response.read(48*1024*1024+1)
                    if len(data)>48*1024*1024:raise ValueError('IMAGE_RESPONSE_BOUND')
                return json.loads(data)
            request=urllib.request.Request(base+'/sdcpp/v1/img_gen',data=json.dumps(payload,separators=(',',':')).encode(),headers={'Content-Type':'application/json'})
            accepted=read_json(request,30);native_id=accepted.get('id')
            if not isinstance(native_id,str) or not re.fullmatch(r'job_[a-zA-Z0-9_-]{1,80}',native_id):raise ValueError('NATIVE_JOB_ID')
            if on_stage:on_stage('preparing_inputs')
            deadline=time.monotonic()+4*3600
            while True:
                if cancel is not None and cancel.is_set():raise InterruptedError()
                if process.poll() is not None:raise RuntimeError('NATIVE_MEMORY_SERVER_EXITED')
                refresh_resident_budget(process)
                job=read_json(base+'/sdcpp/v1/jobs/'+native_id)
                if job.get('id')!=native_id or job.get('kind')!='img_gen':raise ValueError('NATIVE_JOB_BINDING')
                state=job.get('status')
                preview=job.get('preview') or {}
                if (preview.get('pass')==1 and type(preview.get('step')) is int and
                    type(preview.get('total_steps')) is int and preview['total_steps']==steps and
                    0<preview['step']<=steps and on_progress):on_progress(preview['step'])
                if state=='completed':
                    images=job.get('result',{}).get('images',[])
                    if len(images)!=1:raise ValueError('IMAGE_COUNT')
                    if cancel is not None and cancel.is_set():raise InterruptedError()
                    if on_progress:on_progress(steps)
                    return base64.b64decode(images[0]['b64_json'],validate=True)
                if state=='cancelled':raise InterruptedError()
                if state=='failed':raise RuntimeError('NATIVE_MEMORY_GENERATION_FAILED')
                if state not in ('queued','generating'):raise ValueError('NATIVE_JOB_STATE')
                if time.monotonic()>deadline:raise TimeoutError('NATIVE_MEMORY_GENERATION_TIMEOUT')
                if cancel is not None:
                    if cancel.wait(1):raise InterruptedError()
                else:time.sleep(1)
        finally:
            finished.set()
            if process.poll() is None:
                try:process.terminate()
                except OSError:pass
                # Keep the caller's compute lock until even an unregistered child exits.
                while process.poll() is None:
                    try:process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        try:process.kill()
                        except OSError:pass
            if reader and reader.ident is not None:reader.join(timeout=2)
            if progress_reader and progress_reader.ident is not None:progress_reader.join(timeout=2)
