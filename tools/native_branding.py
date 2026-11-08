"""Apply product presentation only to exact pinned upstream/compatibility preimages.
Technical identifiers, authentication, licenses and upstream lock identities are retained.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def digest(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def apply_branding(projects: dict[str, Path]) -> None:
    bundle = ROOT / 'integration/branding'
    manifest = json.loads((bundle / 'manifest.json').read_text())
    pins = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams']
    if manifest.get('format') != 1 or set(manifest.get('upstreams', {})) != {'admin', 'teacher', 'backend'}:
        raise RuntimeError('Branding manifest target mismatch')
    staged = []
    for name, pin in manifest['upstreams'].items():
        project = projects[name].resolve()
        if pin['commit'] != pins[name]['commit']:
            raise RuntimeError('Branding upstream lock drift')
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
        if head != pin['commit']:
            raise RuntimeError('Branding upstream commit drift')
        patch = bundle / f'{name}.patch'
        if patch.is_symlink() or digest(patch) != pin['patchSha256']:
            raise RuntimeError('Branding patch checksum drift')
        if not pin['files']:
            raise RuntimeError('Branding manifest has no files')
        for relative, images in pin['files'].items():
            if not re.fullmatch(r'[A-Za-z0-9_./-]+', relative) or '..' in Path(relative).parts or relative.startswith('/'):
                raise RuntimeError('Unsafe branding target path')
            target = project / relative
            if target.resolve() != target or (target.exists() and not target.is_file()) or digest(target) != images['before']:
                raise RuntimeError('Branding preimage drift: ' + relative)
        paths = subprocess.check_output(['git', 'apply', '--numstat', str(patch)], cwd=project, text=True)
        if {line.split('\t', 2)[2] for line in paths.splitlines()} != set(pin['files']):
            raise RuntimeError('Branding patch target set drift')
        subprocess.run(['git', 'apply', '--check', '--whitespace=error', str(patch)], cwd=project, check=True)
        staged.append((project, patch, pin))
    # Validate all three projects before writing to any project.
    for project, patch, pin in staged:
        subprocess.run(['git', 'apply', '--whitespace=error', str(patch)], cwd=project, check=True)
        for relative, images in pin['files'].items():
            if digest(project / relative) != images['after']:
                raise RuntimeError('Branding postimage drift: ' + relative)


# The generator integration owns the whole index preimage; rename downloads only
# after it has applied. These are the exact compat-only, PR12 safe-apply and
# education-entry outputs, all after the pinned compatibility patch.
GENERATOR_PREIMAGES = {'9ac87b7d662d1c858b6cc2a3f978dcb0372d7cbb6bb5494d1ebf959da98cadd8', '77d62198f4145fada332581a20e832975ab609448f5af06a8950a84321aa22fa', '3c727c50f5793aa8bd69294128e56fab136164f399cbe22b5fd60eab37d7a99e'}
GENERATOR_POSTIMAGES = {'cd065485eec38a07930f5ec5b85fb5cb2bb14a20ba2a44228abec5f81100ca39', '8641b7e27623bbb69c6ef47c971d7581a0d290ca7b1bf99c8d6bee33ef35479e', 'e95334eaa436f4d4a756ba828777e6865d059adc6f1fb1d5430951122e64e5f2'}


def apply_generator_branding(project: Path) -> None:
    project = project.resolve()
    target = project / 'src/views/tool/gen/index.vue'
    pin = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams']['admin']['commit']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
    if head != pin or target.resolve() != target or digest(target) not in GENERATOR_PREIMAGES:
        raise RuntimeError('Generator branding preimage drift')
    source = target.read_text()
    if source.count("'ruoyi.zip'") != 1:
        raise RuntimeError('Generator download-name anchor drift')
    after = source.replace("'ruoyi.zip'", "'smart-school-code.zip'", 1)
    if hashlib.sha256(after.encode()).hexdigest() not in GENERATOR_POSTIMAGES:
        raise RuntimeError('Generator branding postimage drift')
    target.write_text(after)
