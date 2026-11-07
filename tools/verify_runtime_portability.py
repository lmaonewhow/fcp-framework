#!/usr/bin/env python3
"""A35/A36 reproducible dependency and real DevHost verification; native execution is separate."""
import hashlib
import json
from pathlib import Path
import platform
import shutil
from datetime import datetime, timezone
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    report = ROOT/'reports/runtime-portability'
    report.mkdir(parents=True, exist_ok=True)
    if (report/'result.json').exists():
        history = report/'history'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        history.mkdir(parents=True)
        for path in report.iterdir():
            if path.is_file() and path.suffix in ('.json', '.log'):
                shutil.copy2(path, history/path.name)
    build = ROOT/'build/runtime-http'
    build.mkdir(parents=True, exist_ok=True)
    trees = ['core/src/main/java', 'business/src/main/java', 'host/src/main/java',
             'development-host/src/main/java', 'tests/runtime-portability', 'tests/runtime-http', 'tests/runtime-native']
    sources = [path for tree in trees for path in sorted((ROOT/tree).rglob('*'))
               if path.is_file() and path.suffix in ('.java', '.py', '.mjs') and '__pycache__' not in path.parts]
    sources += [Path(__file__), ROOT/'tools/check_plugin_dependencies.py']
    hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
    output = dict(status='NOT_RUN', nativeRuoYi='NOT_RUN', nativeInstruction='tools/ci_native.py --ingestion executes the same run_http_contract after genuine native login',
                  osIsolation='NOT_CLAIMED', platform=platform.platform(), groups=[], sourceHashes=hashes)
    commands = [
        ('compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(build),
                     *[str(path) for path in sources if path.suffix == '.java' and ('src' in path.parts or path.parent.name == 'runtime-portability')]]),
        ('dependency-policy', ['java', '-cp', str(build), 'PluginDependencyPolicyTest']),
        ('development-http', [sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/runtime-http', '-v']),
        ('fixture-contract', [sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/runtime-portability', '-p', 'test_*.py', '-v']),
        ('build-gate', [sys.executable, 'tools/check_plugin_dependencies.py', 'runtime/runtime-plugins/roster']),
    ]
    try:
        output['baselineCommit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        output['workingTreeStatus'] = subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).splitlines()
        for name, command in commands:
            with (report/(name+'.log')).open('w') as log:
                result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=180)
            output['groups'].append(dict(name=name, command=command, exitCode=result.returncode))
            if result.returncode:
                raise RuntimeError(name+' failed; inspect '+str(report/(name+'.log')))
        changed = [name for name, digest in hashes.items() if hashlib.sha256((ROOT/name).read_bytes()).hexdigest() != digest]
        if changed:
            raise RuntimeError('Source changed during execution; rerun required: '+', '.join(changed))
        classes = sorted(build.rglob('*.class'))
        output['artifactHashes'] = {str(path.relative_to(build)): hashlib.sha256(path.read_bytes()).hexdigest() for path in classes}
        output['status'] = 'PASS'
    except Exception as error:
        output.update(status='FAIL', error=str(error))
    (report/'result.json').write_text(json.dumps(output, indent=2)+'\n')
    print(json.dumps({key: value for key, value in output.items() if key not in ('sourceHashes', 'artifactHashes', 'workingTreeStatus')}, indent=2))
    return 0 if output['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
