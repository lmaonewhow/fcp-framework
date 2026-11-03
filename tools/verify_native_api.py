#!/usr/bin/env python3
"""Requires four real native login sessions. Writes synthetic rows only to an isolated fcp_* schema.
Tokens must come from normal RuoYi login; this tool never signs or fabricates sessions.
"""
import json, sys, time, uuid
from pathlib import Path
from native_client import from_env, envelope
ROOT=Path(__file__).resolve().parents[1]
def main():
 report={'status':'BLOCKED','kind':'real-native-http','checks':[]};admin=None;original=None
 def check(name,condition):
  report['checks'].append({'name':name,'pass':bool(condition)})
  if not condition: raise AssertionError(name)
 def rejected(response,allowed):
  status,data=response
  return str(data.get('code')) in {str(x) for x in allowed} and data.get('code')!=200
 try:
  admin,teacher,viewer,other=(from_env(x) for x in ['ADMIN','TEACHER','VIEWER','OTHER'])
  report['host']=admin.evidence()
  a,t,v,o=(x.snapshot() for x in [admin,teacher,viewer,other]); original=a['pythonEnabled']
  check('four actual account identities',len({(s['caller']['scope'],s['caller']['subject']) for s in [a,t,v,o]})==4)
  check('admin native shell',a['shell']=='admin')
  check('teacher default shell and constrained classes',t['allowedShells']==['teacher'] and t['classes']==['class-a'])
  check('viewer no write or manage',not {'create','manage'}&set(v['operations']))
  check('other tenant truly differs',o['caller']['scope']!=t['caller']['scope'])
  check('anonymous rejected',rejected(admin.request('/fcp/school/bootstrap',token=False),[401]))
  check('teacher cannot read runtime admin endpoint',rejected(teacher.request('/fcp/school/runtime'),[403]))
  routes=teacher.data('/fcp/ui/soybean-routes')
  check('native raw route contract',routes[0]['component']=='Layout' and routes[0]['children'][0]['component']=='fcp/school/index')
  key='acceptance-'+uuid.uuid4().hex
  data=dict(student='verify-'+uuid.uuid4().hex[:12],classId='class-a',indicator='课堂参与',score=84,comment='Synthetic isolated native acceptance')
  write=dict(data=data,requestKey=key)
  status,created=teacher.invoke(t,'create',write);check('real JDBC write',status==200 and created.get('code')==200)
  row=created['data'];_,again=teacher.invoke(t,'create',write)
  check('real JDBC idempotency',again.get('code')==200 and again.get('data',{}).get('id')==row['id'])
  check('readonly direct API write denied',rejected(viewer.invoke(v,'create',write),[403]))
  forbidden={'data':dict(data,classId='class-b'),'requestKey':key+'-other-class'}
  check('teacher class boundary denied',rejected(teacher.invoke(t,'create',forbidden),[403]))
  check('other tenant cannot read written row',rejected(other.invoke(o,'get',{'id':row['id']}),[403,404]))
  forged=envelope(t,{});forged['tenant_id']='000001'
  check('injected tenant field rejected',rejected(teacher.request('/fcp/school/evaluation/list',forged),[400]))
  if not original: admin.data('/fcp/school/composition',envelope(a,{'enabled':True}))
  t=teacher.snapshot()
  _,stats=teacher.invoke(t,'analyze',{})
  check('native host reaches actual Python child',stats.get('code')==200 and stats.get('data',{}).get('engine')=='python-stdlib' and stats['data']['pid']>0)
  admin.data('/fcp/school/composition',envelope(admin.snapshot(),{'enabled':False}))
  check('old generation rejected',rejected(teacher.invoke(t,'list',{}),[409]))
  current=teacher.snapshot()
  check('disabled plugin absent from UI operations','analyze' not in current['operations'])
  check('disabled plugin direct API rejected',rejected(teacher.invoke(current,'analyze',{}),[409]))
  _,read=teacher.invoke(current,'get',{'id':row['id']})
  check('data retained while Python disabled',read.get('code')==200 and read['data']['id']==row['id'])
  report['status']='PASS'
 except Exception as exc:
  report.update(status='FAIL' if report['checks'] else 'BLOCKED',error=f'{type(exc).__name__}: {exc}')
 finally:
  if admin and original is not None:
   try:
    current=admin.snapshot()
    if current['pythonEnabled']!=original: admin.data('/fcp/school/composition',envelope(current,{'enabled':original}))
   except Exception as exc: report.update(status='FAIL',restoreError=type(exc).__name__)
  (ROOT/'reports').mkdir(exist_ok=True)
  (ROOT/'reports/native-api.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps(report,ensure_ascii=False,indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':sys.exit(main())
