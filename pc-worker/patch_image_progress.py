"""Prepare a narrow worker upgrade. This module never deploys or restarts a worker."""
def replace_once(text,old,new):
    if text.count(old)!=1:raise ValueError('IMAGE_PROGRESS_BASELINE_DRIFT')
    return text.replace(old,new)

def patch_engines(text):
    marker='from image_progress import mark,resources_waiting,public_stage'
    if marker in text:return text
    text=marker+'\n'+text
    text=replace_once(text,"   if job['result'] is not None:result.update(","   stage=public_stage(job)\n   if stage is not None:result['stage']=stage\n   if job['result'] is not None:result.update(")
    text=replace_once(text,'    while available_memory()<4*1024**3 or gpu_busy():',
        '    while resources_waiting(job,available_memory(),gpu_busy()):')
    text=replace_once(text,"     job['phase']='waiting_resources'\n",'')
    text=replace_once(text,"    selected=profile(job.get('modelId',MODEL_ID))",
        "    mark(job,'verifying_models')\n    selected=profile(job.get('modelId',MODEL_ID))")
    text=replace_once(text,"    arguments=selected['arguments']",
        "    mark(job,'preparing_runtime')\n    arguments=selected['arguments']")
    text=replace_once(text,"    started=time.monotonic();job['phase']='loading'",
        "    started=time.monotonic();mark(job,'loading_model')")
    compile(text,'engines.py','exec')
    return text

def patch_owned(text):
    marker='from image_progress import acquire_slot'
    if marker in text:return text
    text=marker+'\n'+text
    text=replace_once(text,"   while not acquired:\n    if job['cancel'].is_set():raise InterruptedError()\n    acquired=self.lock.acquire(timeout=.1)",
        '   acquired=acquire_slot(self.lock,job)')
    compile(text,'owned_engines.py','exec')
    return text

def patch_recovery(text):
    marker='from image_progress import mark,acquire_slot,resources_waiting'
    if marker in text:return text
    text=replace_once(text,'from pathlib import Path','from pathlib import Path\n'+marker)
    text=replace_once(text,"            while not acquired:\n                if job['cancel'].is_set():raise InterruptedError()\n                acquired=self.lock.acquire(timeout=.1)",
        '            acquired=acquire_slot(self.lock,job)')
    text=replace_once(text,'            while available_memory()<4*1024**3 or gpu_busy():',
        '            while resources_waiting(job,available_memory(),gpu_busy()):')
    text=replace_once(text,"                job['phase']='waiting_resources'\n",'')
    text=replace_once(text,"            selected=profile(job['modelId'])",
        "            mark(job,'verifying_models')\n            selected=profile(job['modelId'])")
    text=replace_once(text,"            runtime=TOOLS/'runtime-checkpoint-37bc88b-v1'",
        "            mark(job,'preparing_runtime')\n            runtime=TOOLS/'runtime-checkpoint-37bc88b-v1'")
    text=replace_once(text,"job['step']=job['resumedFrom'];job['phase']='loading';self._persist(job)",
        "job['step']=job['resumedFrom'];mark(job,'loading_model');self._persist(job)")
    compile(text,'recovery_engines.py','exec')
    return text

def transformed(root):
    from pathlib import Path
    root=Path(root)
    changes={name:patch((root/name).read_text(encoding='utf-8')) for name,patch in (
        ('engines.py',patch_engines),('owned_engines.py',patch_owned),('recovery_engines.py',patch_recovery))}
    changes['image_progress.py']=Path(__file__).with_name('image_progress.py').read_text(encoding='utf-8')
    return changes
