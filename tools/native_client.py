"""Real native host test client. There are no response substitutes in this module."""
from __future__ import annotations
import ipaddress, json, os, re, urllib.request, urllib.error, urllib.parse

PIN='8136a0191a2258c0e1b36a8146a1c5ebc070c139'
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  # Test credentials must never follow an unexpected login/proxy redirect.
  return None

def require_loopback(base:str):
 parsed=urllib.parse.urlsplit(base)
 if parsed.scheme not in ('http','https') or parsed.path not in ('','/') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
  raise ValueError('Use a plain HTTP(S) origin with no credentials/path')
 host=parsed.hostname
 try: local=ipaddress.ip_address(host or '').is_loopback
 except ValueError: local=host=='localhost'
 if not local: raise ValueError('Local acceptance tools refuse non-loopback origins')
 # Also validate the port now, rather than failing after a login token is attached.
 if parsed.port is not None and not 1<=parsed.port<=65535: raise ValueError('Invalid local port')
 return parsed

class NativeClient:
 def __init__(self,base:str,token:str,client_id:str):
  require_loopback(base)
  if not token or not client_id: raise ValueError('Native login token and clientid are required')
  self.base=base.rstrip('/');self.token=token;self.client_id=client_id
  self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
 def request(self,path:str,body:dict|None=None,token:bool=True):
  if not isinstance(path,str) or not path.startswith('/fcp/') or any(c in path for c in ('\r','\n','#','\\')):
   raise ValueError('Acceptance requests must target fixed local /fcp/ endpoints')
  headers={'Content-Type':'application/json','clientid':self.client_id}
  if token: headers['Authorization']='Bearer '+self.token
  req=urllib.request.Request(self.base+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers=headers)
  try:
   with self.opener.open(req,timeout=20) as r: status,data=r.status,r.read(2*1024*1024)
  except urllib.error.HTTPError as e: status,data=e.code,e.read(2*1024*1024)
  try: result=json.loads(data)
  except (ValueError,UnicodeDecodeError): raise RuntimeError(f'Expected native JSON at {path}; received HTTP {status}') from None
  return status,result
 def data(self,path:str,body:dict|None=None):
  status,result=self.request(path,body)
  if status>=400 or str(result.get('code'))!='200': raise RuntimeError(f'{path} rejected: HTTP={status}, code={result.get("code")}')
  return result['data']
 def snapshot(self): return self.data('/fcp/school/bootstrap')
 def invoke(self,snapshot,operation,params):
  return self.request('/fcp/school/evaluation/'+operation,envelope(snapshot,params))
 def evidence(self):
  value=self.data('/fcp/school/host-evidence')
  if value.get('implementation')!='ruoyi-native-host-0.6.0-dev.1' or value.get('backendCommit')!=PIN:
   raise RuntimeError('This is not the expected native RuoYi integration')
  if not value.get('springBoot','').startswith('3.5.') or value.get('database')!='MySQL':
   raise RuntimeError('Native Spring/MySQL evidence mismatch')
  if not re.fullmatch('[a-f0-9]{32}',value.get('buildId','')): raise RuntimeError('Native build ID is missing')
  expected=os.environ.get('FCP_ACCEPTANCE_DATABASE','')
  if not re.fullmatch(r'fcp_[a-z0-9_]{1,48}',expected):
   raise RuntimeError('Set FCP_ACCEPTANCE_DATABASE to the exact dedicated test schema before running acceptance')
  if value.get('databaseName')!=expected:
   raise RuntimeError('Native database differs from the explicitly selected acceptance schema')
  return value

def envelope(snapshot,params):
 return dict(input=params,generation=snapshot['generation'],instance=snapshot['instance'],expectedCaller=snapshot['caller'])

def from_env(role='ADMIN'):
 return NativeClient(os.environ.get('FCP_BASE_URL','http://127.0.0.1:8080'),
  os.environ.get(f'FCP_{role}_TOKEN',''),os.environ.get('FCP_CLIENT_ID','e5cd7e4891bf95d1d19206ce24a7b32e'))
