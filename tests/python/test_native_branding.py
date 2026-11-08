import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
import native_branding as branding


class BrandingTests(unittest.TestCase):
    def test_reviewed_manifest_and_patch_integrity(self):
        bundle = ROOT/'integration/branding'
        manifest = json.loads((bundle/'manifest.json').read_text())
        pins = json.loads((ROOT/'upstreams.lock.json').read_text())['upstreams']
        for name, entry in manifest['upstreams'].items():
            self.assertEqual(entry['commit'], pins[name]['commit'])
            data = (bundle/f'{name}.patch').read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry['patchSha256'])
            self.assertNotIn('LICENSE', entry['files'])
            self.assertIn('public/third-party-notices.txt', entry['files']) if name != 'backend' else None
            paths = subprocess.check_output(['git','apply','--numstat',str(bundle/f'{name}.patch')], text=True)
            self.assertEqual({line.split('\t',2)[2] for line in paths.splitlines()},set(entry['files']))

    def test_two_supported_generator_preimages_only(self):
        self.assertEqual(len(branding.GENERATOR_PREIMAGES),2)
        self.assertEqual(len(branding.GENERATOR_POSTIMAGES),2)
        for value in branding.GENERATOR_PREIMAGES | branding.GENERATOR_POSTIMAGES:
            self.assertRegex(value,r'^[a-f0-9]{64}$')

    def test_generator_renaming_runs_after_native_integration(self):
        source=(ROOT/'tools/native.py').read_text()
        self.assertLess(source.index('apply_branding(projects)'),source.index("copy_files(ROOT/f'integration/{overlay}/src'"))
        self.assertGreater(source.index('apply_generator_branding(admin)'),source.index("copy_files(ROOT/f'integration/{overlay}/src'"))
        self.assertIn('<title>智慧云校</title>',source)

    def test_failure_does_not_write_preceding_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); bundle=root/'integration/branding';bundle.mkdir(parents=True)
            projects={};pins={};manifest={'format':1,'upstreams':{}}
            for name in ('backend','admin','teacher'):
                project=root/name;project.mkdir(); projects[name]=project
                subprocess.run(['git','init','-q'],cwd=project,check=True)
                subprocess.run(['git','-c','user.name=Test','-c','user.email=test@example.invalid','commit','--allow-empty','-qm','fixture'],cwd=project,check=True)
                commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip();pins[name]={'commit':commit}
                target=project/'title.txt';target.write_text('old\n')
                data=b'diff --git a/title.txt b/title.txt\n--- a/title.txt\n+++ b/title.txt\n@@ -1 +1 @@\n-old\n+new\n'
                (bundle/f'{name}.patch').write_bytes(data)
                manifest['upstreams'][name]={'commit':commit,'patchSha256':hashlib.sha256(data).hexdigest(),'files':{'title.txt':{'before':hashlib.sha256(b'old\n').hexdigest(),'after':hashlib.sha256(b'new\n').hexdigest()}}}
            (root/'upstreams.lock.json').write_text(json.dumps({'upstreams':pins}))
            (bundle/'manifest.json').write_text(json.dumps(manifest))
            (projects['teacher']/'title.txt').write_text('changed\n')
            with patch.object(branding,'ROOT',root),self.assertRaisesRegex(RuntimeError,'preimage drift'):
                branding.apply_branding(projects)
            self.assertEqual((projects['admin']/'title.txt').read_text(),'old\n')
            self.assertEqual((projects['backend']/'title.txt').read_text(),'old\n')
            (projects['teacher']/'title.txt').write_text('old\n')
            with patch.object(branding,'ROOT',root):branding.apply_branding(projects)
            self.assertTrue(all((p/'title.txt').read_text()=='new\n' for p in projects.values()))
            with patch.object(branding,'ROOT',root),self.assertRaisesRegex(RuntimeError,'preimage drift'):
                branding.apply_branding(projects)

    def test_real_authentication_not_replaced(self):
        changes=(ROOT/'integration/branding/admin.patch').read_text()+(ROOT/'integration/branding/teacher.patch').read_text()
        self.assertIn("+  username: ''",changes)
        self.assertIn("+  password: ''",changes)
        for name in ('admin','teacher'):
            entry=json.loads((ROOT/'integration/branding/manifest.json').read_text())['upstreams'][name]
            self.assertFalse(any('api/login' in path or 'service/api/auth' in path or 'utils/auth' in path for path in entry['files']))
        self.assertNotIn('skipTests',changes)

if __name__=='__main__':unittest.main()
