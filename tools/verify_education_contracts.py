#!/usr/bin/env python3
"""Local-only Java/Python boundary checks. Never connects to a DB or native host."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runtime.education_contracts.catalog import CATALOG, generate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'reports/education-contracts')
    parser.add_argument('--write-catalog', action='store_true', help='Regenerate metadata from verified locked sources; never refreshes source digests')
    args = parser.parse_args()
    if args.write_catalog:
        (ROOT / CATALOG).write_text(json.dumps(generate(), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # Each run is isolated; stale PASS files cannot be reused as the current result.
    output = args.output.resolve() / uuid.uuid4().hex
    output.mkdir(parents=True, exist_ok=False)
    report = {'run_id': output.name, 'status': 'RUNNING', 'completed': False,
              'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'PURE_JAVA_PYTHON_BOUNDARY_AND_REAL_DESIGN_FILES_ONLY',
              'not_run': ['native host identity/authorization', 'MySQL DDL/DML/concurrency/driver bindings',
                          'browser pages', 'production migration or release'],
              'environment': {'python': sys.version, 'platform': platform.platform()}, 'steps': []}
    target = output / 'result.json'
    def save():
        temporary = output / 'result.tmp'
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        temporary.replace(target)
    def execute(name, command):
        process = subprocess.run(list(map(str, command)), cwd=ROOT, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, timeout=180)
        log = output / (name + '.log')
        log.write_bytes(process.stdout)
        report['steps'].append({'name': name, 'command': list(map(str, command)),
            'exit_code': process.returncode, 'status': 'PASS' if process.returncode == 0 else 'FAIL',
            'log_sha256': hashlib.sha256(process.stdout).hexdigest()})
        save()
        return process.returncode == 0
    save()
    try:
        files = []
        for pattern in ('runtime/education_contracts/*.py', 'contracts/education/*.json',
                        'core/src/main/java/fcp/education/*.java', 'tests/education_contracts/*',
                        'tools/verify_education_contracts.py', 'docs/education/*.md'):
            files.extend(p for p in ROOT.glob(pattern) if p.is_file())
        report['source_sha256'] = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in sorted(set(files))}
        report['git_head'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        report['git_status'] = subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True)
        execute('python-contracts', [sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/education_contracts', '-v'])
        java = str(args.java_home / 'bin/java') if args.java_home else shutil.which('java')
        javac = str(args.java_home / 'bin/javac') if args.java_home else shutil.which('javac')
        if java and javac:
            execute('java-version', [java, '-version'])
            classes = output / 'classes'
            classes.mkdir()
            if execute('java-compile', [javac, '--release', '17', '-encoding', 'UTF-8', '-d', classes,
                ROOT / 'core/src/main/java/fcp/education/EducationScalars.java',
                ROOT / 'tests/education_contracts/EducationScalarsCheck.java']):
                execute('java-boundaries', [java, '-cp', classes, 'EducationScalarsCheck'])
                report['artifact_sha256'] = {p.relative_to(output).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                             for p in sorted(classes.rglob('*.class'))}
        else:
            report['steps'].append({'name': 'java-boundaries', 'status': 'NOT_RUN', 'reason': 'JDK required; provide --java-home'})
        report['status'] = ('PASS_LOCAL_CONTRACTS_ONLY' if report['steps'] and
                            all(s['status'] == 'PASS' for s in report['steps']) else 'FAIL_OR_NOT_RUN')
    except BaseException as exc:
        report['status'] = 'FAIL'
        report['error'] = type(exc).__name__ + ': ' + str(exc)
        raise
    finally:
        report['completed'] = True
        save()
        print(target)
    return 0 if report['status'] == 'PASS_LOCAL_CONTRACTS_ONLY' else 1


if __name__ == '__main__':
    raise SystemExit(main())
