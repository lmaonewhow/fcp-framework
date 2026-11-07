"""Real development HTTP staging regression. Native login/MySQL are not simulated."""
import importlib.util,json,urllib.request,urllib.error,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('dev_http',Path(__file__).resolve().parents[1]/'runtime-http/test_dev_host.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
class StagingHttpTest(base.DevHostTest):
 def test_install_publish_invoke_disable(self):
  original=urllib.request.urlopen;staged=False
  def open_request(req,*args,**kwargs):
   nonlocal staged
   if not staged and req.full_url.endswith('/fcp/packages'):
    staged=True
    headers=dict(req.header_items());headers.pop('X-fcp-revision',None)
    url=req.full_url+'/staged'
    def read(request):
     with original(request,timeout=15) as response:return json.load(response)['data']
    first=read(urllib.request.Request(url,data=req.data,headers=headers))
    self.assertEqual(first['state'],'VERIFIED');self.assertFalse(first['replayed'])
    self.assertEqual(read(urllib.request.Request(url,data=req.data,headers=headers))['replayed'],True)
    host=req.full_url.removesuffix('/packages');auth={'Authorization':req.get_header('Authorization')}
    self.assertEqual(read(urllib.request.Request(host+'/ui/catalog',headers=auth))['catalog']['pages'],[])
    state=read(urllib.request.Request(host+'/plugins/test.echo',headers=auth))
    self.assertEqual(state['installation'],'ABSENT');self.assertEqual(state['revision'],0)
    with self.assertRaises(urllib.error.HTTPError) as denied:
     read(urllib.request.Request(host+'/capabilities/unpublished/invoke',data=json.dumps({'input':{},'expectedRevision':0}).encode(),headers={**auth,'Content-Type':'application/json'}))
    self.assertEqual(denied.exception.code,409)
    with self.assertRaises(urllib.error.HTTPError) as anonymous:
     read(urllib.request.Request(url,data=req.data,headers={k:v for k,v in headers.items() if k.lower()!='authorization'}))
    self.assertEqual(anonymous.exception.code,401)
   return original(req,*args,**kwargs)
  urllib.request.urlopen=open_request
  try:super().test_install_publish_invoke_disable();self.assertTrue(staged)
  finally:urllib.request.urlopen=original
if __name__=='__main__':unittest.main()
