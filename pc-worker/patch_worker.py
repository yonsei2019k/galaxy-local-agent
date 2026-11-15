"""Surgical installation into a user-private worker; caller must establish idle twice first."""
import hashlib,pathlib,shutil

def transformed(root):
    root=pathlib.Path(root)
    native=(root/'native_memory_image.py').read_text(encoding='utf-8')
    old='extra_args=()):'
    if native.count(old)!=1:raise ValueError('NATIVE_SIGNATURE_DRIFT')
    native=native.replace(old,'extra_args=(),native_environment=None,on_stage=None,cpu_threads=2):')
    old='    def generate('
    if native.count(old)!=1:raise ValueError('NATIVE_CLASS_DRIFT')
    native=native.replace(old,'    @staticmethod\n'
        '    def thread_count(requested):\n'
        "        if type(requested) is not int or not 1<=requested<=4:raise ValueError('NATIVE_THREAD_BUDGET')\n"
        '        return min(requested,__import__("os").cpu_count() or 2)\n'+old)
    old="'--threads','2'"
    if native.count(old)!=1:raise ValueError('NATIVE_THREADS_DRIFT')
    native=native.replace(old,"'--threads',str(self.thread_count(cpu_threads))")
    old="            if not isinstance(native_id,str) or not re.fullmatch(r'job_[a-zA-Z0-9_-]{1,80}',native_id):raise ValueError('NATIVE_JOB_ID')\n            deadline=time.monotonic()+4*3600\n"
    if native.count(old)!=1:raise ValueError('NATIVE_ASYNC_JOB_DRIFT')
    native=native.replace(old,old.replace('            deadline=',"            if on_stage:on_stage('preparing_inputs')\n            deadline="))
    old='creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)'
    if native.count(old)!=1:raise ValueError('NATIVE_PROCESS_DRIFT')
    native=native.replace(old,'creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS,\n'
        '                                 env={**{k:v for k,v in __import__("os").environ.items() if not k.startswith("ZIPHAP_CHECKPOINT") and k!="ZIPHAP_EULER_CHECKPOINT"},**(native_environment or {})})')
    server=(root/'server.py').read_text(encoding='utf-8')
    old="CONFIG=load();ENGINE=OwnedEngines();PAIRING=Pairing(CONFIG)\nTOKEN=CONFIG['token']"
    if server.count(old)!=1:raise ValueError('SERVER_ENGINE_DRIFT')
    server=server.replace(old,"from recovery_engines import RecoveryEngines\nCONFIG=load();TOKEN=CONFIG['token']\n"
        'ENGINE=RecoveryEngines(hashlib.sha256(TOKEN.encode()).hexdigest());PAIRING=Pairing(CONFIG)')
    old="(/png|/ack)?"
    if server.count(old)!=1:raise ValueError('SERVER_ROUTE_DRIFT')
    server=server.replace(old,'(/png|/ack|/resume|/suspend|/reference)?')
    old="   if self.command=='GET' and suffix=='/png':"
    if server.count(old)!=1:raise ValueError('SERVER_IMAGE_ROUTE_DRIFT')
    server=server.replace(old,"   if self.command=='GET' and suffix=='/reference':\n"
        "    data=ENGINE.reference_bytes(key,owner)\n"
        "    self.send_response(200);self.send_header('Content-Type','image/png');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data);return\n"+old)
    old="   if self.command=='DELETE' or self.command=='POST' and suffix=='/ack':"
    if server.count(old)!=1:raise ValueError('SERVER_CANCEL_DRIFT')
    server=server.replace(old,"   if self.command=='POST' and suffix in ('/resume','/suspend'):\n"
        "    body=self.body()\n"
        "    if suffix=='/suspend' and body:raise ValueError('RECOVERY_BODY_MUST_BE_EMPTY')\n"
        "    if suffix=='/resume' and (set(body)-{'automatic','explicit'} or any(type(v) is not bool for v in body.values())):raise ValueError('INVALID_RESUME_MODE')\n"
        "    result=ENGINE.resume_image(key,owner,automatic=body.get('automatic',False),explicit=body.get('explicit',False)) if suffix=='/resume' else ENGINE.suspend_image(key,owner)\n"
        "    self.reply(result,202 if suffix=='/resume' else 200);return\n"+old)
    return {'native_memory_image.py':native,'server.py':server}

def install(root,expected):
    root=pathlib.Path(root)
    for name,digest in expected.items():
        if hashlib.sha256((root/name).read_bytes()).hexdigest()!=digest:raise ValueError('WORKER_BASELINE_CHANGED')
    changes=transformed(root)
    for name,text in changes.items():compile(text,name,'exec')
    modules=('checkpoint_store.py','image_progress.py','recovery_engines.py')
    for name in modules:compile(pathlib.Path(__file__).with_name(name).read_text(encoding='utf-8'),name,'exec')
    for name in modules:shutil.copyfile(pathlib.Path(__file__).with_name(name),root/name)
    for name,text in changes.items():(root/name).write_text(text,encoding='utf-8')
