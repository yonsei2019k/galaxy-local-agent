import sys, unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'deployed'))
import browser_agent as b


class Policy(unittest.TestCase):
    def test_only_public_web_pages(self):
        for good in ('https://www.letskorail.com/', 'http://example.com/a?b=1', 'https://map.naver.com:443/p'):
            self.assertTrue(b.url_allowed(good), good)
        for bad in ('file:///C:/x', 'chrome://settings', 'javascript:alert(1)', 'data:text/html,x', 'https://localhost/',
                    'http://127.0.0.1/', 'http://192.168.0.1/', 'http://10.0.0.5/', 'http://100.75.229.95/', 'https://router.local/',
                    'https://user:pw@example.com/', 'https://example.com:8443/', 'http://intranet/', 'https://[::1]/', 'x' * 3000):
            self.assertFalse(b.url_allowed(bad), bad)

    def test_sensitive_fields_are_never_typed(self):
        for field in ({'type': 'password'}, {'autocomplete': 'cc-number'}, {'autocomplete': 'one-time-code'},
                      {'name': 'cardNo'}, {'label': '인증번호 6자리'}, {'placeholder': '비밀번호'}, {'id': 'cvc'}, {'label': '주민등록번호'}):
            self.assertTrue(b.sensitive_field(field), field)
        for field in ({'type': 'text', 'label': '출발역'}, {'type': 'search', 'placeholder': '검색어 입력'}, {'type': 'date', 'name': 'travelDate'}):
            self.assertFalse(b.sensitive_field(field), field)

    def test_irreversible_controls_need_approval(self):
        for control in ({'tag': 'button', 'label': '결제하기'}, {'tag': 'a', 'label': '예매'}, {'tag': 'button', 'label': '주문하기'},
                        {'tag': 'input', 'type': 'submit', 'label': 'Place order'}, {'tag': 'div', 'role': 'button', 'label': '예약 확정'},
                        {'tag': 'button', 'label': '보내기'}, {'tag': 'button', 'label': 'Delete'}):
            self.assertTrue(b.risky_control(control), control)
        for control in ({'tag': 'button', 'label': '조회하기'}, {'tag': 'a', 'label': '다음 날짜'}, {'tag': 'button', 'label': '장바구니 보기'},
                        {'tag': 'input', 'type': 'text', 'label': '결제 금액 메모'}, {'tag': 'a', 'label': 'IANA-managed Reserved Domains'},
                        {'tag': 'a', 'label': 'Confirmation details'}, {'tag': 'a', 'label': 'Sender guide'}):
            self.assertFalse(b.risky_control(control), control)

    def test_typed_text_bounds(self):
        self.assertTrue(b.safe_text('서울'))
        self.assertTrue(b.safe_text('2026-11-20'))
        for bad in ('', 'x' * 501, '1234 5678 9012 3456', '110-123-456789', 'a\x00b', None):
            self.assertFalse(b.safe_text(bad), bad)

    def test_signature_changes_with_page(self):
        elements = [{'ref': 'e1', 'tag': 'button', 'type': '', 'label': '조회'}]
        same = b.signature('https://a.com/', elements)
        self.assertEqual(same, b.signature('https://a.com/', [dict(elements[0])]))
        self.assertNotEqual(same, b.signature('https://a.com/', [{'ref': 'e1', 'tag': 'button', 'type': '', 'label': '결제'}]))
        self.assertNotEqual(same, b.signature('https://b.com/', elements))


