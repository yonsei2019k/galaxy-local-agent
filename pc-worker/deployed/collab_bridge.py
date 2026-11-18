"""Paired-owner collaboration data only. No model invocation or execution authority."""
import contextlib,datetime,hashlib,json,math,os,pathlib,re,stat,subprocess,tempfile,threading,time
from urllib.parse import parse_qs

CREATE_NO_WINDOW=0x08000000
STATE_NAME='collab-log.dpapi'
LOCK_NAME='collab-log.lock'
OWNER_FILE='user-to-agents.md'
# Explicit publication only. Private development coordination can contain image
# workroom details and must never be copied automatically into the product room.
SOURCES=(('codex','collab-published-codex.md'),('claude','collab-published-claude.md'))
MAX_MARKDOWN=2*1024*1024
MAX_STATE=MAX_INPUT=MAX_OUTPUT=8*1024*1024
MAX_ENCRYPTED=MAX_STATE+64*1024
MAX_STDERR=64*1024
MAX_EXECUTABLE=256*1024*1024

class CollabError(Exception):
    def __init__(self,code,status=400,metadata=None):
        super().__init__(code);self.code=code;self.status=status;self.metadata=metadata or {}

def _json(raw):
    def unique(items):
        value={}
        for key,item in items:
            if key in value:raise ValueError('duplicate key')
            value[key]=item
        return value
    def constant(_):raise ValueError('nonfinite')
    return json.loads(raw,object_pairs_hook=unique,parse_constant=constant)

