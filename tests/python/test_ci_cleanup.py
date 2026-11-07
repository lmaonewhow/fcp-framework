"""Actual temporary-filesystem regressions; no native acceptance claims."""
from __future__ import annotations
import errno
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_cleanup import OwnedDisposableDirectory, record_private_cleanup


@unittest.skipUnless(os.name == 'posix', 'CI cleanup is intentionally POSIX-only')
class DisposableCleanupTest(unittest.TestCase):
    def setUp(self):
        self.parent = Path(tempfile.mkdtemp(prefix='fcp-cleanup-test-'))
        self.addCleanup(shutil.rmtree, self.parent)

    def tree(self):
        owned = OwnedDisposableDirectory.create(self.parent)
        package = owned.path/'runtime-plugins'/'packages'/'digest'/'contents'
        package.mkdir(parents=True)
        file = package/'plugin.json'
        file.write_text('{"synthetic":true}')
        file.chmod(0o444)
        package.chmod(0o555)
        return owned, package, file

    def test_readonly_installer_tree_is_removed_with_real_filesystem_calls(self):
        owned, package, file = self.tree()
        root = owned.path
        result = owned.cleanup(process_stopped=True)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['directoriesMadeWritable'], 1)
        self.assertEqual(result['entriesUnlinked'], 1)
        self.assertFalse(root.exists())
        # Calling again must not open/reinterpret an arbitrary reused pathname.
        root.mkdir()
        (root/'new-owner-data').write_text('retain')
        self.assertEqual(owned.cleanup(process_stopped=True), result)
        self.assertTrue((root/'new-owner-data').exists())

    @unittest.skipIf(os.geteuid() == 0, 'Root bypasses the original 0555 unlink failure')
    def test_original_rmtree_failure_is_reproduced_before_safe_cleanup(self):
        owned, package, file = self.tree()
        with self.assertRaises(PermissionError) as failure:
            shutil.rmtree(owned.path)
        self.assertEqual(failure.exception.errno, errno.EACCES)
        self.assertTrue(file.exists())
        self.assertEqual(owned.cleanup(process_stopped=True)['status'], 'PASS')

    def test_outside_symlink_targets_and_hardlink_modes_are_not_changed(self):
        owned = OwnedDisposableDirectory.create(self.parent)
        outside = self.parent/'outside'
        outside.mkdir()
        secret = outside/'keep'
        secret.write_text('untouched')
        secret.chmod(0o444)
        outside_mode = stat.S_IMODE(outside.stat().st_mode)
        (owned.path/'directory-link').symlink_to(outside, target_is_directory=True)
        (owned.path/'file-link').symlink_to(secret)
        (owned.path/'loop').symlink_to(owned.path, target_is_directory=True)
        os.link(secret, owned.path/'hardlink')
        self.assertEqual(owned.cleanup(process_stopped=True)['status'], 'PASS')
        self.assertEqual(secret.read_text(), 'untouched')
        self.assertEqual(stat.S_IMODE(secret.stat().st_mode), 0o444)
        self.assertEqual(stat.S_IMODE(outside.stat().st_mode), outside_mode)
        self.assertEqual(secret.stat().st_nlink, 1)

    def test_replaced_root_symlink_is_refused_without_touching_either_tree(self):
        owned = OwnedDisposableDirectory.create(self.parent)
        (owned.path/'keep-original').write_text('original')
        relocated = self.parent/'relocated'
        owned.path.rename(relocated)
        outside = self.parent/'outside'
        outside.mkdir()
        (outside/'keep-outside').write_text('outside')
        owned.path.symlink_to(outside, target_is_directory=True)
        result = owned.cleanup(process_stopped=True)
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(result['errorType'], 'CleanupRefused')
        self.assertTrue(owned.path.is_symlink())
        self.assertEqual((relocated/'keep-original').read_text(), 'original')
        self.assertEqual((outside/'keep-outside').read_text(), 'outside')

    def test_child_swapped_for_symlink_during_open_is_never_followed(self):
        owned = OwnedDisposableDirectory.create(self.parent)
        child = owned.path/'child'
        child.mkdir()
        outside = self.parent/'outside'
        outside.mkdir()
        secret = outside/'keep'
        secret.write_text('unchanged')
        outside.chmod(0o555)
        real_open = os.open
        swapped = False
        def swap_then_open(path, flags, *args, **kwargs):
            nonlocal swapped
            if path == 'child' and not swapped:
                swapped = True
                child.rename(owned.path/'retained-original')
                child.symlink_to(outside, target_is_directory=True)
            return real_open(path, flags, *args, **kwargs)
        try:
            with patch('ci_cleanup.os.open', side_effect=swap_then_open):
                result = owned.cleanup(process_stopped=True)
            self.assertEqual(result['status'], 'FAIL')
            self.assertTrue(swapped)
            self.assertEqual(secret.read_text(), 'unchanged')
            self.assertEqual(stat.S_IMODE(outside.stat().st_mode), 0o555)
            self.assertTrue(child.is_symlink())
        finally:
            outside.chmod(0o755)

    def test_process_running_refuses_any_permission_change_or_removal(self):
        owned, package, file = self.tree()
        result = owned.cleanup(process_stopped=False)
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertTrue(file.exists())
        self.assertEqual(stat.S_IMODE(package.stat().st_mode), 0o555)
        # Test teardown only, not the production cleanup path.
        package.chmod(0o755)

    def test_unlink_failure_is_recorded_without_secret_filename(self):
        owned = OwnedDisposableDirectory.create(self.parent)
        private = owned.path/'sensitive-filename'
        private.write_text('private')
        with patch('ci_cleanup.os.unlink', side_effect=PermissionError(errno.EACCES, 'private-message', str(private))):
            result = owned.cleanup(process_stopped=True)
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(result['errno'], errno.EACCES)
        self.assertNotIn('sensitive-filename', str(result))
        self.assertNotIn('private-message', str(result))
        self.assertTrue(private.exists())

    def test_file_permissions_are_never_chmodded(self):
        owned, package, file = self.tree()
        real_fchmod = os.fchmod
        kinds = []
        def directory_only(fd, mode):
            kinds.append(stat.S_IFMT(os.fstat(fd).st_mode))
            return real_fchmod(fd, mode)
        with patch('ci_cleanup.os.fchmod', side_effect=directory_only):
            self.assertEqual(owned.cleanup(process_stopped=True)['status'], 'PASS')
        self.assertEqual(kinds, [stat.S_IFDIR])


