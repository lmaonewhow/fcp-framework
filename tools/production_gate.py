#!/usr/bin/env python3
"""Production gate: measurements and durable raw evidence, never just PASS flags.
This validates evidence consistency; authenticity still requires a trusted runner and review.
It does not turn an authored JSON document into proof of execution.
"""
from __future__ import annotations
import argparse, hashlib, json, math, re, shutil, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
GROUPS={
 'api': {'auth.realLogin','auth.revokedPermission','tenant.readDenied','tenant.writeDenied','role.viewerWriteDenied','class.crossClassDenied','request.idempotent','plugin.restartPreservesDisabled','python.realChild','python.noOrphans','host.nativePagesCompatible'},
 'browser': {'admin.nativeElementPlus','teacher.nativeNaiveUI','viewer.readOnly','session.sharedLoginLogout','ui.actualWrite','ui.actualPython','ui.desktopMobile','ui.noConsoleErrors'},
 'migration': {'mysql.actualExecution','migration.upgradeWithData','migration.repeatNoChange','migration.checksumDriftDenied','migration.dirtyStateDenied','database.runtimeCannotDDL'},
 'load': {'workload.realMySQL','workload.multipleTenants','workload.mixedReadsWrites'},
 'soak': {'runtime.noCrash','runtime.noThreadLeak','runtime.noConnectionLeak','runtime.noOrphan'},
 'security': {'dependencies.sbom','dependencies.scanned','credentials.noDefaults','credentials.noLeaks','authorization.negativeTests','transport.https','plugin.trustedOnly'},
 'restore': {'backup.actualBackup','restore.newDatabase','restore.dataParity','restore.applicationSmoke'},
 'baseline': {'host.unmodifiedBaseline','host.sameHardware','host.sameWorkload'},
}

def digest(path:Path)->str:
 value=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
 return value.hexdigest()

def number(value)->bool:
 return type(value) in (int,float) and math.isfinite(value)

def probe_video(path:Path)->bool:
 executable=shutil.which('ffprobe')
 if not executable:return False
 try:
  result=subprocess.run([executable,'-v','error','-select_streams','v:0','-show_entries','stream=width,height:format=duration','-of','json',str(path)],capture_output=True,text=True,timeout=20)
  data=json.loads(result.stdout);streams=data.get('streams',[])
  return result.returncode==0 and bool(streams) and streams[0].get('width',0)>=320 and streams[0].get('height',0)>=240 and float(data['format']['duration'])>=5
 except (ValueError,KeyError,OSError,subprocess.TimeoutExpired):return False

