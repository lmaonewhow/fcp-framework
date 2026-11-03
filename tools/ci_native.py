#!/usr/bin/env python3
"""Manual CI-only native build + MySQL adapter + JVM/static-resource smoke.
Does NOT declare browser, native identity, security, load or production readiness.
Uses fresh GitHub Actions MySQL/Redis services on dedicated loopback ports.
"""
from __future__ import annotations
import argparse,hashlib,json,os,re,secrets,shutil,socket,subprocess,sys,tempfile,time,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native import doctor

def command(args,cwd=ROOT,env=None,timeout=2400):
 result=subprocess.run([str(x) for x in args],cwd=cwd,env=env,timeout=timeout,check=False)
 if result.returncode:raise RuntimeError(str(args[0])+' failed: '+str(result.returncode))

def private(path:Path,content:str):
 path.write_text(content,encoding='utf-8');path.chmod(0o600)

def run()->int:
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--resolve-admin-lock',action='store_true');a=parser.parse_args()
 report={'status':'BLOCKED','scope':'real native build, actual MySQL state adapter and Java/static UI smoke ONLY','browser':'NOT_RUN','production':'BLOCKED'}
 process=None;private_dir=None
 try:
  if os.environ.get('GITHUB_ACTIONS')!='true':raise RuntimeError('This orchestration is restricted to its disposable manual GitHub Actions job')
  requirements=doctor()
  if requirements['status']!='READY' or not shutil.which('mysql'):raise RuntimeError('Build tools/network/mysql CLI missing')
  root_password=os.environ.get('FCP_CI_MYSQL_PASSWORD','')
  if not re.fullmatch('[A-Za-z0-9_-]{16,100}',root_password):raise RuntimeError('Explicit CI MySQL fixture password is required')
  private_dir=Path(tempfile.mkdtemp(prefix='fcp-native-ci-'));private_dir.chmod(0o700)
  command(['java',ROOT/'bootstrap/GenerateKeys.java',private_dir/'keys'])
  sources=ROOT/'work/ci-sources'
  command([sys.executable,ROOT/'tools/native.py','assemble','--work',sources,'--build-env',private_dir/'keys/build.env'])
  database='fcp_ci_'+secrets.token_hex(6);runtime_user='fcp_'+secrets.token_hex(6);runtime_password=secrets.token_hex(24);redis_password=secrets.token_hex(24)
  mysql_file=private_dir/'mysql.cnf';private(mysql_file,f'[client]\nhost=127.0.0.1\nport=33306\nuser=root\npassword={root_password}\nssl-mode=DISABLED\n')
  def sql(text):
   result=subprocess.run(['mysql','--defaults-extra-file='+str(mysql_file),'--batch','--skip-column-names'],input=text,text=True,capture_output=True,timeout=60)
   if result.returncode:raise RuntimeError('Isolated CI SQL failed; inspect private database service logs')
  sql(f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
  command([sys.executable,ROOT/'tools/mysql_install.py','--backend',sources/'upstream/backend','--database',database,'--defaults-file',mysql_file,'--apply'])
  sql(f"CREATE USER '{runtime_user}'@'%' IDENTIFIED BY '{runtime_password}'; GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{runtime_user}'@'%';")
  # Local disposable Redis service is unconfigured until this step. No production host is accepted here.
  payload=['CONFIG','SET','requirepass',redis_password]
  request='*'+str(len(payload))+'\r\n'+''.join('$'+str(len(v))+'\r\n'+v+'\r\n' for v in payload)
  with socket.create_connection(('127.0.0.1',36379),timeout=5) as redis:
   redis.sendall(request.encode());reply=redis.recv(1024)
   if not reply.startswith(b'+OK'):raise RuntimeError('CI Redis password initialization failed')
  url=f'jdbc:mysql://127.0.0.1:33306/{database}?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC'
  jdbc=private_dir/'jdbc.properties';private(jdbc,f'jdbc.url={url}\njdbc.user={runtime_user}\njdbc.password={runtime_password}\n')
  props=(private_dir/'keys/fcp-runtime.properties').read_text()
  props=re.sub(r'(?m)^FCP_JDBC_URL=.*$',f'FCP_JDBC_URL={url}',props)
  props=re.sub(r'(?m)^FCP_DB_USER=.*$',f'FCP_DB_USER={runtime_user}',props)
  props=re.sub(r'(?m)^FCP_DB_PASSWORD=.*$',f'FCP_DB_PASSWORD={runtime_password}',props)
  props=re.sub(r'(?m)^FCP_REDIS_PASSWORD=.*$',f'FCP_REDIS_PASSWORD={redis_password}',props)
  props=re.sub(r'(?m)^FCP_REDIS_PORT=.*$', 'FCP_REDIS_PORT=36379', props)
  props=re.sub(r'(?m)^FCP_REDIS_PREFIX=.*$', 'FCP_REDIS_PREFIX=ci'+secrets.token_hex(8), props)
  props+='\nFCP_PORT=18787\nFCP_BIND=127.0.0.1\n'
  private(private_dir/'fcp-runtime.properties',props)
  # @SpringBootTest in the pinned upstream initializes FCP and therefore requires the schema first.
  work=ROOT/'work/ci-native'
  build=[sys.executable,ROOT/'tools/native.py','build','--work',work,'--local-sources',sources/'upstream',
         '--build-env',private_dir/'keys/build.env','--test-runtime-config',private_dir/'fcp-runtime.properties']
  if a.resolve_admin_lock:build+=['--resolve-admin-lock']
  else:build+=['--admin-lock',ROOT/'locks/admin-package-lock.json']
  command(build)
  release=work/'release';report['build']=json.loads((release/'BUILD-EVIDENCE.json').read_text())
  jar=release/'ruoyi-admin.jar';driver=private_dir/'mysql-driver.jar'
  with zipfile.ZipFile(jar) as archive:
   matches=[n for n in archive.namelist() if re.fullmatch(r'BOOT-INF/lib/mysql-connector-j-[^/]+\.jar',n)]
   if len(matches)!=1:raise RuntimeError('One native MySQL Connector/J dependency is required')
   driver.write_bytes(archive.read(matches[0]))
  # Classes were built by the preceding component job step. This invocation uses a REAL database driver.
  command(['java','-cp',str(ROOT/'build/component-classes')+os.pathsep+str(driver),'RealMySqlRegression',jdbc],timeout=120)
  report['mysqlAdapter']='PASS'
  log_path=private_dir/'native-startup.log';started=time.monotonic()
  with log_path.open('w') as log:
   process=subprocess.Popen(['java','-Xms128m','-Xmx1024m','-jar',str(jar),'--spring.profiles.active=prod,fcp'],cwd=private_dir,stdout=log,stderr=subprocess.STDOUT)
   ready=False
   while time.monotonic()-started<120 and process.poll() is None:
    try:
     with urllib.request.urlopen('http://127.0.0.1:18787/admin/',timeout=2) as response:html=response.read(2_000_000).decode()
     if '/admin/' in html and '<script' in html:ready=True;break
    except (OSError,UnicodeError):pass
    time.sleep(.5)
   if not ready:raise RuntimeError('Native JVM/static management frontend did not become ready')
   with urllib.request.urlopen('http://127.0.0.1:18787/teacher/',timeout=5) as response:teacher=response.read(2_000_000).decode()
   if '/teacher/' not in teacher or '<script' not in teacher:raise RuntimeError('Native teacher production assets missing')
   report.update(status='PASS',nativeStartupSeconds=round(time.monotonic()-started,3),nativeJavaPid=process.pid,adminStatic='PASS',teacherStatic='PASS')
 except Exception as error:report.update(status='FAIL' if 'build' in report else 'BLOCKED',error=type(error).__name__+': '+str(error))
 finally:
  if process and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=35);report['processShutdown']='GRACEFUL_EXIT'
   except subprocess.TimeoutExpired:process.kill();process.wait();report.update(status='FAIL',processShutdown='FORCED_KILL')
  output=ROOT/'reports';output.mkdir(exist_ok=True)
  if private_dir and (private_dir/'native-startup.log').exists():
   text=(private_dir/'native-startup.log').read_text(errors='replace')
   for config in private_dir.rglob('*.properties'):
    for line in config.read_text().splitlines():
     if '=' in line:
      value=line.split('=',1)[1]
      if len(value)>=8:text=text.replace(value,'[REDACTED_CI_CONFIGURATION]')
   (output/'native-startup-redacted.log').write_text(text)
  (output/'ci-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  if private_dir:shutil.rmtree(private_dir)
  print(json.dumps(report,ensure_ascii=False,indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(run())
