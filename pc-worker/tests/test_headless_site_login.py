"""Exercise native Chromium POSTs and redirects against an isolated loopback HTTPS server."""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'deployed'))
import browser_agent as browser

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    x509 = None


GENERIC = 'https://login-fixture.example'
OTHER = 'https://other-fixture.example'
LOGIN = '<form method="post" action="/session"><input name="username"><input type="password" name="password"><button type="submit">Sign in</button></form>'
HALLYM = ('<form id="form1" method="post" action="process.asp"><input id="id_req" name="id">'
          '<input id="passwd_req" type="password" name="passwd"><input type="submit" value="로그인"></form>')
CLASSROOM_GATE = "<script>alert('로그인 정보가 없습니다.\\n다시 로그인 해주세요!');document.location='/'</script>"
LOGOUT = '<a href="/logout">로그아웃</a>'


def serve_fixture(directory, respond):
    hosts = [browser.HALLYM_ORIGIN[8:], GENERIC[8:], OTHER[8:]]
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, hosts[1])])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
            .not_valid_after(now + timedelta(hours=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in hosts]), critical=False)
            .sign(key, hashes.SHA256()))
    certfile, keyfile = Path(directory)/'test.pem', Path(directory)/'test.key'
    certfile.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    spki = key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, BrokenPipeError):
                pass  # Chromium closes speculative connections on navigation or shutdown.
        def fulfill(self, status=200, content_type='text/html', body='', headers=None):
            data = body.encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type',content_type)
            self.send_header('Content-Length',str(len(data)))
            for key, value in (headers or {}).items():
                self.send_header(key,value)
            self.end_headers()
            self.wfile.write(data)
        def do_GET(self):
            host = self.headers['Host']
            if host not in hosts:
                self.fulfill(status=403)
                return
            size = int(self.headers.get('Content-Length',0))
            observation = SimpleNamespace(url='https://' + host + self.path, method=self.command, headers=self.headers,
                                          post_data=self.rfile.read(size).decode('ascii') if size else None)
            respond(SimpleNamespace(request=observation,fulfill=self.fulfill))
        do_POST = do_GET
    server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certfile,keyfile)
    server.socket = tls.wrap_socket(server.socket,server_side=True)
    thread = threading.Thread(target=server.serve_forever,daemon=True)
    thread.start()
    rules = ','.join('MAP ' + h + ' 127.0.0.1:' + str(server.server_port) for h in hosts)
    flags = ['--no-proxy-server', '--host-resolver-rules=' + rules + ',MAP * ~NOTFOUND',
             '--ignore-certificate-errors-spki-list=' + base64.b64encode(hashlib.sha256(spki).digest()).decode('ascii')]
    return server, thread, flags


@unittest.skipUnless(x509 and any(Path(p).is_file() for p in browser.CHROME_CANDIDATES),
                     'Requires installed Chromium and cryptography for the disposable fixture certificate')
