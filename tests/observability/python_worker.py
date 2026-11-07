import sys,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime/observability'))
from fcp_trace import scope,current,outbound_headers
for line in sys.stdin:
 request=json.loads(line)
 with scope(request.get('traceparent',request.get('params',{}).get('context',{}).get('traceparent'))) as trace:
  result=dict(trace,downstream=outbound_headers()['traceparent'])
 print(json.dumps(dict(jsonrpc='2.0',id=request['id'],result=result)),flush=True)
 assert current() is None