def _encode(value,maximum,code):
    try:raw=json.dumps(value,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode('utf-8')
    except (TypeError,ValueError,UnicodeError,RecursionError):raise CollabError('COLLAB_REQUEST',400) from None
    if len(raw)>maximum:raise CollabError(code,413)
    return raw

def _read_bounded(path,maximum,code,status=503):
    try:
        details=path.stat()
        if not stat.S_ISREG(details.st_mode) or details.st_size>maximum:raise CollabError(code,status)
        with path.open('rb') as source:raw=source.read(maximum+1)
        if len(raw)>maximum:raise CollabError(code,status)
        return raw
    except CollabError:raise
    except OSError:raise CollabError(code,status) from None

def _default_dpapi(raw,protect):
    from data_store import _dpapi
    return _dpapi(raw,protect)

def load_config(home):
    """Only the fixed operator config selects paths. Invalid/duplicate fields disable the bridge."""
    try:
        data=_json(_read_bounded(pathlib.Path(home)/'collab-bridge.json',4096,'COLLAB_CONFIG').decode('utf-8'))
        keys={'executable','executableSha256','storeDirectory','coordinationDirectory'}
        if type(data) is not dict or set(data)!=keys or not all(type(data[k]) is str for k in keys):return None
        if not re.fullmatch(r'[0-9a-f]{64}',data['executableSha256']):return None
        names=('executable','storeDirectory','coordinationDirectory')
        if any(not data[k] or '\x00' in data[k] or len(data[k])>32768 for k in names):return None
        paths={k:pathlib.Path(data[k]) for k in names}
        if not all(p.is_absolute() for p in paths.values()):return None
        return dict(executable=paths['executable'],sha256=data['executableSha256'],store=paths['storeDirectory'],coordination=paths['coordinationDirectory'])
    except (CollabError,OSError,ValueError,UnicodeError,TypeError,RecursionError):return None

class CollabBridge:
    def __init__(self,config,dpapi=_default_dpapi,clock=time.time,run_timeout=10):
        if type(run_timeout) not in (int,float) or not 0<run_timeout<=10:raise ValueError('COLLAB_TIMEOUT')
        self.config=config;self.dpapi=dpapi;self.clock=clock;self.timeout=run_timeout;self.lock=threading.Lock()
        self._published_cache=None

    def _adapter(self):
        if self.config is None:raise CollabError('COLLAB_NOT_CONFIGURED',503)
        exe=self.config['executable']
        raw=_read_bounded(exe,MAX_EXECUTABLE,'COLLAB_ADAPTER_UNAVAILABLE')
        if hashlib.sha256(raw).hexdigest()!=self.config['sha256']:raise CollabError('COLLAB_ADAPTER_CHANGED',503)
        return exe

    def _run(self,request):
        """Own hidden child only; bounded streams/deadline, no shell, no raw stderr in errors."""
        raw=_encode(request,MAX_INPUT,'COLLAB_INPUT_BOUND');exe=self._adapter()
        child=None;threads=[];overflow=threading.Event();io_failed=threading.Event();output=bytearray();started=time.monotonic()
        try:
            child=subprocess.Popen([str(exe)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                cwd=str(self.config['store']),env={'SystemRoot':os.environ.get('SystemRoot','C:\\Windows')},
                creationflags=CREATE_NO_WINDOW if os.name=='nt' else 0)
            def read(stream,maximum,retain):
                total=0
                try:
                    while True:
                        block=stream.read(16*1024)
                        if not block:return
                        total+=len(block)
                        if total>maximum:overflow.set();return
                        if retain:output.extend(block)
                except (OSError,ValueError):io_failed.set()
            def write():
                try:child.stdin.write(raw);child.stdin.close()
                except (OSError,ValueError):io_failed.set()
            threads=[threading.Thread(target=read,args=(child.stdout,MAX_OUTPUT,True),daemon=True),
                threading.Thread(target=read,args=(child.stderr,MAX_STDERR,False),daemon=True),threading.Thread(target=write,daemon=True)]
            for thread in threads:thread.start()
            while child.poll() is None or any(t.is_alive() for t in threads):
                if overflow.is_set():raise CollabError('COLLAB_ADAPTER_OUTPUT_BOUND',503)
                if time.monotonic()-started>=self.timeout:raise CollabError('COLLAB_ADAPTER_TIMEOUT',503)
                time.sleep(.01)
            if overflow.is_set():raise CollabError('COLLAB_ADAPTER_OUTPUT_BOUND',503)
            if io_failed.is_set() or child.returncode!=0:raise CollabError('COLLAB_ADAPTER_FAILED',503)
            value=_json(bytes(output).decode('utf-8'))
        except CollabError:raise
        except (OSError,ValueError,UnicodeError,RecursionError):raise CollabError('COLLAB_ADAPTER_FAILED',503) from None
        finally:
            if child is not None:
                if child.poll() is None:
                    child.kill()
                    try:child.wait(timeout=1)
                    except subprocess.TimeoutExpired:pass
                for stream in (child.stdin,child.stdout,child.stderr):
                    try:stream.close()
                    except (OSError,ValueError):pass
                for thread in threads:thread.join(timeout=.25)
        if type(value) is not dict or value.get('ok') is not True:
            code=value.get('error') if type(value) is dict else None
            if type(code) is not str or not re.fullmatch(r'[A-Z_]{1,64}',code):code='COLLAB_ADAPTER_FAILED'
            status=value.get('status') if type(value) is dict else None
            if type(status) is not int or status not in (400,409,410,413,500):status=503
            raise CollabError(code,status)
        return value

    @contextlib.contextmanager
    def _transaction(self):
        """OS nonblocking file lock serializes different bridge instances/processes, including mirrors."""
        if self.config is None:raise CollabError('COLLAB_NOT_CONFIGURED',503)
        with self.lock:
            lock_file=None;acquired=False
            try:
                lock_file=(self.config['store']/LOCK_NAME).open('a+b')
                if lock_file.seek(0,os.SEEK_END)==0:lock_file.write(b'\0');lock_file.flush()
                lock_file.seek(0)
                if os.name=='nt':
                    import msvcrt
                    msvcrt.locking(lock_file.fileno(),msvcrt.LK_NBLCK,1)
                else:
                    import fcntl
                    fcntl.flock(lock_file.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
                acquired=True
            except OSError:
                if lock_file is not None:lock_file.close()
                raise CollabError('COLLAB_STORE_BUSY',409) from None
            try:yield
            finally:
                if lock_file is not None:
                    try:
                        if acquired:
                            lock_file.seek(0)
                            if os.name=='nt':
                                import msvcrt
                                msvcrt.locking(lock_file.fileno(),msvcrt.LK_UNLCK,1)
                            else:
                                import fcntl
                                fcntl.flock(lock_file.fileno(),fcntl.LOCK_UN)
                    finally:lock_file.close()

    def _state_path(self):return self.config['store']/STATE_NAME
    def _load(self):
        path=self._state_path()
        if not path.exists():return self._run({'op':'init'})['state']
        cipher=_read_bounded(path,MAX_ENCRYPTED,'COLLAB_STORE_BOUND')
        try:
            raw=self.dpapi(cipher,False)
            if type(raw) is not bytes or len(raw)>MAX_STATE:raise CollabError('COLLAB_STORE_BOUND',503)
            return _json(raw.decode('utf-8'))
        except CollabError:raise
        except Exception:raise CollabError('COLLAB_STORE_UNAVAILABLE',503) from None

    @staticmethod
    def _atomic_write(path,data,code):
        temporary=None
        try:
            with tempfile.NamedTemporaryFile(prefix='.collab-',suffix='.tmp',dir=str(path.parent),delete=False) as out:
                temporary=pathlib.Path(out.name);out.write(data);out.flush();os.fsync(out.fileno())
            os.replace(temporary,path);temporary=None
        except OSError:raise CollabError(code,503) from None
        finally:
            if temporary is not None:
                try:temporary.unlink(missing_ok=True)
                except OSError:pass
    def _save(self,state):
        raw=_encode(state,MAX_STATE,'COLLAB_STATE_BOUND')
        try:
            cipher=self.dpapi(raw,True)
            if type(cipher) is not bytes or len(cipher)>MAX_ENCRYPTED:raise CollabError('COLLAB_STORE_BOUND',503)
        except CollabError:raise
        except Exception:raise CollabError('COLLAB_STORE_UNAVAILABLE',503) from None
        self._atomic_write(self._state_path(),cipher,'COLLAB_STORE_UNAVAILABLE')

    def _import(self,state):
        changed=False
        for source,name in SOURCES:
            path=self.config['coordination']/name
            if not path.exists():continue
            raw=_read_bounded(path,MAX_MARKDOWN,'COLLAB_SOURCE_BOUND')
            try:markdown=raw.decode('utf-8')
            except UnicodeError:raise CollabError('COLLAB_SOURCE_ENCODING',503) from None
            result=self._run({'op':'import','state':state,'nowMs':self._now(),'source':source,'markdown':markdown})
            if result['imported']:state=result['state'];changed=True
        return state,changed

    @staticmethod
    def _published_identity(message):
        return tuple(message[key] for key in ('sender','messageId','recipient','text','bodyDigest'))

    def _published_records(self):
        """Parse only current explicit publications, with the pinned Rust canonical parser.

        A reused historical ID alone cannot publish its previous body. Cache only
        this bounded RAM allowlist; publication bytes are never written to logs.
        The transaction lock held by list() serializes the cache with state reads.
        """
        sources=[];fingerprints=[]
        for sender,name in SOURCES:
            path=self.config['coordination']/name
            if not path.exists():
                fingerprints.append((sender,None));continue
            raw=_read_bounded(path,MAX_MARKDOWN,'COLLAB_SOURCE_BOUND')
            try:markdown=raw.decode('utf-8')
            except UnicodeError:raise CollabError('COLLAB_SOURCE_ENCODING',503) from None
            fingerprints.append((sender,hashlib.sha256(raw).hexdigest()));sources.append((sender,markdown))
        signature=(self.config['sha256'],tuple(fingerprints))
        if self._published_cache is not None and self._published_cache[0]==signature:
            return self._published_cache[1]
        allowed=frozenset()
        if sources:
            state=self._run({'op':'init'})['state']
            now=self._now()
            for sender,markdown in sources:
                state=self._run({'op':'import','state':state,'nowMs':now,'source':sender,'markdown':markdown})['state']
            allowed=frozenset(self._published_identity(message) for message in state['messages'])
        self._published_cache=(signature,allowed)
        return allowed

    def _published_page(self,page):
        allowed=self._published_records()
        messages=[]
        for original in page['messages']:
            message=dict(original)
            if message['sender'] in ('codex','claude') and self._published_identity(message) not in allowed:
                message['text']='비공개 개발 기록입니다. 협업방 공개 요약만 표시합니다.'
                message['bodyDigest']=hashlib.sha256((message['recipient']+'\0'+message['text']).encode('utf-8')).hexdigest()
            messages.append(message)
        return dict(page,messages=messages)

    def _deliver(self,state):
        """Render Rust-validated owner records only; atomic append preserves existing bytes on crashes."""
        path=self.config['coordination']/OWNER_FILE
        original=_read_bounded(path,MAX_MARKDOWN,'COLLAB_DELIVERY_BOUND') if path.exists() else b''
        try:
            existing=original.decode('utf-8').replace('\r\n','\n')
            headers=set(re.findall(r'^## \[(U-[0-9]{6,20})\] [^\n]*$',existing,re.MULTILINE));blocks=[]
            for message in state['messages']:
                if message['sender']!='user' or message['recipient']=='user':continue
                stamp=datetime.datetime.fromtimestamp(message['createdAtMs']/1000,datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
                block='\n## ['+message['messageId']+'] '+stamp+' | type: request | needs-response: yes | priority: normal | to: '+message['recipient']+'\n'
                block+=''.join('> '+line+'\n' for line in message['text'].splitlines())
                if message['messageId'] in headers:
                    if block not in existing:raise CollabError('COLLAB_DELIVERY_CONFLICT',503)
                    continue
                blocks.append(block)
            if not blocks:return
            updated=original+''.join(blocks).encode('utf-8')
            if len(updated)>MAX_MARKDOWN:raise CollabError('COLLAB_DELIVERY_BOUND',503)
        except CollabError:raise
        except (ValueError,TypeError,KeyError,OverflowError,OSError,UnicodeError):raise CollabError('COLLAB_DELIVERY_FAILED',503) from None
        self._atomic_write(path,updated,'COLLAB_DELIVERY_FAILED')
    def _now(self):
        value=self.clock()
        if type(value) not in (int,float) or not math.isfinite(value) or not 0<=value<=253402300799:raise CollabError('COLLAB_CLOCK',503)
        return int(value*1000)

    def list(self,after,limit):
        if type(after) is not int or after<0 or type(limit) is not int or not 1<=limit<=50:raise CollabError('COLLAB_QUERY',400)
        with self._transaction():
            state=self._load();state,changed=self._import(state)
            try:page=self._run({'op':'list','state':state,'after':after,'limit':limit})['page']
            except CollabError as error:
                if error.code!='COLLAB_CURSOR_EXPIRED':raise
                # A successful tail read validates state before any cursor metadata is exposed.
                next_sequence=state.get('nextSequence') if type(state) is dict else None
                if type(next_sequence) is not int or next_sequence<1:raise CollabError('COLLAB_STORE_UNAVAILABLE',503) from None
                validated=self._run({'op':'list','state':state,'after':next_sequence-1,'limit':1})['page']
                if changed:self._save(state)
                metadata={'resetRequired':True,'firstRetainedSequence':validated['firstRetainedSequence'],'latestSequence':validated['latestSequence']}
                raise CollabError('COLLAB_CURSOR_EXPIRED',410,metadata) from None
            if changed:self._save(state)
            self._deliver(state)
            return self._published_page(page)
    def post(self,body):
        if type(body) is not dict or set(body)!={'clientMessageId','text','recipient'}:raise CollabError('COLLAB_REQUEST',400)
        _encode(body,40000,'COLLAB_REQUEST_BOUND')
        with self._transaction():
            state=self._load();state,_=self._import(state)
            result=self._run({'op':'post','state':state,'nowMs':self._now(),'message':body})
            self._save(result['state']);self._deliver(result['state'])
            return {'message':result['message'],'created':result['created'],'dataOnly':True,'executionAuthority':False}
    def handle(self,method,path,query,read_body):
        try:
            if path!='/v1/collab/messages':raise CollabError('COLLAB_ROUTE',404)
            if method=='GET':
                params=parse_qs(query or '',keep_blank_values=True,strict_parsing=False,max_num_fields=4)
                if set(params)-{'after','limit'} or any(len(v)!=1 for v in params.values()):raise CollabError('COLLAB_QUERY',400)
                after=params.get('after',['0'])[0];limit=params.get('limit',['50'])[0]
                if not re.fullmatch(r'[0-9]{1,15}',after) or not re.fullmatch(r'[0-9]{1,2}',limit) or not 1<=int(limit)<=50:raise CollabError('COLLAB_QUERY',400)
                self._adapter();return 200,self.list(int(after),int(limit))
            if method=='POST':
                body=read_body(40000)
                if type(body) is not dict or set(body)!={'clientMessageId','text','recipient'}:raise CollabError('COLLAB_REQUEST',400)
                self._adapter();result=self.post(body);return (201 if result['created'] else 200),result
            raise CollabError('COLLAB_METHOD',405)
        except CollabError as error:return error.status,{'error':error.code,**error.metadata}
        except (ValueError,TypeError,UnicodeError,RecursionError):return 400,{'error':'COLLAB_REQUEST'}
