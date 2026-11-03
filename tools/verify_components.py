#!/usr/bin/env python3
"""Run only locally executable component checks; never label them native integration tests."""
from __future__ import annotations
import argparse, json, os, platform, re, shutil, stat, subprocess, sys, tempfile, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'build'
REPORTS=ROOT/'reports'

def execute(name,args,cwd=ROOT,timeout=120):
    executable=shutil.which(str(args[0]))
    if not executable: raise RuntimeError('Missing executable: '+str(args[0]))
    process=subprocess.run([executable,*map(str,args[1:])],cwd=cwd,timeout=timeout,text=True,encoding='utf-8',errors='replace',capture_output=True)
    text=process.stdout+process.stderr
    (REPORTS/(name+'.log')).write_text(text,encoding='utf-8')
    print(text,end='' if text.endswith('\n') else '\n',flush=True)
    if process.returncode: raise RuntimeError(f'{name} failed with exit {process.returncode}')
    return text

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--windows-file-runtime',type=Path,help='Explicit prepared pinned SQLite runtime for Windows file-state checks')
    args=parser.parse_args()
    BUILD.mkdir(exist_ok=True);REPORTS.mkdir(exist_ok=True)
    report={'status':'BLOCKED','scope':'JDK/Python process + TypeScript unit + assembler/gate unit checks; Windows file state uses actual pinned SQLite/DLL',
            'nativeSpringCompilation':'NOT_RUN','nativeMySQL':'NOT_RUN','nativeVueBuilds':'NOT_RUN','browserE2E':'NOT_RUN',
            'environment':{'os':platform.platform(),'python':sys.version.split()[0]},'groups':[]}
    started=time.monotonic()
    try:
        for label,command in [('java',['java','-version']),('javac',['javac','-version']),('node',['node','--version']),('tsc',['tsc','--version'])]:
            report['environment'][label]=execute('version-'+label,command).strip()
        classes=BUILD/'component-classes';classes.mkdir(exist_ok=True)
        classpath=str(classes);java=['java']
        if os.name=='nt':
            if args.windows_file_runtime is None:raise RuntimeError('Windows file-state checks require --windows-file-runtime; prepare with tools/windows_file_state.py')
            from windows_file_state import runtime
            jar,native=runtime(args.windows_file_runtime)
            classpath+=os.pathsep+str(jar);java+=['-Dorg.sqlite.tmpdir='+str(native)]
        files=[]
        for tree in ['core/src/main/java','business/src/main/java','host/src/main/java','deploy/src/main/java','tests/java']:
            files.extend(sorted((ROOT/tree).rglob('*.java')))
        # Quote paths in the javac argfile, including Windows paths and directories with spaces.
        argfile=BUILD/'component-sources.txt'
        argfile.write_text('\n'.join('"'+p.as_posix()+'"' for p in files)+'\n',encoding='utf-8')
        execute('javac-components',['javac','--release','17','-encoding','UTF-8','-d',classes,'@'+str(argfile)])
        result=execute('java-components',java+['-cp',classpath,'NativeBridgeRegression',ROOT/'runtime'])
        count=int(re.search(r'RESULT (\d+) component checks PASS',result)[1])
        report['groups'].append({'name':'Java component + real Python child','checks':count,'status':'PASS','dataStore':'FileRecordStore test fixture; no MySQL'})
        for label,java_class,argument,pattern,meaning in [
            ('process-termination','ProcessTerminationRegression',None,r'RESULT (\d+) process-termination',
             'real non-reading SIGTERM-ignoring Python child, bounded cleanup and quota release'),
            ('process-tree','ProcessTreeRegression',None,r'RESULT (\d+) process-tree',
             'actual root/late child termination with explicit slow enumeration double'),
            ('lifecycle-hardening','ProductionRegression',ROOT/'runtime',r'RESULT (\d+) production-hardening',
             'real file persistence/restart, thread concurrency, authorization failures, actual Python; NOT Spring/MySQL'),
            ('admission-commit-fence','EngineAdmissionFenceRegression',ROOT/'runtime',r'RESULT (\d+) admission/commit-fence',
             'deterministic memory/latch fixture: read admission, serialized durable actions, publication/close boundaries; NOT MySQL throughput'),
            ('jdbc-protocol','JdbcProtocolRegression',None,r'RESULT (\d+) JDBC protocol',
             'explicit JDBC protocol doubles; SQL NOT executed by MySQL'),
            ('migration-package','MigrationRegression',ROOT/'sql/native',r'RESULT (\d+) migration package',
             'real SQL package checksums and restricted parser; database deployment NOT_RUN')]:
            command=java+['-cp',classpath,java_class]
            if argument is not None: command.append(argument)
            result=execute(label,command)
            report['groups'].append({'name':label,'checks':int(re.search(pattern,result)[1]),'status':'PASS','scope':meaning})
        if os.name=='nt':
            result=execute('windows-file-state',java+['-cp',classpath,'WindowsFileStateRegression'])
            report['groups'].append({'name':'Windows file-state recovery','checks':int(re.search(r'RESULT (\d+) Windows file-state',result)[1]),'status':'PASS','scope':'actual pinned SQLite/DLL; process kill, not physical power loss'})
        session=BUILD/'session' ;session.mkdir(exist_ok=True)
        execute('tsc-session',['tsc',ROOT/'integration/frontend-shared/session.ts','--target','ES2022','--module','ES2022','--lib','ES2022,DOM','--strict','--outDir',session])
        (session/'package.json').write_text('{"type":"module"}\n')
        result=execute('js-session',['node',ROOT/'tests/js/session.test.mjs',session/'session.js'])
        count=int(re.search(r'RESULT (\d+) JavaScript unit checks PASS',result)[1])
        report['groups'].append({'name':'TypeScript shared-session unit','checks':count,'status':'PASS','fetch':'explicit unit double; not a real backend'})
        result=execute('python-builder',[sys.executable,'-m','unittest','discover','-s','tests/python','-v'])
        count=int(re.search(r'Ran (\d+) tests',result)[1])
        skip_match=re.search(r'OK \(skipped=(\d+)\)',result)
        skipped=int(skip_match[1]) if skip_match else 0
        report['groups'].append({'name':'assembler and release-gate unit','checks':count-skipped,'executedTests':count,'skipped':skipped,'status':'PASS','upstreamScope':'four complete upstream fixture files verified by Git blob SHA; not whole-repository build'})
        # Source-launch a real Java key generator. Private material is held in a temporary directory and removed.
        with tempfile.TemporaryDirectory(prefix='fcp-keycheck-') as directory:
            private=Path(directory)/'private'
            execute('key-generator',['java',ROOT/'bootstrap/GenerateKeys.java',private])
            build_file=private/'build.env';runtime_file=private/'fcp-runtime.properties'
            import hashlib
            before={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [build_file,runtime_file]}
            values=dict(line.split('=',1) for line in build_file.read_text().splitlines() if line and not line.startswith('#'))
            if not all(values.get(key) for key in ['FCP_CLIENT_ID','FCP_REQUEST_PUBLIC_KEY','FCP_RESPONSE_PRIVATE_KEY']):raise AssertionError('Incomplete build key file')
            if os.name!='nt' and any(stat.S_IMODE(p.stat().st_mode)!=0o600 for p in [build_file,runtime_file]):raise AssertionError('Key permissions are not 0600')
            repeat=subprocess.run([shutil.which('java'),str(ROOT/'bootstrap/GenerateKeys.java'),str(private)],capture_output=True,text=True,timeout=30)
            after={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [build_file,runtime_file]}
            if repeat.returncode==0 or before!=after:raise AssertionError('Key generator overwrote existing material')
            (REPORTS/'key-generator.log').write_text('PASS actual Java source launcher generated matching config files\nPASS required build keys present\nPASS POSIX file mode 0600 (when supported)\nPASS existing files refused and unchanged\nPrivate outputs removed with temporary directory.\n')
            report['groups'].append({'name':'Java key bootstrap execution','status':'PASS','checks':4,'scope':'generation, fields, permissions, overwrite refusal; native crypto handshake NOT_RUN'})
        report['status']='PASS'
    except Exception as exc:
        report.update(status='FAIL',error=f'{type(exc).__name__}: {exc}')
    report['elapsedSeconds']=round(time.monotonic()-started,3)
    report['totalComponentChecks']=sum(g.get('checks',0) for g in report['groups'])
    (REPORTS/'components.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
