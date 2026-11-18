"""Synthetic private-runtime isolation; no user data, compute or server import."""
import pathlib
import json
import sys
import tempfile
import unittest

sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'deployed'))
from agent_bridge import WorkspaceExecutor, BridgeError

class PrivateImageWorkspaceTest(unittest.TestCase):
    def test_private_root_children_and_broad_parent_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp).resolve()
            private=root/'compute';private.mkdir()
            child=private/'recovery';child.mkdir()
            for candidate in (root,private,child):
                with self.subTest(candidate=candidate.name):
                    with self.assertRaises(BridgeError) as error:
                        WorkspaceExecutor(candidate,private_roots=(private,))
                    self.assertEqual('WORKSPACE_PRIVATE_RUNTIME',error.exception.code)

    def test_separate_public_workspace_still_opens(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp).resolve()
            public=root/'public';public.mkdir()
            private=root/'private';private.mkdir()
            executor=WorkspaceExecutor(public,private_roots=(private,))
            self.assertEqual('WorkspaceExecutor(private configured root)',repr(executor))

    def test_workspace_with_private_local_records_is_rejected_even_without_collab_config(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp).resolve();(root/'.local').mkdir()
            with self.assertRaises(BridgeError) as error:WorkspaceExecutor(root)
            self.assertEqual('WORKSPACE_PRIVATE_RUNTIME',error.exception.code)

    def test_direct_private_subtree_and_late_created_private_files_remain_inaccessible(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp).resolve()
            executor=WorkspaceExecutor(root)
            private=root/'.local';private.mkdir();(private/'synthetic.txt').write_text('private image data')
            for candidate in (private,):
                with self.assertRaises(BridgeError):WorkspaceExecutor(candidate)
            with self.assertRaises(BridgeError):executor.execute('pc_read_file',{'path':'.local/synthetic.txt'})
            visible=json.loads(executor.execute('pc_inspect_workspace',{'path':''})['content'])
            self.assertFalse(any(e['name'].lower()=='.local' for e in visible['entries']))
            self.assertTrue(visible['partial']);self.assertFalse(visible['enumerationComplete'])

if __name__=='__main__':unittest.main()
