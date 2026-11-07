"""Remove only a temporary private tree created by this CI invocation.

The native installer deliberately publishes read-only package directories. Cleanup runs
only after the native JVM has stopped, restores owner access on this run's directories,
and unlinks entries relative to held directory descriptors. It never chmods files or
symlinks, follows symlinks, crosses filesystems, or accepts an arbitrary cleanup path.
This is disposable test-fixture cleanup, not a production permission repair utility.
"""
from __future__ import annotations
import errno
import os
from pathlib import Path
import stat
import tempfile


class CleanupRefused(RuntimeError):
    pass


class OwnedDisposableDirectory:
    """An ownership token for one freshly-created directory, not a path-based remover."""
    def __init__(self, path: Path, parent_fd: int, root_fd: int):
        self.path = path
        self._parent_fd = parent_fd
        self._root_fd = root_fd
        self._identity = os.fstat(root_fd)
        self._uid = os.geteuid()
        self._result = None
        self._counts = dict(directoriesRemoved=0, entriesUnlinked=0, directoriesMadeWritable=0)

    @classmethod
    def create(cls, parent: Path | None = None):
        if os.name != 'posix' or not hasattr(os, 'O_NOFOLLOW') or os.scandir not in os.supports_fd:
            raise CleanupRefused('Safe descriptor-relative cleanup requires POSIX support')
        path = Path(tempfile.mkdtemp(prefix='fcp-native-ci-', dir=parent)).absolute()
        parent_fd = root_fd = None
        try:
            parent_fd = os.open(path.parent, cls._flags())
            root_fd = os.open(path.name, cls._flags(), dir_fd=parent_fd)
            created = cls(path, parent_fd, root_fd)
            created._same_entry(parent_fd, path.name, created._identity)
            return created
        except BaseException:
            if root_fd is not None:
                os.close(root_fd)
            if parent_fd is not None:
                os.close(parent_fd)
            # No payload was started or written yet. rmdir never follows a symlink.
            try:
                os.rmdir(path)
            except OSError:
                pass
            raise

    @staticmethod
    def _flags():
        return os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, 'O_CLOEXEC', 0)

    def _validate(self, info):
        if info.st_uid != self._uid:
            raise CleanupRefused('Refusing an entry not owned by this CI user')
        if info.st_dev != self._identity.st_dev:
            raise CleanupRefused('Refusing to cross a filesystem boundary')

    def _same_entry(self, parent_fd, name, expected):
        current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (current.st_dev, current.st_ino, stat.S_IFMT(current.st_mode)) != (
                expected.st_dev, expected.st_ino, stat.S_IFMT(expected.st_mode)):
            raise CleanupRefused('Disposable directory identity changed; tree retained')
        self._validate(current)

    def _remove_contents(self, directory_fd, parent_fd, name):
        current = os.fstat(directory_fd)
        self._validate(current)
        if not stat.S_ISDIR(current.st_mode):
            raise CleanupRefused('Disposable directory handle is not a directory')
        self._same_entry(parent_fd, name, current)
        mode = stat.S_IMODE(current.st_mode)
        if mode & stat.S_IRWXU != stat.S_IRWXU:
            # Held directory fd cannot become a symlink. Files/hardlinks are never chmodded.
            os.fchmod(directory_fd, mode | stat.S_IRWXU)
            self._counts['directoriesMadeWritable'] += 1
        with os.scandir(directory_fd) as entries:
            names = [entry.name for entry in entries]
        for entry_name in names:
            info = os.stat(entry_name, dir_fd=directory_fd, follow_symlinks=False)
            self._validate(info)
            if stat.S_ISDIR(info.st_mode):
                child_fd = os.open(entry_name, self._flags(), dir_fd=directory_fd)
                try:
                    child = os.fstat(child_fd)
                    if (child.st_dev, child.st_ino) != (info.st_dev, info.st_ino):
                        raise CleanupRefused('Child directory identity changed; tree retained')
                    self._remove_contents(child_fd, directory_fd, entry_name)
                    self._same_entry(directory_fd, entry_name, child)
                    os.rmdir(entry_name, dir_fd=directory_fd)
                    self._counts['directoriesRemoved'] += 1
                finally:
                    os.close(child_fd)
            else:
                # unlink removes a symlink itself; it cannot follow its target. A hardlink
                # also loses only this owned directory entry, without changing file mode.
                os.unlink(entry_name, dir_fd=directory_fd)
                self._counts['entriesUnlinked'] += 1

    def cleanup(self, *, process_stopped: bool):
        """Return a diagnostic result and never mask the native gate's original failure.

        A directory with no readable access may fail closed: do not repair it by pathname
        chmod, which could follow a replacement symlink. Installer directories are 0555.
        """
        if self._result is not None:
            return dict(self._result)
        try:
            if not process_stopped:
                self._result = dict(status='BLOCKED', reason='Native JVM still running; private tree retained')
            else:
                self._same_entry(self._parent_fd, self.path.name, self._identity)
                self._remove_contents(self._root_fd, self._parent_fd, self.path.name)
                self._same_entry(self._parent_fd, self.path.name, self._identity)
                os.rmdir(self.path.name, dir_fd=self._parent_fd)
                self._counts['directoriesRemoved'] += 1
                self._result = dict(status='PASS', **self._counts)
        except Exception as error:
            # Do not disclose exception filenames: private paths or hostile entry names
            # can contain sensitive strings. The original gate error stays in its report.
            self._result = dict(status='FAIL', errorType=type(error).__name__, **self._counts)
            if isinstance(error, CleanupRefused):
                self._result['reason'] = str(error)
            elif isinstance(error, OSError):
                self._result['errno'] = error.errno
                self._result['reason'] = errno.errorcode.get(error.errno, 'OS_ERROR')
            else:
                self._result['reason'] = 'Disposable cleanup failed; private remainder retained'
        finally:
            for fd in (self._root_fd, self._parent_fd):
                try:
                    os.close(fd)
                except OSError:
                    pass
        return dict(self._result)


def record_private_cleanup(report: dict, owned: OwnedDisposableDirectory | None, *, process_stopped: bool):
    """Attach cleanup evidence without overwriting a prior FAIL/BLOCKED status or error."""
    if owned is None:
        return
    try:
        result = owned.cleanup(process_stopped=process_stopped)
    except Exception as error:
        # Belt-and-braces boundary: unexpected cleanup implementation failures must not
        # hide a real acceptance failure or prevent the final JSON summary being written.
        result = dict(status='FAIL', errorType=type(error).__name__, reason='Disposable cleanup failed')
    report['privateCleanup'] = result
    if result.get('status') != 'PASS' and report.get('status') == 'PASS':
        report['status'] = 'FAIL'
