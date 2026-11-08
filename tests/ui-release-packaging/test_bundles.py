"""Synthetic static dist packaging only; no claim of native Vue/browser acceptance."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('bundle',ROOT/'tools/ui_release_bundle.py')
bundle=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundle)

class Packaging(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.base=Path(self.temp.name);self.dist=self.base/'dist';self.dist.mkdir();self.output=self.base/'out'
        (self.dist/'index.html').write_bytes(b'<html><head></head><body>synthetic</body></html>')
        (self.dist/'assets').mkdir();(self.dist/'assets/app.js').write_bytes(b'export const version="synthetic";')
    def tearDown(self):self.temp.cleanup()
    def build(self,shell='admin'):return bundle.build_bundle(self.dist,self.output,shell)
    def test_deterministic_hash_and_unchanged_bytes(self):
        first=self.build();m=first['manifest'];identity={k:v for k,v in m.items() if k!='digest'}
        self.assertEqual(hashlib.sha256(bundle.canonical(identity)).hexdigest(),m['digest'])
        path=Path(first['bundlePath']);self.assertEqual(path,self.output/m['digest']/'admin')
        self.assertEqual((path/'index.html').read_bytes(),(self.dist/'index.html').read_bytes())
        self.assertEqual(first,self.build());self.assertEqual(bundle.verify_bundle(path,m['digest']),m)
    def test_precompressed_index_not_published_before_base_injection(self):
        (self.dist/'index.html.gz').write_bytes(b'compressed-old-index')
        (self.dist/'index.html.br').write_bytes(b'compressed-old-index')
        result=self.build();path=Path(result['bundlePath'])
        self.assertNotIn('index.html.gz',result['manifest']['files'])
        self.assertFalse((path/'index.html.gz').exists())
        (path/'index.html.gz').write_bytes(b'extra-index')
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
    def test_public_license_and_declarations_preserved(self):
        for name in ['assets/license.md','assets/tinymce.d.ts']:
            (self.dist/name).write_bytes(b'public upstream distribution asset')
        result=self.build();path=Path(result['bundlePath'])
        for name in ['assets/license.md','assets/tinymce.d.ts']:
            self.assertEqual((path/name).read_bytes(),(self.dist/name).read_bytes())
    def test_shell_and_contract_change_identity(self):
        self.assertNotEqual(self.build()['manifest']['digest'],self.build('teacher')['manifest']['digest'])
        self.assertNotEqual(self.build()['manifest']['digest'],bundle.build_bundle(self.dist,self.output,'admin','fcp.host/2')['manifest']['digest'])
    def test_content_change_new_bundle_old_retained(self):
        a=self.build();(self.dist/'assets/app.js').write_bytes(b'v2');b=self.build()
        self.assertNotEqual(a['bundlePath'],b['bundlePath']);self.assertTrue(Path(a['bundlePath']).exists())
    def test_tamper_is_not_overwritten(self):
        result=self.build();path=Path(result['bundlePath']);(path/'assets/app.js').write_bytes(b'tampered')
        with self.assertRaises(bundle.BundleError):self.build()
        self.assertEqual((path/'assets/app.js').read_bytes(),b'tampered')
    def test_missing_and_extra_assets_rejected(self):
        path=Path(self.build()['bundlePath']);(path/'extra.js').write_bytes(b'x')
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
        (path/'extra.js').unlink();(path/'assets/app.js').unlink()
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
    def test_missing_index_and_empty_input(self):
        (self.dist/'index.html').unlink()
        with self.assertRaises(bundle.BundleError):self.build()
    def test_symlink_file_directory_and_source_ancestor(self):
        (self.dist/'linked.js').symlink_to(self.dist/'assets/app.js')
        with self.assertRaises(bundle.BundleError):self.build()
        (self.dist/'linked.js').unlink();(self.dist/'linked').symlink_to(self.dist/'assets',target_is_directory=True)
        with self.assertRaises(bundle.BundleError):self.build()
        (self.dist/'linked').unlink();(self.base/'alias').symlink_to(self.dist,target_is_directory=True)
        with self.assertRaises(bundle.BundleError):bundle.build_bundle(self.base/'alias',self.output,'admin')
    def test_destination_symlink_and_nested_roots(self):
        self.output.symlink_to(self.dist,target_is_directory=True)
        with self.assertRaises(bundle.BundleError):self.build()
        self.output.unlink()
        with self.assertRaises(bundle.BundleError):bundle.build_bundle(self.dist,self.dist/'out','admin')
        self.assertFalse((self.dist/'out').exists())
    def test_reserved_hidden_unsupported_and_unsafe_names(self):
        for name in ['manifest.json','.env','source.js.map','source.js.map.gz','asset%.js','bad\\file.js','unsafe.sh','source.ts']:
            with self.subTest(name=name):
                p=self.dist/name;p.write_bytes(b'no')
                with self.assertRaises(bundle.BundleError):self.build()
                p.unlink()
    def test_per_file_total_and_count_limits(self):
        for setting,limit in [('MAX_FILE_BYTES',1),('MAX_TOTAL_BYTES',1),('MAX_FILES',1)]:
            with self.subTest(setting=setting),patch.object(bundle,setting,limit):
                with self.assertRaises(bundle.BundleError):self.build()
    def test_manifest_tamper_expected_digest_and_boolean_size(self):
        path=Path(self.build()['bundlePath']);m=json.loads((path/'manifest.json').read_text())
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path,'0'*64)
        m['files']['index.html']['sizeBytes']=True
        (path/'manifest.json').write_text(json.dumps(m))
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
    def test_traversal_manifest_rejected_even_rehashed(self):
        path=Path(self.build()['bundlePath']);m=json.loads((path/'manifest.json').read_text());m['files']['../secret.js']=m['files']['assets/app.js']
        m['digest']=hashlib.sha256(bundle.canonical({k:v for k,v in m.items() if k!='digest'})).hexdigest();(path/'manifest.json').write_text(json.dumps(m))
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
    def test_duplicate_manifest_keys_rejected(self):
        path=Path(self.build()['bundlePath']);original=(path/'manifest.json').read_text();(path/'manifest.json').write_text(original.replace('{','{"format":1,',1))
        with self.assertRaises(bundle.BundleError):bundle.verify_bundle(path)
    def test_compressed_static_assets_are_preserved(self):
        import gzip
        raw=gzip.compress((self.dist/'assets/app.js').read_bytes(),mtime=0)
        (self.dist/'assets/app.js.gz').write_bytes(raw)
        target=Path(self.build()['bundlePath'])
        self.assertEqual((target/'assets/app.js.gz').read_bytes(),raw)
    def test_cli_build_and_verify(self):
        result=subprocess.run([sys.executable,str(ROOT/'tools/ui_release_bundle.py'),'build','--dist',str(self.dist),'--root',str(self.output),'--shell','teacher'],check=True,text=True,capture_output=True)
        response=json.loads(result.stdout)
        verify=subprocess.run([sys.executable,str(ROOT/'tools/ui_release_bundle.py'),'verify',response['bundlePath']],check=True,text=True,capture_output=True)
        self.assertEqual(json.loads(verify.stdout),response['manifest'])
    def test_concurrent_publish_same_bytes_is_idempotent(self):
        command=[sys.executable,str(ROOT/'tools/ui_release_bundle.py'),'build','--dist',str(self.dist),'--root',str(self.output),'--shell','admin']
        processes=[subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True) for _ in range(4)]
        results=[]
        for process in processes:
            stdout,stderr=process.communicate(timeout=15);self.assertEqual(process.returncode,0,stderr);results.append(json.loads(stdout))
        self.assertTrue(all(r==results[0] for r in results));bundle.verify_bundle(Path(results[0]['bundlePath']))
    def test_existing_empty_target_not_replaced(self):
        result=self.build();target=Path(result['bundlePath'])
        for p in sorted(target.rglob('*'),reverse=True):
            p.rmdir() if p.is_dir() else p.unlink()
        with self.assertRaises(bundle.BundleError):self.build()
        self.assertEqual(list(target.iterdir()),[])

if __name__=='__main__':unittest.main()
