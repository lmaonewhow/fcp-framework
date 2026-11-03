#!/usr/bin/env python3
"""Read-only HTTP list workload. Verifies native RuoYi + MySQL evidence before measurement.
Reports the dataset/query, connection strategy, failures, elapsed time and optional Linux Java RSS/PSS.
"""
from __future__ import annotations
import argparse, concurrent.futures, http.client, json, math, os, platform, threading, time, urllib.parse
from pathlib import Path
from native_client import from_env, envelope
ROOT=Path(__file__).resolve().parents[1]

def proc_memory(pid:int):
 result={'rssMiB':None,'pssMiB':None,'threads':None}
 try:
  for line in Path(f'/proc/{pid}/status').read_text().splitlines():
   if line.startswith('VmRSS:'): result['rssMiB']=int(line.split()[1])/1024
   if line.startswith('Threads:'): result['threads']=int(line.split()[1])
  try:
   for line in Path(f'/proc/{pid}/smaps_rollup').read_text().splitlines():
    if line.startswith('Pss:'): result['pssMiB']=int(line.split()[1])/1024
  except OSError: pass
 except OSError: pass
 return result

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--requests',type=int,default=2000);p.add_argument('--concurrency',type=int,default=8)
 p.add_argument('--java-pid',type=int);a=p.parse_args()
 if not 1<=a.requests<=20000 or not 1<=a.concurrency<=min(32,a.requests): raise ValueError('Workload exceeds acceptance bounds')
 report={'status':'BLOCKED','kind':'native-host-read-workload'}; stop=threading.Event();sampler=None;memory=[]
 try:
  client=from_env();report['host']=client.evidence();snapshot=client.snapshot()
  _,first=client.invoke(snapshot,'list',{'limit':25})
  if first.get('code')!=200: raise RuntimeError('Initial list failed')
  report.update(platform=platform.platform(),query='authorized evaluation list, first 25 rows',
   sampleRows=len(first['data']['items']),concurrency=a.concurrency,totalRequests=a.requests,
   connectionStrategy='one persistent HTTP connection per worker; 10 warmup requests per worker excluded',
   scope='single selected identity; local network/DB characteristics must accompany comparisons')
  if a.java_pid:
   command=Path(f'/proc/{a.java_pid}/cmdline').read_bytes().replace(b'\0',b' ')
   if b'ruoyi-admin.jar' not in command: raise ValueError('PID does not identify the native packaged host')
   def sample():
    while not stop.wait(0.1): memory.append({'elapsed':time.perf_counter(),'java':proc_memory(a.java_pid)})
   sampler=threading.Thread(target=sample,daemon=True);sampler.start()
  parsed=urllib.parse.urlsplit(client.base)
  headers={'Content-Type':'application/json','Authorization':'Bearer '+client.token,'clientid':client.client_id}
  payload=json.dumps(envelope(snapshot,{'limit':25})).encode()
  barrier=threading.Barrier(a.concurrency)
  def worker(count):
   connection=(http.client.HTTPSConnection if parsed.scheme=='https' else http.client.HTTPConnection)(parsed.hostname,parsed.port,timeout=15)
   latencies=[];failed=0
   def request():
    connection.request('POST','/fcp/school/evaluation/list',body=payload,headers=headers)
    response=connection.getresponse();data=json.loads(response.read())
    return response.status==200 and data.get('code')==200
   try:
    for _ in range(10):
     if not request(): raise RuntimeError('Warmup request failed')
    barrier.wait(timeout=30)
    begin=time.perf_counter()
    for _ in range(count):
     started=time.perf_counter()
     try:
      if not request(): failed+=1
     except Exception: failed+=1;connection.close()
     latencies.append((time.perf_counter()-started)*1000)
    end=time.perf_counter()
    return latencies,failed,begin,end
   finally: connection.close()
  counts=[a.requests//a.concurrency+(i<a.requests%a.concurrency) for i in range(a.concurrency)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as pool: results=list(pool.map(worker,counts))
  latencies=sorted(x for result in results for x in result[0]);failed=sum(r[1] for r in results)
  elapsed=max(r[3] for r in results)-min(r[2] for r in results)
  percentile=lambda q:latencies[max(0,math.ceil(len(latencies)*q)-1)]
  report.update(status='PASS' if failed==0 else 'FAIL',failures=failed,elapsedSeconds=elapsed,
   successfulRequestsPerSecond=(a.requests-failed)/elapsed,p50Ms=percentile(.5),p95Ms=percentile(.95),p99Ms=percentile(.99))
 except Exception as exc: report.update(status='FAIL' if 'host' in report else 'BLOCKED',error=f'{type(exc).__name__}: {exc}')
 finally:
  stop.set()
  if sampler:sampler.join(timeout=2)
  if memory:
   values=[m['java']['rssMiB'] for m in memory if m['java']['rssMiB'] is not None]
   report['javaRssPeakMiB']=max(values) if values else None;report['memorySamples']=memory
  (ROOT/'reports').mkdir(exist_ok=True)
  (ROOT/'reports/native-performance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps({k:v for k,v in report.items() if k!='memorySamples'},ensure_ascii=False,indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(main())
