"""PC-side source-of-truth SQLite store. Photos and generated images never enter it; they stay on the phone."""
import ctypes,ctypes.wintypes as w,hashlib,hmac,json,os,pathlib,sqlite3,time,uuid

DEFAULT_ROOT=pathlib.Path(os.environ.get('ZIPHAP_DATA_ROOT',r'D:\ZipHap\Data'))
SCHEMA_VERSION=1
KINDS={'conversation','message','run','trace','memory','sms','notification','call_log','calendar','contact','analysis','media_ref'}
BLOCKED_FIELDS={'contentBase64','pngBase64','imageBase64','photoBase64'}

class _Blob(ctypes.Structure):
    _fields_=[('size',w.DWORD),('data',ctypes.POINTER(ctypes.c_ubyte))]

def _dpapi(raw:bytes,protect:bool)->bytes:
    buffer=ctypes.create_string_buffer(raw,len(raw))
    source=_Blob(len(raw),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)));target=_Blob()
    call=ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    args=(ctypes.byref(source),'ZipHapData' if protect else None,None,None,None,1,ctypes.byref(target))
    if not call(*args):raise OSError('DPAPI_FAILED')
    try:return ctypes.string_at(target.data,target.size)
    finally:ctypes.windll.kernel32.LocalFree(target.data)

class DataStore:
    def __init__(self,root=DEFAULT_ROOT):
        self.root=pathlib.Path(root);self.root.mkdir(parents=True,exist_ok=True)
        self.index_key=self._key()
        self.db=sqlite3.connect(self.root/'ziphap.sqlite3',timeout=30,isolation_level=None,check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL');self.db.execute('PRAGMA synchronous=FULL');self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,scope TEXT NOT NULL,body BLOB NOT NULL,
            source_version TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(kind,scope,source_version));
          CREATE INDEX IF NOT EXISTS records_scope ON records(kind,scope,updated_at);
          CREATE TABLE IF NOT EXISTS search_terms(term BLOB NOT NULL,record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,PRIMARY KEY(term,record_id)) WITHOUT ROWID;
        ''')
        self.db.execute(f'PRAGMA user_version={SCHEMA_VERSION}')
    def _key(self)->bytes:
        path=self.root/'index.key.dpapi'
        if path.exists():return _dpapi(path.read_bytes(),False)
        key=os.urandom(32);path.write_bytes(_dpapi(key,True));return key
    def _term(self,word:str)->bytes:return hmac.new(self.index_key,word.casefold().encode(),hashlib.sha256).digest()
    def put(self,kind:str,scope:str,value:dict,terms=(),source_version:str|None=None,record_id:str|None=None)->str:
        """Idempotent for a repeated phone source version, so a retried transfer cannot duplicate records."""
        if kind not in KINDS:raise ValueError('UNKNOWN_KIND')
        if BLOCKED_FIELDS&value.keys():raise ValueError('MEDIA_STAYS_ON_PHONE')
        if source_version is not None:
            row=self.db.execute('SELECT id FROM records WHERE kind=? AND scope=? AND source_version=?',(kind,scope,source_version)).fetchone()
            if row:return row[0]
        identifier=record_id or str(uuid.uuid4());now=int(time.time()*1000)
        body=_dpapi(json.dumps(value,ensure_ascii=False,separators=(',',':')).encode(),True)
        self.db.execute('BEGIN IMMEDIATE')
        try:
            self.db.execute('INSERT INTO records VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body,updated_at=excluded.updated_at',(identifier,kind,scope,body,source_version,now,now))
            self.db.execute('DELETE FROM search_terms WHERE record_id=?',(identifier,))
            self.db.executemany('INSERT OR IGNORE INTO search_terms VALUES(?,?)',[(self._term(t),identifier) for t in terms])
            self.db.execute('COMMIT')
        except BaseException:self.db.execute('ROLLBACK');raise
        return identifier
    def get(self,record_id:str)->dict|None:
        row=self.db.execute('SELECT body FROM records WHERE id=?',(record_id,)).fetchone()
        return json.loads(_dpapi(row[0],False)) if row else None
    def search(self,kind:str,scope:str,term:str,limit:int=50)->list[str]:
        return [r[0] for r in self.db.execute('SELECT r.id FROM search_terms s JOIN records r ON r.id=s.record_id WHERE s.term=? AND r.kind=? AND r.scope=? ORDER BY r.updated_at DESC LIMIT ?',(self._term(term),kind,scope,min(limit,200)))]
    def delete(self,record_id:str)->bool:return self.db.execute('DELETE FROM records WHERE id=?',(record_id,)).rowcount==1
    def close(self):self.db.close()
