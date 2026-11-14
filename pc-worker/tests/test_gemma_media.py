import hashlib,importlib.util,pathlib,tempfile,unittest
from unittest.mock import patch,Mock

ROOT=pathlib.Path(__file__).parents[1]
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/file);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
media=load('gemma_media_test','media_engine.py')

class GemmaMediaTests(unittest.TestCase):
    def test_unknown_model_is_rejected_before_creating_private_scratch(self):
        engine=media.MediaEngine();engine._scratch=Mock(side_effect=AssertionError('must not create scratch'))
        with self.assertRaises(ValueError):engine.describe({'modelId':'other','prompt':'describe','contentBase64':'AA=='})
    def test_hash_mismatch_and_file_replacement_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder)/'model.gguf';path.write_bytes(b'original');sha=hashlib.sha256(b'original').hexdigest()
            media.verify_model(path,sha);path.write_bytes(b'replaced with other weights')
            with self.assertRaises(ValueError):media.verify_model(path,sha)
    def test_selected_gemma_and_projector_reach_native_call_and_input_is_cleaned(self):
        for name in ('gemma-e2b','gemma-e4b'):
            with self.subTest(model=name),tempfile.TemporaryDirectory() as folder:
                root=pathlib.Path(folder);(root/'scratch').mkdir();models=root/'Models';models.mkdir()
                model,proj,_,_,layers=media.GEMMA_PROFILES[name]
                for file in (model,proj):(models/file).write_bytes(b'GGUF fixture')
                sha=hashlib.sha256(b'GGUF fixture').hexdigest()
                profile=(model,proj,sha,sha,layers)
                with patch.object(media,'ROOT',root),patch.object(media,'MODELS',models),patch.dict(media.GEMMA_PROFILES,{name:profile}),patch.object(media,'image_template',return_value='synthetic template'),patch.object(media,'available_memory',return_value=8*1024**3),patch.object(media.subprocess,'run',return_value=Mock(returncode=0,stdout=b'a red circle')) as run:
                    result=media.MediaEngine().describe({'modelId':name,'prompt':'describe','contentBase64':'aW1hZ2U='})
                    args=run.call_args.args[0];self.assertEqual(str(models/model),args[args.index('-m')+1]);self.assertEqual(str(models/proj),args[args.index('--mmproj')+1]);self.assertEqual(name,result['engine']);self.assertIn('--jinja',args);self.assertEqual('synthetic template',args[args.index('--chat-template')+1])
                    self.assertEqual([],list((root/'scratch').iterdir()))
    def test_gguf_template_uses_model_template_without_thinking(self):
        import struct
        def text(value):
            encoded=value.encode();return struct.pack('<Q',len(encoded))+encoded
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder)/'fixture.gguf'
            path.write_bytes(b'GGUF'+struct.pack('<IQQ',3,0,2)+text('metadata')+struct.pack('<I',9)+struct.pack('<IQ',8,1)+text('skip this')+text('tokenizer.chat_template')+struct.pack('<I',8)+text('{{ messages }}'))
            result=media.image_template(path)
            self.assertEqual('{%- set enable_thinking = false -%}{{ messages }}',result)
            path.write_bytes(b'invalid')
            with self.assertRaises(ValueError):media.image_template(path)

    def test_busy_or_low_memory_does_not_start_native_model(self):
        with patch.object(media,'verify_model'),patch.object(media,'available_memory',return_value=1),patch.object(media.subprocess,'run') as native:
            for engine in (media.MediaEngine(lambda:False),media.MediaEngine(lambda:True)):
                with self.assertRaises(RuntimeError):engine.describe({'modelId':'gemma-e2b','prompt':'describe','contentBase64':'AA=='})
            native.assert_not_called()

if __name__=='__main__':unittest.main()
