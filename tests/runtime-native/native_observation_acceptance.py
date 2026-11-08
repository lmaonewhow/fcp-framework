"""Synthetic identity-echo/1 semantics on the real native host, never metrics injection."""
import hashlib,hmac,io,json,secrets,time,zipfile,re
from pathlib import Path
from native_runtime_acceptance import package_bytes as base_package, exported
from ui_boundaries import is_rejected
FAULT_MESSAGE='synthetic-identity-echo-violation'

def package_bytes(language,version):
    content,manifest=base_package(language,version)
    if language!='python':return content,manifest
    # Both trusted synthetic packages declare this narrow acceptance purpose in source.
    with zipfile.ZipFile(io.BytesIO(content)) as archive: entries={name:archive.read(name) for name in archive.namelist()}
    runtime=entries['runtime.py'].decode()
    if version=='1.1.0':
        runtime=runtime.replace("    else:raise RuntimeError('Unsupported native acceptance method')", "        if params['input'].get('message')=="+repr(FAULT_MESSAGE)+":result['tenantId']='synthetic-wrong-tenant'\n    else:raise RuntimeError('Unsupported native acceptance method')")
    entries['runtime.py']=runtime.encode()
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,body in entries.items():
            info=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=(0o100644<<16);archive.writestr(info,body)
    return output.getvalue(),manifest

def configure(private_dir,key_path):
    # Generate a strong private routing key whose ordinary tenant hash selects the test tenant.
    # This does not bypass routing or install a policy/allowlist through test-only APIs.
    for _ in range(1024):
        key=secrets.token_bytes(32)
        bucket=int.from_bytes(hmac.new(key,b'["bucket-v1","native-ci","000000"]',hashlib.sha256).digest()[:4],'big')%10000
        if bucket<2500:break
    else:raise RuntimeError('Could not prepare synthetic tenant distribution')
    key_path.write_bytes(key);key_path.chmod(0o600)
    config=private_dir/'identity-echo-bindings.json'
    config.write_text(json.dumps(dict(format=1,bindings=[dict(plugin='ci.python',capability='echo',sha256=hashlib.sha256(package_bytes('python',version)[0]).hexdigest(),contract='identity-echo/1') for version in ('1.0.0','1.1.0')])))
    config.chmod(0o600)
    return '\nfcp.release.identity-echo.enabled=true\nFCP_IDENTITY_ECHO_BINDINGS_FILE='+str(config)+'\n'

def identity_violation_rejected(status, body):
    """Typed host rejection only: never consider an arbitrary 5xx a successful denial."""
    if status != 503 or not isinstance(body,dict) or set(body) != {'code','msg','data'}:
        return False
    data=body.get('data')
    if body.get('code') != 503 or body.get('msg') != 'Plugin operation unavailable; use the operation receipt to reconcile':
        return False
    if not isinstance(data,dict) or set(data) != {'fcpCode','traceId'} or data.get('fcpCode') != 'HOST_RESULT_IDENTITY_VIOLATION':
        return False
    trace=data.get('traceId')
    return isinstance(trace,str) and re.fullmatch(r'(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})',trace) is not None


def identity_violation_diagnostic(status,body,release,generation,digest):
    # Response values and even unknown key names can contain secrets. Preserve only enums/shapes.
    codes={200,400,401,403,404,409,410,422,429,500,502,503,504}
    known_fcp={'HOST_RESULT_IDENTITY_VIOLATION','OUTCOME_UNKNOWN','RELEASE_UNAVAILABLE',
               'PERMISSION_DENIED','UNAUTHENTICATED','STALE_RELEASE_AFFINITY','RELEASE_DISABLED',
               'INVALID_ARGUMENT','DEADLINE_EXCEEDED','BUSY'}
    body_map=body if isinstance(body,dict) else {}
    data=body_map.get('data');data_map=data if isinstance(data,dict) else {}
    raw_code=body_map.get('code');raw_fcp=data_map.get('fcpCode')
    code=(int(raw_code) if type(raw_code) in (int,str) and str(raw_code) in {str(c) for c in codes} else 'REDACTED')
    fcp_code=raw_fcp if isinstance(raw_fcp,str) and raw_fcp in known_fcp else 'REDACTED'
    body_keys={'code','msg','data'};data_keys={'fcpCode','traceId'}
    return dict(httpStatus=status if type(status) is int and status in codes else 'REDACTED',
                code=code,fcpCode=fcp_code,
                bodyKeyPresence={key:key in body_map for key in sorted(body_keys)},
                dataKeyPresence={key:key in data_map for key in sorted(data_keys)},
                bodyUnknownKeyCount=sum(key not in body_keys for key in body_map),
                dataUnknownKeyCount=sum(key not in data_keys for key in data_map),
                releaseId=release,generation=generation,candidatePackageSha256=digest)

