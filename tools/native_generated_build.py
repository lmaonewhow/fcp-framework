#!/usr/bin/env python3
"""Repackage an already assembled native tree; never assemble or claim runtime PASS."""
from __future__ import annotations
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
import zipfile
from native import BuildError, PINS, ROOT, collect_java_tests, run, validate_dist, validate_test_runtime


def digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise BuildError('Missing or unsafe build input: '+str(path))
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_files(root: Path) -> dict[str, str]:
    if root.is_symlink() or not root.is_dir():
        raise BuildError('Missing or unsafe input directory')
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise BuildError('Symlink in build input')
        if path.is_file():
            result[path.relative_to(root).as_posix()] = digest(path)
    return result


def binding_digest(files: dict) -> str:
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def verify_generated(projects: dict[str, Path], files: dict[str, str]) -> dict[str, str]:
    if not files or not any(p.startswith('backend/') and p.endswith('.java') for p in files) or not any(p.startswith('admin/') and p.endswith('.vue') for p in files):
        raise BuildError('Generated binding requires backend Java and admin Vue sources')
    for relative, expected in files.items():
        parts = relative.split('/')
        if parts[0] not in ('backend', 'admin') or len(parts) < 2 or any(p in ('', '.', '..') for p in parts) or '\\' in relative:
            raise BuildError('Unsafe generated source path')
        root = projects[parts[0]].resolve()
        path = root.joinpath(*parts[1:])
        if not path.resolve().is_relative_to(root) or any(parent.is_symlink() for parent in [path, *path.parents] if parent != root):
            raise BuildError('Unsafe generated source location')
        if not re.fullmatch('[0-9a-f]{64}', expected) or digest(path) != expected:
            raise BuildError('Generated source digest mismatch: '+relative)
    return dict(sorted(files.items()))


def embed_admin(dist: Path, static: Path) -> dict[str, str]:
    """Keep the verified release entry/security scripts byte-for-byte."""
    validate_dist(dist, 'admin')
    teacher_before = tree_files(static/'teacher')
    admin = static/'admin'
    protected = {name: (admin/name).read_bytes() for name in ('index.html', 'release-loader.mjs', 'release-trace.mjs')}
    tree_files(admin)  # Reject symlinks before modifying this disposable tree.
    inputs = tree_files(dist)
    if any(name in inputs for name in ('release-loader.mjs', 'release-trace.mjs', 'embedded-index.html')):
        raise BuildError('Generated dist shadows release launcher')
    original = (dist/'index.html').read_text(encoding='utf-8')
    if '<base' in original.lower() or original.count('<head>') != 1:
        raise BuildError('Unexpected UI base template')
    shutil.rmtree(admin)
    shutil.copytree(dist, admin)
    for suffix in ('.gz', '.br'):
        (admin/('index.html'+suffix)).unlink(missing_ok=True)
    (admin/'embedded-index.html').write_text(original.replace('<head>', '<head><base href="/admin/"><meta name="fcp-ui-bundle" content="embedded">', 1), encoding='utf-8')
    for name, content in protected.items():
        (admin/name).write_bytes(content)
    if tree_files(static/'teacher') != teacher_before:
        raise BuildError('Teacher assets changed')
    return tree_files(static)


def source_snapshot(projects: dict[str, Path]) -> dict[str, str]:
    result = {}
    for name, root in projects.items():
        # Include adaptations and generated sources, but not ignored dependency/build outputs.
        paths = run(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root, capture=True).split('\0')
        for relative in sorted(set(paths)):
            if relative and (root/relative).is_file():
                result[name+'/'+relative] = digest(root/relative)
    return result


