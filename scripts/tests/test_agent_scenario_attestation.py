"""Attestation tests for scripts/evaluate-agent-scenarios.py (owner: Claude, Codex assignment B).

SYNTHETIC ONLY: keys are generated in memory per test and discarded; no real phone/PC key, receipt or runtime
evidence is used, and passing here is not device validation."""
import base64,copy,hashlib,importlib.util,json,pathlib,tempfile,unittest

SPEC=importlib.util.spec_from_file_location('evaluate_agent_scenarios',pathlib.Path(__file__).resolve().parents[1]/'evaluate-agent-scenarios.py')
ev=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(ev)

@unittest.skipUnless(ev.CRYPTO,'cryptography not installed: evaluator fails closed (covered by test_missing_verifier_fails_closed)')
class AttestationTest(unittest.TestCase):
    def setUp(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec,ed25519
        self.ser,self.ec,self.ed=serialization,ec,ed25519
        self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
        self.private=ed25519.Ed25519PrivateKey.generate()
        self.keys_path=self.write_keys([('synthetic-android','ed25519','android_runtime',self.private.public_key())])
        self.keys=ev.load_trusted_keys(self.keys_path)
        self.case=dict(id='shop',expectedStatus='completed',allowedTools=['search_coupang','read_screen'],
            allowedTargets=['fixture:coupang','fixture:screen'],requiredTools=['search_coupang','read_screen'],maxWrites=0,
            requiredEvidence=['screen_opened'],requiredObserved={'noPurchase':True})
        self.receipt=dict(runId='synthetic-run',caseId='shop',repeat=1,version='test',model='fixture',effort='test',coldWarm='cold',
            actual=True,finalStatus='completed',reportedComplete=True,phaseTimesMs={'total':10},
            actions=[self.action('a1','search_coupang','fixture:coupang','screen_open','PREPARED',False),
                     self.action('a2','read_screen','fixture:screen','read','VERIFIED',True)],
            outcomeEvidence=['screen_opened'],observed={'noPurchase':True},artifacts=[])
    def tearDown(self):self.tmp.cleanup()
    def action(self,i,tool,target,effect,verification,readback,ref='evidence/run.json'):
        return dict(actionId=i,tool=tool,target=target,effect=effect,verification=verification,readback=readback,effectId='',evidenceRef=ref)
    def write_keys(self,rows):
        der=lambda k:base64.b64encode(k.public_bytes(self.ser.Encoding.DER,self.ser.PublicFormat.SubjectPublicKeyInfo)).decode()
        path=self.root/'trusted.json'
        path.write_text(json.dumps({'keys':[{'keyId':i,'algorithm':a,'source':s,'publicKey':der(k)} for i,a,s,k in rows]}),encoding='utf-8')
        return path
    def envelope(self,receipt,**override):
        value=dict(schemaVersion=1,source='android_runtime',runId=receipt['runId'],caseId=receipt['caseId'],repeat=receipt['repeat'],
            receiptSha256=ev.receipt_binding(receipt),actions=[{k:a[k] for k in ev.ACTION_FIELDS} for a in receipt['actions']],
            outcomeEvidence=list(receipt['outcomeEvidence']),observed=dict(receipt['observed']));value.update(override);return value
    def sign(self,envelope,name='evidence/run.json',key=None,key_id='synthetic-android',algorithm='ed25519',tamper=None):
        key=key or self.private
        signature=key.sign(ev.canonical(envelope)) if algorithm=='ed25519' else key.sign(ev.canonical(envelope),self.ec.ECDSA(__import__('cryptography.hazmat.primitives.hashes',fromlist=['SHA256']).SHA256()))
        body=dict(envelope=tamper(copy.deepcopy(envelope)) if tamper else envelope,
            signature=dict(keyId=key_id,algorithm=algorithm,value=base64.b64encode(signature).decode()))
        raw=json.dumps(body).encode();path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
        return dict(path=name,sha256=hashlib.sha256(raw).hexdigest())
    def run_case(self,receipt,artifacts,keys=None):
        receipt=dict(receipt,artifacts=artifacts)
        return ev.evaluate(self.case,receipt,self.root,self.keys if keys is None else keys)
    def signed_artifacts(self,receipt,**kw):
        # receiptSha256 binds the receipt WITHOUT artifacts, so the envelope can be signed before listing it.
        return [self.sign(self.envelope(receipt),**kw)]

    def test_signed_bound_runtime_evidence_is_success(self):
        self.assertEqual('success',self.run_case(self.receipt,self.signed_artifacts(self.receipt))['result'])
    def test_ecdsa_p256_android_keystore_style_key_is_supported(self):
        private=self.ec.generate_private_key(self.ec.SECP256R1())
        keys=ev.load_trusted_keys(self.write_keys([('synthetic-ks','ecdsa-p256-sha256','android_runtime',private.public_key())]))
        art=[self.sign(self.envelope(self.receipt),key=private,key_id='synthetic-ks',algorithm='ecdsa-p256-sha256')]
        self.assertEqual('success',self.run_case(self.receipt,art,keys)['result'])
    def test_unsigned_self_declared_envelope_is_unverified(self):
        raw=json.dumps({'actual':True,'source':'android_runtime','receiptSha256':ev.receipt_binding(self.receipt),
            'runId':'synthetic-run','caseId':'shop'}).encode()
        (self.root/'self.json').write_bytes(raw)
        art=[dict(path='self.json',sha256=hashlib.sha256(raw).hexdigest())]
        self.assertEqual('unverified',self.run_case(self.receipt,art)['result'])
    def test_untrusted_key_wrong_source_and_tampering_are_unverified(self):
        stranger=self.ed.Ed25519PrivateKey.generate()
        self.assertEqual('unverified',self.run_case(self.receipt,self.signed_artifacts(self.receipt,key=stranger))['result'])
        self.assertEqual('unverified',self.run_case(self.receipt,self.signed_artifacts(self.receipt,key_id='unknown'))['result'])
        art=[self.sign(self.envelope(self.receipt,source='pc_runtime'))]  # android key cannot sign as PC runtime
        self.assertEqual('unverified',self.run_case(self.receipt,art)['result'])
        tampered=self.signed_artifacts(self.receipt,tamper=lambda e:dict(e,outcomeEvidence=e['outcomeEvidence']+['extra']))
        self.assertEqual('unverified',self.run_case(self.receipt,tampered)['result'])
        self.assertEqual('unverified',self.run_case(self.receipt,self.signed_artifacts(self.receipt),keys={})['result'])
    def test_envelope_is_bound_to_this_exact_receipt(self):
        other=dict(copy.deepcopy(self.receipt),runId='other-run')
        art=[self.sign(self.envelope(other))]
        self.assertEqual('unverified',self.run_case(self.receipt,art)['result'])
        changed=copy.deepcopy(self.receipt);art=self.signed_artifacts(changed);changed['model']='swapped-after-signing'
        self.assertEqual('unverified',self.run_case(changed,art)['result'])
    def test_action_must_match_signed_action_in_its_own_referenced_artifact(self):
        receipt=copy.deepcopy(self.receipt)
        receipt['actions'][1]['evidenceRef']='evidence/other.json'
        only_first=self.envelope(receipt,actions=[{k:receipt['actions'][0][k] for k in ev.ACTION_FIELDS}])
        second=self.envelope(receipt,actions=[{k:receipt['actions'][1][k] for k in ev.ACTION_FIELDS}],outcomeEvidence=[])
        arts=[self.sign(only_first),self.sign(second,name='evidence/other.json')]
        self.assertEqual('success',self.run_case(receipt,arts)['result'])
        crossed=copy.deepcopy(receipt);crossed['actions'][1]['evidenceRef']='evidence/run.json'  # points at another action's artifact
        arts=[self.sign(self.envelope(crossed,actions=only_first['actions'])),self.sign(self.envelope(crossed,actions=second['actions'],outcomeEvidence=[]),name='evidence/other.json')]
        self.assertEqual('failure',self.run_case(crossed,arts)['result'])
    def test_receipt_cannot_upgrade_signed_action_or_oracle(self):
        signed=copy.deepcopy(self.receipt);signed['actions'][1]['verification']='FAILED';art=self.signed_artifacts(signed)
        upgraded=copy.deepcopy(signed);upgraded['actions'][1]['verification']='VERIFIED'
        # receiptSha differs too, but even a matching binding cannot lift FAILED to VERIFIED:
        self.assertNotEqual('success',self.run_case(upgraded,art)['result'])
        no_oracle=self.envelope(self.receipt,outcomeEvidence=[],observed={})
        self.assertEqual('failure',self.run_case(self.receipt,[self.sign(no_oracle)])['result'])
    def test_catalogue_effect_overrides_receipt_claims(self):
        self.case.update(allowedTools=['prepare_sms'],allowedTargets=['fixture:sms'],requiredTools=['prepare_sms'],maxWrites=1)
        receipt=copy.deepcopy(self.receipt);receipt['actions']=[self.action('s1','prepare_sms','fixture:sms','handoff','PREPARED',False)]
        self.assertEqual('failure',self.run_case(receipt,self.signed_artifacts(receipt))['result'])  # pending send is never completion
        relabel=copy.deepcopy(receipt);relabel['actions'][0]['effect']='screen_open'
        self.assertEqual('failure',self.run_case(relabel,self.signed_artifacts(relabel))['result'])
        self.case.update(allowedTools=['future_tool'],allowedTargets=['fixture:x'],requiredTools=['future_tool'])
        unknown=copy.deepcopy(self.receipt);unknown['actions']=[self.action('u1','future_tool','fixture:x','read','VERIFIED',True)]
        self.assertEqual('failure',self.run_case(unknown,self.signed_artifacts(unknown))['result'])
    def test_omitted_signed_action_is_unresolved(self):
        full=self.envelope(self.receipt)
        partial=copy.deepcopy(self.receipt);partial['actions']=partial['actions'][:1]
        art=[self.sign(dict(full,receiptSha256=ev.receipt_binding(partial)))]
        self.assertEqual('failure',self.run_case(partial,art)['result'])

class FailClosedTest(unittest.TestCase):
    def test_missing_verifier_fails_closed(self):
        saved=ev.CRYPTO
        try:
            ev.CRYPTO=False
            self.assertEqual({},ev.load_trusted_keys('does-not-matter.json'))
            self.assertIsNone(ev.verified_envelope({'path':'x','sha256':'0'*64},'.',{},{'k':('ed25519','android_runtime',None)}))
            self.assertFalse(ev.verify_signature('ed25519',None,b'',b''))
        finally:ev.CRYPTO=saved
    def test_builtin_self_test_and_catalog_validation_still_run(self):
        with open(__import__('os').devnull,'w') as sink:
            result=unittest.TextTestRunner(stream=sink).run(unittest.defaultTestLoader.loadTestsFromTestCase(ev.SelfTest))
        self.assertTrue(result.wasSuccessful())
        catalog=pathlib.Path(__file__).resolve().parents[2]/'rust/scenarios/smartphone-agent-v1.json'
        if catalog.exists():self.assertEqual(30,len(ev.catalog_load(catalog)['cases']))

if __name__=='__main__':unittest.main()
