"""CI-only real MySQL DevHost closure; schema was deployed by the native fixture.

No business SQL writes, no DDL credentials in DevHost, and a separate tenant/state root.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
sys.path.insert(0,str(ROOT/'tests/runtime-exchange'))
from build_devhost import build
from ci_ingestion import safe_diagnostic
from business_contract import run_business_contract
from native_exchange_acceptance import ExchangeClient, package_bytes
from native_runtime_acceptance import RuntimeFixture

def verify_development_business(private_root, url, user, password, driver, endpoint, native_result):
    if os.environ.get('GITHUB_ACTIONS')!='true' or native_result.get('status')!='PASS':
        raise RuntimeError('Requires isolated CI and successful genuine native business contract')
    root=Path(private_root)/'development-business';root.mkdir(mode=0o700)
    output=ROOT/'reports/development-business';output.mkdir(parents=True,exist_ok=True)
    classes=build();token=secrets.token_hex(32);tenant='dev-portability'
    permissions=['fcp:plugin:'+p for p in ('install','view','manage','exchange.read','exchange.import','exchange.operator','storage.read','storage.write')]
    grants=[]
    for plugin in ('roster','mock-exam'):
        package,manifest=package_bytes(plugin)
        for cap in manifest['provides']+manifest['requires']:
            grants.append(dict(plugin=plugin,capability=cap['id'],operation='invoke',dataScope='class:class-a' if cap['id'].startswith('host.storage.') else 'tenant:'+tenant,permission='fcp:plugin:'+cap['permission']))
        for operation in ('read','write'):
            grants.append(dict(plugin=plugin,capability='storage',operation=operation,dataScope='class:class-a',permission='fcp:plugin:storage.'+operation))
        for operation in ('import','export'):
            grants.append(dict(plugin=plugin,capability='exchange',operation=operation,dataScope='class:class-a',permission='fcp:plugin:exchange.operator'))
    identity=dict(token=token,userId='dev-contract-admin',tenantId=tenant,scopes=['class:class-a','tenant:'+tenant],permissions=permissions,grants=grants)
    config=dict(development=True,trustedPublishers=['fcp-fixtures'],executables=dict(python=sys.executable,node=shutil.which('node')),identities=[identity],storageJdbc=dict(url=url,user=user,password=password),exchangeFixture=dict(classId='class-a',campusApi=endpoint))
    private=root/'identities.json'
    def save():
        temporary=root/'identities.new';temporary.write_text(json.dumps(config));temporary.chmod(0o600);temporary.replace(private)
    save()
    def grant_import(allow):
        identity['grants']=[g for g in identity['grants'] if not(g['plugin']=='roster' and g['capability']=='exchange' and g['operation']=='import')]
        if allow:identity['grants'].append(dict(plugin='roster',capability='exchange',operation='import',dataScope='class:class-a',permission='fcp:plugin:exchange.operator'))
        save()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    command=['java','-cp',str(classes)+os.pathsep+str(driver),'dev.fcp.dev.DevHost','--development',str(private),str(root/'state'),str(port)]
    client=ExchangeClient('http://127.0.0.1:'+str(port),token,'development')
    process=None
    def start():
        nonlocal process
        process=subprocess.Popen(command,stdout=log,stderr=log)
        for _ in range(200):
            try:client.data('/fcp/plugins');return
            except OSError:
                if process.poll() is not None:raise RuntimeError('DevHost JVM exited; inspect private startup log')
                time.sleep(.05)
        raise RuntimeError('DevHost readiness timeout')
    def stop():
        if process and process.poll() is None:
            process.terminate();process.wait(timeout=20)
    report=dict(status='NOT_RUN',production='NOT_ACCEPTED',nativeAuthentication='NOT_CLAIMED',database='real isolated CI MySQL, DML-only runtime credentials',baselineCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip())
    try:
        with (root/'host.log').open('w') as log:
            try:
                start()
                for plugin in ('roster','mock-exam'):
                    package,manifest=package_bytes(plugin);digest=hashlib.sha256(package).hexdigest()
                    client.upload(package,digest,'dev-install-'+plugin,client.data('/fcp/plugins')['revision'])
                    plan=client.data('/fcp/plugins/'+plugin+'/plans',dict(action='ENABLE',expectedRevision=client.data('/fcp/plugins')['revision']))
                    if RuntimeFixture.confirm(client,plan,'dev-enable-'+plugin)['phase']!='COMPLETE':raise AssertionError('Development enable failed')
                result=run_business_contract(client,grant_import)
                if result['rows']!=native_result['rows']:raise AssertionError('Hosts disagree on identical student business data')
                stop();start()
                for _ in range(100):
                    try:
                        handle=client.data('/fcp/exchange/configs/roster-fixture/exports',dict(fields=['student_code']));break
                    except RuntimeError:time.sleep(.1)
                else:raise AssertionError('Development recovery did not become ready')
                status,headers,body=client.binary('/fcp/exchange/downloads/'+handle['handle'])
                if status!=200 or b'portable1' not in body:raise AssertionError('MySQL student data lost after actual JVM restart')
                report.update(status='PASS',sharedBusinessContract=result,sameNativeRows=True,jvmRestartPersistence='PASS')
            finally:stop()
    except Exception as error:
        report.update(status='FAIL',error=safe_diagnostic(str(error),[password,token]))
        raise
    finally:
        if (root/'host.log').exists():
            (output/'startup-redacted.log').write_text(safe_diagnostic((root/'host.log').read_text(errors='replace'),[password,token]))
        (output/'acceptance.json').write_text(json.dumps(report,indent=2)+'\n')
    return report
