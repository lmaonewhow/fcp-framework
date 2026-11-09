#!/usr/bin/env python3
"""CI-only P042 -> guarded CREATE -> exact tracked sources in an isolated clone.

Preparation does not execute SQL, compile the host, or claim browser acceptance.
The separate --native-root invocation builds/runs ONLY the verified generated clone
through the existing native gate. It never enables --generator-business.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROFILE = 'education-p042-v1'
BASELINE = 'design/education-platform/v4.2.1/'
SOURCE_INPUTS = frozenset(BASELINE + name for name in (
    'database/model.json', 'database/sql/P042__fresh_isolated_schema.sql',
    'contracts/data-contracts.json', 'docs/02_数据库完整设计基线.md',
    'docs/03_事务与版本协议.md', 'docs/11_最终核查与4.2.1修订.md',
    'tools/generate_schema.py', 'tools/check_design.py', 'tools/common.py',
)) | {'contracts/education/sources.lock.json', 'contracts/education/catalog.json'}
RESOURCE_INPUTS = frozenset({'classpath:education-generator/rules.json'} | {
    f'classpath:education-generator/templates/{module}/{name}.tpl'
    for module in ('foundation', 'roster') for name in ('Workspace.vue', 'api.ts', 'schema.ts')
})
OUTPUT = Path('reports/education-codegen')
NATIVE_FLAGS = ('--ingestion', '--education', '--roster', '--generator', '--education-generator')


class PrerequisiteError(RuntimeError):
    """A required executable is absent; no execution is claimed."""


def expected_targets():
    result = {}
    for module, view, controller in [('foundation', 'education', 'Foundation'), ('roster', 'roster', 'Roster')]:
        names = [f'vue/views/fcp/{view}/generated/Workspace.vue',
                 *(f'vue/api/fcp/education/generated/{module}.{extension}' for extension in ('schema.ts', 'api.ts', 'provenance.json')),
                 f'main/java/org/dromara/fcp/education/generated/{controller}GeneratedController.java']
        for name in names:
            result[name] = ('integration/plus-ui/src/' + name[4:] if name.startswith('vue/')
                            else 'integration/ruoyi/src/' + name)
    return result


TARGETS = expected_targets()


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def no_links(path: Path):
    """Reject links even in ancestors; resolving first would hide a link boundary."""
    path = Path(path).absolute()
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ValueError('Symbolic links are not accepted for generation evidence')
    return path


def safe_file(root, relative, *, must_exist=True):
    if (not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative
            or any(ord(c) < 32 for c in relative) or relative.startswith('/')
            or any(p in ('', '.', '..') for p in relative.split('/'))):
        raise ValueError('Unsafe repository-relative path')
    root = no_links(Path(root))
    path = no_links(root.joinpath(*PurePosixPath(relative).parts))
    if must_exist and not path.is_file():
        raise ValueError('Required regular file is missing')
    if path.exists() and (not stat.S_ISREG(path.stat().st_mode) or path.stat().st_nlink != 1):
        raise ValueError('Generation evidence must be a single-link regular file')
    return path


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate generation manifest key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True, stderr=subprocess.PIPE).strip()


def clean_tree(root):
    if git(root, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Tracked or unignored source changes invalidate exact-commit generation')


def checked_digest_map(value, *, required=None):
    if not isinstance(value, dict) or not value or any(not isinstance(key, str) or
            not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest) for key, digest in value.items()):
        raise ValueError('Invalid SHA-256 generation manifest')
    if required is not None and set(value) != set(required):
        raise ValueError('Generation manifest path inventory differs from the fixed profile')
    return value


def verify_input_digests(root, values):
    values = checked_digest_map(values, required=SOURCE_INPUTS | RESOURCE_INPUTS)
    for name, digest in values.items():
        if name in SOURCE_INPUTS:
            relative = name
        elif name.startswith('classpath:education-generator/'):
            relative = 'core/src/main/resources/' + name[len('classpath:'):]
        else:
            raise ValueError('Unexpected generation input provenance')
        if sha256(safe_file(root, relative)) != digest:
            raise ValueError('Generation input provenance differs from checkout bytes')
    return values


def validate_manifest(root, manifest):
    if not isinstance(manifest, dict) or manifest.get('profileId') != PROFILE or not manifest.get('version'):
        raise ValueError('Unexpected education generation profile')
    inputs = checked_digest_map(manifest.get('workspaceInputDigests'), required=SOURCE_INPUTS)
    verify_input_digests(root, manifest.get('inputDigests'))
    outputs = checked_digest_map(manifest.get('outputDigests'), required=TARGETS)
    if manifest.get('provenance', {}).get('outputTargets') != TARGETS:
        raise ValueError('Renderer target mapping differs from the fixed generation scope')
    for name, digest in inputs.items():
        if sha256(safe_file(root, name)) != digest:
            raise ValueError('Renderer input digest differs from actual checkout bytes')
    return {TARGETS[name]: digest for name, digest in outputs.items()}


def verify_snapshot(root, outputs):
    checked_digest_map(outputs, required=TARGETS.values())
    for name, digest in outputs.items():
        path = safe_file(root, name)
        stage = git(root, 'ls-files', '--stage', '--', name).split()
        if len(stage) < 4 or stage[0] not in ('100644', '100755') or stage[2] != '0':
            raise ValueError('Every removable generation target must be tracked and regular')
        if sha256(path) != digest:
            raise ValueError('Tracked generated snapshot differs from the actual renderer')
    return dict(outputs)


def verify_create_receipt(manifest, expected):
    plan = manifest.get('plan', {})
    files = plan.get('files', [])
    if not plan.get('applicable') or plan.get('conflicts') or not isinstance(files, list) or len(files) != len(expected):
        raise ValueError('The actual CREATE plan is incomplete or conflicting')
    if {entry.get('path'): entry.get('candidateSha256') for entry in files} != expected:
        raise ValueError('CREATE plan does not bind exactly the verified candidates')
    if any(entry.get('status') != 'create' or entry.get('currentSha256') is not None for entry in files):
        raise ValueError('The prebuild gate requires genuine source CREATE, not adoption or no-op')
    receipt = manifest.get('receipt', {})
    if (receipt.get('status') != 'applied' or receipt.get('planId') != plan.get('planId')
            or not re.fullmatch(r'[a-f0-9-]{36}', receipt.get('operationId', ''))
            or len(receipt.get('files', [])) != len(expected) or set(receipt.get('files', [])) != set(expected)):
        raise ValueError('Missing actual guarded source-application receipt')
    return {key: receipt[key] for key in ('operationId', 'planId', 'status', 'files')}


class GeneratedClone:
    """Capability created only by making a new independent clone in this invocation."""
    def __init__(self, root, commit):
        self.root, self.commit = no_links(root), commit
        self.identity = self.root.stat()

    @classmethod
    def create(cls, source, destination):
        source, destination = no_links(source), no_links(destination)
        clean_tree(source)
        commit = git(source, 'rev-parse', 'HEAD')
        if not re.fullmatch('[a-f0-9]{40}', commit):
            raise ValueError('Exact source commit required')
        if destination.exists() or destination == source or destination in source.parents:
            raise ValueError('A new empty clone destination is required')
        if not destination.parent.is_dir():
            raise ValueError('Clone destination parent must already exist')
        # --no-local transfers objects without hardlinks. No remote checkout, reset,
        # file copying over an existing checkout, or user worktree mutation occurs.
        subprocess.run(['git', 'clone', '--no-local', '--no-checkout', '--', str(source), str(destination)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        subprocess.run(['git', 'checkout', '--detach', commit], cwd=destination, check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if git(destination, 'rev-parse', 'HEAD') != commit:
            raise ValueError('Clone commit drift')
        clean_tree(destination)
        return cls(destination, commit)

    def remove_verified_targets(self, outputs):
        now = self.root.stat()
        if (now.st_dev, now.st_ino) != (self.identity.st_dev, self.identity.st_ino):
            raise ValueError('Owned clone identity changed')
        if git(self.root, 'rev-parse', 'HEAD') != self.commit:
            raise ValueError('Owned clone commit changed')
        clean_tree(self.root)
        verify_snapshot(self.root, outputs)  # Validate the complete batch BEFORE unlinking.
        for name in sorted(outputs):
            safe_file(self.root, name).unlink()


def run_command(args, root, output, name, report, *, timeout=180):
    arguments = list(map(str, args))
    # Persist relative repository paths. No credentials are arguments in this gate.
    public = [value.replace(str(root), '<generated-clone>') for value in arguments]
    unavailable = False
    try:
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('FCP_', 'SPRING_')) and key not in
                       {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS'}}
        process = subprocess.run(arguments, cwd=root, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        raw, code = process.stdout, process.returncode
    except subprocess.TimeoutExpired as error:
        raw = error.stdout or b''
        if isinstance(raw, str): raw = raw.encode()
        code = None
    except OSError as error:
        raw = ('Executable unavailable: ' + type(error).__name__ + '\n').encode()
        code, unavailable = None, True
    log = raw.replace(str(root).encode(), b'<generated-clone>')
    (output / (name + '.log')).write_bytes(log)
    report.setdefault('commands', []).append(dict(name=name, command=public, exitCode=code,
        status='BLOCKED' if unavailable else ('TIMEOUT' if code is None else ('PASS' if code == 0 else 'FAIL')), logSha256=hashlib.sha256(log).hexdigest()))
    if unavailable:
        raise PrerequisiteError('Education code generation executable unavailable: ' + name)
    if code != 0:
        raise RuntimeError('Education code generation command failed: ' + name)


def prepare(source, destination):
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('This orchestration is restricted to its isolated GitHub Actions job')
    source = Path(source).absolute()
    # No destructive action is possible before this function owns a fresh clone.
    clone = GeneratedClone.create(source, Path(destination).absolute())
    root = clone.root
    output = root / OUTPUT
    output.mkdir(parents=True, exist_ok=False)
    report = dict(status='RUNNING', scope='REAL_P042_RENDERER_GUARDED_CREATE_IN_EXACT_COMMIT_CLONE',
                  sourceCommit=clone.commit, profileId=PROFILE, native='NOT_RUN', browser='NOT_RUN',
                  mysql='NOT_RUN', sqlExecution='NOT_RUN', hotReload='NOT_SUPPORTED', production='BLOCKED',
                  sourceWorkspace='isolated exact-commit build clone', commands=[])
    try:
        classes = root / 'build/education-codegen-cli'
        classes.mkdir(parents=True, exist_ok=False)
        sources = [root/'core/src/main/java/fcp/Json.java',
                   *sorted((root/'core/src/main/java/dev/fcp/generator').glob('*.java'))]
        run_command(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', classes, *sources], root, output, 'compile-cli', report)
        command = ['java', '-cp', str(classes) + os.pathsep + str(root/'core/src/main/resources'), 'dev.fcp.generator.EducationGeneratorCli']
        run_command([*command, 'inspect', root, output/'inspected.json'], root, output, 'inspect', report)
        inspected = read_json(output/'inspected.json')
        outputs = validate_manifest(root, inspected)
        verify_snapshot(root, outputs)
        report.update(profileVersion=inspected['version'], workspaceInputDigests=inspected['workspaceInputDigests'],
                      inputDigests=inspected['inputDigests'], outputDigests=outputs, outputTargets=TARGETS,
                      rendererSourceSha256=sha256(root/'core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java'),
                      compiledClassDigests={p.relative_to(classes).as_posix(): sha256(p) for p in sorted(classes.rglob('*.class'))})
        clone.remove_verified_targets(outputs)
        run_command([*command, 'apply', root, output/'applied.json'], root, output, 'guarded-create', report)
        applied = read_json(output/'applied.json')
        if validate_manifest(root, applied) != outputs or applied['inputDigests'] != inspected['inputDigests']:
            raise ValueError('Inputs or candidate outputs drifted between inspect and apply')
        report['receipt'] = verify_create_receipt(applied, outputs)
        verify_snapshot(root, outputs)
        clean_tree(root)
        if git(root, 'rev-parse', 'HEAD') != clone.commit:
            raise ValueError('Actual generated source commit drifted')
        report.update(status='PASS', genuineCreateCount=len(outputs), restoredTrackedSnapshot=True,
                      cleanGeneratedSourceTree=True, nativeInvocation=list(NATIVE_FLAGS))
    except BaseException as error:
        report.update(status='BLOCKED' if isinstance(error, PrerequisiteError) else 'FAIL', errorType=type(error).__name__)
        raise
    finally:
        text = json.dumps(report, indent=2, ensure_ascii=False) + '\n'
        (output/'prebuild.json').write_text(text, encoding='utf-8')
        mirror = no_links(source/OUTPUT)
        mirror.mkdir(parents=True, exist_ok=True)
        safe_file(source, (OUTPUT/'prebuild.json').as_posix(), must_exist=False).write_text(text, encoding='utf-8')
    return root, report


def validate_prebuild_report(root):
    root = no_links(Path(root))
    report = read_json(safe_file(root, (OUTPUT/'prebuild.json').as_posix()))
    if (report.get('status') != 'PASS' or report.get('scope') != 'REAL_P042_RENDERER_GUARDED_CREATE_IN_EXACT_COMMIT_CLONE'
            or not report.get('restoredTrackedSnapshot') or not report.get('cleanGeneratedSourceTree')
            or report.get('genuineCreateCount') != len(TARGETS) or git(root, 'rev-parse', 'HEAD') != report.get('sourceCommit')):
        raise ValueError('Verified generated clone receipt required before native build')
    verify_snapshot(root, report['outputDigests'])
    for name, digest in checked_digest_map(report.get('workspaceInputDigests'), required=SOURCE_INPUTS).items():
        if sha256(safe_file(root, name)) != digest:
            raise ValueError('Generated clone input changed before native build')
    verify_input_digests(root, report.get('inputDigests'))
    if sha256(safe_file(root, 'core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java')) != report.get('rendererSourceSha256'):
        raise ValueError('Renderer source changed before native build')
    applied = read_json(safe_file(root, (OUTPUT/'applied.json').as_posix()))
    if validate_manifest(root, applied) != report['outputDigests'] or verify_create_receipt(applied, report['outputDigests']) != report.get('receipt'):
        raise ValueError('Actual guarded-application receipt is missing or changed')
    clean_tree(root)
    return report


def run_native(root):
    """Run after component prerequisites in this same clone; never uses old build classes."""
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('The real native gate is isolated-CI only')
    root = no_links(Path(root))
    validate_prebuild_report(root)
    # Component tests create this in the same clone. Do not copy classes from the
    # original checkout or quietly switch to a static, previously built source tree.
    if not (root/'build/component-classes/RealMySqlRegression.class').is_file():
        raise ValueError('Run component verification inside the generated clone first')
    environment = os.environ.copy()
    environment['FCP_EDUCATION_CODEGEN_REPORT'] = str(root/OUTPUT/'prebuild.json')
    process = subprocess.run([sys.executable, str(root/'tools/ci_native.py'), *NATIVE_FLAGS], cwd=root, env=environment)
    return process.returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--clone-dir', type=Path, help='New disposable exact-HEAD source clone destination')
    mode.add_argument('--native-root', type=Path, help='Previously generated clone; run real native gate after its component checks')
    args = parser.parse_args(argv)
    if args.native_root:
        return run_native(args.native_root)
    root, _ = prepare(ROOT, args.clone_dir)
    github_output = os.environ.get('GITHUB_OUTPUT')
    if github_output:
        if any(c in str(root) for c in '\r\n'):
            raise ValueError('Invalid CI output path')
        with open(github_output, 'a', encoding='utf-8') as stream:
            stream.write('generated_root=' + str(root) + '\n')
    print('Generated clone verified; downstream builds and evidence must use this clone: ' + str(root))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