def verify_jar(jar: Path, binding: dict, resources: Path, generated: dict[str, str]) -> None:
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise BuildError('Duplicate JAR entries')
        prefix = 'BOOT-INF/classes/'
        if json.loads(archive.read(prefix+'fcp/generated-build.json')) != binding:
            raise BuildError('JAR generated/source binding mismatch')
        if 'Start-Class: org.dromara.DromaraApplication' not in archive.read('META-INF/MANIFEST.MF').decode():
            raise BuildError('Unexpected native entry point')
        required = ['org/dromara/DromaraApplication.class', 'org/dromara/fcp/school/FcpSchoolController.class', 'fcp/runtime/plugins/statistics.py']
        for path in required:
            if prefix+path not in names:
                raise BuildError('Incomplete genuine native JAR: '+path)
        for relative, expected in tree_files(resources/'static').items():
            if hashlib.sha256(archive.read(prefix+'static/'+relative)).hexdigest() != expected:
                raise BuildError('JAR static asset differs: '+relative)
        expected_static = {prefix+'static/'+p for p in tree_files(resources/'static')}
        actual_static = {p for p in names if p.startswith(prefix+'static/') and not p.endswith('/')}
        if actual_static != expected_static:
            raise BuildError('JAR has stale static assets')
        for relative in generated:
            if relative.startswith('backend/') and '/src/main/java/' in relative and relative.endswith('.java'):
                compiled = relative.split('/src/main/java/', 1)[1][:-5]+'.class'
                if prefix+compiled not in names:
                    found = False
                    for dependency in names:
                        if dependency.startswith('BOOT-INF/lib/ruoyi-') and dependency.endswith('.jar'):
                            with zipfile.ZipFile(io.BytesIO(archive.read(dependency))) as nested:
                                if compiled in nested.namelist():
                                    found = True
                                    break
                    if not found:
                        raise BuildError('Generated class missing from JAR: '+compiled)


def sanitized_log(text: str, config: Path, projects: dict[str, Path]) -> str:
    secrets = []
    for path in [config, projects['admin']/'.env.production.local', projects['teacher']/'.env.prod.local']:
        if path.is_file():
            for line in path.read_text().splitlines():
                if '=' in line and not line.lstrip().startswith('#'):
                    value = line.split('=', 1)[1].strip().strip('\"\'')
                    if len(value) >= 4:
                        secrets.append(value)
    for value in sorted(set(secrets), key=len, reverse=True):
        text = text.replace(value, '[REDACTED]')
    return re.sub(r'(?i)((?:password|secret|token|authorization)\s*[:=]\s*)[^\s,;]+', r'\1[REDACTED]', text)