class CleanupReportTest(unittest.TestCase):
    def test_gate_failure_and_blocker_are_preserved_even_if_cleanup_raises(self):
        class Broken:
            def cleanup(self, **kwargs):
                raise PermissionError('do not surface private paths')
        for status in ('FAIL', 'BLOCKED'):
            report = dict(status=status, error='Original native gate failure', nativeChecks=17)
            record_private_cleanup(report, Broken(), process_stopped=True)
            self.assertEqual(report['status'], status)
            self.assertEqual(report['error'], 'Original native gate failure')
            self.assertEqual(report['nativeChecks'], 17)
            self.assertEqual(report['privateCleanup']['status'], 'FAIL')
            self.assertNotIn('private paths', str(report))

    def test_cleanup_failure_turns_an_otherwise_passing_run_into_failure(self):
        class Failed:
            def cleanup(self, **kwargs):
                return dict(status='FAIL', reason='cleanup failed')
        report = dict(status='PASS')
        record_private_cleanup(report, Failed(), process_stopped=True)
        self.assertEqual(report['status'], 'FAIL')
        self.assertNotIn('error', report)
        self.assertEqual(report['privateCleanup']['reason'], 'cleanup failed')

    def test_ci_hook_persists_diagnostics_before_cleanup_and_report_after(self):
        source = (ROOT/'tools/ci_native.py').read_text()
        self.assertNotIn('shutil.rmtree(private_dir)', source)
        self.assertIn('private_cleanup=OwnedDisposableDirectory.create()', source)
        hook = source.index('  record_private_cleanup(report,private_cleanup,')
        self.assertLess(source.index("(output/'native-startup-redacted.log').write_text(text)"), hook)
        self.assertLess(source.index("(output/'ci-native.json').write_text"), hook)
        self.assertGreater(source.rindex("(output/'ci-native.json').write_text"), hook)
        self.assertIn('process_stopped=process is None or process.poll() is not None', source)


if __name__ == '__main__':
    unittest.main()
