"""Keeps the ZipHap compute worker alive across crashes. Started at sign-in; never touches other processes."""
import os,pathlib,subprocess,time
HOME=pathlib.Path(r'D:\ZipHap\Compute');WORKER=HOME/'worker'
# security.py derives HOME from LOCALAPPDATA/ZipHap/Compute.
ENV=dict(os.environ,LOCALAPPDATA='D:\\')
LOG=HOME/'supervisor.log'
def note(text):
    with LOG.open('a',encoding='utf-8') as f:f.write(time.strftime('%Y-%m-%d %H:%M:%S ')+text+'\n')
def running():
    pid=HOME/'worker.pid'
    if not pid.exists():return False
    out=subprocess.run(['tasklist','/FI',f'PID eq {pid.read_text().strip()}','/FI','IMAGENAME eq pythonw.exe','/NH'],capture_output=True,text=True,creationflags=0x08000000).stdout
    return 'pythonw.exe' in out
delay=2
while True:
    if running():delay=2
    else:
        note('starting worker')
        subprocess.Popen([str(HOME/'python/pythonw.exe'),'-E','-s',str(WORKER/'server.py')],cwd=WORKER,env=ENV,creationflags=0x08000008,close_fds=True)
        time.sleep(delay);delay=min(delay*2,60)
    time.sleep(10)
