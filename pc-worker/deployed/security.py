"""Per-user private setup. No pairing token or network address is printed."""
import base64
import ctypes
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import ssl
import subprocess
import uuid
from pathlib import Path

TOOLS=Path(__file__).resolve().parent
HOME=Path(os.environ['LOCALAPPDATA'])/'ZipHap'/'Compute'
HOME.mkdir(parents=True,exist_ok=True)
class Blob(ctypes.Structure):
 _fields_=[('size',ctypes.c_uint32),('data',ctypes.POINTER(ctypes.c_ubyte))]
def dpapi(data:bytes,decrypt:bool=False)->bytes:
 buffer=ctypes.create_string_buffer(data);source=Blob(len(data),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)));target=Blob()
 if decrypt:ok=ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source),None,None,None,None,0,ctypes.byref(target))
 else:ok=ctypes.windll.crypt32.CryptProtectData(ctypes.byref(source),'ZipHap paired compute',None,None,None,0,ctypes.byref(target))
 if not ok:raise ctypes.WinError()
 try:return ctypes.string_at(target.data,target.size)
 finally:ctypes.windll.kernel32.LocalFree(target.data)
def private_v4(value):
 try:
  ip=ipaddress.ip_address(value)
  return ip.version==4 and (ip.is_loopback or any(ip in ipaddress.ip_network(n) for n in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','100.64.0.0/10']))
 except ValueError:return False
def current_address():
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
  sock.connect(('192.0.2.1',9));address=sock.getsockname()[0]
 assert private_v4(address) and address!='127.0.0.1','Connect the PC to the local network first'
 return address
def load():
 data=json.loads((HOME/'config.json').read_text())
 data['token']=dpapi(base64.b64decode(data['token_dpapi']),True).decode('ascii')
 return data
def setup():
 cert=HOME/'server-cert.pem';key=HOME/'server-key.pem'
 if not cert.exists():
  openssl=Path(r'C:\Program Files\Git\usr\bin\openssl.exe')
  subprocess.run([str(openssl),'req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-sha256','-days','3650','-nodes','-keyout',str(key),'-out',str(cert),'-subj','/CN=ZipHap paired compute'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
 fingerprint=hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
 config_file=HOME/'config.json'
 if config_file.exists():config=json.loads(config_file.read_text());token=dpapi(base64.b64decode(config['token_dpapi']),True).decode('ascii')
 else:
  token=secrets.token_hex(32)
  config={'version':1,'server_id':str(uuid.uuid4()),'port':18434,'discovery_port':18435,'token_dpapi':base64.b64encode(dpapi(token.encode())).decode(),'certificate_sha256':fingerprint}
  config_file.write_text(json.dumps(config,indent=2),encoding='utf-8')
 assert config['certificate_sha256']==fingerprint
 pairing={'version':1,'serverId':config['server_id'],'endpoint':f"https://{current_address()}:{config['port']}",'token':token,'certificateSha256':fingerprint,'discoveryPort':config['discovery_port']}
 (HOME/'pairing.json').write_text(json.dumps(pairing),encoding='utf-8')
 print('Private compute identity, certificate, DPAPI credential and pairing file ready.')
if __name__=='__main__':setup()
