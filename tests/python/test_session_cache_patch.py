import pathlib,sys,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'));import native
REL='ruoyi-common/ruoyi-common-satoken/src/main/java/org/dromara/common/satoken/core/dao/PlusSaTokenDao.java'
class SessionCachePatchTests(unittest.TestCase):
    def fixture(self,root):
        p=root/REL;p.parent.mkdir(parents=True);p.write_bytes((ROOT/'tests/fixtures/upstream/PlusSaTokenDao.java.txt').read_bytes());return p
    def test_pinned_delete_paths_evict_without_changing_cache_policy(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);p=self.fixture(root);before=p.read_text(encoding='utf-8')
            native.invalidate_deleted_auth_cache(root);after=p.read_text(encoding='utf-8')
            self.assertEqual(after.count('CAFFEINE.invalidate(key);'),before.count('CAFFEINE.invalidate(key);')+2)
            self.assertEqual(after.count('finally {'),2)
            self.assertIn('.expireAfterWrite(5, TimeUnit.SECONDS)',after)
            self.assertIn('.maximumSize(1000)',after)
    def test_preimage_drift_cannot_be_silently_patched(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);p=self.fixture(root);p.write_bytes(p.read_bytes()+b'\n');before=p.read_bytes()
            with self.assertRaises(native.BuildError):native.invalidate_deleted_auth_cache(root)
            self.assertEqual(p.read_bytes(),before)
