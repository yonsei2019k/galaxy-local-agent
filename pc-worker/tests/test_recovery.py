import base64,hashlib,json,os,pathlib,struct,sys,tempfile,threading,time,unittest,uuid,zlib
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(os.environ['LOCALAPPDATA'])/'ZipHap/Compute/worker'))
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from security import dpapi
from checkpoint_store import CheckpointStore
import recovery_engines as module
from recovery_engines import RecoveryEngines
from owned_engines import OwnedEngines,OwnershipMismatch
from image_profiles import SDXL_ID
from patch_worker import transformed

def checkpoint(step=5,total=20,binding='a'*64):
    return b'ZHCP0001'+struct.pack('<III',step,total,2)+binding.encode()+struct.pack('<qq',2,3)+struct.pack('<'+'f'*(total+1),*[float(i) for i in range(total,-1,-1)])+struct.pack('<ffffff',*([.25]*6))
def png():
    def chunk(name,data):return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1024,1024,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\0'*3072)*1024))+chunk(b'IEND',b'')
class ThreadStub:
    def __init__(self,*a,**k):pass
    def start(self):pass
    def is_alive(self):return False
    def join(self,*a,**k):pass
def init_stub(self):
    self.lock=threading.RLock();self.jobs_lock=threading.RLock();self.jobs={};self.llm=None
    self.owner_lock=threading.RLock();self.owner=None;self.owner_set=False;self.operation=None
    self.records={};self.request_local=threading.local();self.last_used=time.monotonic()

class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
        self.owner=str(uuid.uuid4());self.key=str(uuid.uuid4())
        self.patches=[patch.object(module,'HOME',self.root),patch.object(OwnedEngines,'__init__',init_stub),
            patch.object(threading,'Thread',ThreadStub),patch.object(module,'available_memory',return_value=8*1024**3),
            patch.object(module,'gpu_busy',return_value=False),patch.object(module,'verify_file'),
            patch.object(RecoveryEngines,'stop_llm'),patch.object(RecoveryEngines,'_binding',return_value='a'*64)]
        for p in self.patches:p.start()
        self.engine=RecoveryEngines('epoch-a')
        self.request=dict(requestId=self.key,modelId=SDXL_ID,ioMode='encrypted_checkpoint',prompt='synthetic red cube',negativePrompt='',seed=123,steps=20)
    def tearDown(self):
        for p in reversed(self.patches):p.stop()
        self.tmp.cleanup()
    def run_job(self):self.engine.run_image(self.key,self.request['prompt'],'',b'',.65)
    def test_failure_resumes_exact_job_and_completed_result_survives_restart(self):
        self.engine.image(self.request,self.owner)
        store=self.engine.store;key=self.key
        class Failing:
            def __init__(self,*args):pass
            def generate(self,*args,**kwargs):
                pathlib.Path(kwargs['native_environment']['ZIPHAP_EULER_CHECKPOINT']).write_bytes(dpapi(checkpoint()))
                raise RuntimeError('synthetic native death')
        with patch.object(module,'NativeMemoryImage',Failing):self.run_job()
        report=self.engine.public_job(key,self.owner)
        self.assertEqual(('resumable',5),(report['phase'],report['checkpointStep']))
        with self.assertRaises(OwnershipMismatch):self.engine.resume_image(key,str(uuid.uuid4()))
        with self.assertRaises(ValueError):self.engine.image(self.request|{'prompt':'different'},self.owner)
        restart=RecoveryEngines('epoch-a');self.assertEqual('suspended',restart.public_job(key,self.owner)['phase'])
        self.engine=restart;self.engine.resume_image(key,self.owner)
        calls=[]
        class Succeeding:
            def __init__(self,*args):pass
            def generate(self,*args,**kwargs):calls.append(args);return png()
        with patch.object(module,'NativeMemoryImage',Succeeding):self.run_job()
        self.assertEqual((123,20),(calls[0][2],calls[0][3]))
        self.assertEqual(5,self.engine.public_job(key,self.owner)['resumedFrom'])
        restored=RecoveryEngines('epoch-a')
        self.assertEqual(png(),restored.image_bytes(key,self.owner))
        self.assertEqual('completed',restored.resume_image(key,self.owner)['phase'])
        self.assertEqual(1,len(calls))
        self.assertTrue(restored.cancel_image(key,self.owner)['cleanup_complete'])
        self.assertFalse((store.root/key).exists())
        for _ in range(5):
            self.assertEqual('acknowledged',restored.public_job(key,self.owner)['phase'])
            self.assertFalse((store.root/key).exists(),'Status reads must not consume a recovery slot after ACK')
        restored.suspend_image(key,self.owner)
        self.assertFalse((store.root/key).exists())
        with self.assertRaises((ValueError,FileNotFoundError)):restored.resume_image(key,self.owner)
    def test_zero_checkpoint_is_suspended_without_claiming_saved_steps(self):
        self.engine.image(self.request,self.owner)
        self.engine.jobs[self.key]['cleanup_done'].set()
        self.engine.suspend_image(self.key,self.owner)
        report=self.engine.public_job(self.key,self.owner)
        self.assertEqual(('suspended',0),(report['phase'],report['checkpointStep']))
    def test_encrypted_binding_tamper_expiry_and_epoch_fail_closed(self):
        store=self.engine.store;store.folder(self.key);path=store.native(self.key);path.write_bytes(dpapi(checkpoint()))
        self.assertEqual(5,store.native_step(self.key,'a'*64,20))
        with self.assertRaises(ValueError):store.native_step(self.key,'b'*64,20)
        with self.assertRaises(ValueError):store.native_step(self.key,'a'*64,21)
        raw=bytearray(path.read_bytes());raw[-1]^=1;path.write_bytes(raw)
        with self.assertRaises(Exception):store.native_step(self.key,'a'*64,20)
        store.save(self.key,{'createdWall':time.time()-25*3600})
        with self.assertRaises(ValueError):store.load(self.key)
        store.save(self.key,{'createdWall':time.time()})
        with self.assertRaises(ValueError):CheckpointStore(store.root,'epoch-b').load(self.key)
        with self.assertRaises(ValueError):store.folder('../foreign')
    def test_surgical_worker_patch_preserves_owner_validation_and_routes(self):
        root=pathlib.Path(os.environ.get('ZIPHAP_TEST_WORKER_BASELINE',str(pathlib.Path(os.environ['LOCALAPPDATA'])/'ZipHap/Compute/worker')))
        if 'native_environment=None' in (root/'native_memory_image.py').read_text():self.skipTest('Supply original worker baseline for the surgical installation test')
        changes=transformed(root)
        for name,text in changes.items():compile(text,name,'exec')
        self.assertIn('ENGINE.suspend_image(key,owner)',changes['server.py'])
        self.assertIn('ENGINE.reference_bytes(key,owner)',changes['server.py'])
        self.assertIn('native_environment=None',changes['native_memory_image.py'])

    def test_missing_read_does_not_allocate_a_slot_but_four_live_folders_are_bounded(self):
        store=self.engine.store
        self.assertEqual(0,store.native_step(self.key,'a'*64,20))
        self.assertFalse((store.root/self.key).exists())
        for _ in range(4):store.folder(str(uuid.uuid4()))
        with self.assertRaises(ValueError):store.folder(self.key)
        self.assertEqual(0,store.native_step(self.key,'a'*64,20))
        self.assertFalse((store.root/self.key).exists())

if __name__=='__main__':unittest.main()
