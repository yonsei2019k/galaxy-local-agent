import argparse
import json
import ssl
import urllib.request
from security import load,HOME
parser=argparse.ArgumentParser();parser.add_argument('--stop',action='store_true');args=parser.parse_args()
config=load();context=ssl.create_default_context(cafile=str(HOME/'server-cert.pem'));context.check_hostname=False
url=f"https://127.0.0.1:{config['port']}"+('/v1/control/stop' if args.stop else '/v1/health')
request=urllib.request.Request(url,data=b'{}' if args.stop else None,headers={'Authorization':'Bearer '+config['token'],'Content-Type':'application/json'})
with urllib.request.urlopen(request,context=context,timeout=5) as response:
 value=json.loads(response.read(4096))
print('Worker graceful shutdown requested.' if args.stop else f"TLS/auth health PASS; image model {value['image_model']}; paired text models {len(value['llm_models'])}; durable personal store {value['durable_personal_store']}")
