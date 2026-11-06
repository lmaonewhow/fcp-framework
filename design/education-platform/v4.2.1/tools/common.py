"""Small, UTF-8-explicit helpers. No environment-dependent text decoding."""
from pathlib import Path
import hashlib
import json
import re
import os
import tempfile

def load_json(path: Path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def write_json(path: Path, data):
    path=Path(path);path.parent.mkdir(parents=True, exist_ok=True)
    content=json.dumps(data,ensure_ascii=False,indent=2,allow_nan=False)+'\n'
    temp=None
    try:
        with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8',newline='\n',dir=path.parent,prefix='.'+path.name+'.',suffix='.tmp',delete=False) as stream:
            temp=Path(stream.name);stream.write(content);stream.flush();os.fsync(stream.fileno())
        os.replace(temp,path)
    finally:
        if temp is not None and temp.exists():temp.unlink()

def canonical(data) -> bytes:
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')

def sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def canonical_hash(data) -> str:
    return hashlib.sha256(canonical(data)).hexdigest()

def safe_file(root: Path, value: str) -> Path:
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or '\x00' in value:
        raise ValueError('invalid portable relative path')
    p = Path(value)
    if p.is_absolute() or any(x in ('..', '.') for x in value.split('/')):
        raise ValueError('unsafe relative path')
    target = root / p
    for i in range(1, len(p.parts)+1):
        if (root / Path(*p.parts[:i])).is_symlink():
            raise ValueError('symlink forbidden')
    target.resolve().relative_to(root.resolve())
    if not target.is_file():
        raise ValueError('file missing')
    return target

HEX64 = re.compile(r'^[0-9a-f]{64}$')
