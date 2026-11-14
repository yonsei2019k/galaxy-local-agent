import importlib.util
from pathlib import Path
import subprocess
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('overlay_endpoint', Path(__file__).resolve().parents[1] / 'deployed/overlay_endpoint.py')
overlay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(overlay)


class OverlayEndpointTests(unittest.TestCase):
    def setUp(self):
        overlay._cached, overlay._expires = None, 0

    def test_hostname_address_avoids_cli(self):
        with patch.object(overlay.socket, 'gethostbyname_ex', return_value=('fixture', [], ['100.64.0.1'])), patch.object(overlay.subprocess, 'run') as run:
            self.assertEqual('100.64.0.1', overlay.discover_ipv4())
            run.assert_not_called()

    def test_missing_dns_adapter_uses_bounded_cli(self):
        with patch.object(overlay.socket, 'gethostbyname_ex', return_value=('fixture', [], ['127.0.0.1'])), patch.object(overlay.Path, 'is_file', return_value=True), patch.object(overlay.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0, stdout='100.64.0.1\n')) as run:
            self.assertEqual('100.64.0.1', overlay.discover_ipv4())
            self.assertEqual(['ip', '-4'], run.call_args.args[0][1:])
            self.assertEqual(2, run.call_args.kwargs['timeout'])

    def test_dns_error_still_tries_cli(self):
        with patch.object(overlay.socket, 'gethostbyname_ex', side_effect=OSError), patch.object(overlay.Path, 'is_file', return_value=True), patch.object(overlay.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0, stdout='100.64.0.1\n')):
            self.assertEqual('100.64.0.1', overlay.discover_ipv4())

    def test_cli_non_overlay_output_and_failure_are_rejected(self):
        self.assertIsNone(overlay._select(['127.0.0.1', '100.128.0.1', 'not-an-ip', '::1']))
        with patch.object(overlay.socket, 'gethostbyname_ex', side_effect=OSError), patch.object(overlay.Path, 'is_file', return_value=True), patch.object(overlay.subprocess, 'run', side_effect=subprocess.TimeoutExpired('tailscale', 2)):
            self.assertIsNone(overlay.discover_ipv4())

    def test_positive_cache_prevents_process_per_health_poll(self):
        with patch.object(overlay, 'discover_ipv4', return_value='100.64.0.1') as discover:
            self.assertIn('overlayEndpoint', overlay.overlay_endpoint(18434))
            self.assertIn('overlayEndpoint', overlay.overlay_endpoint(18434))
            self.assertEqual(1, discover.call_count)

    def test_invalid_port_fails_closed(self):
        for port in (0, 65536, True, '18434'):
            with self.assertRaises(ValueError):
                overlay.overlay_endpoint(port)


if __name__ == '__main__':
    unittest.main()
