"""One-time LAN enrollment: a 60-bit code appears only on the owner's PC, never in logs."""
import ctypes
import hashlib
import hmac
import re
import secrets
import threading
import time
import uuid

ALPHABET='23456789ABCDEFGHJKLMNPQRSTUVWXYZ' # 32 symbols; 60 bits for 12 characters.

def proof(code,server_id,ticket,server_nonce,client_nonce,certificate):
    message='|'.join(['ZipHapPair-v1',server_id,ticket,server_nonce,client_nonce,certificate])
    return hmac.new(code.encode('ascii'),message.encode('ascii'),hashlib.sha256).hexdigest()

def show_code(code):
    grouped='-'.join(code[index:index+4] for index in range(0,12,4))
    ctypes.windll.user32.MessageBoxW(None,
        '스마트폰 ZipHap.Agent의 PC 연결 화면에 다음 코드를 입력하세요.\n\n'+grouped+
        '\n\n코드는 5분 동안 한 번만 사용할 수 있습니다.\n연결 후 이 창을 닫으세요. 이미지 생성 때마다 뜨는 창이 아닙니다.',
        'ZipHap · 새 스마트폰 연결',0x00050040)

class Pairing:
    def __init__(self,config,display=show_code,clock=time.monotonic):
        self.config=config;self.display=display;self.clock=clock;self.lock=threading.Lock();self.sessions={};self.starts={}
    def start(self,peer,request):
        if set(request)!={'clientNonce'} or not re.fullmatch('[a-f0-9]{64}',request['clientNonce']):raise ValueError('PAIR_REQUEST')
        with self.lock:
            now=self.clock();self.sessions={k:v for k,v in self.sessions.items() if v['expires']>now}
            self.starts={k:[t for t in times if now-t<300] for k,times in self.starts.items() if any(now-t<300 for t in times)}
            times=self.starts.setdefault(peer,[])
            if len(times)>=3 or len(self.sessions)>=2:raise ValueError('PAIR_RATE_LIMIT')
            times.append(now)
            ticket=str(uuid.uuid4());code=''.join(secrets.choice(ALPHABET) for _ in range(12));nonce=secrets.token_hex(32)
            self.sessions[ticket]={'peer':peer,'client':request['clientNonce'],'nonce':nonce,'code':code,'expires':now+300,'attempts':0}
            threading.Thread(target=self.display,args=(code,),daemon=True).start()
            return {'ticketId':ticket,'serverNonce':nonce,'expiresInSeconds':300}
    def finish(self,peer,request):
        if set(request)!={'ticketId','proof'} or not isinstance(request['proof'],str) or not re.fullmatch('[a-f0-9]{64}',request['proof']):raise ValueError('PAIR_PROOF')
        with self.lock:
            ticket=request['ticketId'];session=self.sessions.get(ticket)
            if not session or session['expires']<=self.clock() or session['peer']!=peer or session['attempts']>=5:raise ValueError('PAIR_EXPIRED')
            session['attempts']+=1
            expected=proof(session['code'],self.config['server_id'],ticket,session['nonce'],session['client'],self.config['certificate_sha256'])
            if not hmac.compare_digest(expected,request['proof']):raise ValueError('PAIR_PROOF')
            del self.sessions[ticket]
            return {'version':1,'serverId':self.config['server_id'],'token':self.config['token'],
                    'certificateSha256':self.config['certificate_sha256'],'discoveryPort':self.config['discovery_port']}
