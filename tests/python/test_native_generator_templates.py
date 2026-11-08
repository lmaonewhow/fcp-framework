"""Component tests for a pinned native Velocity resource, not native rendering."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import native_generator_templates as generator


class GeneratorTemplateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name)
        self.target = self.project / generator.TARGET
        self.target.parent.mkdir(parents=True)
        self.original = (ROOT / 'tests/fixtures/upstream/GeneratorVueIndex.vue.vm.txt').read_bytes()
        self.target.write_bytes(self.original)
        mock = patch.object(generator.subprocess, 'check_output', return_value=generator.PIN + '\n')
        mock.start()
        self.addCleanup(mock.stop)

    def test_pinned_standard_template_repairs_date_and_checkbox(self):
        self.assertEqual(hashlib.sha256(self.original).hexdigest(), generator.PREIMAGE)
        generator.apply_generator_templates(self.project)
        text = self.target.read_text()
        self.assertIn("const dateRange${AttrName} = ref<[string, string]>(['', '']);", text)
        self.assertNotIn('DateModelType', text)
        self.assertIn('value-format="YYYY-MM-DD HH:mm:ss"', text)
        self.assertIn('const initFormData: ${BusinessName}EditForm', text)
        self.assertIn('reactive<PageData<${BusinessName}EditForm', text)
        self.assertIn("String(record.${column.javaField}).split(',') : []", text)
        self.assertIn(generator.PAYLOAD, text)
        self.assertNotIn(generator.OLD_JOIN, text)
        self.assertIn('await update${BusinessName}(payload)', text)
        self.assertIn('await add${BusinessName}(payload)', text)
        self.assertEqual(list(self.project.rglob('*.vm')), [self.target])

    def test_drift_and_repeat_refuse_without_write(self):
        generator.apply_generator_templates(self.project)
        modified = self.target.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'preimage drift'):
            generator.apply_generator_templates(self.project)
        self.assertEqual(modified, self.target.read_bytes())
        self.target.write_bytes(self.original + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'preimage drift'):
            generator.apply_generator_templates(self.project)
        self.assertEqual(self.target.read_bytes(), self.original + b'\n')

    def test_wrong_commit_refuses(self):
        with patch.object(generator.subprocess, 'check_output', return_value='wrong'):
            with self.assertRaisesRegex(RuntimeError, 'commit drift'):
                generator.apply_generator_templates(self.project)
        self.assertEqual(self.target.read_bytes(), self.original)

    def test_symlink_refuses(self):
        outside = self.project / 'outside'
        outside.write_bytes(self.original)
        self.target.unlink()
        self.target.symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, 'symlink refused'):
            generator.apply_generator_templates(self.project)
        self.assertEqual(outside.read_bytes(), self.original)

    def test_missing_anchor_refuses_before_write(self):
        with patch.object(generator, 'REPLACEMENTS', (('missing anchor', 'wrong'),)):
            with self.assertRaisesRegex(RuntimeError, 'single-anchor drift'):
                generator.apply_generator_templates(self.project)
        self.assertEqual(self.target.read_bytes(), self.original)

    def test_template_is_patched_before_backend_overlay(self):
        source = (ROOT / 'tools/native.py').read_text()
        self.assertLess(source.index('    apply_generator_templates(backend)'),
                        source.index("    java=backend/'ruoyi-admin/src/main/java'"))


if __name__ == '__main__':
    unittest.main()
