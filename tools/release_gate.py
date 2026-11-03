#!/usr/bin/env python3
"""A source/component PASS cannot grant a real-host release PASS."""
import argparse,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def evaluate(build:dict,api:dict,browser:dict,performance:dict)->dict:
 reasons=[];bid=build.get('buildId')
 if not bid or build.get('nativeBuild')!='PASS':reasons.append('真实 Maven/双前端构建未通过')
 for name,item in [('真实API',api),('真实浏览器',browser),('真实性能',performance)]:
  if item.get('status')!='PASS':reasons.append(name+'未通过')
  if not bid or item.get('host',{}).get('buildId')!=bid:reasons.append(name+'未绑定同一构建')
 return {'status':'PASS' if not reasons else 'BLOCKED','buildId':bid,'reasons':reasons,
         'meaning':'限定场景集成验收；不代表生产安全、所有上游功能或所有操作系统验收'}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--release',type=Path,required=True);p.add_argument('--reports',type=Path,default=ROOT/'reports');a=p.parse_args()
 def read(path):return json.loads(path.read_text()) if path.is_file() else {}
 build=read(a.release/'BUILD-EVIDENCE.json')
 result=evaluate(build,read(a.reports/'native-api.json'),read(a.reports/'native-browser/browser-report.json'),read(a.reports/'native-performance.json'))
 jar=a.release/'ruoyi-admin.jar'
 if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest()!=build.get('jarSha256'):
  result['reasons'].append('原生 JAR 缺失或校验和不匹配');result['status']='BLOCKED'
 for role in ['admin','teacher','viewer']:
  video=a.reports/'native-browser'/f'{role}.webm'
  if not video.is_file() or video.stat().st_size==0:
   result['reasons'].append(role+'真实录屏缺失');result['status']='BLOCKED'
 a.reports.mkdir(exist_ok=True)
 (a.reports/'RELEASE-GATE.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(result,ensure_ascii=False,indent=2));return 0 if result['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
