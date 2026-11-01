"""Surgical installation into a user-private worker; caller must establish idle twice first."""
import hashlib,pathlib,shutil

def transformed(root):
    root=pathlib.Path(root)
    native=(root/'native_memory_image.py').read_text(encoding='utf-8')
    old='extra_args=()):'
    if native.count(old)!=1:raise ValueError('NATIVE_SIGNATURE_DRIFT')
    native=native.replace(old,'extra_args=(),native_environment=None):')
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
        "    if self.body():raise ValueError('RECOVERY_BODY_MUST_BE_EMPTY')\n"
        "    result=ENGINE.resume_image(key,owner) if suffix=='/resume' else ENGINE.suspend_image(key,owner)\n"
        "    self.reply(result,202 if suffix=='/resume' else 200);return\n"+old)
    return {'native_memory_image.py':native,'server.py':server}

def install(root,expected):
    root=pathlib.Path(root)
    for name,digest in expected.items():
        if hashlib.sha256((root/name).read_bytes()).hexdigest()!=digest:raise ValueError('WORKER_BASELINE_CHANGED')
    changes=transformed(root)
    for name,text in changes.items():compile(text,name,'exec')
    for name in ('checkpoint_store.py','recovery_engines.py'):
        shutil.copyfile(pathlib.Path(__file__).with_name(name),root/name)
    for name,text in changes.items():(root/name).write_text(text,encoding='utf-8')
