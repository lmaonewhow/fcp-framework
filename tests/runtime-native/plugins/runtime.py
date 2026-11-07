"""Trusted native acceptance package. Real stdio process, no network/database credentials."""
import json,os,sys
for line in sys.stdin:
    request=json.loads(line);method=request['method'];params=request['params']
    if method=='handshake':result=params
    elif method in ('ready','health','drain','cancel','shutdown'):result={'ok':True}
    elif method=='invoke':
        result={'language':'python','message':params['input'].get('message',''),'tenantId':params['context']['tenantId'],'principalKind':params['context']['principal']['kind'],'principalId':params['context']['principal']['id'],'pluginPid':os.getpid()}
    else:raise RuntimeError('Unsupported native acceptance method')
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
    if method=='shutdown':break
