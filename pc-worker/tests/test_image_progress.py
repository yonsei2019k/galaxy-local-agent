import importlib.util,pathlib,threading,unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
progress=load('image_progress')
patcher=load('patch_image_progress')

class ImageProgressTest(unittest.TestCase):
    def test_uncontended_slot_never_claims_another_job_and_retains_lock(self):
        lock=threading.Lock();job={'cancel':threading.Event()}
        self.assertTrue(progress.acquire_slot(lock,job))
        self.assertEqual('releasing_model',progress.public_stage(job))
        self.assertFalse(lock.acquire(blocking=False));lock.release()

    def test_only_failed_admission_produces_waiting_compute_and_cancel_is_preserved(self):
        job={'cancel':threading.Event()}
        class Contended:
            def acquire(self,timeout):
                if job.get('stage')=='waiting_compute':
                    job['cancel'].set()
                return False
        with self.assertRaises(InterruptedError):progress.acquire_slot(Contended(),job)
        self.assertEqual('waiting_compute',progress.public_stage(job))

    def test_resource_reason_and_new_phase_replace_previous_wait(self):
        job={}
        self.assertTrue(progress.resources_waiting(job,1,False))
        self.assertEqual('waiting_memory',progress.public_stage(job))
        self.assertTrue(progress.resources_waiting(job,8*1024**3,True))
        self.assertEqual('waiting_gpu',progress.public_stage(job))
        self.assertFalse(progress.resources_waiting(job,8*1024**3,False))
        progress.mark(job,'verifying_models')
        self.assertEqual('queued',job['phase'])
        self.assertEqual('verifying_models',progress.public_stage(job))
        progress.mark(job,'preparing_runtime');progress.mark(job,'loading_model')
        self.assertEqual('loading_model',progress.public_stage(job))
        job['phase']='sampling'
        self.assertIsNone(progress.public_stage(job),'Stale preparation metadata cannot override sampling')
        job['phase']='completed'
        self.assertIsNone(progress.public_stage(job))

    def test_patch_fails_closed_on_unknown_baseline(self):
        for function in (patcher.patch_engines,patcher.patch_owned,patcher.patch_recovery):
            with self.assertRaises(ValueError):function('unknown worker source')

if __name__=='__main__':unittest.main()
