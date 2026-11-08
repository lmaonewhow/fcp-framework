#!/usr/bin/env python3
"""Preserve actual component execution evidence; no native/production pass inference."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
report_dir = root / 'reports/release-routing'
report_dir.mkdir(parents=True, exist_ok=True)
command = ['bash', 'tests/release-routing/run.sh']
report = {
    'status': 'FAIL',
    'scope': 'Java durable policy and actual two-version loopback HTTP component tests',
    'nativeRuoYi': 'NOT_RUN', 'mysql': 'NOT_RUN', 'browser': 'NOT_RUN',
    'production': 'NOT_ACCEPTED', 'command': command,
    'baselineCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    'workingTreeStatus': subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).splitlines(),
    'sourceHashes': {},
}
for tree in ['core/src/main/java/fcp/release/routing', 'tests/release-routing']:
    for path in sorted((root / tree).rglob('*')):
        if path.is_file():
            report['sourceHashes'][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
for path in [root / 'core/src/main/java/fcp' / (name + '.java') for name in ['Json', 'Schema', 'FcpException']]:
    report['sourceHashes'][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
for name in ['host/TrustedContext.java', 'control/StateJournal.java', 'control/FileStateJournal.java']:
    path = root / 'core/src/main/java/fcp' / name
    report['sourceHashes'][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
try:
    with (report_dir / 'test.log').open('w') as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=90)
    report['exitCode'] = result.returncode
    report['status'] = 'PASS' if result.returncode == 0 else 'FAIL'
except Exception as error:
    report['error'] = str(error)
(report_dir / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: report[key] for key in ['status', 'scope', 'nativeRuoYi', 'mysql', 'browser', 'production']}))
sys.exit(0 if report['status'] == 'PASS' else 1)
