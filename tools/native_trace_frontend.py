"""Fail-closed response diagnostics patches for the pinned native frontends."""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def apply_frontend_trace(project: Path, name: str) -> None:
    if name not in ('admin', 'teacher'):
        raise RuntimeError('Unknown trace frontend')
    manifest = json.loads((ROOT / 'integration/request-trace/manifest.json').read_text())
    pin = manifest['upstreams'][name]
    expected = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams'][name]['commit']
    if manifest['format'] != 1 or pin['commit'] != expected:
        raise RuntimeError('Trace frontend upstream lock drift')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
    if head != expected:
        raise RuntimeError('Trace frontend commit drift')
    allowed = {'admin': 'src/utils/request.ts', 'teacher': 'src/service/request/index.ts'}
    if pin['path'] != allowed[name]:
        raise RuntimeError('Unexpected trace frontend target')
    target = project / pin['path']
    patch = ROOT / f'integration/request-trace/{name}.patch'
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    if target.is_symlink() or patch.is_symlink() or digest(target) != pin['preimageSha256'] or digest(patch) != pin['patchSha256']:
        raise RuntimeError('Trace frontend preimage/patch drift')
    paths = subprocess.check_output(['git', 'apply', '--numstat', str(patch)], cwd=project, text=True)
    if {line.split('\t', 2)[2] for line in paths.splitlines()} != {pin['path']}:
        raise RuntimeError('Unexpected trace frontend patch paths')
    subprocess.run(['git', 'apply', '--check', '--whitespace=error', str(patch)], cwd=project, check=True)
    subprocess.run(['git', 'apply', '--whitespace=error', str(patch)], cwd=project, check=True)
    if digest(target) != pin['postimageSha256']:
        raise RuntimeError('Trace frontend postimage drift')
