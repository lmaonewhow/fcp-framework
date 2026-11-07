#!/usr/bin/env python3
"""Verify runtime package and host contracts, explicitly separate from native acceptance."""
import json
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'reports' / 'runtime-contracts'

def main():
    REPORT.mkdir(parents=True, exist_ok=True)
    output = {'status': 'BLOCKED', 'scope': 'Java host and package unit/integration tests only',
              'nativeRuoYi': 'NOT_RUN', 'mysql': 'NOT_RUN', 'browser': 'NOT_RUN',
              'platform': platform.platform(), 'groups': []}
    try:
        if not shutil.which('javac'):
            raise RuntimeError('Full JDK with javac and Java 17 target support is required')
        build = ROOT / 'build' / 'runtime-contracts'
        build.mkdir(parents=True, exist_ok=True)
        sources = sorted((ROOT / 'core/src/main/java').rglob('*.java'))
        sources += sorted((ROOT / 'tests/runtime-contracts').rglob('*.java'))
        sources += sorted((ROOT / 'tests/runtime-packages').rglob('*.java'))
        commands = [('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(build), *map(str,sources)])]
        for name, main_class in [('host-contract', 'HostContractTest'), ('catalog-publication', 'CatalogPublicationTest'), ('runtime-packages', 'RuntimePackagesTest')]:
            commands.append((name, ['java', '-cp', str(build), main_class]))
        for name, command in commands:
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=120)
            (REPORT / (name + '.log')).write_text(result.stdout + result.stderr)
            output['groups'].append({'name': name, 'exitCode': result.returncode, 'command': command})
            if result.returncode:
                output['status'] = 'FAIL'
                raise RuntimeError(name + ' failed')
        output['status'] = 'PASS'
    except Exception as exc:
        output['error'] = str(exc)
    (REPORT / 'result.json').write_text(json.dumps(output, indent=2) + '\n')
    print(json.dumps(output, indent=2))
    return 0 if output['status'] == 'PASS' else 2

if __name__ == '__main__':
    sys.exit(main())
