import pathlib,sys,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'deployed'))
from resident_budget import expanded_limit,GIB

class ImageBudgetTest(unittest.TestCase):
 def test_current_resident_pages_can_be_retained_when_desktop_memory_recovers(self):
  self.assertEqual(5*GIB,expanded_limit(4*GIB,3*GIB,4*GIB))
 def test_pressure_never_expands_or_forces_live_model_page_eviction(self):
  self.assertEqual(4*GIB,expanded_limit(4*GIB,GIB,4*GIB))
 def test_reserve_cap_and_hysteresis(self):
  for free in (3*GIB,4*GIB,8*GIB):
   result=expanded_limit(4*GIB,free,4*GIB)
   self.assertLessEqual(result,6*GIB)
   self.assertGreaterEqual(free-(result-4*GIB),2*GIB)
  self.assertEqual(4*GIB,expanded_limit(4*GIB,2*GIB+128*1024**2,4*GIB))
