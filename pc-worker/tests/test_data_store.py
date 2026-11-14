import pathlib,sys,tempfile,unittest
sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
from data_store import DataStore

class DataStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=DataStore(self.temp.name)
    def tearDown(self):self.store.close();self.temp.cleanup()
    def test_body_is_encrypted_and_reads_back_after_reopen(self):
        rid=self.store.put('sms','owner',{'text':'합성비밀문장'},terms=['합성비밀문장'])
        self.store.close()
        raw=b''.join(p.read_bytes() for p in pathlib.Path(self.temp.name).glob('ziphap.sqlite3*'))
        self.assertNotIn('합성비밀문장'.encode(),raw)
        self.store=DataStore(self.temp.name)
        self.assertEqual(self.store.get(rid),{'text':'합성비밀문장'})
        self.assertEqual(self.store.search('sms','owner','합성비밀문장'),[rid])
    def test_repeated_source_version_is_not_duplicated(self):
        a=self.store.put('sms','owner',{'text':'a'},source_version='v1')
        self.assertEqual(self.store.put('sms','owner',{'text':'a'},source_version='v1'),a)
    def test_scope_is_isolated_and_delete_removes_terms(self):
        rid=self.store.put('memory','a',{'x':1},terms=['t'])
        self.assertEqual(self.store.search('memory','b','t'),[])
        self.assertTrue(self.store.delete(rid));self.assertEqual(self.store.search('memory','a','t'),[])
    def test_media_and_unknown_kinds_are_rejected(self):
        with self.assertRaises(ValueError):self.store.put('analysis','a',{'pngBase64':'x'})
        with self.assertRaises(ValueError):self.store.put('photo','a',{})

if __name__=='__main__':unittest.main()