class Shapes(unittest.TestCase):
    def test_pc_media_observation_is_opt_in_and_never_reads_login(self):
        agent = b.BrowserAgent(profile='Z:/never-used')
        agent._ensure = lambda: None
        js = []
        agent.page = type('Page', (), {'evaluate': lambda self, expression, **kwargs:
            js.append(expression) or {'status': 'observed', 'positionMs': 4000}})()
        agent._read = lambda: {'loginRequired': True}
        self.assertEqual(agent.read({'observeMedia': True}), {'loginRequired': True})
        self.assertEqual(js, [])
        agent._read = lambda: {'snapshotId': 'pc-page'}
        self.assertEqual(agent.read({}), {'snapshotId': 'pc-page'})
        self.assertEqual(agent.read({'observeMedia': True})['media']['positionMs'], 4000)
        self.assertEqual(len(js), 1)
        for bad in ({'observeMedia': False}, {'observeMedia': True, 'script': 'x'}):
            with self.assertRaises(b.BrowserError):
                agent.read(bad)

    def test_public_read_uses_its_own_profile_and_returns_no_action_targets(self):
        owner_browser = b.BrowserAgent(profile='Z:/existing-profile')
        observed = {'url': 'https://example.com/', 'title': 'Public', 'text': 'Evidence',
                    'snapshotId': 'bound-to-pc', 'elements': [{'ref': 'e1'}]}

        def open_once(reader, request):
            self.assertNotEqual(owner_browser.profile, reader.profile)
            self.assertEqual(request, {'url': 'https://example.com/'})
            return observed

        with patch.object(b.BrowserAgent, 'open', open_once):
            self.assertEqual(owner_browser.public_read({'url': 'https://example.com/'}),
                             {'url': 'https://example.com/', 'title': 'Public', 'text': 'Evidence'})

    def test_navigation_error_is_never_returned_as_page_evidence(self):
        agent = b.BrowserAgent(profile='Z:/never-used')
        agent._ensure = lambda: None
        agent.page = type('Page', (), {'call': lambda *args: {'errorText': 'net::ERR_CERT_AUTHORITY_INVALID'}})()
        with self.assertRaises(b.BrowserError) as error:
            agent.open({'url': 'https://example.com/'})
        self.assertEqual(error.exception.code, 'PAGE_NAVIGATION_FAILED')
        self.assertIsNone(agent.snapshot)

    def test_login_snapshot_returns_no_credentials_or_navigation_targets(self):
        agent = b.BrowserAgent(profile='Z:/never-used')
        agent.page = type('Page', (), {'evaluate': lambda *args, **kwargs: {
            'url': 'https://example.com/login?state=opaque', 'text': 'private view',
            'elements': [{'editable': True, 'autocomplete': 'one-time-code'}],
        }})()
        self.assertEqual(agent._read(), {'loginRequired': True, 'dataOnly': True})
        self.assertIsNone(agent.snapshot)

    def test_public_read_rejects_urls_with_authentication_state(self):
        agent = b.BrowserAgent(profile='Z:/never-used')
        for url in ('http://example.com/', 'https://example.com/?state=opaque', 'https://example.com/#code'):
            with self.assertRaises(b.BrowserError) as error:
                agent.public_read({'url': url})
            self.assertEqual(error.exception.code, 'URL_NOT_ALLOWED')

    def test_bad_requests_are_rejected_before_touching_a_browser(self):
        agent = b.BrowserAgent(profile='Z:/never-used', chrome='Z:/missing.exe')
        with self.assertRaises(b.BrowserError) as e: agent.open({'url': 'http://127.0.0.1/'})
        self.assertEqual(e.exception.code, 'URL_NOT_ALLOWED')
        with self.assertRaises(b.BrowserError) as e: agent.open({'url': 'https://example.com', 'extra': 1})
        self.assertEqual(e.exception.code, 'URL_NOT_ALLOWED')
        with self.assertRaises(b.BrowserError) as e: agent.act({'snapshotId': 'x', 'action': 'run_js'})
        self.assertEqual(e.exception.code, 'ACTION_UNKNOWN')
        with self.assertRaises(b.BrowserError) as e: agent.act({'snapshotId': 'x', 'action': 'click', 'script': '1'})
        self.assertEqual(e.exception.code, 'ACT_SHAPE')


if __name__ == '__main__':
    unittest.main()
