"""Exercise the actual owner-input CDP code using locally fulfilled HTTPS pages."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'deployed'))
import browser_agent as browser


class OwnerLoginDialog(unittest.TestCase):
    def test_only_login_response_alert_is_dismissed(self):
        def event(kind, url):
            return {'method': 'Page.javascriptDialogOpening', 'params': {'type':kind, 'url':url}}
        origin = 'https://www.kairb.org/member/login_reg.php'
        messages = iter([event('confirm', origin), event('alert', 'https://example.org/'),
                         event('alert', origin), {'id': 1, 'result': {'value': True}}])
        class Wire:
            sent = []
            sock = type('Socket', (), {'settimeout': lambda self, timeout: None})()
            def send(self, data):
                self.sent.append(json.loads(data))
            def recv(self):
                return json.dumps(next(messages))
        page = browser._Page.__new__(browser._Page)
        page.ws = Wire()
        page.next_id = 0
        page.owner_login_dialogs = 0
        self.assertEqual(page.call('Runtime.evaluate'), {'value':True})
        self.assertEqual(page.owner_login_dialogs, 1)
        self.assertEqual([r['method'] for r in page.ws.sent], ['Runtime.evaluate', 'Page.handleJavaScriptDialog'])

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None


@unittest.skipUnless(sync_playwright and any(Path(p).is_file() for p in browser.CHROME_CANDIDATES),
                     'Headless browser fixture requires Playwright and installed Chrome')
class OwnerLogin(unittest.TestCase):
    def test_worker_reads_through_the_result_alert(self):
        origin = 'https://www.kairb.org'
        agent = browser.BrowserAgent()
        try:
            agent._ensure()
            with sync_playwright() as pw:
                chrome = pw.chromium.connect_over_cdp(f'http://127.0.0.1:{agent._port()}')
                context = chrome.contexts[0]
                def route(request):
                    path = request.request.url.removeprefix(origin)
                    if request.request.method == 'POST':
                        response = '<script>alert("fixture response");location.replace("/");</script>'
                    elif path == '/':
                        response = '<p>Landing page without logout control</p>'
                    elif path == '/cme/cme_ing.php':
                        response = '<h1>나의 교육수강</h1><button>수강하기</button>'
                    else:
                        response = '<form method="post" action="/member/login_reg.php"><input name="id"><input type="password" name="passwd"><input type="submit"></form>'
                    request.fulfill(content_type='text/html; charset=utf-8', body=response)
                context.route('https://www.kairb.org/**', route)
                # Let Playwright serve routed requests while the actual worker waits in CDP.
                from concurrent.futures import ThreadPoolExecutor
                def login():
                    agent.open({'url':origin+'/member/'})
                    challenge = agent.owner_login_form({'origin':origin})['challenge']
                    return agent.owner_login_submit({'challenge':challenge, 'account':'fixture', 'password':'fixture'})
                with ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(login)
                    for _ in range(150):
                        if future.done(): break
                        context.pages[0].wait_for_timeout(100)
                    self.assertEqual(future.result(timeout=2), {'signedIn': True})
        finally:
            agent.dispose()

    def test_exact_form_one_use_and_real_signed_in_marker(self):
        origin = 'https://www.kairb.org'
        form_html = '<form name="loginF" method="post" action="/member/login_reg.php"><input name="id"><input type="password" name="passwd"><input type="submit"></form>'
        submitted = []
        with sync_playwright() as pw:
            chrome = pw.chromium.launch(executable_path=next(p for p in browser.CHROME_CANDIDATES if Path(p).is_file()),headless=True)
            context = chrome.new_context()
            page = context.new_page()
            def route(request):
                if request.request.method == 'POST':
                    submitted.append(request.request.post_data)
                    body = '<a href="/member/logout.php">Logout</a>'
                else:
                    body = form_html
                request.fulfill(content_type='text/html; charset=utf-8', body=body)
            context.route('**/*', route)
            cdp = context.new_cdp_session(page)
            class Page:
                def call(self, command, params=None, timeout=None):
                    return cdp.send(command, params or {})
                def evaluate(self, expression, timeout=None):
                    return page.evaluate(expression)
            agent = browser.BrowserAgent(profile='unused')
            agent.page = Page()
            agent._settle = lambda: page.wait_for_url('**/login_reg.php')
            page.goto(origin + '/member/')
            def prepare():
                return agent.owner_login_form({'origin': origin})['challenge']
            def submit(token):
                return agent.owner_login_submit({'challenge': token, 'account': 'fixture', 'password': 'test-value'})
            changed = prepare()
            page.eval_on_selector('form', "f => f.action='https://example.org/'")
            with self.assertRaises(browser.BrowserError): submit(changed)
            self.assertEqual(submitted, [])
            page.goto(origin + '/member/')
            valid = prepare()
            self.assertEqual(submit(valid), {'signedIn': True})
            self.assertEqual(submitted, ['id=fixture&passwd=test-value'])
            with self.assertRaises(browser.BrowserError): submit(valid)
            page.goto('https://example.org/member/')
            with self.assertRaises(browser.BrowserError): prepare()
            self.assertEqual(len(submitted), 1)
            chrome.close()
