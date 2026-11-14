"""Worker log: dated lines, one rotated file, quiet successful image polls, chat start/end/failure."""
import pathlib,re,shutil,sys
p=pathlib.Path(r'D:\ZipHap\Compute\worker\server.py');s=p.read_text(encoding='utf-8')
if 'LOG_LIMIT' in s:sys.exit('already patched')
shutil.copy(p,p.with_name('server.before-chatlog-20261114.py'))
old_note=s[s.index('def note(path,status,error):'):s.index('CONFIG=load()')]
new_note=r'''LOG=r'D:\ZipHap\Compute\requests.log';LOG_LIMIT=2*1024*1024
POLL=re.compile(r'^/v1/images/[0-9a-f-]{36}$')
def note(path,status,error):
 # Successful image progress polls are ~95% of traffic and carry no diagnostic value.
 if status==200 and not error and POLL.match(path.split('?')[0]):return
 try:
  import os
  if os.path.exists(LOG) and os.path.getsize(LOG)>LOG_LIMIT:os.replace(LOG,LOG[:-4]+'.1.log')
  with open(LOG,'a',encoding='utf-8') as out:out.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {path} {status} {error}\n")
 except OSError:pass
'''
s=s.replace(old_note,new_note)
old_chat='''   try:ENGINE.chat(self.body(),send,owner)
   except Exception:
    if not started:raise
    self.close_connection=True
   return'''
new_chat='''   began=time.time()
   try:ENGINE.chat(self.body(),send,owner)
   except Exception as failure:
    note(path,'stream' if started else 'before-stream',f"{type(failure).__name__}:{str(failure) if re.fullmatch(r'[A-Z_]{3,40}',str(failure)) else '-'} after {time.time()-began:.1f}s")
    if not started:raise
    self.close_connection=True
    return
   note(path,200,f"ok {time.time()-began:.1f}s")
   return'''
assert old_chat in s,'chat block not found'
s=s.replace(old_chat,new_chat)
compile(s,str(p),'exec');p.write_text(s,encoding='utf-8');print('patched')
