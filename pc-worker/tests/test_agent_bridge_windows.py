"""Actual Windows filesystem boundaries, isolated fixtures only; no model/service."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec=importlib.util.spec_from_file_location("ziphap_bridge_windows_subject",Path(__file__).parents[1]/"deployed/agent_bridge.py")
b=importlib.util.module_from_spec(spec);sys.modules[spec.name]=b;spec.loader.exec_module(b)


@unittest.skipUnless(os.name=="nt","Windows reparse-point qualification")
class WindowsWorkspaceTests(unittest.TestCase):
    def test_real_junction_is_denied_without_touching_its_target(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-bridge-junction-") as directory:
            base=Path(directory).resolve();root=base/"allowed";outside=base/"outside"
            root.mkdir();outside.mkdir();(outside/"keep.txt").write_text("synthetic outside boundary",encoding="utf-8")
            link=root/"alias"
            script=base/"create-junction.ps1"
            script.write_text('param([string]$Link,[string]$Target)\n$ErrorActionPreference="Stop"\nNew-Item -ItemType Junction -Path $Link -Target $Target | Out-Null\n',encoding="utf-8")
            process=subprocess.run(["powershell.exe","-NoProfile","-NonInteractive","-File",str(script),"-Link",str(link),"-Target",str(outside)],
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15,creationflags=0x08000000)
            self.assertEqual(0,process.returncode,"Could not create isolated junction fixture")
            try:
                executor=b.WorkspaceExecutor(root)
                for tool,args in [("pc_inspect_workspace",{"path":"alias"}),("pc_read_file",{"path":"alias/keep.txt"})]:
                    with self.assertRaises(b.BridgeError):executor.execute(tool,args)
                self.assertEqual("synthetic outside boundary",(outside/"keep.txt").read_text(encoding="utf-8"))
            finally:
                # Remove only the verified junction entry, never traverse its target.
                self.assertEqual(root,link.parent)
                self.assertTrue(link.lstat().st_file_attributes&0x400)
                os.rmdir(link)
            self.assertTrue((outside/"keep.txt").exists())

    def test_replacing_configured_root_identity_is_denied(self):
        with tempfile.TemporaryDirectory(prefix="ziphap-bridge-root-") as directory:
            base=Path(directory).resolve();root=base/"allowed";moved=base/"original"
            root.mkdir();(root/"seed.txt").write_text("original",encoding="utf-8")
            executor=b.WorkspaceExecutor(root)
            # Both absolute rename targets are checked inside the unique fixture workspace.
            self.assertEqual(str(base),os.path.commonpath([str(base),str(root.resolve())]))
            self.assertEqual(str(base),os.path.commonpath([str(base),str(moved.resolve())]))
            os.rename(root,moved);root.mkdir();(root/"seed.txt").write_text("replacement",encoding="utf-8")
            with self.assertRaises(b.BridgeError):executor.execute("pc_read_file",{"path":"seed.txt"})
            self.assertEqual("original",(moved/"seed.txt").read_text(encoding="utf-8"))


if __name__=="__main__":unittest.main()
