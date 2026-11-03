#!/usr/bin/env python3
"""Run real process/HTTP/file-store checks; do not label them native host acceptance."""
import hashlib
import json
import platform
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    reports = ROOT / 'reports'
    reports.mkdir(exist_ok=True)
    build = ROOT / 'build/ingestion-classes'
    build.mkdir(parents=True, exist_ok=True)
    report = dict(status='FAIL', scope='real Java/Python/Node, loopback synthetic platform API, durable file store',
                  nativeSpring='NOT_RUN', nativeMySQL='NOT_RUN', browser='NOT_RUN', production='NOT_ACCEPTED',
                  environment=dict(python=sys.version.split()[0], os=platform.platform()), groups=[])
    started = time.monotonic()
    fixture = None
    def command(name, args, timeout=120):
        result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        output = result.stdout + result.stderr
        (reports / (name + '.log')).write_text(output)
        print(output, end='', flush=True)
        if result.returncode:
            raise RuntimeError(f'{name} failed with exit {result.returncode}')
        return output
    try:
        output = command('ingestion-python', [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', 'tests/ingestion', '-p', 'test_*.py', '-v'])
        report['groups'].append(dict(name='Python input and business rules', checks=int(re.search(r'Ran (\d+) tests', output)[1]), status='PASS'))
        sources = sorted(p for tree in ('core/src/main/java', 'business/src/main/java', 'tests/ingestion') for p in (ROOT / tree).rglob('*.java'))
        argfile = ROOT / 'build/ingestion-sources.txt'
        argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources) + '\n')
        command('ingestion-javac', ['javac', '--release', '17', '-encoding', 'UTF-8', '-d', build, '@' + str(argfile)])
        fixture = subprocess.Popen([sys.executable, '-B', str(ROOT / 'tests/ingestion/platform.py')], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        port = fixture.stdout.readline().strip()
        if not port.isdecimal(): raise RuntimeError('Synthetic platform did not start')
        output = command('ingestion-java', ['java', '-cp', build, 'IngestionRegression', ROOT / 'runtime', 'http://127.0.0.1:' + port, ROOT / 'tests/ingestion/node-worker.mjs'])
        report['groups'].append(dict(name='Java host + real Python/Node/HTTP/restart', checks=int(re.search(r'RESULT (\d+) ingestion', output)[1]), status='PASS'))
        report['status'] = 'PASS'
    except Exception as error:
        report['error'] = f'{type(error).__name__}: {error}'
    finally:
        if fixture:
            fixture.terminate()
            try: fixture.wait(timeout=5)
            except subprocess.TimeoutExpired: fixture.kill(); fixture.wait()
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        report['totalChecks'] = sum(g['checks'] for g in report['groups'])
        report['sourceSha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                 for tree in ('runtime/ingestion', 'business/src/main/java/dev/fcp/ingestion', 'tests/ingestion')
                                 for p in sorted((ROOT / tree).rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
        (reports / 'ingestion.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
