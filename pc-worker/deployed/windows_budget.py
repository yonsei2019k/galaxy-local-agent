"""Restrict only this worker's native children; never changes another app's priority."""
import ctypes

class Basic(ctypes.Structure):
 _fields_=[('process_time',ctypes.c_int64),('job_time',ctypes.c_int64),('flags',ctypes.c_uint32),('minimum',ctypes.c_size_t),('maximum',ctypes.c_size_t),('active',ctypes.c_uint32),('affinity',ctypes.c_size_t),('priority',ctypes.c_uint32),('scheduling',ctypes.c_uint32)]
class Io(ctypes.Structure):
 _fields_=[(name,ctypes.c_uint64) for name in ['read_ops','write_ops','other_ops','read_bytes','write_bytes','other_bytes']]
class Extended(ctypes.Structure):
 _fields_=[('basic',Basic),('io',Io)]+[(name,ctypes.c_size_t) for name in ['process_memory','job_memory','peak_process','peak_job']]
kernel=ctypes.windll.kernel32
kernel.CreateJobObjectW.restype=ctypes.c_void_p
kernel.SetInformationJobObject.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p,ctypes.c_uint32]
kernel.AssignProcessToJobObject.argtypes=[ctypes.c_void_p,ctypes.c_void_p]
HANDLE=kernel.CreateJobObjectW(None,None)
info=Extended();info.basic.flags=0x2000 # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
if not HANDLE or not kernel.SetInformationJobObject(HANDLE,9,ctypes.byref(info),ctypes.sizeof(info)):raise ctypes.WinError()
class MemoryStatus(ctypes.Structure):
 _fields_=[('length',ctypes.c_uint32),('load',ctypes.c_uint32)]+[(name,ctypes.c_uint64) for name in ['total_physical','available_physical','total_page','available_page','total_virtual','available_virtual','available_extended']]
def bound_resident_memory(process):
 status=MemoryStatus();status.length=ctypes.sizeof(status)
 if not kernel.GlobalMemoryStatusEx(ctypes.byref(status)):raise ctypes.WinError()
 # Bound only our child, leaving headroom for the owner's desktop. This is a resident
 # page limit, not a commitment/allocation cap: disk-backed larger models may page.
 maximum=min(6*1024**3,max(1024**3,int(status.available_physical)-2*1024**3))
 maximum=maximum//4096*4096
 setter=kernel.SetProcessWorkingSetSizeEx;setter.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_size_t,ctypes.c_uint32]
 if not setter(ctypes.c_void_p(int(process._handle)),128*1024,maximum,0x6):raise ctypes.WinError()
 minimum=ctypes.c_size_t();actual=ctypes.c_size_t();flags=ctypes.c_uint32()
 getter=kernel.GetProcessWorkingSetSizeEx;getter.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_size_t),ctypes.POINTER(ctypes.c_size_t),ctypes.POINTER(ctypes.c_uint32)]
 if not getter(ctypes.c_void_p(int(process._handle)),ctypes.byref(minimum),ctypes.byref(actual),ctypes.byref(flags)):raise ctypes.WinError()
 if actual.value!=maximum or not flags.value&0x4:raise OSError('NATIVE_RESIDENT_MEMORY_LIMIT_NOT_APPLIED')
 process.ziphap_working_set_limit_bytes=maximum
def constrain(process):
 if not kernel.AssignProcessToJobObject(HANDLE,ctypes.c_void_p(int(process._handle))):
  process.terminate();raise ctypes.WinError()
 bound_resident_memory(process)
 try:
  function=ctypes.windll.gdi32.D3DKMTSetProcessSchedulingPriorityClass
  function.argtypes=[ctypes.c_void_p,ctypes.c_int];function.restype=ctypes.c_long
  return function(ctypes.c_void_p(int(process._handle)),1)==0 # BELOW_NORMAL
 except (AttributeError,OSError):return False
