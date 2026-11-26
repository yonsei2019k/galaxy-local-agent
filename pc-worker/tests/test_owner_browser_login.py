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
    def test_image_link_follows_its_same_origin_popup(self):
        from concurrent.futures import ThreadPoolExecutor
        origin = 'https://example.org'
        agent = browser.BrowserAgent()
        try:
            agent._ensure()
            with sync_playwright() as pw:
                chrome = pw.chromium.connect_over_cdp(f'http://127.0.0.1:{agent._port()}')
                context = chrome.contexts[0]
                context.route(origin + '/**', lambda route: route.fulfill(
                    content_type='text/html; charset=utf-8',
                    body=('<video controls></video>' if route.request.url.endswith('/player')
                          else '<a href="/player" target="_blank" rel="opener"><img alt="수강하기" width="36" height="36"></a>')))
                def open_popup():
                    opened = agent.open({'url': origin + '/'})
                    control = next(e for e in opened['elements'] if e['label'] == '수강하기')
                    return agent.act({'snapshotId':opened['snapshotId'],'ref':control['ref'],'action':'click'})
                with ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(open_popup)
                    for _ in range(120):
                        if future.done(): break
                        context.pages[0].wait_for_timeout(100)
                    result = future.result(timeout=2)
                self.assertEqual(result['url'], origin + '/player')
                self.assertIn('동영상 재생', [e['label'] for e in result['elements']])
        finally:
            agent.dispose()
    def test_completed_course_proof_requires_a_visible_row_on_the_official_list(self):
        from concurrent.futures import ThreadPoolExecutor
        origin = 'https://www.kairb.org'
        rows = """<table class="bbs">
          <tr><th>번호</th><th>강의 제목</th><th>이수 교시</th><th>재수강</th></tr>
          <tr><td>1</td><td>Verified Fixture</td><td>3교시</td><td></td></tr>
          <tr hidden><td>2</td><td>Hidden Fixture</td><td>3</td><td></td></tr>
          <tr><td>3</td><td>Pending Fixture</td><td>0</td><td></td></tr>
        </table>"""
        agent = browser.BrowserAgent()
        try:
            agent._ensure()
            with sync_playwright() as pw:
                chrome = pw.chromium.connect_over_cdp(f'http://127.0.0.1:{agent._port()}')
                context = chrome.contexts[0]
                context.route(origin + '/**', lambda route: route.fulfill(
                    content_type='text/html; charset=utf-8', body=rows))

                def observe():
                    agent.open({'url': browser.KAIRB_COMPLETED})
                    official = agent.read({})
                    agent.open({'url': origin + '/faq/'})
                    return official, agent.read({})

                with ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(observe)
                    for _ in range(160):
                        if future.done():
                            break
                        context.pages[0].wait_for_timeout(100)
                    official, unrelated = future.result(timeout=2)
                self.assertEqual(official['completedCourseTitles'], ['Verified Fixture'])
                self.assertNotIn('completedCourseTitles', unrelated)
        finally:
            agent.dispose()

    def test_pc_pdf_reads_only_a_same_session_observed_document(self):
        from concurrent.futures import ThreadPoolExecutor
        origin = 'https://example.org'
        agent = browser.BrowserAgent()
        try:
            agent._ensure()
            with sync_playwright() as pw:
                chrome = pw.chromium.connect_over_cdp(f'http://127.0.0.1:{agent._port()}')
                context = chrome.contexts[0]
                def route(request):
                    if request.request.url.endswith('/receipt.pdf'):
                        request.fulfill(content_type='application/pdf', body='%PDF-1.7 fixture')
                    else:
                        request.fulfill(content_type='text/html', body=
                            '<a href="/receipt.pdf">Receipt</a><a href="https://invalid.example/receipt.pdf">External</a>')
                context.route(origin+'/**',route)

                def observe():
                    observed=agent.open({'url':origin+'/'})
                    eligible=[item for item in observed['elements'] if item.get('sameSessionPdf')]
                    self.assertEqual([item['label'] for item in eligible],['Receipt'])
                    selection = {'snapshotId': observed['snapshotId'], 'ref': eligible[0]['ref']}
                    result = agent.save_pdf(selection)
                    agent.page.evaluate("document.querySelector('a').href = '/new-receipt.pdf'")
                    with self.assertRaises(browser.BrowserError) as changed:
                        agent.save_pdf(selection)
                    self.assertEqual(str(changed.exception), 'SNAPSHOT_STALE')
                    return result

                with ThreadPoolExecutor(max_workers=1) as executor:
                    task=executor.submit(observe)
                    for _ in range(160):
                        if task.done(): break
                        context.pages[0].wait_for_timeout(100)
                    result=task.result(timeout=2)
                self.assertEqual(browser.base64.b64decode(result['pdfBase64']), b'%PDF-1.7 fixture')
                self.assertEqual(result['sourceSha256'], browser.hashlib.sha256((origin+'/receipt.pdf').encode()).hexdigest())
        finally:
            agent.dispose()

    def test_owner_login_reopens_the_canonical_form_from_the_site_home(self):
        from concurrent.futures import ThreadPoolExecutor
        origin = 'https://www.kairb.org'
        home = 'https://kairb.org/'
        login_path = origin + '/member/'
        form = ('<form method="post" action="/member/login_reg.php"><input name="id">'
                '<input type="password" name="passwd"><input type="submit"></form>')
        agent = browser.BrowserAgent()
        try:
            agent._ensure()
            with sync_playwright() as pw:
                chrome = pw.chromium.connect_over_cdp(f'http://127.0.0.1:{agent._port()}')
                context = chrome.contexts[0]
                context.route(home, lambda route: route.fulfill(
                    content_type='text/html', body='<input type="password">'))
                context.route(login_path, lambda route: route.fulfill(content_type='text/html', body=form))
                def observe():
                    initial = agent.open({'url': home})
                    self.assertEqual(initial.get('loginOrigin'), origin)
                    return agent.owner_login_form({'origin': origin})
                with ThreadPoolExecutor(max_workers=1) as executor:
                    task = executor.submit(observe)
                    for _ in range(160):
                        if task.done():
                            break
                        context.pages[0].wait_for_timeout(100)
                    result = task.result(timeout=2)
                self.assertEqual(result.get('origin'), origin)
                self.assertRegex(result['challenge'], r'^[a-f0-9]{64}$')
                self.assertEqual(context.pages[0].url, login_path)
        finally:
            agent.dispose()

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
