"""Campus-v1 synthetic CSV fixture. Trusted local plugin, not a school schema claim.
Atomic class snapshot import; existing identities are conflicts, never overwritten.
"""
import csv, io, json, sys, re
from pathlib import Path
if (Path(__file__).resolve().parents[2] / "observability").is_dir():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "observability"))
from fcp_trace import scope, outbound_headers
FIELDS = ('student_code', 'full_name', 'class_code')
IDENTITY = 'student_code'
COLLECTION = 'roster'

def call(operation, data):
    print(json.dumps({'jsonrpc':'2.0','id':'storage','method':'fcp.call','params':{'dependency':'host.storage.'+operation,'operation':'invoke','input':data},**outbound_headers()}), flush=True)
    response=json.loads(sys.stdin.readline())
    if 'error' in response: raise RuntimeError('STORAGE_DENIED')
    return response['result']

def invoke(operation, data):
    scope=data['dataScope']
    if not re.fullmatch(r'class:[A-Za-z0-9_.-]{1,100}',scope): raise ValueError('SCOPE_DENIED')
    base={'collection':COLLECTION,'id':'snapshot','dataScope':scope}
    current=call('get',base)
    revision=current['revision'] if current else 0
    existing=current['body']['rows'] if current else []
    if operation=='exchange.read': return {'rows':existing,'revision':revision}
    if data.get('format')!='campus-v1-csv': raise ValueError('FORMAT_NOT_CONFIGURED')
    reader=csv.DictReader(io.StringIO(data['content'],newline=''), strict=True)
    errors=[]; rows=[]; seen=set(); occupied={row[IDENTITY] for row in existing}
    if tuple(reader.fieldnames or ())!=FIELDS: errors.append({'row':1,'code':'HEADER_INVALID','fields':list(FIELDS)})
    else:
        try:
            for number,row in enumerate(reader,2):
                if len(rows)>=500: errors.append({'row':number,'code':'ROW_LIMIT'});break
                if set(row)!=set(FIELDS) or any(not isinstance(row[f],str) or not row[f].strip() or len(row[f])>100 for f in FIELDS):
                    errors.append({'row':number,'code':'INVALID_FIELDS'});continue
                row={f:row[f].strip() for f in FIELDS}
                if not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}',row[IDENTITY]): errors.append({'row':number,'code':'INVALID_IDENTITY','field':IDENTITY})
                if row['class_code']!=scope[6:]: errors.append({'row':number,'code':'CLASS_SCOPE_DENIED','field':'class_code'})
                if row[IDENTITY] in seen: errors.append({'row':number,'code':'DUPLICATE','identity':row[IDENTITY]})
                if row[IDENTITY] in occupied: errors.append({'row':number,'code':'CONFLICT','identity':row[IDENTITY]})
                seen.add(row[IDENTITY]);rows.append(row)
        except csv.Error:
            errors.append({'row':reader.line_num,'code':'INVALID_CSV'})
    if not rows: errors.append({'row':1,'code':'EMPTY_BATCH'})
    if len(existing)+len(rows)>500: errors.append({'row':1,'code':'CLASS_SNAPSHOT_LIMIT'})
    preview={'valid':not errors,'revision':revision,'rows':rows,'errors':errors,'insertCount':len(rows),'overwriteCount':0}
    if operation=='exchange.preview': return preview
    if operation!='exchange.commit': raise ValueError('OPERATION_DENIED')
    if errors or data.get('expectedRevision')!=revision: return {'committed':False,'errors':errors or [{'row':1,'code':'VERSION_CONFLICT'}]}
    result=call('put',dict(base,expectedRevision=revision,idempotencyKey=data['receiptKey'],body={'rows':existing+rows}))
    return {'committed':True,'revision':result['revision'],'inserted':len(rows),'batchSemantics':'atomic-class-snapshot-reject-conflicts'}

for line in sys.stdin:
    request=json.loads(line)
    try:
        method=request['method'];p=request['params']
        if method=='handshake': result=p
        elif method in ('ready','health','drain','cancel','shutdown'): result={'ok':True}
        elif method=='invoke':
            with scope(p.get('context', {}).get('traceparent')):
                result=invoke(p['capability'],p['input'])
        else: raise ValueError('METHOD_DENIED')
        response={'jsonrpc':'2.0','id':request['id'],'result':result}
    except Exception as error: response={'jsonrpc':'2.0','id':request['id'],'error':{'code':-32000,'message':str(error)}}
    print(json.dumps(response),flush=True)
    if request['method']=='shutdown': break
