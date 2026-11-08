"""Local packaging regression tests, NOT genuine native runtime acceptance."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'tools'))
from native import BuildError
from native_generated_build import embed_admin, tree_files, verify_generated, verify_jar, sanitized_log, binding_digest

class GeneratedPackagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
    def write(self, relative, content='content'):
        path = self.root/relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path
    def assets(self):
        self.write('dist/index.html', '<html><head></head><script src="./assets/new.js"></script></html>')
        self.write('dist/assets/new.js', 'new generated UI')
        self.write('dist/index.html.gz', 'stale compressed entry')
        for name in ('index.html', 'release-loader.mjs', 'release-trace.mjs'):
            self.write('static/admin/'+name, 'verified '+name)
        self.write('static/admin/assets/old.js', 'old')
        self.write('static/teacher/index.html', 'teacher unchanged')
    def test_embed_preserves_security_and_teacher_removes_stale_assets(self):
        self.assets()
        teacher = tree_files(self.root/'static/teacher')
        embed_admin(self.root/'dist', self.root/'static')
        self.assertEqual(tree_files(self.root/'static/teacher'), teacher)
        self.assertEqual((self.root/'static/admin/index.html').read_text(), 'verified index.html')
        self.assertEqual((self.root/'static/admin/release-loader.mjs').read_text(), 'verified release-loader.mjs')
        self.assertFalse((self.root/'static/admin/assets/old.js').exists())
        self.assertFalse((self.root/'static/admin/index.html.gz').exists())
        self.assertIn('<base href="/admin/">', (self.root/'static/admin/embedded-index.html').read_text())
    def test_dist_cannot_shadow_launcher(self):
        self.assets()
        self.write('dist/release-loader.mjs', 'override')
        before = tree_files(self.root/'static')
        with self.assertRaises(BuildError): embed_admin(self.root/'dist', self.root/'static')
        self.assertEqual(tree_files(self.root/'static'), before)
    def test_generated_files_require_exact_bytes_and_safe_paths(self):
        java = self.write('backend/module/src/main/java/org/example/Generated.java', 'class Generated {}')
        vue = self.write('admin/src/views/generated.vue', '<template>real</template>')
        projects = {name: self.root/name for name in ('backend', 'admin')}
        files = {'backend/module/src/main/java/org/example/Generated.java': hashlib.sha256(java.read_bytes()).hexdigest(), 'admin/src/views/generated.vue': hashlib.sha256(vue.read_bytes()).hexdigest()}
        self.assertEqual(verify_generated(projects, files), files)
        java.write_text('changed')
        with self.assertRaises(BuildError): verify_generated(projects, files)
        with self.assertRaises(BuildError): verify_generated(projects, dict(files, **{'backend/../escape.java': '0'*64}))
    def make_jar(self, binding, extra=None):
        jar = self.root/'test.jar'
        files = {'META-INF/MANIFEST.MF': 'Start-Class: org.dromara.DromaraApplication', 'BOOT-INF/classes/fcp/generated-build.json': json.dumps(binding)}
        for name in ('org/dromara/DromaraApplication.class', 'org/dromara/fcp/school/FcpSchoolController.class', 'fcp/runtime/plugins/statistics.py', 'org/example/Generated.class'):
            files['BOOT-INF/classes/'+name] = 'test fixture, not real compiled artifact'
        for path in (self.root/'resources/static').rglob('*'):
            if path.is_file(): files['BOOT-INF/classes/static/'+path.relative_to(self.root/'resources/static').as_posix()] = path.read_text()
        files.update(extra or {})
        with zipfile.ZipFile(jar, 'w') as archive:
            for path, value in files.items(): archive.writestr(path, value)
        return jar
    def test_jar_binding_rejects_changed_stamp_and_stale_assets(self):
        self.write('resources/static/admin/index.html', 'verified')
        binding = {'sourceCommit': 'abc', 'generatedFilesSha256': binding_digest({'one': 'two'})}
        generated = {'backend/module/src/main/java/org/example/Generated.java': 'unused here'}
        jar = self.make_jar(binding)
        verify_jar(jar, binding, self.root/'resources', generated)
        with self.assertRaises(BuildError): verify_jar(jar, dict(binding, sourceCommit='drift'), self.root/'resources', generated)
        jar = self.make_jar(binding, {'BOOT-INF/classes/static/admin/stale.js': 'stale'})
        with self.assertRaises(BuildError): verify_jar(jar, binding, self.root/'resources', generated)
        jar = self.make_jar(binding)
        self.write('resources/static/admin/index.html', 'modified after packaging')
        with self.assertRaises(BuildError): verify_jar(jar, binding, self.root/'resources', generated)
    def test_generated_class_in_real_module_layout(self):
        self.write('resources/static/admin/index.html', 'verified')
        binding = {'sourceCommit': 'abc'}
        generated = {'backend/module/src/main/java/org/example/Other.java': 'digest'}
        jar = self.make_jar(binding)
        with self.assertRaises(BuildError): verify_jar(jar, binding, self.root/'resources', generated)
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as nested:
            nested.writestr('org/example/Other.class', b'test fixture only')
        with zipfile.ZipFile(jar, 'a') as archive:
            archive.writestr('BOOT-INF/lib/ruoyi-system-test.jar', data.getvalue())
        verify_jar(jar, binding, self.root/'resources', generated)
    def test_logs_redact_private_values(self):
        config = self.write('private.properties', 'FCP_DB_PASSWORD=secretpassword\nFCP_JDBC_URL=jdbc:mysql://localhost/private\n')
        projects = {name: self.root/name for name in ('admin', 'teacher')}
        result = sanitized_log('secretpassword jdbc:mysql://localhost/private password=other-secret', config, projects)
        self.assertNotIn('secretpassword', result)
        self.assertNotIn('other-secret', result)
        self.assertNotIn('jdbc:mysql', result)

if __name__ == '__main__': unittest.main()
