"""User-DPAPI encrypted, atomic, bounded image recovery storage; never a public image directory."""
import json, math, os, pathlib, struct, time, uuid
from security import dpapi

TTL=24*3600
class CheckpointStore:
    def __init__(self,root,epoch):
        self.root=pathlib.Path(root);self.root.mkdir(parents=True,exist_ok=True);self.epoch=epoch
    def folder(self,key,create=True):
        if not isinstance(key,str) or str(uuid.UUID(key))!=key:raise ValueError('RECOVERY_ID')
        path=self.root/key
        if path.is_symlink() or path.resolve().parent!=self.root.resolve():raise ValueError('RECOVERY_PATH')
        if create:
            if not path.exists() and sum(1 for p in self.root.iterdir() if p.is_dir())>=4:raise ValueError('RECOVERY_CAPACITY')
            path.mkdir(exist_ok=True)
        return path
    def write(self,key,name,data,limit):
        if name not in ('job.dpapi','result.dpapi'):raise ValueError('RECOVERY_FILE')
        if not 0<len(data)<=limit:raise ValueError('RECOVERY_BOUND')
        path=self.folder(key)/name;temporary=path.with_suffix('.next')
        if path.is_symlink() or temporary.is_symlink():raise ValueError('RECOVERY_PATH')
        encrypted=dpapi(data)
        with temporary.open('wb') as stream:stream.write(encrypted);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
    def save(self,key,value):
        self.write(key,'job.dpapi',json.dumps(value|{'epoch':self.epoch},separators=(',',':')).encode(),12*1024*1024)
    def load(self,key):
        path=self.folder(key,create=False)/'job.dpapi'
        if path.stat().st_size>13*1024*1024:raise ValueError('RECOVERY_BOUND')
        value=json.loads(dpapi(path.read_bytes(),decrypt=True))
        now=time.time();created=value['createdWall']
        if value['epoch']!=self.epoch or not isinstance(created,(float,int)) or not created-300<=now<created+TTL:
            raise ValueError('RECOVERY_EXPIRED_OR_REPAIRED')
        return value
    def result(self,key,data=None):
        if data is not None:self.write(key,'result.dpapi',data,32*1024*1024);return data
        path=self.folder(key,create=False)/'result.dpapi'
        if path.stat().st_size>33*1024*1024:raise ValueError('RECOVERY_BOUND')
        return dpapi(path.read_bytes(),decrypt=True)
    def native(self,key):return self.folder(key,create=False)/'latent.dpapi'
    def native_step(self,key,binding,total):
        path=self.native(key)
        if not path.exists():return 0
        if path.is_symlink() or not 0<path.stat().st_size<=32*1024*1024:raise ValueError('CHECKPOINT_BOUND')
        data=dpapi(path.read_bytes(),decrypt=True)
        if len(data)<100 or data[:8]!=b'ZHCP0001':raise ValueError('CHECKPOINT_FORMAT')
        step,count,dimensions=struct.unpack_from('<III',data,8)
        if not 0<step<=count==total<=50 or not 1<=dimensions<=8 or data[20:84]!=binding.encode():raise ValueError('CHECKPOINT_BINDING')
        shape=struct.unpack_from('<'+'q'*dimensions,data,84)
        if any(not 0<d<=4096 for d in shape):raise ValueError('CHECKPOINT_SHAPE')
        size=84+8*dimensions+4*(total+1)+4*math.prod(shape)
        if size!=len(data):raise ValueError('CHECKPOINT_SIZE')
        return step
    def keys(self):
        return [p.name for p in self.root.iterdir() if p.is_dir() and not p.is_symlink() and
            len(p.name)==36 and (p/'job.dpapi').is_file()]
    def remove(self,key):
        folder=self.folder(key,create=False)
        if not folder.exists():return
        for name in ('job.dpapi','job.next','result.dpapi','result.next','latent.dpapi','latent.dpapi.next'):
            path=folder/name
            if path.is_symlink():raise ValueError('RECOVERY_PATH')
            path.unlink(missing_ok=True)
        if not any(folder.iterdir()):folder.rmdir()
