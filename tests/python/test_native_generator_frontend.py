"""Component-only patch tests: real pinned page fixture, mocked Git identity.

No browser, upstream checkout, database or network is required. The fixture is
plus-ui d0d451967676707021b9857df529c395b27e90a7 src/views/tool/gen/index.vue
(MIT); its exact SHA-256 is enforced by the production patcher.
"""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import native_generator_frontend as generator


class GeneratorFrontendPatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name) / 'admin'
        self.page = self.project / generator.TARGET
        self.page.parent.mkdir(parents=True)
        self.original = (ROOT / 'tests/fixtures/upstream/GeneratorIndex.vue.txt').read_bytes()
        self.page.write_bytes(self.original)
        self.git = patch.object(generator.subprocess, 'check_output', return_value=generator.PIN + '\n')
        self.git.start()
        self.addCleanup(self.git.stop)

    def test_pinned_page_integrates_and_keeps_download(self):
        self.assertEqual(hashlib.sha256(self.original).hexdigest(), generator.PREIMAGE)
        generator.apply_generator_frontend(self.project)
        content = self.page.read_text()
        self.assertIn('@click="handleGenTable()">下载</el-button>', content)
        self.assertIn('handleGenTable(scope.row)', content)
        self.assertIn('generatorApplyRef?.show(ids)', content)
        self.assertIn('generatorApplyRef?.show([scope.row.tableId])', content)
        for name in ('GeneratorApply.vue', 'api.ts', 'types.ts'):
            self.assertEqual((self.project / 'src/fcp/generator' / name).read_bytes(),
                             (ROOT / 'integration/plus-ui/src/fcp/generator' / name).read_bytes())

    def test_exact_compatibility_postimage_integrates_without_relaxing_drift(self):
        # This is the exact gen-page hunk in the separately verified compatibility bundle.
        before = "const dateRange = ref<[DateModelType, DateModelType]>(['', '']);"
        after = "const dateRange = ref<[string, string]>(['', '']);"
        self.assertEqual(self.original.decode().count(before), 1)
        compatible = self.original.decode().replace(before, after).encode()
        self.assertEqual(hashlib.sha256(compatible).hexdigest(), generator.COMPAT_PREIMAGE)
        self.page.write_bytes(compatible)
        generator.apply_generator_frontend(self.project)
        self.assertIn(after, self.page.read_text())
        self.assertIn('generatorApplyRef?.show(ids)', self.page.read_text())
        self.page.write_bytes(compatible + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'preimage drift'):
            generator.apply_generator_frontend(self.project)

    def test_repeat_and_drift_refused_without_mutation(self):
        generator.apply_generator_frontend(self.project)
        patched = self.page.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'preimage drift'):
            generator.apply_generator_frontend(self.project)
        self.assertEqual(self.page.read_bytes(), patched)
        self.page.write_bytes(self.original + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'preimage drift'):
            generator.apply_generator_frontend(self.project)
        self.assertEqual(self.page.read_bytes(), self.original + b'\n')

    def test_overlay_collision_refused_before_any_write(self):
        overlay = self.project / 'src/fcp/generator'
        overlay.mkdir(parents=True)
        (overlay / 'types.ts').write_text('user edit')
        with self.assertRaisesRegex(RuntimeError, 'overwrite existing changes'):
            generator.apply_generator_frontend(self.project)
        self.assertEqual((overlay / 'types.ts').read_text(), 'user edit')
        self.assertFalse((overlay / 'api.ts').exists())
        self.assertFalse((overlay / 'GeneratorApply.vue').exists())
        self.assertEqual(self.page.read_bytes(), self.original)

    def test_symlink_destination_refused_without_write(self):
        outside = Path(self.temp.name) / 'outside'
        outside.mkdir()
        (self.project / 'src/fcp').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'symlink refused'):
            generator.apply_generator_frontend(self.project)
        self.assertEqual(list(outside.iterdir()), [])
        self.assertEqual(self.page.read_bytes(), self.original)

    def test_wrong_git_identity_refused(self):
        with patch.object(generator.subprocess, 'check_output', return_value='unrecognized\n'):
            with self.assertRaisesRegex(RuntimeError, 'commit drift'):
                generator.apply_generator_frontend(self.project)
        self.assertEqual(self.page.read_bytes(), self.original)

    def test_duplicate_anchor_refused_even_with_matching_test_digest(self):
        content = self.original + b'\n<!-- <el-tooltip content="' + '生成代码'.encode() + b'" placement="top"> -->'
        self.page.write_bytes(content)
        with patch.object(generator, 'PREIMAGE', hashlib.sha256(content).hexdigest()):
            with self.assertRaisesRegex(RuntimeError, 'single-anchor drift'):
                generator.apply_generator_frontend(self.project)
        self.assertEqual(self.page.read_bytes(), content)
        self.assertFalse((self.project / 'src/fcp').exists())


if __name__ == '__main__':
    unittest.main()
