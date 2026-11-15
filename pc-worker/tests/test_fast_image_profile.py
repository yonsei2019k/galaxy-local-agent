"""Admission tests: host-owned model identity/options, with no model or native execution."""
import ast,base64,hashlib,importlib.util,json,pathlib,threading,time,unittest,uuid
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]/'deployed'
spec=importlib.util.spec_from_file_location('fast_image_profiles_test_subject',ROOT/'image_profiles.py')
profiles=importlib.util.module_from_spec(spec);spec.loader.exec_module(profiles)
class IdleThread:
 def __init__(self,*a,**kw):pass
 def start(self):pass

def subject():
 tree=ast.parse((ROOT/'engines.py').read_text(encoding='utf-8'))
 engine=next(n for n in tree.body if isinstance(n,ast.ClassDef) and n.name=='Engines')
 methods=[n for n in engine.body if isinstance(n,ast.FunctionDef) and n.name=='image']
 node=ast.ClassDef(name='Engine',bases=[],keywords=[],body=methods,decorator_list=[])
 namespace=dict(uuid=uuid,base64=base64,json=json,hashlib=hashlib,threading=threading,time=time,
   profile=profiles.profile,MODEL_ID=profiles.DEFAULT_ID,validate_png=lambda data,size:None,ReferenceUnavailable=ValueError,Busy=ValueError)
 exec(compile(ast.fix_missing_locations(ast.Module(body=[node],type_ignores=[])),'image_admission','exec'),namespace)
 value=namespace['Engine']();value.jobs={};value.jobs_lock=threading.RLock()
 value.run_image=lambda *args:None
 value.public_job=lambda key:dict(value.jobs[key])
 return value

class FastImageProfileTest(unittest.TestCase):
 def request(self,model=None,**options):
  return dict(requestId=str(uuid.uuid4()),modelId=model or profiles.FAST_ID,prompt='synthetic red cube',**options)
 def test_explicit_fast_default_is_four_and_keeps_exact_effective_identity(self):
  engine=subject()
  with patch.object(threading,'Thread',IdleThread):report=engine.image(self.request())
  self.assertEqual((profiles.FAST_ID,4),(report['modelId'],report['steps']))
  self.assertEqual('d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4',profiles.profile(report['modelId'])['sha256'])
 def test_fast_options_cannot_weaken_existing_model_step_boundaries(self):
  for model in (profiles.DEFAULT_ID,profiles.SDXL_ID,profiles.EDIT_ID):
   with self.assertRaisesRegex(ValueError,'IMAGE_OPTIONS'):subject().image(self.request(model,steps=4))
  for steps in (True,0,3,51):
   with self.assertRaisesRegex(ValueError,'IMAGE_OPTIONS'):subject().image(self.request(steps=steps))
 def test_negative_conditions_are_rejected_instead_of_silently_ignored(self):
  with self.assertRaisesRegex(ValueError,'NEGATIVE_PROMPT_NOT_SUPPORTED'):
   subject().image(self.request(negativePrompt='remove lettering'))
 def test_reference_uses_same_supported_fast_model_not_qwen_substitution(self):
  engine=subject()
  with patch.object(threading,'Thread',IdleThread):report=engine.image(self.request(referencePng=base64.b64encode(b'synthetic fixture').decode()))
  self.assertEqual(profiles.FAST_ID,report['modelId'])
  self.assertTrue(profiles.profile(profiles.FAST_ID)['supports_reference'])
  self.assertFalse(profiles.profile(profiles.FAST_ID).get('requires_reference',False))
 def test_unknown_models_and_fields_stay_rejected(self):
  with self.assertRaisesRegex(ValueError,'UNKNOWN_IMAGE_MODEL'):subject().image(self.request('unregistered'))
  with self.assertRaisesRegex(ValueError,'UNKNOWN_FIELD'):subject().image(self.request(nativeArgs=['--unsafe']))
