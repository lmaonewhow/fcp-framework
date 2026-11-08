#!/usr/bin/env python3
"""Package trusted, already-built static UI bytes. Does not build Vue or verify publisher trust."""
from __future__ import annotations
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile

MAX_FILES = 10_000
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
EXTENSIONS = frozenset({'.html','.js','.mjs','.css','.json','.svg','.png','.jpg','.jpeg','.gif','.webp','.avif','.ico','.woff','.woff2','.ttf','.otf','.eot','.txt','.md','.webmanifest','.wasm','.mp4','.webm','.mp3','.ogg'})

class BundleError(ValueError):
    pass

def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')

def _name(value: str) -> str:
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_./-]{1,512}',value):
        raise BundleError('Unsafe asset path')
    parts=value.split('/')
    if any(not p or p in ('.','..') or p.startswith('.') for p in parts):
        raise BundleError('Unsafe asset path segment')
    suffix=Path(value).suffix.lower()
    underlying=value[:-3] if suffix in ('.gz','.br') else value
    if Path(underlying).suffix.lower() not in EXTENSIONS and not underlying.endswith('.d.ts'):
        raise BundleError('Unsupported static asset type')
    if value == 'manifest.json':
        raise BundleError('manifest.json is reserved for the bundle envelope')
    return value

def _directory(path: Path, create: bool=False) -> Path:
    path=Path(os.path.abspath(path))
    part=Path(path.anchor)
    for segment in path.parts[1:]:
        part=part/segment
        if create:
            try: part.mkdir(mode=0o700)
            except FileExistsError: pass
        try: info=part.lstat()
        except FileNotFoundError as e: raise BundleError('Missing directory') from e
        if not stat.S_ISDIR(info.st_mode): raise BundleError('Unsafe directory ancestor')
    return path

def _read(path: Path, limit: int) -> bytes:
    try:
        with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as stream:
            before=os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size>limit: raise BundleError('Asset type or size limit')
            data=stream.read(limit+1)
            after=os.fstat(stream.fileno())
            if len(data)>limit or len(data)!=before.st_size or (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):
                raise BundleError('Asset changed while being read')
            return data
    except OSError as e: raise BundleError('Cannot safely read asset') from e

def _inventory(directory: Path, envelope: bool=False) -> dict[str,dict]:
    entries={};total=0;directory_count=0
    for current,dirs,files in os.walk(directory,followlinks=False):
        for name in dirs:
            directory_count+=1
            if directory_count>MAX_FILES: raise BundleError('Directory count limit')
            path=Path(current)/name
            rel=path.relative_to(directory).as_posix()
            if len(rel)>512 or not re.fullmatch(r'[A-Za-z0-9_./-]+',rel) or any(p.startswith('.') for p in rel.split('/')) or not stat.S_ISDIR(path.lstat().st_mode):
                raise BundleError('Unsafe asset directory')
        for name in files:
            path=Path(current)/name;rel=path.relative_to(directory).as_posix()
            if rel=='manifest.json' and envelope: continue
            if rel in ('index.html.gz','index.html.br') and not envelope:
                _read(path,MAX_FILE_BYTES) # Validate type/path, but never publish pre-base-injection index copies.
                continue
            _name(rel)
            data=_read(path,MAX_FILE_BYTES);total+=len(data)
            if total>MAX_TOTAL_BYTES or len(entries)>=MAX_FILES: raise BundleError('Bundle size/count limit')
            entries[rel]={'sha256':hashlib.sha256(data).hexdigest(),'sizeBytes':len(data)}
    if 'index.html' not in entries: raise BundleError('index.html required')
    return dict(sorted(entries.items()))

def _contract(value: str) -> str:
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]{0,127}',value): raise BundleError('Invalid backend contract')
    return value

def _parse_manifest(data: bytes) -> dict:
    def unique(pairs):
        obj={}
        for k,v in pairs:
            if k in obj: raise BundleError('Duplicate manifest field')
            obj[k]=v
        return obj
    try: m=json.loads(data.decode('utf-8'),object_pairs_hook=unique)
    except (UnicodeError,json.JSONDecodeError) as e: raise BundleError('Invalid manifest JSON') from e
    if not isinstance(m,dict) or set(m)!={'format','shell','backendContract','files','digest'} or type(m['format']) is not int or m['format']!=1 or m['shell'] not in ('admin','teacher'):
        raise BundleError('Invalid manifest schema')
    _contract(m['backendContract'])
    if not isinstance(m['digest'],str) or not re.fullmatch(r'[a-f0-9]{64}',m['digest']) or not isinstance(m['files'],dict): raise BundleError('Invalid manifest identity')
    if not 1<=len(m['files'])<=MAX_FILES: raise BundleError('Invalid manifest count')
    total=0
    for name,entry in m['files'].items():
        _name(name)
        if name in ('index.html.gz','index.html.br'): raise BundleError('Precompressed index cannot represent the served base-injected entry')
        if not isinstance(entry,dict) or set(entry)!={'sha256','sizeBytes'} or not isinstance(entry['sha256'],str) or not re.fullmatch(r'[a-f0-9]{64}',entry['sha256']) or type(entry['sizeBytes']) is not int or not 0<=entry['sizeBytes']<=MAX_FILE_BYTES:
            raise BundleError('Invalid manifest file entry')
        total+=entry['sizeBytes']
    if total>MAX_TOTAL_BYTES or 'index.html' not in m['files']: raise BundleError('Invalid manifest total/index')
    identity={k:v for k,v in m.items() if k!='digest'}
    if hashlib.sha256(canonical(identity)).hexdigest()!=m['digest']: raise BundleError('Manifest digest mismatch')
    return m

