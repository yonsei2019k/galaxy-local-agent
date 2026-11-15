"""Additive evidence for image phases; no model, owner, cancellation or scheduling changes."""
STAGES={
    'admitting':'queued','waiting_compute':'queued','releasing_model':'queued',
    'verifying_models':'queued','preparing_runtime':'queued','loading_model':'loading','preparing_inputs':'loading',
    'waiting_memory':'waiting_resources','waiting_gpu':'waiting_resources',
}

def mark(job,stage):
    job.update(stage=stage,phase=STAGES[stage])

def acquire_slot(lock,job):
    mark(job,'admitting')
    while True:
        if job['cancel'].is_set():raise InterruptedError()
        if lock.acquire(timeout=.1):
            mark(job,'releasing_model')
            return True
        mark(job,'waiting_compute')

def resources_waiting(job,memory,gpu_busy):
    if memory<4*1024**3:mark(job,'waiting_memory');return True
    if gpu_busy:mark(job,'waiting_gpu');return True
    return False

def public_stage(job):
    stage=job.get('stage')
    return stage if STAGES.get(stage)==job.get('phase') else None
