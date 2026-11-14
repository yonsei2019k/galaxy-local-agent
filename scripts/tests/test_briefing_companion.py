import importlib.util, pathlib, unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location("briefing_companion",pathlib.Path(__file__).parents[1]/"briefing_companion.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class BriefingTests(unittest.TestCase):
    def test_phone_stop_discards_pending_pc_delivery_without_new_inference(self):
        runner=module.Companion.__new__(module.Companion);runner.state={"delivery":{"results":[{"report":"private"}]}}
        runner.bridge=Mock(return_value={"enabled":False});runner.persist=Mock()
        self.assertEqual("disabled",runner.cycle());self.assertNotIn("delivery",runner.state)
    def test_pending_delivery_expires_without_acknowledging_phone_sources(self):
        runner=module.Companion.__new__(module.Companion);runner.state={"delivery":{"createdAt":0,"authorization":"a","results":[]}}
        runner.bridge=Mock(return_value={"enabled":True,"authorization":"a"});runner.persist=Mock()
        self.assertEqual("delivery_expired_originals_not_acknowledged",runner.cycle());self.assertEqual(1,runner.bridge.call_count)
    def test_detector_never_uses_another_phone(self):
        runner=module.Companion.__new__(module.Companion)
        runner.cfg={"adb":"adb","target":"previous","deviceIdentitySha256":module.hashlib.sha256(b"expected-phone").hexdigest()}
        responses=[Mock(stdout=b"List of devices attached\nother device\n",returncode=0),Mock(stdout=b"other-phone\n",returncode=0),Mock(stdout=b"List of discovered mdns services\n",returncode=0)]
        with patch.object(module.subprocess,"run",side_effect=responses):self.assertFalse(runner.ensure_phone())
        self.assertEqual("previous",runner.cfg["target"])
    def test_wireless_reconnect_selects_only_verified_original_phone(self):
        runner=module.Companion.__new__(module.Companion)
        runner.cfg={"adb":"adb","target":"previous","deviceIdentitySha256":module.hashlib.sha256(b"expected-phone").hexdigest()}
        responses=[Mock(stdout=b"List of devices attached\n",returncode=0),Mock(stdout=b"paired _adb-tls-connect._tcp. 192.0.2.1:3333\n",returncode=0),Mock(stdout=b"connected",returncode=0),Mock(stdout=b"expected-phone\n",returncode=0)]
        with patch.object(module.subprocess,"run",side_effect=responses):self.assertTrue(runner.ensure_phone())
        self.assertEqual("192.0.2.1:3333",runner.cfg["target"])
    def test_connected_but_empty_inbox_does_not_start_codex(self):
        runner=module.Companion.__new__(module.Companion)
        runner.state={};runner.bridge=Mock(side_effect=[{"enabled":True,"authorization":"current"},{"pending":0}])
        runner.analyse=Mock(side_effect=AssertionError("must not start Codex"))
        self.assertEqual("no_new_data_no_model_call",runner.cycle())
        runner.analyse.assert_not_called()
    def test_cached_result_is_delivered_without_second_model_call(self):
        runner=module.Companion.__new__(module.Companion)
        runner.state={"delivery":{"authorization":"a","results":[{"version":"1"}]}}
        runner.bridge=Mock(side_effect=[{"enabled":True,"authorization":"a"},{"savedAndReadBack":True,"version":"1"}])
        runner.persist=Mock();runner.analyse=Mock(side_effect=AssertionError("must reuse result"))
        self.assertEqual("delivered_without_reanalysis",runner.cycle())
        runner.analyse.assert_not_called();self.assertNotIn("delivery",runner.state)
    def test_unverified_phone_receipt_preserves_cached_analysis(self):
        runner=module.Companion.__new__(module.Companion)
        runner.state={"delivery":{"authorization":"a","results":[{"version":"1"}]}}
        runner.bridge=Mock(side_effect=[{"enabled":True,"authorization":"a"},{"savedAndReadBack":False,"version":"1"}])
        runner.persist=Mock()
        with self.assertRaises(RuntimeError):runner.cycle()
        self.assertIn("delivery",runner.state)
    def test_no_new_data_never_uses_model_and_budget_bounds_real_attempts(self):
        self.assertFalse(module.eligible({},0,100000))
        self.assertTrue(module.eligible({},1,100000))
        self.assertFalse(module.eligible({"lastAttempt":99999},1,100000))
        day=module.datetime.datetime.fromtimestamp(100000,module.datetime.timezone(module.datetime.timedelta(hours=9))).date().isoformat()
        self.assertFalse(module.eligible({"day":day,"calls":4},1,100000))
    def test_unknown_missing_duplicate_or_fabricated_sources_are_rejected(self):
        batch={"records":[{"version":"a","body":{"text":"회의 확정"}}]}
        good={"version":"a","report":"확인 필요","quote":"회의","events":[]}
        self.assertEqual([good],module.validate({"results":[good]},batch))
        for values in ([],[good,good],[dict(good,version="other")],[dict(good,quote="가짜")]):
            with self.assertRaises(ValueError):module.validate({"results":values},batch)

if __name__=="__main__":unittest.main()
