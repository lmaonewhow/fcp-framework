"""Classpath packaging guards only; not native or generated-page acceptance."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from native import BuildError, copy_core_resources


class EducationCodegenResourceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / 'core/src/main/resources'
        self.destination = self.root / 'assembled/resources'
        self.renderer = self.root / 'core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java'
        self.renderer.parent.mkdir(parents=True)
        self.renderer.write_text('// unit source marker, not compilation\n')

    def test_enabled_generator_requires_its_classpath_data(self):
        with self.assertRaisesRegex(BuildError, 'required'):
            copy_core_resources(self.root, self.destination)

    def test_exact_bytes_copy_and_upstream_collision_refuses(self):
        template = self.source / 'education-generator/templates/foundation/Workspace.vue.tpl'
        template.parent.mkdir(parents=True)
        template.write_bytes(b'unit template\n')
        copy_core_resources(self.root, self.destination)
        self.assertEqual((self.destination / template.relative_to(self.source)).read_bytes(), template.read_bytes())
        with self.assertRaisesRegex(BuildError, 'overwrite'):
            copy_core_resources(self.root, self.destination)

    def test_root_and_nested_symlinks_are_rejected(self):
        outside = self.root / 'outside'
        (outside / 'education-generator').mkdir(parents=True)
        self.source.parent.mkdir(parents=True, exist_ok=True)
        self.source.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(BuildError, 'symlink'):
            copy_core_resources(self.root, self.destination)
        self.source.unlink()
        (self.source / 'education-generator').mkdir(parents=True)
        (self.source / 'education-generator/linked').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(BuildError, 'symlink'):
            copy_core_resources(self.root, self.destination)

    def test_older_source_without_generator_does_not_require_new_data(self):
        self.renderer.unlink()
        copy_core_resources(self.root, self.destination)
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()