class HeadlessSiteLogin(unittest.TestCase):
    def run_site(self, respond, flow):
        with tempfile.TemporaryDirectory() as directory:
            server, thread, flags = serve_fixture(directory, respond)
            agent = browser.BrowserAgent()
            start = browser.subprocess.Popen
            try:
                with patch.object(browser.subprocess, 'Popen', lambda args, **kw: start([*args[:-1], *flags, args[-1]], **kw)):
                    agent._ensure()
                return flow(agent)
            finally:
                agent.dispose()
                server.shutdown()
                thread.join()
                server.server_close()

    def test_classroom_alert_and_one_card_return_to_the_authenticated_classroom(self):
        posts = []
        def respond(route):
            url = route.request.url
            if route.request.method == 'POST':
                data = parse_qs(route.request.post_data or '')
                received = data == {'id':['fixture'], 'passwd':['fixture']} and 'preauth=fixture' in route.request.headers.get('Cookie','')
                posts.append((url, received))
                route.fulfill(content_type='text/html', body="<script>location.replace('/')</script>",
                              headers={'Set-Cookie': 'authenticated=fixture; Path=/; Secure; HttpOnly; SameSite=Strict'} if received else {})
                return
            elif url == browser.HALLYM_CLASSROOM:
                body = '<h1>나의강의실</h1>' + LOGOUT if 'authenticated=fixture' in route.request.headers.get('Cookie','') else CLASSROOM_GATE
            elif url == browser.HALLYM_LOGIN:
                route.fulfill(content_type='text/html; charset=utf-8', body=HALLYM,
                              headers={'Set-Cookie':'preauth=fixture; Path=/; Secure; HttpOnly; SameSite=Strict'})
                return
            else:
                body = '<main>홈</main>'
            route.fulfill(content_type='text/html; charset=utf-8', body=body)
        def flow(agent):
            gate = agent.open({'url': browser.HALLYM_ORIGIN + '/new_home/My/'})
            self.assertEqual(gate, {'loginRequired':True,'dataOnly':True,'loginOrigin':browser.HALLYM_ORIGIN})
            self.assertEqual(agent.read({}), gate)
            card = agent.owner_login_form({'origin':browser.HALLYM_ORIGIN})
            result = agent.owner_login_submit({'challenge':card['challenge'],'account':'fixture','password':'fixture'})
            self.assertEqual(result, {'signedIn':True,'outcome':'signed_in'})
            self.assertEqual(agent.owner_login_status({'challenge':card['challenge']}), result)
            self.assertEqual(agent.read({})['url'], browser.HALLYM_CLASSROOM)
            with self.assertRaisesRegex(browser.BrowserError,'OWNER_LOGIN_EXPIRED'):
                agent.owner_login_submit({'challenge':card['challenge'],'account':'fixture','password':'fixture'})
        self.run_site(respond, flow)
        self.assertEqual(posts, [(browser.HALLYM_POST, True)])

    def test_generic_form_binding_and_verification_in_the_same_session(self):
        posted = []
        def respond(route):
            if route.request.method == 'POST':
                payload = parse_qs(route.request.post_data or '')
                posted.append((route.request.url, payload == {'username':['fixture'],'password':['fixture']}))
                body = '<input autocomplete="one-time-code">'
            elif route.request.url.endswith('/done'):
                body = LOGOUT
            else:
                body = LOGIN
            route.fulfill(content_type='text/html; charset=utf-8',body=body)
        def flow(agent):
            self.assertEqual(agent.open({'url':GENERIC+'/login'}),
                             {'dataOnly':True,'loginRequired':True,'loginOrigin':GENERIC})
            with self.assertRaises(browser.BrowserError):
                agent.owner_login_form({'origin':OTHER})
            def card():
                return agent.owner_login_form({'origin':GENERIC})['challenge']
            def submit(challenge):
                return agent.owner_login_submit({'challenge':challenge,'account':'fixture','password':'fixture'})
            changed = card()
            agent.page.evaluate("document.querySelector('form').action='/signin'")
            with self.assertRaisesRegex(browser.BrowserError,'OWNER_LOGIN_FORM_CHANGED'):
                submit(changed)
            self.assertEqual(posted, [])
            agent.page.evaluate("document.querySelector('form').action='/session'")
            check = card()
            self.assertEqual(submit(check), {'signedIn':False,'outcome':'owner_code_on_site'})
            for destination, expected in [(OTHER+'/done','unconfirmed'),(GENERIC+'/done','signed_in')]:
                agent.page.call('Page.navigate', {'url':destination})
                agent._settle()
                self.assertEqual(agent.owner_login_status({'challenge':check})['outcome'], expected)
        self.run_site(respond, flow)
        self.assertEqual(posted, [(GENERIC+'/session', True)])

    def test_unsafe_forms_and_external_identity_redirect_never_offer_credentials(self):
        def respond(route):
            if route.request.url == GENERIC+'/external':
                route.fulfill(status=302,headers={'Location':OTHER+'/login'})
            else:
                route.fulfill(content_type='text/html',body=LOGIN)
        def flow(agent):
            blocked = agent.open({'url':GENERIC+'/external'})
            self.assertEqual(blocked, {'dataOnly':True,'loginRequired':True})
            with self.assertRaises(browser.BrowserError):
                agent.owner_login_form({'origin':OTHER})
            agent.open({'url':GENERIC+'/login'})
            for change in ["f.action='http://login-fixture.example/session'",
                           "f.action='https://other-fixture.example/session'",
                           "f.action='/checkout/login'",
                           "f.querySelector('button').setAttribute('formaction','/session')",
                           "f.querySelector('button').textContent='Pay'",
                           "f.insertAdjacentHTML('beforeend','<input type=hidden name=password>')",
                           "f.insertAdjacentHTML('beforeend','<input name=otp>')"]:
                agent.page.evaluate("document.body.innerHTML=" + browser.json.dumps(LOGIN))
                agent.page.evaluate("((f)=>{"+change+"})(document.querySelector('form'))")
                self.assertEqual(agent.read({}), {'dataOnly':True,'loginRequired':True})
                with self.assertRaisesRegex(browser.BrowserError,'OWNER_LOGIN_FORM_UNAVAILABLE'):
                    agent.owner_login_form({'origin':GENERIC})
        self.run_site(respond, flow)

    def test_post_redirect_cannot_forward_credentials_to_another_origin(self):
        local_posts = []
        external_posts = []
        def respond(route):
            if route.request.url == GENERIC+'/session' and route.request.method == 'POST':
                local_posts.append(True)
                route.fulfill(status=307,headers={'Location':OTHER+'/session'})
            else:
                if route.request.url.startswith(OTHER) and route.request.method == 'POST':
                    external_posts.append(True)
                route.fulfill(content_type='text/html',body=LOGIN)
        def flow(agent):
            agent.open({'url':GENERIC+'/login'})
            card = agent.owner_login_form({'origin':GENERIC})
            result = agent.owner_login_submit({'challenge':card['challenge'],'account':'fixture','password':'fixture'})
            self.assertFalse(result['signedIn'])
        self.run_site(respond, flow)
        self.assertEqual(local_posts, [True])
        self.assertEqual(external_posts, [])
