"""Speech and image understanding on the PC. Inputs live only in a per-request scratch folder and are deleted after use."""
import base64,binascii,ctypes,hashlib,pathlib,shutil,struct,subprocess,threading,uuid

FLAGS=subprocess.CREATE_NO_WINDOW
ROOT=pathlib.Path(r'D:\ZipHap\Compute')
MODELS=ROOT/'Models'
WHISPER=ROOT/'whisper/Release/whisper-cli.exe'
MTMD=ROOT/'worker/llama-runtime/llama-mtmd-cli.exe'
LIMITS={'audio':8*1024*1024,'image':8*1024*1024}
GEMMA_PROFILES={
 'gemma-e2b':('gemma-e2b.gguf','gemma-e2b-mmproj-f16.gguf','0796d58372742ef8ddc76dd64cd2fde217b7ef32e3dc58e10873253e569cad6b','628b7e999f89beef70b32396ae84f59c096e867747d7901f0134064ff672e290',24),
 'gemma-e4b':('gemma-e4b.gguf','gemma-e4b-mmproj-f16.gguf','d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96','debad39ab9c1152ab67695a674fb35e8375b2320c57bfd5075835d3ccb16c7db',18),
}
_verified={}
_templates={}

def image_template(path):
    """Use the pinned model's own template with thinking disabled for short image answers."""
    stat=path.stat();key=(str(path),stat.st_size,stat.st_mtime_ns)
    if key in _templates:return _templates[key]
    with path.open('rb') as stream:
        def read(size):
            data=stream.read(size)
            if len(data)!=size:raise ValueError('MEDIA_TEMPLATE_FORMAT')
            return data
        def number(fmt):return struct.unpack('<'+fmt,read(struct.calcsize(fmt)))[0]
        def string(keep=False):
            size=number('Q')
            if size>16*1024*1024:raise ValueError('MEDIA_TEMPLATE_BOUND')
            if keep:return read(size).decode('utf-8')
            stream.seek(size,1)
        def skip(kind,depth=0):
            if depth>4:raise ValueError('MEDIA_TEMPLATE_BOUND')
            if kind==8:string();return
            if kind==9:
                item=number('I');count=number('Q')
                if count>2_000_000:raise ValueError('MEDIA_TEMPLATE_BOUND')
                for _ in range(count):skip(item,depth+1)
                return
            size={0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8}.get(kind)
            if size is None:raise ValueError('MEDIA_TEMPLATE_FORMAT')
            read(size)
        if read(4)!=b'GGUF' or number('I') not in (2,3):raise ValueError('MEDIA_TEMPLATE_FORMAT')
        number('Q');count=number('Q')
        if count>10000:raise ValueError('MEDIA_TEMPLATE_BOUND')
        for _ in range(count):
            name=string(True);kind=number('I')
            if name=='tokenizer.chat_template':
                if kind!=8:raise ValueError('MEDIA_TEMPLATE_FORMAT')
                template=string(True)
                if not 1<=len(template)<=24000:raise ValueError('MEDIA_TEMPLATE_BOUND')
                result='{%- set enable_thinking = false -%}'+template
                _templates[key]=result
                return result
            skip(kind)
    raise ValueError('MEDIA_TEMPLATE_MISSING')

def available_memory():
    class Memory(ctypes.Structure):
        _fields_=[('length',ctypes.c_uint32),('load',ctypes.c_uint32)]+[(key,ctypes.c_uint64) for key in ('total','available','page_total','page_available','virtual_total','virtual_available','extended')]
    info=Memory();info.length=ctypes.sizeof(info)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(info)):raise ctypes.WinError()
    return info.available

