import sys, unittest
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
