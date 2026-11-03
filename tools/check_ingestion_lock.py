#!/usr/bin/env python3
"""Verify the accepted admin dependency pin without downloading or resolving packages."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHA256 = 'ae581b28b46e756ef06d73f58807e997e76f877a993da174c1e2a2873c2dc8c5'
EXPECTED_ADMIN_COMMIT = 'd0d451967676707021b9857df529c395b27e90a7'


def verify(root: Path) -> dict:
    """Fail closed on absent/drifted bytes or an incompatible upstream selection."""
    lock = root / 'locks/admin-package-lock.json'
    digest = hashlib.sha256(lock.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError('Admin dependency lock differs from the accepted pin; review and revalidate before updating it')
    upstream = json.loads((root / 'upstreams.lock.json').read_text(encoding='utf-8'))
    if upstream['upstreams']['admin']['commit'] != EXPECTED_ADMIN_COMMIT:
        raise ValueError('Admin upstream changed; the dependency pin requires new acceptance')
    document = json.loads(lock.read_text(encoding='utf-8'))
    if document.get('lockfileVersion') != 3 or not document.get('packages', {}).get(''):
        raise ValueError('Expected the accepted npm v3 lock with a root package')
    return dict(status='PASS', scope='dependency pin integrity only; no runtime or vulnerability audit',
                sha256=digest, adminUpstreamCommit=EXPECTED_ADMIN_COMMIT)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        result = verify(args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps(dict(status='FAIL', error=str(error))))
        return 2
    print(json.dumps(result, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
