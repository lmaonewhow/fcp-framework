import sys,json,time,os
count=0
for line in sys.stdin:
    r=json.loads(line);m=r['method'];p=r['params'];result=None
    if m=='handshake': result=p
    elif m in ('ready','health','drain','cancel','shutdown'): result={'ok':True}
    elif m=='invoke':
        count+=1
        data=p['input']
        if data.get('crash'): os._exit(7)
        if data.get('sleep'): time.sleep(data['sleep'])
        if data.get('dependency'):
            print(json.dumps({'jsonrpc':'2.0','id':'callback','method':'fcp.call','params':{'dependency':data['dependency'] if isinstance(data['dependency'],str) else 'echo','operation':'invoke','input':data.get('payload',{'message':'from callback'})}}),flush=True)
            reply=json.loads(sys.stdin.readline())
            result=reply.get('result',{'callbackError':reply.get('error')})
        else: result={'input':data,'tenant':p['context']['tenantId'],'principal':p['context']['principal'],'count':count,'traceId':p['context']['traceId'],'traceparent':p['context'].get('traceparent'),'secretPresent':'FCP_TEST_SECRET' in os.environ,'pid':os.getpid()}
    else: raise RuntimeError('unsupported method')
    print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':result}),flush=True)
    if m=='shutdown':break
