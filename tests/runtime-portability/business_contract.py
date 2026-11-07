"""Identical real-HTTP student storage/exchange assertions for both hosts.

Caller owns actual authentication, reviewed installation, grants and database. No response
substitution and no direct SQL business writes. Synthetic Campus-v1, not school acceptance.
"""
import csv
import io
import json

CSV = 'student_code,full_name,class_code\nportable1,Portable Synthetic Student,class-a\n'

def run_business_contract(client, revoke_import):
    checks = []
    def check(name, result):
        if not result:
            raise AssertionError('Shared student contract: '+name)
        checks.append(dict(name=name, status='PASS'))
    def export():
        handle = client.data('/fcp/exchange/configs/roster-fixture/exports', dict(fields=['student_code','full_name','class_code']))
        status, headers, body = client.binary('/fcp/exchange/downloads/'+handle['handle'])
        check('controlled CSV download', status == 200 and 'no-store' in headers.get('Cache-Control', ''))
        return list(csv.DictReader(io.StringIO(body.decode())))
    def subset(rows):
        return [r for r in rows if r['student_code']=='portable1']
    def semantic(p):
        return {k:p['result'][k] for k in ('rows','errors','valid')}
    status, reply = client.request('/fcp/exchange/configs/roster-fixture/exports', dict(fields=['student_code']), token=False)
    check('unauthenticated exchange denied', status >= 400 or str(reply.get('code')) != '200')
    preview = client.preview('roster-fixture', CSV)
    api = client.data('/fcp/exchange/configs/roster-fixture/preview-api', dict(cursor='business-contract'))
    check('file and fixed API share normalized business preview', semantic(preview)==semantic(api) and preview['result']['valid'])
    check('preview has no business write', not subset(export()))
    receipt = client.confirm_exchange(preview, 'shared-business-confirm')
    check('explicit confirmation commits through installed plugin', receipt['committed'])
    check('exact confirmation replay', client.confirm_exchange(preview,'shared-business-confirm')==receipt)
    rows = subset(export())
    check('durable business row and field projection', rows == [dict(student_code='portable1',full_name='Portable Synthetic Student',class_code='class-a')])
    status, reply = client.request('/fcp/exchange/previews/'+api['previewId']+'/confirm', dict(digest=api['digest'],idempotencyKey='shared-stale-preview'))
    check('stale preview cannot overwrite', status==409 and reply.get('data',{}).get('fcpCode')=='CONFLICT')
    bad=client.preview('roster-fixture','student_code,full_name,class_code\nwrong,Wrong Scope,another-class\n')
    check('class data boundary enforced', not bad['result']['valid'] and any(e['code']=='CLASS_SCOPE_DENIED' for e in bad['result']['errors']))
    revoke_import(False)
    try:
        status, headers, body = client.binary('/fcp/exchange/configs/roster-fixture/preview', CSV.encode())
        check('live import revocation denies file', status==403)
        status, reply = client.request('/fcp/exchange/configs/roster-fixture/preview-api',dict(cursor='business-contract'))
        check('live import revocation denies API',status==403)
    finally:
        revoke_import(True)
    return dict(status='PASS',scope='same Campus-v1 student fixture over real HTTP and installed process storage',checks=checks,rows=rows,totalChecks=len(checks))
