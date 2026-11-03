import hashlib,json,pathlib,subprocess,sys,tempfile,unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from native import apply_compatibility_patch,BuildError,git_blob

class CompatibilityPatchRegression(unittest.TestCase):
    def test_real_patch_applies_and_rejects_dirty_source_or_changed_patch(self):
        with tempfile.TemporaryDirectory(prefix='fcp-compat-') as directory:
            base=pathlib.Path(directory);repo=base/'project';repo.mkdir();bundle=base/'bundle';bundle.mkdir()
            def git(*args):return subprocess.check_output(['git','-C',str(repo),*args])
            git('init','-b','fixture');git('config','core.autocrlf','false')
            file=repo/'src/selected.ts';file.parent.mkdir()
            before=b"export const selected = 'before';\n";after=b"export const selected = 'after';\n"
            file.write_bytes(before);git('add','src/selected.ts')
            git('-c','user.name=FCP fixture','-c','user.email=fixture@invalid','commit','-m','fixture')
            commit=git('rev-parse','HEAD').decode().strip()
            file.write_bytes(after);patch=git('diff','--','src/selected.ts');file.write_bytes(before)
            (bundle/'types.patch').write_bytes(patch)
            manifest={'format':1,'upstreamCommit':commit,'patchFile':'types.patch','sha256':hashlib.sha256(patch).hexdigest(),'preimages':{'src/selected.ts':git_blob(before)}}
            (bundle/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
            apply_compatibility_patch(repo,bundle,commit)
            self.assertEqual(file.read_bytes(),after)
            with self.assertRaises(BuildError):apply_compatibility_patch(repo,bundle,commit)
            self.assertEqual(file.read_bytes(),after)
            file.write_bytes(before)
            (bundle/'types.patch').write_bytes(patch+b'\n')
            with self.assertRaises(BuildError):apply_compatibility_patch(repo,bundle,commit)
            self.assertEqual(file.read_bytes(),before)

if __name__=='__main__':unittest.main()