def build_generated(work: Path, test_runtime_config: Path, generated_files: dict[str, str], *, source_commit: str, output: Path | None = None, existing_redis_no_password: bool = False) -> dict:
    work = work.resolve()
    projects = {name: work/'upstream'/name for name in PINS}
    if run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture=True) != source_commit:
        raise BuildError('FCP source commit binding mismatch')
    for name, project in projects.items():
        if run(['git', 'rev-parse', 'HEAD'], cwd=project, capture=True) != PINS[name]['commit']:
            raise BuildError('Upstream pin mismatch: '+name)
    config = validate_test_runtime(test_runtime_config, existing_redis_no_password=existing_redis_no_password)
    generated = verify_generated(projects, generated_files)
    resources = projects['backend']/'ruoyi-admin/src/main/resources'
    assembly = json.loads((resources/'fcp/native-assembly.json').read_text())
    if assembly.get('pins') != PINS or not assembly.get('sourceAssembly'):
        raise BuildError('Existing genuine assembly binding missing')
    original = work/'release/ruoyi-admin.jar'
    original_digest = digest(original)
    output = (output or work/'generated-release').resolve()
    if output.exists() or output == work/'release' or output.is_relative_to(work/'upstream'):
        raise BuildError('Generated release requires a fresh separate output directory')
    output.mkdir(parents=True)
    assets = embed_admin(projects['admin']/'dist', resources/'static')
    binding = {'format': 1, 'kind': 'generated-native-build', 'buildId': 'generated-'+uuid.uuid4().hex,
               'sourceCommit': source_commit, 'pins': PINS, 'parentBuildId': assembly['buildId'],
               'originalJarSha256': original_digest, 'generatedFiles': generated,
               'generatedFilesSha256': binding_digest(generated), 'staticSha256': binding_digest(assets),
               'runtimeVerification': 'NOT_RUN'}
    # This new resource is excluded from its own source digest to avoid recursion.
    binding['generatedBuildId'] = binding['buildId']
    binding['baselineBuildId'] = assembly['buildId']
    binding_path = resources/'fcp/generated-build.json'
    if binding_path.exists():
        raise BuildError('Tree already carries a generated build binding')
    snapshot = source_snapshot(projects)
    binding['assembledSourceSha256'] = binding_digest(snapshot)
    binding_path.write_text(json.dumps(binding, sort_keys=True, indent=2)+'\n')
    (output/'SOURCE-FILES.json').write_text(json.dumps(snapshot, sort_keys=True, indent=2)+'\n')
    args = ['mvn', '-B', '-Pprod', '-pl', 'ruoyi-admin', '-am', '-DskipTests=false', '-Dmaven.test.skip=false', '-DreuseForks=false', 'clean', 'verify']
    env = {k: v for k, v in os.environ.items() if not k.startswith(('FCP_', 'SPRING_')) and k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','MAVEN_OPTS','MAVEN_ARGS'}}
    env.update(SPRING_CONFIG_ADDITIONAL_LOCATION=config.as_uri(), SPRING_PROFILES_ACTIVE='prod,fcp')
    evidence = dict(binding, status='FAIL', nativeBuild='FAIL', command=args, applicationGate='BLOCKED_UNTIL_REAL_API_AND_BROWSER_VERIFICATION')
    try:
        try:
            result = subprocess.run(args, cwd=projects['backend'], env=env, text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=2400)
            raw = result.stdout+'\n'+result.stderr
            evidence['exitCode'] = result.returncode
        except subprocess.TimeoutExpired as exc:
            raw = (exc.stdout or b'')+(exc.stderr or b'') if isinstance(exc.stdout or b'', bytes) else (exc.stdout or '')+(exc.stderr or '')
            if isinstance(raw, bytes): raw = raw.decode('utf-8', errors='replace')
            evidence['exitCode'] = None
            raise BuildError('Generated Maven build timed out') from None
        finally:
            if 'raw' in locals():
                (output/'maven.log').write_text(sanitized_log(raw, config, projects))
        if result.returncode:
            raise BuildError('Generated Maven build failed; see sanitized maven.log')
        tests = collect_java_tests(projects['backend'])
        evidence['javaUnitTests'] = tests
        if tests['status'] != 'PASS':
            raise BuildError('Generated prod-tag tests did not fully pass')
        verify_generated(projects, generated)
        after = source_snapshot(projects)
        after.pop('backend/ruoyi-admin/src/main/resources/fcp/generated-build.json', None)
        if after != snapshot:
            raise BuildError('Sources changed during generated Maven build')
        jar = projects['backend']/'ruoyi-admin/target/ruoyi-admin.jar'
        verify_jar(jar, binding, resources, generated)
        if digest(jar) == original_digest or digest(original) != original_digest:
            raise BuildError('Generated JAR is not distinct or original release changed')
        shutil.copy2(jar, output/'ruoyi-admin.jar')
        evidence.update(status='BUILT_NOT_RUNTIME_VERIFIED', nativeBuild='PASS', jarSha256=digest(output/'ruoyi-admin.jar'), jarPath=str(output/'ruoyi-admin.jar'), jar=str(output/'ruoyi-admin.jar'))
        return evidence
    except Exception as exc:
        evidence['failureReason'] = sanitized_log(str(exc), config, projects)
        if not (output/'maven.log').exists():
            (output/'maven.log').write_text('Maven did not produce captured output.\n')
        raise
    finally:
        (output/'BUILD-EVIDENCE.json').write_text(json.dumps(evidence, sort_keys=True, indent=2)+'\n')
