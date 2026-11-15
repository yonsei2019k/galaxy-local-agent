"""Surgical legacy installation, no worker/native process, model or network execution."""
import ast,hashlib,pathlib,sys,tempfile,unittest
from unittest.mock import patch

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from patch_worker import transformed,install

# Exact pre-recovery async signature/command/acceptance anchors, with a bounded fake body.
NATIVE='''import re,subprocess,time
class NativeMemoryImage:
    def generate(self,prompt,negative,seed,steps,size,cfg,sampler,reference=b'',cancel=None,on_process=None,on_progress=None,extra_args=()):
        command=['--threads','2',*extra_args]
        process=subprocess.Popen(command,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
        if True:
            accepted=read_json(None,30);native_id=accepted.get('id')
            if not isinstance(native_id,str) or not re.fullmatch(r'job_[a-zA-Z0-9_-]{1,80}',native_id):raise ValueError('NATIVE_JOB_ID')
            deadline=time.monotonic()+4*3600
        return command,process,deadline
'''
SERVER='''CONFIG=load();ENGINE=OwnedEngines();PAIRING=Pairing(CONFIG)
TOKEN=CONFIG['token']
ROUTE='(/png|/ack)?'
class Handler:
 def route(self,key,owner,suffix):
  owner=check_owner(owner)
  if True:
   if self.command=='GET' and suffix=='/png':
    return
   if self.command=='DELETE' or self.command=='POST' and suffix=='/ack':
    return
'''

class PatchWorkerTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
  (self.root/'native_memory_image.py').write_text(NATIVE)
  (self.root/'server.py').write_text(SERVER)
 def tearDown(self):self.tmp.cleanup()
 def test_transformed_native_accepts_current_recovery_arguments_and_default_two_thread_math(self):
  changes=transformed(self.root);events=[];calls=[]
  namespace={'read_json':lambda *args:events.append('accepted') or {'id':'job_fixture'}}
  exec(compile(changes['native_memory_image.py'],'legacy_native','exec'),namespace)
  native=namespace['NativeMemoryImage']()
  with patch('subprocess.CREATE_NO_WINDOW',0,create=True),patch('subprocess.BELOW_NORMAL_PRIORITY_CLASS',0,create=True),patch('subprocess.Popen',side_effect=lambda *a,**kw:calls.append((a,kw)) or 'fixture_process'),patch('os.cpu_count',return_value=8):
   command,_,_=native.generate('fixture','',1,20,1024,2.5,'euler',native_environment={'ZIPHAP_CHECKPOINT_BINDING':'fixture'},on_stage=lambda stage:events.append(stage))
  self.assertEqual(['--threads','2'],command)
  self.assertEqual(['accepted','preparing_inputs'],events)
  self.assertEqual('fixture',calls[0][1]['env']['ZIPHAP_CHECKPOINT_BINDING'])
  with patch('os.cpu_count',return_value=2):self.assertEqual(2,native.thread_count(4))
  for invalid in (True,0,5,'2'):
   with self.assertRaisesRegex(ValueError,'NATIVE_THREAD_BUDGET'):native.thread_count(invalid)
 def test_generated_resume_block_matches_current_deployed_validation_and_keeps_owner_gate(self):
  changes=transformed(self.root)
  def resume_block(source):
   return next(node for node in ast.walk(ast.parse(source)) if isinstance(node,ast.If) and isinstance(node.test,ast.BoolOp) and "suffix in ('/resume', '/suspend')" in ast.unparse(node.test))
  self.assertEqual(ast.dump(resume_block((ROOT/'deployed/server.py').read_text())),ast.dump(resume_block(changes['server.py'])))
  self.assertIn('owner=check_owner(owner)',changes['server.py'])
  self.assertIn('ENGINE.reference_bytes(key,owner)',changes['server.py'])
  self.assertIn('ENGINE.suspend_image(key,owner)',changes['server.py'])
  compile(changes['server.py'],'legacy_server','exec')
 def test_install_copies_dependencies_without_touching_legacy_model_profiles_or_assets(self):
  (self.root/'image_profiles.py').write_text('legacy profile sentinel')
  expected={name:hashlib.sha256((self.root/name).read_bytes()).hexdigest() for name in ('native_memory_image.py','server.py')}
  install(self.root,expected)
  for name in ('checkpoint_store.py','image_progress.py','recovery_engines.py'):
   self.assertEqual((ROOT/name).read_bytes(),(self.root/name).read_bytes())
  self.assertEqual('legacy profile sentinel',(self.root/'image_profiles.py').read_text())
  self.assertFalse((self.root/'image_models').exists())
 def test_drift_or_changed_expected_hash_cannot_partially_install_recovery(self):
  with self.assertRaisesRegex(ValueError,'WORKER_BASELINE_CHANGED'):install(self.root,{'server.py':'0'*64})
  self.assertFalse((self.root/'recovery_engines.py').exists())
  (self.root/'native_memory_image.py').write_text(NATIVE.replace("'--threads','2'","'--threads','3'"))
  with self.assertRaisesRegex(ValueError,'NATIVE_THREADS_DRIFT'):transformed(self.root)
  self.assertFalse((self.root/'image_progress.py').exists())

if __name__=='__main__':unittest.main()
