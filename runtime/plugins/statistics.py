#!/usr/bin/env python3
"""Pure, bounded JSON-RPC scoring summary. No HTTP server, database or third-party packages."""
import json
import os
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "observability"))
from fcp_trace import scope
MAX_FRAME=1_048_576

def summarize(params):
    if not isinstance(params,dict) or set(params)!={'scores'}:
        raise ValueError('Only scores are accepted')
    scores=params['scores']
    if not isinstance(scores,list) or len(scores)>1000:
        raise ValueError('At most 1000 scores are accepted')
    if any(type(value) is not int or not 0<=value<=100 for value in scores):
        raise ValueError('Scores must be integers in 0..100')
    return dict(count=len(scores),average=round(sum(scores)/len(scores),2) if scores else 0,
                minimum=min(scores,default=0),maximum=max(scores,default=0),
                engine='python-stdlib',method='arithmetic-summary',pid=os.getpid())

def main():
    while True:
        raw=sys.stdin.buffer.readline(MAX_FRAME+1)
        if not raw:
            return
        if len(raw)>MAX_FRAME or not raw.endswith(b'\n'):
            raise SystemExit(2)
        request_id=None
        try:
            request=json.loads(raw)
            if not isinstance(request,dict) or set(request) not in ({'jsonrpc','id','method','params'}, {'jsonrpc','id','method','params','traceparent'}):
                raise ValueError('Invalid request envelope')
            request_id=request['id']
            if request['jsonrpc']!='2.0' or not isinstance(request_id,str) or request['method']!='stats.analyze':
                raise ValueError('Unknown method or protocol')
            with scope(request.get('traceparent')):
                result=summarize(request['params'])
            reply=dict(jsonrpc='2.0',id=request_id,result=result)
        except (ValueError,TypeError,KeyError,UnicodeDecodeError) as error:
            reply=dict(jsonrpc='2.0',id=request_id,error=dict(code=-32602,message=str(error),data={'code':'INVALID_INPUT'}))
        sys.stdout.write(json.dumps(reply,ensure_ascii=False,allow_nan=False,separators=(',',':'))+'\n')
        sys.stdout.flush()

if __name__=='__main__':
    main()
