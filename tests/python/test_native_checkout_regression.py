import pathlib, subprocess, sys, tempfile, unittest
from unittest.mock import patch

ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
import native

class NativeCheckoutRegression(unittest.TestCase):
    def test_local_source_existing_adaptation_branch_is_preserved(self):
        with tempfile.TemporaryDirectory(prefix='fcp-fetch-') as directory:
            base=pathlib.Path(directory)
            source=base/'local/backend';source.mkdir(parents=True)
            def git(*args):
                return subprocess.check_output(['git','-C',str(source),*args],text=True).strip()
            git('init','-b','fcp/production-hardening-0.5.0')
            (source/'tracked.txt').write_text('pinned\n',encoding='utf-8')
            git('add','tracked.txt')
            git('-c','user.name=FCP fixture','-c','user.email=fixture@invalid','commit','-m','fixture')
            commit=git('rev-parse','HEAD')
            (source/'tracked.txt').write_text('uncommitted adaptation\n',encoding='utf-8')
            with patch.object(native,'PINS',{'backend':{'commit':commit}}), patch.object(native,'BLOBS',{'backend':{}}):
                fetched=native.fetch(base/'work',base/'local')['backend']
            actual=subprocess.check_output(['git','-C',str(fetched),'branch','--show-current'],text=True).strip()
            self.assertTrue(actual.startswith('fcp/production-hardening-0.5.0-'))
            self.assertEqual((fetched/'tracked.txt').read_text(),'pinned\n')
            self.assertEqual(git('branch','--show-current'),'fcp/production-hardening-0.5.0')
            self.assertEqual((source/'tracked.txt').read_text(),'uncommitted adaptation\n')

    def test_locale_addition_targets_route_map_and_refuses_reapplication(self):
        with tempfile.TemporaryDirectory(prefix='fcp-locale-') as directory:
            path=pathlib.Path(directory)/'locale.ts'
            path.write_text("const locale = {\n  route: {\n    login: 'Login'\n  }\n};\n",encoding='utf-8')
            native.add_school_locale(path,'Evaluation','Student evaluation')
            first=path.read_bytes()
            self.assertIn("fcp_school: 'Student evaluation'",first.decode())
            with self.assertRaises(native.BuildError):
                native.add_school_locale(path,'Evaluation','Student evaluation')
            self.assertEqual(path.read_bytes(),first)

if __name__=='__main__':unittest.main()
