import pathlib,sys,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'));import native
REL='ruoyi-common/ruoyi-common-satoken/src/main/java/org/dromara/common/satoken/handler/SaTokenExceptionHandler.java'
class AuthLogPatchTests(unittest.TestCase):
    def fixture(self,root):
        path=root/REL;path.parent.mkdir(parents=True);path.write_bytes((ROOT/'tests/fixtures/upstream/SaTokenExceptionHandler.java.txt').read_bytes());return path
    def test_exact_pinned_handler_only_changes_auth_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);path=self.fixture(root);before=path.read_text(encoding='utf-8')
            native.redact_auth_failure_log(root);after=path.read_text(encoding='utf-8')
            self.assertEqual(before.count('e.getMessage()')-after.count('e.getMessage()'),1)
            self.assertIn('Authentication rejected uri={} exception={}',after)
            self.assertEqual([line for line in before.splitlines() if 'return R.' in line],
                             [line for line in after.splitlines() if 'return R.' in line])
    def test_preimage_drift_rejected_without_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);path=self.fixture(root);path.write_bytes(path.read_bytes()+b'\n')
            before=path.read_bytes()
            with self.assertRaises(native.BuildError):native.redact_auth_failure_log(root)
            self.assertEqual(path.read_bytes(),before)
