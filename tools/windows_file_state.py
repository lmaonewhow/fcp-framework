#!/usr/bin/env python3
"""Explicitly prepare the approved, pinned Windows file-state runtime; no global install."""
import argparse,hashlib,json,os,pathlib,subprocess,sys,time,urllib.request,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
LOCK=ROOT/'core/dependencies/windows-file-state.lock.json'

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def verified_jar(path):
    lock=json.loads(LOCK.read_text(encoding='utf-8'))
    if path.is_symlink() or not path.is_file() or path.stat().st_size!=lock['bytes'] or digest(path)!=lock['sha256']:
        raise RuntimeError('Pinned SQLite runtime checksum mismatch')
    return lock

def prepare(output,source=None):
    if os.name!='nt':raise RuntimeError('Native Windows preparation required')
    output=output.resolve()
    if output.exists():raise RuntimeError('Use a new application-private runtime directory; existing output is preserved')
    lock=json.loads(LOCK.read_text(encoding='utf-8'))
    output.mkdir(parents=False)
    owner=subprocess.check_output(['whoami'],text=True).strip()
    subprocess.run(['icacls',str(output),'/inheritance:r','/grant:r',owner+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    target=output/lock['filename']
    if source:
        verified_jar(source)
        # Copy only verified bytes, then verify the resulting artifact again.
        with source.open('rb') as incoming,target.open('xb') as outgoing:
            while block:=incoming.read(65536):outgoing.write(block)
            outgoing.flush();os.fsync(outgoing.fileno())
    else:
        start=time.monotonic()
        with urllib.request.urlopen(lock['url'],timeout=20) as incoming,target.open('xb') as outgoing:
            while block:=incoming.read(65536):
                if outgoing.tell()+len(block)>lock['bytes'] or time.monotonic()-start>300:raise RuntimeError('Runtime download limit')
                outgoing.write(block)
            outgoing.flush();os.fsync(outgoing.fileno())
    verified_jar(target)
    native=output/'native';native.mkdir()
    notices=output/'notices';notices.mkdir()
    with zipfile.ZipFile(target) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/') and any(word in name.upper() for word in ['LICENSE','NOTICE']):
                (notices/pathlib.PurePosixPath(name).name).write_bytes(archive.read(name))
        if not list(notices.iterdir()):raise RuntimeError('Dependency notices missing')
    (output/'dependency.json').write_text(json.dumps(lock,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':'PREPARED','jar':str(target),'nativeDirectory':str(native),'sha256':lock['sha256']}))

def runtime(output):
    lock=json.loads(LOCK.read_text(encoding='utf-8'));jar=output/lock['filename'];verified_jar(jar)
    native=output/'native'
    if not native.is_dir() or native.is_symlink():raise RuntimeError('Native extraction directory missing/unsafe')
    return jar.resolve(),native.resolve()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','verify'])
    parser.add_argument('output',type=pathlib.Path)
    parser.add_argument('--source-jar',type=pathlib.Path,help='Use an already downloaded exact pinned artifact')
    args=parser.parse_args()
    if args.command=='prepare':prepare(args.output,args.source_jar)
    else:
        jar,native=runtime(args.output)
        print(json.dumps({'status':'VERIFIED','jar':str(jar),'nativeDirectory':str(native)}))
if __name__=='__main__':main()
