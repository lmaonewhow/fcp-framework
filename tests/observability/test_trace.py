import sys, unittest, concurrent.futures
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime/observability'))
from fcp_trace import scope,current,capture,outbound_headers
class TraceTest(unittest.TestCase):
 def test_scope_async_isolation(self):
  parent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'
  with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
   with scope(parent) as trace:
    self.assertEqual(trace['traceId'],parent[3:35]);self.assertEqual(trace['parentSpanId'],parent[36:52])
    self.assertEqual(pool.submit(capture(current)).result(),trace)
    with self.assertRaises(ValueError):
     with scope(parent): raise ValueError('synthetic')
    self.assertEqual(current(),trace)
   self.assertIsNone(current());self.assertIsNone(pool.submit(current).result())
 def test_async_capture_executes_in_origin_not_awaiters_scope(self):
  import asyncio
  async def read():
   await asyncio.sleep(0)
   return current()
  async def check():
   with scope('00-'+'1'*32+'-'+'2'*16+'-00') as original:
    copied=capture(read)
   self.assertEqual(await copied(),original)
   with scope('00-'+'3'*32+'-'+'4'*16+'-00') as other:
    self.assertEqual(await copied(),original)
    self.assertEqual(current(),other)
   self.assertIsNone(current())
  asyncio.run(check())
 def test_bad_context(self):
  for header in (None,'', 'x'*10000,'00-'+'0'*32+'-0123456789abcdef-00'):
   with scope(header) as trace:
    self.assertRegex(trace['traceId'],r'^[a-f0-9]{32}$');self.assertNotEqual(trace['traceId'],'0'*32)
    self.assertEqual(len(outbound_headers()['traceparent']),55)
 def test_http_propagation_is_explicit_and_matches_plugin_scope(self):
  import http.server, threading, time
  sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime/ingestion'))
  import inputs
  headers=[]
  class Handler(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    headers.append(dict(self.headers));self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(b'{"items":[]}')
   def log_message(self,*args):pass
  server=http.server.HTTPServer(('127.0.0.1',0),Handler)
  worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
  try:
   config={'baseUrl':f'http://127.0.0.1:{server.server_port}','allowLoopback':True}
   with scope('00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00'):
    inputs.fetch_json(config,'/',time.monotonic()+3)
    self.assertNotIn('traceparent',headers[-1])
    config['propagateTrace']=True;expected=outbound_headers()['traceparent']
    inputs.fetch_json(config,'/',time.monotonic()+3)
    self.assertEqual(headers[-1]['traceparent'],expected)
    self.assertNotIn('baggage',headers[-1]);self.assertNotIn('tracestate',headers[-1])
  finally:server.shutdown();server.server_close();worker.join()
if __name__=='__main__':unittest.main()
