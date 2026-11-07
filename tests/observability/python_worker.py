import sys,json,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime/observability'))
from fcp_trace import scope,current,outbound_headers
SECRET='synthetic-payload-query-token-principal-error-stderr-canary'
for line in sys.stdin:
 request=json.loads(line)
 mode=request['params'].get('mode')
 if mode=='timeout': time.sleep(2)
 with scope(request.get('traceparent',request.get('params',{}).get('context',{}).get('traceparent'))) as trace:
  result=dict(trace,downstream=outbound_headers()['traceparent'])
 response=dict(jsonrpc='2.0',id=request['id'],result=result)
 if mode=='error':
  print(SECRET,file=sys.stderr,flush=True)
  response=dict(jsonrpc='2.0',id=request['id'],error=dict(code=-32000,message=SECRET,data=dict(code=request['params']['errorCode'])))
 elif mode=='protocol-error': response['id']='wrong-id'
 elif mode=='business-unknown': response['result']=dict(ok=False,code=500,token=SECRET)
 print(json.dumps(response),flush=True)
 assert current() is None
