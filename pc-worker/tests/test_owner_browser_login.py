"""Exercise the actual owner-input CDP code using locally fulfilled HTTPS pages."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'deployed'))
import browser_agent as browser

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None


@unittest.skipUnless(sync_playwright and any(Path(p).is_file() for p in browser.CHROME_CANDIDATES),
                     'Headless browser fixture requires Playwright and installed Chrome')
class OwnerLogin(unittest.TestCase):
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
                def evaluate(self, expression):
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
