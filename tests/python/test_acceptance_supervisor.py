import json,os,pathlib,subprocess,sys,tempfile,unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]
class AcceptanceSupervisorTests(unittest.TestCase):
    def invoke(self,folder,argv):
        spec=folder/'spec.json';spec.write_text(json.dumps({'argv':argv,'cwd':str(folder)}),encoding='utf-8')
        p=subprocess.run([sys.executable,str(ROOT/'tools/supervise_acceptance.py'),'--spec',str(spec),'--run-dir',str(folder/'run')],capture_output=True,text=True,timeout=30)
        return p,json.loads((folder/'run/result.json').read_text(encoding='utf-8'))
    def test_actual_zero_and_nonzero_exit_identity(self):
        for code in (0,7):
            with self.subTest(code=code),tempfile.TemporaryDirectory() as directory:
                folder=pathlib.Path(directory);p,r=self.invoke(folder,[sys.executable,'-c',f'import sys;print("child-output");sys.exit({code})'])
                self.assertEqual(p.returncode,code);self.assertEqual(r['exitCode'],code);self.assertEqual(r['status'],'COMPLETE')
                self.assertGreater(r['pid'],0);self.assertIn('endedUtc',r);self.assertGreaterEqual(r['elapsedSeconds'],0)
                if os.name=='nt':self.assertGreater(r['windowsCreationFiletime'],0);self.assertIn('createdUtc',r)
                self.assertEqual((folder/'run/stdout.log').read_text().strip(),'child-output')
                before=(folder/'run/result.json').read_bytes()
                again=subprocess.run([sys.executable,str(ROOT/'tools/supervise_acceptance.py'),'--spec',str(folder/'spec.json'),'--run-dir',str(folder/'run')],capture_output=True,timeout=30)
                self.assertNotEqual(again.returncode,0);self.assertEqual((folder/'run/result.json').read_bytes(),before)
    def test_launch_failure_is_not_child_success(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=pathlib.Path(directory);p,r=self.invoke(folder,[str(folder/'missing-executable')])
            self.assertEqual(p.returncode,2);self.assertIsNone(r['exitCode']);self.assertEqual(r['status'],'FAILED_TO_START')
            self.assertNotIn('pid',r)