def evaluate(build:dict,evidence:dict,root:Path,targets:dict,targets_hash:str,media_probe=probe_video)->dict:
 reasons=[];bid=build.get('buildId');jarhash=build.get('jarSha256')
 def require(condition,message):
  if not condition:reasons.append(message)
 require(bool(re.fullmatch(r'[a-f0-9]{32}',bid or '')),'缺少规范构建 ID')
 require(bool(re.fullmatch(r'[a-f0-9]{64}',jarhash or '')),'缺少 JAR 摘要')
 require(build.get('nativeBuild')=='PASS','真实若依和双前端构建未通过')
 require(not build.get('localTestOnlyReasons'),'构建使用仅本地功能测试基础设施，不能作为生产安全基线')
 java=build.get('javaUnitTests',{})
 require(isinstance(java,dict) and java.get('status')=='PASS' and number(java.get('tests')) and java['tests']>0 and java.get('skipped')==0 and java.get('failures')==0 and java.get('errors')==0,'上游 Java 测试缺失、跳过或失败')
 for shell in ('admin','teacher'):
  ui=build.get('frontend',{}).get(shell,{})
  require(ui.get('productionBuild')=='PASS' and ui.get('typecheck')=='PASS' and bool(re.fullmatch(r'[a-f0-9]{64}',ui.get('lockSha256',''))),shell+'原生构建、类型检查或锁文件证据缺失')
  require(ui.get('lockPolicy')==('restored-npm-lock' if shell=='admin' else 'upstream-pnpm-lock'),shell+'依赖锁尚未固定为可复用基线')
 root=root.resolve()
 def artifact(item,group):
  if not isinstance(item,dict):reasons.append(group+'无效证据条目');return
  name=item.get('path','')
  try:
   relative=Path(name)
   path=(root/relative).resolve(strict=True)
   if relative.is_absolute() or '..' in relative.parts or root not in path.parents or not path.is_file():raise ValueError()
   # Reject every symlink component, even if its final destination is inside root.
   current=root
   for part in relative.parts:
    current=current/part
    if current.is_symlink():raise ValueError()
   if path.stat().st_size<=0 or path.stat().st_size>512*1024*1024:raise ValueError()
   if digest(path)!=item.get('sha256'):raise ValueError()
   if path.suffix=='.webm':
    with path.open('rb') as stream:
     if stream.read(4)!=b'\x1aE\xdf\xa3':raise ValueError()
    if not media_probe(path):raise ValueError()
  except (OSError,ValueError):reasons.append(group+'原始证据缺失、路径越界或摘要/格式错误')
 for name,checks in GROUPS.items():
  report=evidence.get(name,{})
  require(report.get('schemaVersion')==1 and report.get('status')=='PASS' and report.get('execution')=='REAL',name+'未提供真实执行结果')
  require(report.get('buildId')==bid and report.get('jarSha256')==jarhash and bool(bid) and bool(jarhash),name+'未绑定同一产物')
  require(report.get('targetsSha256')==targets_hash,name+'未绑定本轮验收目标')
  require(report.get('mocked') is False and report.get('skipped')==0,name+'含模拟或未完成测试')
  passed={r.get('id') for r in report.get('checks',[]) if isinstance(r,dict) and r.get('passed') is True}
  require(checks<=passed,name+'缺少必需功能断言: '+','.join(sorted(checks-passed)))
  require(not any(isinstance(r,dict) and r.get('passed') is not True for r in report.get('checks',[])),name+'存在失败断言')
  raw=report.get('artifacts',[]);require(isinstance(raw,list) and len(raw)>0,name+'缺少原始日志/轨迹')
  if isinstance(raw,list):
   for item in raw:artifact(item,name)
  if name=='browser':
   roles={x.get('role') for x in raw if isinstance(x,dict) and str(x.get('path','')).endswith('.webm')}
   require({'admin','teacher','viewer'}<=roles,'三种身份的真实录屏不齐全')
 def threshold(group,key,bound,lower=False):
  value=evidence.get(group,{}).get('metrics',{}).get(key)
  require(number(value) and value>=0 and (value>=bound if lower else value<=bound),f'{group}.{key} 未测量或未达到目标 {">=" if lower else "<="} {bound}')
 for key in ['readP95Ms','writeP95Ms','javaIdleRssMiB','pythonRssMiB','startupSeconds']:
  threshold('load',key,targets[key])
 for key in ['concurrentUsers','recordCount','tenantCount','durationSeconds']:
  threshold('load',key,targets[key],True)
 threshold('load','successRatio',targets['successRatio'],True)
 ratio=evidence.get('load',{}).get('metrics',{}).get('successRatio')
 require(number(ratio) and ratio<=1,'load.successRatio 超过合法比例范围')
 threshold('soak','durationSeconds',targets['soakSeconds'],True)
 threshold('soak','rssGrowthRatio',targets['maxRssGrowthRatio'])
 threshold('soak','crashes',0);threshold('soak','orphanProcesses',0)
 threshold('security','unresolvedCritical',0);threshold('security','unresolvedHigh',0)
 threshold('security','successfulUnauthorizedOperations',0)
 threshold('restore','rtoSeconds',targets['rtoSeconds']);threshold('restore','rpoSeconds',targets['rpoSeconds'])
 return {'status':'PRODUCTION_READY' if not reasons else 'BLOCKED','buildId':bid,'jarSha256':jarhash,
         'targetsSha256':targets_hash,'reasons':reasons,'scope':targets['scope'],
         'trustBoundary':'Consistency checks only. Raw evidence must be generated by a trusted runner and independently reviewed.'}

def main()->int:
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--release',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
 p.add_argument('--targets',type=Path,default=ROOT/'config/production-targets.json');a=p.parse_args()
 def read(path):
  return json.loads(path.read_text(encoding='utf-8'),parse_constant=lambda x:(_ for _ in ()).throw(ValueError('Nonfinite JSON'))) if path.is_file() else {}
 try:
  targets=read(a.targets);build=read(a.release/'BUILD-EVIDENCE.json')
  reports={name:read(a.evidence/(name+'.json')) for name in GROUPS}
  result=evaluate(build,reports,a.evidence,targets,digest(a.targets))
  jar=a.release/'ruoyi-admin.jar'
  if not jar.is_file() or digest(jar)!=build.get('jarSha256'):
   result['status']='BLOCKED';result['reasons'].append('实际 Java 产物不存在或摘要不一致')
 except (ValueError,OSError,KeyError,TypeError,AttributeError) as error:
  result={'status':'BLOCKED','reasons':['验收文件无效: '+type(error).__name__]}
 a.evidence.mkdir(parents=True,exist_ok=True)
 (a.evidence/'PRODUCTION-GATE.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps(result,ensure_ascii=False,indent=2));return 0 if result['status']=='PRODUCTION_READY' else 2
if __name__=='__main__':raise SystemExit(main())
