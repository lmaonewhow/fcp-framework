#!/usr/bin/env python3
"""Run a private acceptance command, retaining process identity and measured exit code.

The private JSON specification has argv (array), cwd, and optional env (object).
The new run directory contains private command/output records; publish only reviewed
result fields. This is a process supervisor, not a scheduler or service installer.
"""
import argparse
import ctypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def write_result(path, value):
    temp=path.with_suffix('.tmp')
    with temp.open('w',encoding='utf-8') as stream:
        json.dump(value,stream,indent=2);stream.flush();os.fsync(stream.fileno())
    os.replace(temp,path)

def windows_creation(process):
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE]+[ctypes.POINTER(wintypes.FILETIME)]*4
    kernel.GetProcessTimes.restype=wintypes.BOOL
    created,exited,kern,user=(wintypes.FILETIME() for _ in range(4))
    if not kernel.GetProcessTimes(wintypes.HANDLE(int(process._handle)),ctypes.byref(created),ctypes.byref(exited),ctypes.byref(kern),ctypes.byref(user)):
        raise ctypes.WinError(ctypes.get_last_error())
    ticks=(created.dwHighDateTime<<32)|created.dwLowDateTime
    return {'windowsCreationFiletime':ticks,'createdUtc':(datetime.datetime(1601,1,1,tzinfo=datetime.timezone.utc)+datetime.timedelta(microseconds=ticks//10)).isoformat()}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spec',type=Path,required=True)
    parser.add_argument('--run-dir',type=Path,required=True)
    args=parser.parse_args()
    spec_bytes=args.spec.read_bytes();spec=json.loads(spec_bytes)
    command=spec['argv']
    if not isinstance(command,list) or not command or not all(isinstance(x,str) for x in command):
        raise ValueError('argv must be a nonempty string array')
    args.run_dir.mkdir(parents=True,exist_ok=False)
    # Private command and logs stay together. Do not print arguments/environment.
    (args.run_dir/'command.json').write_bytes(spec_bytes)
    result={'status':'STARTING','supervisorPid':os.getpid(),'startedUtc':utc(),
            'commandSha256':hashlib.sha256(spec_bytes).hexdigest(),'exitCode':None}
    target=args.run_dir/'result.json';write_result(target,result)
    environment=dict(os.environ);environment.update(spec.get('env',{}))
    started=time.perf_counter()
    with (args.run_dir/'stdout.log').open('xb') as stdout,(args.run_dir/'stderr.log').open('xb') as stderr:
        try:
            child=subprocess.Popen(command,cwd=spec['cwd'],env=environment,stdout=stdout,stderr=stderr,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        except OSError as error:
            result.update(status='FAILED_TO_START',launcherErrorType=type(error).__name__,endedUtc=utc(),elapsedSeconds=time.perf_counter()-started)
            write_result(target,result)
            return 2
        result.update(status='RUNNING',pid=child.pid)
        try:
            if os.name=='nt':result.update(windows_creation(child))
            write_result(target,result)
            code=child.wait()
        finally:
            # If metadata capture or observation fails, still retain the child's
            # actual eventual exit code; do not manufacture success or orphan it.
            code=child.wait()
            result.update(status='COMPLETE',exitCode=code,endedUtc=utc(),elapsedSeconds=time.perf_counter()-started)
            write_result(target,result)
    print(json.dumps(result))
    return code

if __name__=='__main__':
    sys.exit(main())
