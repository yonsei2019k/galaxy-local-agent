import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import browser_agent as browser


class BrowserSessionVaultTest(unittest.TestCase):
    def test_only_current_site_cookies_are_preserved(self):
        now = time.time()
        same = {'name': 'session', 'value': 'test-cookie', 'domain': '.example.org', 'path': '/course',
                'expires': now + 100, 'httpOnly': True, 'secure': True, 'sameSite': 'Lax'}
        cookies = [same, dict(same, domain='sibling.example.org'), dict(same, expires=now - 1),
                   dict(same, partitionKey={'topLevelSite': 'https://other.example'})]
        saved = browser._site_cookie_parameters('https://example.org', cookies, now)
        self.assertEqual(1, len(saved))
        self.assertEqual('https://example.org/', saved[0]['url'])
        self.assertEqual('/course', saved[0]['path'])
        self.assertNotIn('domain', saved[0])
        self.assertTrue(saved[0]['httpOnly'])

    @unittest.skipUnless(os.name == 'nt', 'Windows DPAPI')
    def test_a_fresh_browser_can_restore_only_with_the_same_site_and_key(self):
        origin = 'https://example.org'
        key = 'a' * 64
        observed = Mock()
        observed.call.return_value = {'cookies': [{'name': 'session', 'value': 'test-vault-cookie',
                                                   'domain': 'example.org', 'path': '/', 'httpOnly': True}]}
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {'LOCALAPPDATA': root}):
            self.assertTrue(browser._write_site_session(observed, origin, key))
            encrypted, = Path(root).rglob('*.dpapi')
            self.assertNotIn(b'test-vault-cookie', encrypted.read_bytes())
            fresh = Mock()
            browser._restore_site_session(fresh, origin, 'b' * 64)
            browser._restore_site_session(fresh, 'https://other.example.org', key)
            fresh.call.assert_not_called()
            browser._restore_site_session(fresh, origin, key)
            fresh.call.assert_called_once_with('Storage.setCookies', {'cookies': [{'name': 'session', 'value': 'test-vault-cookie',
                                                                                    'path': '/', 'httpOnly': True, 'url': origin + '/'}]}, timeout=4)