def verify(f,admin,plan):
    from release_composition_acceptance import RELEASES
    record=f.report['observation'];record.update(scope='Real native host identity-echo/1 verification on deployment-pinned synthetic packages; not general product business success',automaticExpansion='RUNNING',securityContainment='NOT_RUN',semanticContract='identity-echo/1 (synthetic, explicitly deployment-approved package digests)')
    f._write()
    try:
        for round_name in ('healthy','security'):
            state=admin.data('/fcp/releases/state')
            f.check('observation next plan uses actual retained stable baseline '+round_name,state.get('phase')=='RETIRED' and state.get('currentRelease')==plan['stableRelease'] and state.get('currentDigest')==plan['stableDigest'])
            admin.data('/fcp/releases/plans',dict(expectedRevision=state['revision'],operationId='observation-'+round_name+'-plan',plan=plan))
            f.operation(admin,'DEPLOY',0,'READY');f.operation(admin,'CANARY',2500,'CANARY')
            catalog=f.selection(admin,'admin','candidate')
            policy=admin.data('/fcp/releases/automation/state')
            enabled=admin.data('/fcp/releases/automation/enable',dict(expectedRevision=policy['revision'],stages=[5000,10000],minimumDwellMillis=1000,minimumSamples=3,maximumAgeMillis=60000,maximumErrorRate=.05,maximumP95Millis=10000))
            for target in (5000,10000):
                before=admin.data('/fcp/releases/state')
                if round_name=='security' and target==10000:
                    stale=dict(expectedRevision=before['revision'],operationId='observation-stale-late-promote',action='PROMOTE',basisPoints=10000)
                    status,body=admin.request('/fcp/capabilities/'+exported('ci.python')+'/invoke',dict(input=dict(message=FAULT_MESSAGE,dependency=False),expectedRevision=catalog['catalog']['revision'],idempotencyKey='observed-identity-failure'),affinity=catalog['affinity'])
                    artifact=next(p for p in f.report['packages'] if p['component']=='ci.python' and p['version']=='1.1.0')
                    record['identityViolationResponse']=identity_violation_diagnostic(status,body,catalog['releaseId'],before['generation'],artifact['sha256'])
                    f._write()
                    f.check('host rejects actual wrong-tenant synthetic package reply',identity_violation_rejected(status,body))
                    settled=admin.data('/fcp/releases/automation/tick',{})
                    after=admin.data('/fcp/releases/state')
                    f.check('trusted result identity violation produces observed native STOPPED',after['phase']=='ABORTED' and after['observed']['routingMode']=='STOPPED' and settled['status']=='PAUSED')
                    status,body=admin.request('/fcp/releases/operations',stale)
                    f.check('late old-revision PROMOTE rejected after security containment',is_rejected(status,body) and admin.data('/fcp/releases/state')==after)
                    status,body=admin.request('/fcp/ui/catalog',affinity=catalog['affinity'])
                    f.check('security containment rejects actual old candidate ingress',is_rejected(status,body))
                    record['securityContainment']='PASS';break
                for index in range(4):f.invoke(admin,catalog,'1.1.0','observation-'+round_name+'-'+str(target)+'-'+str(index))
                time.sleep(1.1)
                settled=admin.data('/fcp/releases/automation/tick',{})
                after=admin.data('/fcp/releases/state')
                action='PROMOTE' if target==10000 else 'EXPAND'
                op='auto-'+enabled['id']+'-'+str(0 if target==5000 else 1)
                f.check('native trusted semantic samples '+round_name+' '+action,after['basisPoints']==target and after['generation']==before['generation']+1 and after['operations'][op]['command']['action']==action and after['observed']['complete'] is True)
                catalog=f.selection(admin,'admin','candidate')
            if round_name=='healthy':record['automaticExpansion']='PASS'
            f.operation(admin,'ROLLBACK',0,'ROLLED_BACK');f.operation(admin,'RETIRE',0,'RETIRED');f._write()
    except Exception:
        record['status']='FAIL';f._write();raise
    record['status']='PASS';f._write()