def verify_bundle(bundle: Path, expected_digest: str|None=None) -> dict:
    directory=_directory(bundle)
    manifest=_parse_manifest(_read(directory/'manifest.json',MAX_MANIFEST_BYTES))
    if expected_digest is not None and manifest['digest']!=expected_digest: raise BundleError('Unexpected bundle digest')
    if _inventory(directory,envelope=True)!=manifest['files']: raise BundleError('Bundle files differ from immutable manifest')
    return manifest

def build_bundle(dist: Path, root: Path, shell: str, backend_contract: str='fcp.host/1') -> dict:
    if shell not in ('admin','teacher'): raise BundleError('Invalid shell')
    contract=_contract(backend_contract);source=_directory(dist);target_root=Path(os.path.abspath(root))
    if target_root==source or source in target_root.parents or target_root in source.parents: raise BundleError('Source and destination must be disjoint')
    target_root=_directory(target_root,create=True)
    files=_inventory(source)
    manifest={'format':1,'shell':shell,'backendContract':contract,'files':files}
    digest=hashlib.sha256(canonical(manifest)).hexdigest();manifest['digest']=digest
    target=target_root/digest/shell
    # Serialize cooperating packagers. This directory is deployer-owned, not an untrusted upload area.
    with os.fdopen(os.open(target_root/'.publish.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW|os.O_NONBLOCK,0o600),'r+b') as lock:
        if not stat.S_ISREG(os.fstat(lock.fileno()).st_mode): raise BundleError('Invalid publication lock')
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        if target.exists() or target.is_symlink():
            if verify_bundle(target,digest)!=manifest: raise BundleError('Immutable publication conflict')
            return {'bundlePath':str(target),'manifest':manifest}
        parent=_directory(target.parent,create=True)
        stage=Path(tempfile.mkdtemp(prefix='.stage-',dir=parent))
        try:
            for rel,entry in files.items():
                # Recheck parents and hashes so source changes between enumeration/copy fail closed.
                _directory((source/rel).parent)
                data=_read(source/rel,MAX_FILE_BYTES)
                if len(data)!=entry['sizeBytes'] or hashlib.sha256(data).hexdigest()!=entry['sha256']: raise BundleError('Source changed during packaging')
                output=stage/rel;output.parent.mkdir(parents=True,exist_ok=True)
                with output.open('xb') as stream: stream.write(data);stream.flush();os.fsync(stream.fileno())
            with (stage/'manifest.json').open('xb') as stream: stream.write(canonical(manifest)+b'\n');stream.flush();os.fsync(stream.fileno())
            if _inventory(source)!=files: raise BundleError('Source changed during packaging')
            for directory, _, _ in os.walk(stage,topdown=False):
                fd=os.open(directory,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
                try: os.fsync(fd)
                finally: os.close(fd)
            if verify_bundle(stage,digest)!=manifest: raise BundleError('Staged bundle validation failed')
            if target.exists() or target.is_symlink(): raise BundleError('Concurrent publication conflict')
            os.rename(stage,target)
            fd=os.open(parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try: os.fsync(fd)
            finally: os.close(fd)
        finally:
            if stage.exists(): shutil.rmtree(stage)
    return {'bundlePath':str(target),'manifest':manifest}

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    build=sub.add_parser('build');build.add_argument('--dist',required=True,type=Path);build.add_argument('--root',required=True,type=Path);build.add_argument('--shell',required=True,choices=['admin','teacher']);build.add_argument('--backend-contract',default='fcp.host/1')
    verify=sub.add_parser('verify');verify.add_argument('bundle',type=Path);verify.add_argument('--digest')
    args=parser.parse_args()
    try:
        result=build_bundle(args.dist,args.root,args.shell,args.backend_contract) if args.command=='build' else verify_bundle(args.bundle,args.digest)
        print(json.dumps(result,ensure_ascii=False,sort_keys=True));return 0
    except (BundleError,OSError) as e:
        parser.exit(2,'Bundle rejected: '+str(e)+'\n')
if __name__=='__main__':raise SystemExit(main())
