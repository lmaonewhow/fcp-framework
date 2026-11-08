"""Real JDBC runner for the fresh isolated school roster composition.

A separate report, not a claim inherited from FoundationStore or HTTP login.
"""
from pathlib import Path
import hashlib
import json
import re
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def wait_native_login_window(stage):
    """Keep separate genuine login batches below the native 20/IP/60s gate.

    Waiting is test orchestration, never a limiter or Redis configuration change.
    Multiple native page navigations can request tenant/list for a single login.
    """
    import time
    if stage not in {'foundation-only', 'roster-http', 'roster-browser'}:
        raise ValueError('Unknown native login batch')
    started = time.monotonic()
    time.sleep(60)
    elapsed = time.monotonic() - started
    if elapsed < 60:
        raise RuntimeError('The complete native login window did not elapse')
    return {'stage': stage, 'waitSeconds': round(elapsed, 3), 'nativeRateLimitChanged': False}


def run_roster_native_probe(*, database, url, user, password, manifest, driver, output, root=ROOT):
    """Execute the separate JDBC transaction gate with the real packaged driver.

    Authentication is intentionally not claimed here; the HTTP/browser fixture
    that follows authenticates through the genuine native login flow.
    """
    from ci_ingestion import safe_diagnostic
    root, output = Path(root), Path(output)
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
        raise ValueError('Native roster probe requires the owned disposable CI schema')
    expected = f'jdbc:mysql://127.0.0.1:33306/{database}?'
    if not url.startswith(expected) or not re.fullmatch('[a-f0-9]{64}', manifest):
        raise ValueError('Native roster probe target or artifact binding is invalid')
    classes = root / 'build/education-roster-native-probe'
    classes.mkdir(parents=True, exist_ok=False)
    sources = [root/'core/src/main/java/fcp/FcpException.java', root/'core/src/main/java/fcp/Json.java']
    sources += sorted((root/'core/src/main/java/fcp/education').glob('*.java'))
    sources += [root/'tests/education_roster/RosterStoreNativeProbe.java']
    environment = {key: value for key, value in os.environ.items() if not key.startswith(('FCP_', 'SPRING_'))
                   and key not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS', 'MAVEN_ARGS'}}
    environment.update(FCP_EDUCATION_TEST_DATABASE=database, FCP_EDUCATION_TEST_URL=url,
                       FCP_EDUCATION_TEST_USER=user, FCP_EDUCATION_TEST_PASSWORD=password,
                       FCP_EDUCATION_TEST_MANIFEST=manifest)
    output.mkdir(parents=True, exist_ok=True)
    report = {'status': 'RUNNING', 'scope': 'REAL_MYSQL_ROSTER_JDBC_NOT_HTTP_LOGIN',
              'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              'manifestDigest': manifest, 'commands': [], 'writeCommands': 'NOT_IMPLEMENTED'}
    secrets = [password, url]
    try:
        commands = [
            ('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)]),
            ('run', ['java', '-cp', str(classes) + os.pathsep + str(driver), 'RosterStoreNativeProbe'])
        ]
        for name, command in commands:
            try:
                result = subprocess.run(command, cwd=root, env=environment, text=True, capture_output=True, timeout=180)
            except subprocess.TimeoutExpired as error:
                # TimeoutExpired may retain bytes even when text=True. Preserve
                # the actual bounded-process failure, never fabricate an exit code.
                def decoded(value):
                    return value.decode(errors='replace') if isinstance(value, bytes) else value or ''
                raw = decoded(error.stdout) + decoded(error.stderr)
                (output / (name + '.log')).write_text(safe_diagnostic(raw, secrets))
                report['commands'].append({'name': name, 'command': command, 'exitCode': None,
                                           'status': 'TIMEOUT', 'timeoutSeconds': error.timeout})
                raise
            (output / (name + '.log')).write_text(safe_diagnostic(result.stdout + result.stderr, secrets))
            report['commands'].append({'name': name, 'command': command, 'exitCode': result.returncode})
            if result.returncode:
                raise RuntimeError('Native roster JDBC probe ' + name + ' failed; redacted evidence preserved')
        parsed = json.loads(result.stdout.strip().splitlines()[-1])
        if parsed.get('status') != 'PASS' or parsed.get('scope') != report['scope'] or not isinstance(parsed.get('checks'), int) or parsed['checks'] <= 0:
            raise ValueError('Native roster JDBC probe did not produce valid execution evidence')
        report.update(parsed)
        report['compiledClassSha256'] = {str(path.relative_to(classes)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(classes.rglob('*.class'))}
    except BaseException as error:
        report.update(status='FAIL', errorType=type(error).__name__)
        raise
    finally:
        (output/'result.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def verify_artifact_mismatch(*, jar, private_dir, output, manifest, root=ROOT):
    """Start this actual native JAR with a wrong public digest and require rejection.

    Only the owned CI configuration is reused. No configured credential or full
    startup log is copied into public evidence. Timeout is FAIL, never PASS.
    """
    import time
    from ci_ingestion import safe_diagnostic
    jar, private_dir, output = Path(jar), Path(private_dir), Path(output)
    if os.environ.get('GITHUB_ACTIONS') != 'true' or jar.is_symlink() or not jar.is_file():
        raise ValueError('Actual CI native JAR is required')
    if not re.fullmatch('[a-f0-9]{64}', manifest) or hashlib.sha256(jar.read_bytes()).hexdigest() != manifest:
        raise ValueError('Artifact binding negative test requires the exact real JAR digest')
    config = private_dir / 'fcp-runtime.properties'
    if config.is_symlink() or not config.is_file() or config.stat().st_mode & 0o077:
        raise ValueError('Owned private runtime configuration required')
    wrong = ('0' if manifest[0] != '0' else '1') + manifest[1:]
    command = ['java', '-Duser.timezone=UTC', '-Xms128m', '-Xmx1024m', '-jar', str(jar), '--spring.profiles.active=prod,fcp',
               '--server.port=0', '--FCP_EDUCATION_ROSTER_MANIFEST_DIGEST=' + wrong]
    output.mkdir(parents=True, exist_ok=False)
    log = private_dir / 'roster-artifact-mismatch-startup.log'
    report = {'status': 'RUNNING', 'scope': 'REAL_NATIVE_STARTUP_WRONG_ROSTER_ARTIFACT_DIGEST',
              'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              'actualJarSha256': manifest, 'configuredWrongDigest': wrong, 'command': command}
    process = None
    started = time.monotonic()
    try:
        with log.open('x') as stream:
            process = subprocess.Popen(command, cwd=private_dir, stdout=stream, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            report['reason'] = 'Process stayed alive with an incorrect artifact digest'
            raise RuntimeError('Wrong artifact digest did not reject native startup')
        report['exitCode'] = code
        content = log.read_text(errors='replace')
        report['expectedRejectionObserved'] = 'ROSTER_EXECUTION_ARTIFACT_MISMATCH' in content
        if code == 0 or not report['expectedRejectionObserved']:
            raise RuntimeError('Native startup did not fail for the exact expected artifact binding reason')
        report['status'] = 'PASS'
    except BaseException as error:
        report.update(status='FAIL', errorType=type(error).__name__)
        raise
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                process.kill();process.wait();report['forcedTermination'] = True
        if log.exists():
            raw = log.read_text(errors='replace')
            values = []
            for path in private_dir.rglob('*.properties'):
                for line in path.read_text(errors='replace').splitlines():
                    if '=' in line and len(line.split('=', 1)[1]) > 6:
                        values.append(line.split('=', 1)[1])
            # Retain bounded redacted failure context. Never treat diagnostic text
            # as policy, credentials or an instruction for the test orchestrator.
            lines = raw.splitlines()[-100:]
            safe = '\n'.join(safe_diagnostic(line, values) for line in lines)
            (output / 'startup-redacted.log').write_text(safe + '\n')
            report['privateRawLogSha256'] = hashlib.sha256(log.read_bytes()).hexdigest()
            report['redactedLogSha256'] = hashlib.sha256((output / 'startup-redacted.log').read_bytes()).hexdigest()
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    return report
