"""Composition metadata for the fresh CI-only native education database.

This module describes the locked host plan before execution. It never adopts
whatever tables happen to exist in a database as the expected installation.
"""
from pathlib import Path
import hashlib
import json
import re
import os
import subprocess

from mysql_install import checked_stages
from native import BLOBS, check_blob

ROOT = Path(__file__).resolve().parents[1]
CREATE_TABLE = re.compile(r"(?im)^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([a-zA-Z][a-zA-Z0-9_]*)`?\s*\(")


def restore_browser_fixture_permission(fixture):
    """Restore only the synthetic row deliberately revoked by the HTTP gate."""
    database = fixture.plan.database
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
        raise ValueError('Fixture restoration requires the owned disposable CI schema')
    if not fixture.plan.applied or fixture.report.get('status') != 'PASS':
        raise ValueError('HTTP revocation acceptance must pass before browser preparation')
    predicate = "tenant_id='000000' AND native_role_id=987102 AND admin_kind='SCHOOL_ADMIN' AND school_id=800001 AND operation_key='class:rename' AND source_approval_id='synthetic-ci-explicit-approval'"
    before = fixture.sql(f"SELECT CONCAT(enabled,'|',row_version) FROM `{database}`.ed_host_education_role WHERE {predicate};").strip()
    if before != '0|1':
        raise ValueError('Synthetic revocation row differs from its expected verified state')
    affected = fixture.sql(f"UPDATE `{database}`.ed_host_education_role SET enabled=1,row_version=row_version+1 WHERE {predicate} AND enabled=0 AND row_version=1; SELECT ROW_COUNT();").strip()
    after = fixture.sql(f"SELECT CONCAT(enabled,'|',row_version) FROM `{database}`.ed_host_education_role WHERE {predicate};").strip()
    if affected != '1' or after != '1|2':
        raise ValueError('Synthetic permission restoration did not affect exactly its owned row')
    result = {'status': 'PASS', 'scope': 'SYNTHETIC_FIXTURE_ONLY', 'nativeRoleId': '987102',
              'operation': 'class:rename', 'before': before, 'after': after, 'freshBrowserLoginRequired': True}
    (fixture.plan.output/'browser-fixture-restore.json').write_text(json.dumps(result, indent=2)+'\n')
    return result


def verified_host_plan(backend, *, root=ROOT):
    backend, root = Path(backend), Path(root)
    for relative, digest in BLOBS['backend'].items():
        if relative.startswith('script/sql/'):
            check_blob(backend / relative, digest)
    lock = json.loads((root / 'sql/native/migrations.lock.json').read_text())
    names = sorted(lock['files'])
    if any(not re.fullmatch(r'V\d{3,}__[a-z0-9_]+\.sql', name) for name in names):
        raise ValueError('Unsafe locked host migration name')
    paths = [backend / 'script/sql/ry_vue_5.X.sql', backend / 'script/sql/ry_workflow.sql']
    paths += [root / 'sql/native' / name for name in names]
    stages = checked_stages(paths, lock)
    tables = {'fcp_install_history', 'fcp_migration_history'}
    for stage in stages:
        tables.update(CREATE_TABLE.findall(stage['sql']))
    if not {'sys_user', 'sys_role', 'sys_user_role', 'sys_tenant'}.issubset(tables):
        raise ValueError('Locked host schema lacks native identity tables')
    if any(name.startswith('ed_') for name in tables):
        raise ValueError('Education tables must not be adopted as existing host tables')
    public = [{key: value for key, value in stage.items() if key != 'sql'} for stage in stages]
    document = {'format': 1, 'stages': public, 'expectedHostTables': sorted(tables)}
    canonical = json.dumps(document, sort_keys=True, separators=(',', ':')).encode()
    return {**document, 'planSha256': hashlib.sha256(canonical).hexdigest()}


def run_native_probe(*, database, url, user, password, manifest, driver, output, root=ROOT):
    """Execute the separate JDBC transaction gate with the real packaged driver.

    Authentication is intentionally not claimed here; the HTTP/browser fixture
    that follows authenticates through the genuine native login flow.
    """
    from ci_ingestion import safe_diagnostic
    root, output = Path(root), Path(output)
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
        raise ValueError('Native education probe requires the owned disposable CI schema')
    expected = f'jdbc:mysql://127.0.0.1:33306/{database}?'
    if not url.startswith(expected) or not re.fullmatch('[a-f0-9]{64}', manifest):
        raise ValueError('Native education probe target or artifact binding is invalid')
    classes = root / 'build/education-native-probe'
    classes.mkdir(parents=True, exist_ok=False)
    sources = [root/'core/src/main/java/fcp/FcpException.java', root/'core/src/main/java/fcp/Json.java']
    sources += sorted((root/'core/src/main/java/fcp/education').glob('*.java'))
    sources += [root/'tests/education/FoundationStoreNativeProbe.java']
    environment = {key: value for key, value in os.environ.items() if not key.startswith(('FCP_', 'SPRING_'))
                   and key not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS', 'MAVEN_ARGS'}}
    environment.update(FCP_EDUCATION_TEST_DATABASE=database, FCP_EDUCATION_TEST_URL=url,
                       FCP_EDUCATION_TEST_USER=user, FCP_EDUCATION_TEST_PASSWORD=password,
                       FCP_EDUCATION_TEST_MANIFEST=manifest)
    output.mkdir(parents=True, exist_ok=True)
    report = {'status': 'RUNNING', 'scope': 'REAL_MYSQL_FOUNDATION_JDBC_NOT_HTTP_LOGIN',
              'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              'manifestDigest': manifest, 'commands': [], 'unknownCommitFaultInjection': 'NOT_RUN'}
    secrets = [password, url]
    try:
        commands = [
            ('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)]),
            ('run', ['java', '-cp', str(classes) + os.pathsep + str(driver), 'FoundationStoreNativeProbe'])
        ]
        for name, command in commands:
            result = subprocess.run(command, cwd=root, env=environment, text=True, capture_output=True, timeout=180)
            (output / (name + '.log')).write_text(safe_diagnostic(result.stdout + result.stderr, secrets))
            report['commands'].append({'name': name, 'command': command, 'exitCode': result.returncode})
            if result.returncode:
                raise RuntimeError('Native education JDBC probe ' + name + ' failed; redacted evidence preserved')
        parsed = json.loads(result.stdout.strip().splitlines()[-1])
        if parsed.get('status') != 'PASS' or parsed.get('scope') != report['scope'] or not isinstance(parsed.get('checks'), int) or parsed['checks'] <= 0:
            raise ValueError('Native education JDBC probe did not produce valid execution evidence')
        report.update(parsed)
        report['compiledClassSha256'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(classes.rglob('*.class'))}
    except BaseException as error:
        report.update(status='FAIL', errorType=type(error).__name__)
        raise
    finally:
        (output/'result.json').write_text(json.dumps(report, indent=2) + '\n')
    return report