def verify_model(path,expected):
    stat=path.stat();key=(str(path),stat.st_size,stat.st_mtime_ns,expected)
    if key not in _verified:
        with path.open('rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest()!=expected:raise ValueError('MEDIA_MODEL_INTEGRITY')
        _verified[key]=True

class MediaEngine:
    def __init__(self,gpu_free=lambda:True):
        self.lock=threading.Lock();self.gpu_free=gpu_free
    def health(self,state,native_busy=False):
        return state|{'compute_busy':bool(state.get('compute_busy') or native_busy or self.lock.locked())}
    def _input(self,request,kind,suffix,directory):
        try:raw=base64.b64decode(request['contentBase64'],validate=True)
        except (KeyError,TypeError,binascii.Error) as error:raise ValueError('MEDIA_FORMAT') from error
        if not 1<=len(raw)<=LIMITS[kind]:raise ValueError('MEDIA_BOUND')
        path=directory/('input'+suffix);path.write_bytes(raw);return path
    def _scratch(self):
        directory=ROOT/'scratch'/str(uuid.uuid4());directory.mkdir(parents=True);return directory
    def _cleanup(self,directory):
        resolved=directory.resolve()
        if resolved.parent!=(ROOT/'scratch').resolve():raise ValueError('SCRATCH_SCOPE')
        shutil.rmtree(resolved,ignore_errors=True)
    def transcribe(self,request):
        if set(request)-{'contentBase64','language'}:raise ValueError('UNKNOWN_FIELD')
        language=request.get('language','ko')
        if language not in ('ko','en','auto'):raise ValueError('LANGUAGE')
        directory=self._scratch()
        try:
            source=self._input(request,'audio','.wav',directory)
            with self.lock:
                done=subprocess.run([str(WHISPER),'-m',str(MODELS/'ggml-large-v3-turbo-q5_0.bin'),'-f',str(source),'-l',language,'-nt','-otxt','-of',str(directory/'out'),'-t','6'],
                    cwd=WHISPER.parent,capture_output=True,timeout=600,creationflags=FLAGS)
            if done.returncode:raise RuntimeError('ASR_FAILED')
            return {'text':(directory/'out.txt').read_text(encoding='utf-8').strip(),'engine':'whisper-large-v3-turbo'}
        finally:self._cleanup(directory)
    def describe(self,request):
        if set(request)-{'contentBase64','prompt','modelId'}:raise ValueError('UNKNOWN_FIELD')
        prompt=request.get('prompt')
        if not isinstance(prompt,str) or not 1<=len(prompt)<=4000:raise ValueError('PROMPT')
        model_id=request.get('modelId','qwen2.5-vl-7b')
        if model_id in GEMMA_PROFILES:
            model,projector,model_sha,projector_sha,layers=GEMMA_PROFILES[model_id]
            model=MODELS/model;projector=MODELS/projector
            verify_model(model,model_sha);verify_model(projector,projector_sha)
            if not self.gpu_free():raise RuntimeError('MEDIA_GPU_BUSY')
            if available_memory()<3*1024**3:raise RuntimeError('MEDIA_LOW_MEMORY')
            template_args=['--jinja','--chat-template',image_template(model)]
        elif model_id=='qwen2.5-vl-7b':
            model=MODELS/'qwen-image-2512/Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf'
            projector=MODELS/'qwen-image-edit-2511/Qwen2.5-VL-7B-Instruct.mmproj-f16.gguf';layers=24
            template_args=[]
        else:raise ValueError('MEDIA_MODEL')
        directory=self._scratch()
        try:
            source=self._input(request,'image','.jpg',directory)
            with self.lock:
                # Falls back to CPU instead of disturbing a resident text model or failing on GPU memory.
                for layer_count in ((layers,0) if self.gpu_free() else (0,)):
                    done=subprocess.run([str(MTMD),'-m',str(model),'--mmproj',str(projector),'--image',str(source),'-p',prompt,'-ngl',str(layer_count),'-n','384','-c','4096','-t','4','--image-max-tokens','280','--no-warmup','--temp','0.2']+template_args,
                        cwd=MTMD.parent,stdin=subprocess.DEVNULL,capture_output=True,timeout=600,creationflags=FLAGS)
                    if done.returncode==0:break
            if done.returncode:raise RuntimeError('VISION_FAILED')
            text=done.stdout.decode('utf-8','replace').strip()
            if not text:raise RuntimeError('VISION_EMPTY')
            return {'text':text,'engine':model_id}
        finally:self._cleanup(directory)
