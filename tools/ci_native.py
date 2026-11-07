#!/usr/bin/env python3
"""CI-only genuine native build and MySQL/JVM smoke, with optional real UI ingestion.
--ingestion adds encrypted native login, both shells, imports, authorization and restart.
No load, long-running stability or production-readiness claim is made.
Uses fresh GitHub Actions MySQL/Redis services on dedicated loopback ports.
"""
from __future__ import annotations
import argparse,hashlib,json,os,re,secrets,shutil,socket,subprocess,sys,tempfile,time,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from native import doctor
from ci_cleanup import OwnedDisposableDirectory,record_private_cleanup
from ci_native_modules import NativeModule,run_native_modules

class PrerequisiteError(RuntimeError):
 """The isolated CI environment is missing an explicitly required prerequisite."""

def acceptance_redis_prefix()->str:
 # Keep the actual Spring/Redis acceptance guard intact: fresh 128-bit namespace.
 return 'fcp_accept_'+secrets.token_hex(16)

def failure_status(error:Exception)->str:
 # A compiler/test failure is observed failure, even before a JAR exists.
 return 'BLOCKED' if isinstance(error,PrerequisiteError) else 'FAIL'

def command(args,cwd=ROOT,env=None,timeout=2400):
 result=subprocess.run([str(x) for x in args],cwd=cwd,env=env,timeout=timeout,check=False)
 if result.returncode:raise RuntimeError(str(args[0])+' failed: '+str(result.returncode))

def private(path:Path,content:str):
 path.write_text(content,encoding='utf-8');path.chmod(0o600)

def run()->int:
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--resolve-admin-lock',action='store_true');parser.add_argument('--ingestion',action='store_true');parser.add_argument('--exchange',action='store_true',help='Explicit synthetic exchange acceptance; requires --ingestion');a=parser.parse_args()
 if a.exchange and not a.ingestion:parser.error('--exchange requires genuine --ingestion login fixture')
 report={'status':'BLOCKED','scope':'real native build, actual MySQL state adapter and Java/static UI smoke ONLY','browser':'NOT_RUN','runtimePlugins':{'status':'NOT_RUN'},'runtimeStorageLifecycle':{'status':'NOT_RUN'},'runtimeExchange':{'status':'NOT_RUN'},'production':'BLOCKED'}
 process=None;private_dir=None;private_cleanup=None;fixture=None;runtime_fixture=None;exchange_fixture=None;failure_fixture=None;storage_lifecycle_fixture=None;deployment_user=None;deployment_password=None
 try:
  if os.environ.get('GITHUB_ACTIONS')!='true':raise PrerequisiteError('This orchestration is restricted to its disposable manual GitHub Actions job')
  requirements=doctor()
  if requirements['status']!='READY' or not shutil.which('mysql'):raise PrerequisiteError('Build tools/network/mysql CLI missing')
  root_password=os.environ.get('FCP_CI_MYSQL_PASSWORD','')
  if not re.fullmatch('[A-Za-z0-9_-]{16,100}',root_password):raise PrerequisiteError('Explicit CI MySQL fixture password is required')
  private_cleanup=OwnedDisposableDirectory.create();private_dir=private_cleanup.path
  command(['java',ROOT/'bootstrap/GenerateKeys.java',private_dir/'keys'])
  sources=ROOT/'work/ci-sources'
  command([sys.executable,ROOT/'tools/native.py','assemble','--work',sources,'--build-env',private_dir/'keys/build.env'])
  database='fcp_ci_'+secrets.token_hex(6);runtime_user='fcp_'+secrets.token_hex(6);runtime_password=secrets.token_hex(24);redis_password=secrets.token_hex(24)
  mysql_file=private_dir/'mysql.cnf';private(mysql_file,f'[client]\nhost=127.0.0.1\nport=33306\nuser=root\npassword={root_password}\nssl-mode=DISABLED\n')
  def sql(text):
   result=subprocess.run(['mysql','--defaults-extra-file='+str(mysql_file),'--batch','--skip-column-names'],input=text,text=True,capture_output=True,timeout=60)
   if result.returncode:raise RuntimeError('Isolated CI SQL failed; inspect private database service logs')
   return result.stdout
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
  props=re.sub(r'(?m)^FCP_REDIS_PREFIX=.*$', 'FCP_REDIS_PREFIX='+acceptance_redis_prefix(), props)
  props+='\nFCP_PORT=18787\nFCP_BIND=127.0.0.1\n'
  if (ROOT/'core/src/main/java/fcp/runtime/EngineRuntimePort.java').is_file():
   props+='\nfcp.runtime.enabled=true\nFCP_RUNTIME_ROOT='+str(private_dir/'runtime-plugins')+'\nFCP_TRUSTED_PUBLISHERS='+('test,fcp-fixtures' if a.exchange else 'test')+'\nFCP_RUNTIME_PYTHON='+str(shutil.which('python3'))+'\nFCP_RUNTIME_NODE='+str(shutil.which('node'))+'\n'
  if a.ingestion:
   from ci_ingestion import Fixture
   prefix=re.search(r'(?m)^FCP_REDIS_PREFIX=(.+)$',props)[1]
   fixture=Fixture(database,sql,private_dir,redis_password,prefix)
   if (ROOT/'tests/runtime-native/native_runtime_acceptance.py').is_file():
    sys.path.insert(0,str(ROOT/'tests/runtime-native'))
    from native_runtime_acceptance import RuntimeFixture
    runtime_fixture=RuntimeFixture(database,sql,private_dir,fixture)
    from failure_boundaries import FailureBoundaryFixture, run_mysql_failure_boundaries
    failure_fixture=FailureBoundaryFixture(database,sql,private_dir,fixture)
    from storage_lifecycle_acceptance import StorageLifecycleFixture
    storage_lifecycle_fixture=StorageLifecycleFixture(database,sql,private_dir,fixture)
    if not a.exchange:
     deployment_user='fcpm_'+secrets.token_hex(6);deployment_password=secrets.token_hex(24)
     sql(f"CREATE USER '{deployment_user}'@'%' IDENTIFIED BY '{deployment_password}'; GRANT SELECT,INSERT,UPDATE,CREATE,ALTER,INDEX ON `{database}`.* TO '{deployment_user}'@'%';")
     props+='\nfcp.runtime.storage-deployment.enabled=true\nFCP_STORAGE_DEPLOYMENT_JDBC_URL='+url+'\nFCP_STORAGE_DEPLOYMENT_USER='+deployment_user+'\nFCP_STORAGE_DEPLOYMENT_PASSWORD='+deployment_password+'\n'

   props+='\nFCP_INGESTION_CONNECTIONS='+str(fixture.connection_file)+'\n'
   if a.exchange:
    sys.path.insert(0,str(ROOT/'tests/runtime-exchange'))
    from native_exchange_acceptance import ExchangeFixture
    exchange_fixture=ExchangeFixture(database,sql,private_dir,fixture)
    deployment_user='fcpx_'+secrets.token_hex(6);deployment_password=secrets.token_hex(24)
    sql(f"CREATE USER '{deployment_user}'@'%' IDENTIFIED BY '{deployment_password}'; GRANT SELECT,INSERT,UPDATE,CREATE,ALTER,INDEX ON `{database}`.* TO '{deployment_user}'@'%';")
    props+=exchange_fixture.properties()+'\nfcp.runtime.storage-deployment.enabled=true\nFCP_STORAGE_DEPLOYMENT_JDBC_URL='+url+'\nFCP_STORAGE_DEPLOYMENT_USER='+deployment_user+'\nFCP_STORAGE_DEPLOYMENT_PASSWORD='+deployment_password+'\n'
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
  if exchange_fixture:
   retention_classes=ROOT/'build/export-retention-native';retention_classes.mkdir(parents=True,exist_ok=True)
   retention_sources=sorted((ROOT/'core/src/main/java').rglob('*.java'))+[ROOT/'tests/runtime-exchange/NativeExportRetentionProbe.java']
   command(['javac','--release','17','-encoding','UTF-8','-d',retention_classes,*retention_sources],timeout=120)
   retention_env=os.environ.copy();retention_env.update(FCP_EXCHANGE_RETENTION_TEST_URL=url,FCP_EXCHANGE_RETENTION_TEST_USER=runtime_user,FCP_EXCHANGE_RETENTION_TEST_PASSWORD=runtime_password)
   exchange_fixture.configure_retention_probe(['java','-cp',str(retention_classes)+os.pathsep+str(driver),'NativeExportRetentionProbe'],retention_env)
  # Classes were built by the preceding component job step. This invocation uses a REAL database driver.
  command(['java','-cp',str(ROOT/'build/component-classes')+os.pathsep+str(driver),'RealMySqlRegression',jdbc],timeout=120)
  report['mysqlAdapter']='PASS'
  # New storage checks execute against this freshly-created database, not a JDBC proxy.
  if (ROOT/'tests/runtime-storage/MysqlStorageTest.java').is_file():
   if deployment_user is None:
    deployment_user='fcpm_'+secrets.token_hex(6);deployment_password=secrets.token_hex(24)
    sql(f"CREATE USER '{deployment_user}'@'%' IDENTIFIED BY '{deployment_password}'; GRANT SELECT,INSERT,UPDATE,CREATE,ALTER,INDEX ON `{database}`.* TO '{deployment_user}'@'%';")
   storage_classes=ROOT/'build/runtime-storage-native';storage_classes.mkdir(parents=True,exist_ok=True)
   storage_sources=sorted((ROOT/'core/src/main/java').rglob('*.java'))+sorted((ROOT/'tests/runtime-storage').glob('*.java'))
   command(['javac','--release','17','-encoding','UTF-8','-d',storage_classes,*storage_sources],timeout=120)
   storage_env=os.environ.copy();storage_env.update(FCP_STORAGE_TEST_URL=url,FCP_STORAGE_TEST_USER=runtime_user,FCP_STORAGE_TEST_PASSWORD=runtime_password,FCP_STORAGE_TEST_DEPLOY_USER=deployment_user,FCP_STORAGE_TEST_DEPLOY_PASSWORD=deployment_password)
   command(['java','-cp',str(storage_classes)+os.pathsep+str(driver),'MysqlStorageTest'],env=storage_env,timeout=180)
   report['runtimePluginStorage']='PASS'
   if failure_fixture:
    failure_mysql=run_mysql_failure_boundaries(driver,storage_env)
    report['runtimeMysqlFailureBoundaries']={'status':failure_mysql['status'],'totalChecks':failure_mysql['totalChecks']}
  # Grant-binding probes own a second fresh database: never modify the native lifecycle journal.
  grant_test=ROOT/'tests/runtime-storage/native/MysqlGrantBindingTest.java'
  if grant_test.is_file():
   grant_database='fcp_ci_'+secrets.token_hex(6);grant_user='fcpg_'+secrets.token_hex(6);grant_password=secrets.token_hex(24)
   sql(f'CREATE DATABASE `{grant_database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
   sql(f'USE `{grant_database}`;\n'+(ROOT/'sql/native/V005__runtime_plugins.sql').read_text())
   sql(f"CREATE USER '{grant_user}'@'%' IDENTIFIED BY '{grant_password}'; GRANT SELECT,INSERT,UPDATE,DELETE ON `{grant_database}`.* TO '{grant_user}'@'%';")
   grant_classes=ROOT/'build/runtime-grants-native';grant_classes.mkdir(parents=True,exist_ok=True)
   grant_sources=sorted((ROOT/'core/src/main/java').rglob('*.java'))+[ROOT/'host/src/main/java/dev/fcp/host/PluginPolicy.java',ROOT/'host/src/main/java/dev/fcp/host/JdbcPluginPolicy.java',ROOT/'tests/runtime-storage/MysqlStorageTest.java',ROOT/'tests/runtime-storage/StorageDeclarationTest.java',grant_test]
   command(['javac','--release','17','-encoding','UTF-8','-d',grant_classes,*grant_sources],timeout=120)
   grant_env=os.environ.copy();grant_env.update(FCP_STORAGE_TEST_URL=f'jdbc:mysql://127.0.0.1:33306/{grant_database}?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC',FCP_STORAGE_TEST_USER=grant_user,FCP_STORAGE_TEST_PASSWORD=grant_password)
   command(['java','-cp',str(grant_classes)+os.pathsep+str(driver),'MysqlGrantBindingTest'],env=grant_env,timeout=180)
   report['runtimePluginGrantBinding']='PASS'
  # Crash matrix owns a third isolated database and cannot modify application/grant state.
  if (ROOT/'tests/runtime-crash/run.sh').is_file():
   crash_database='fcp_ci_crash_'+secrets.token_hex(6);crash_user='fcpc_'+secrets.token_hex(6);crash_password=secrets.token_hex(24)
   sql(f'CREATE DATABASE `{crash_database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
   sql(f'USE `{crash_database}`;\n'+(ROOT/'sql/native/V005__runtime_plugins.sql').read_text())
   sql(f"CREATE USER '{crash_user}'@'%' IDENTIFIED BY '{crash_password}'; GRANT SELECT,INSERT,UPDATE ON `{crash_database}`.fcp_runtime_state TO '{crash_user}'@'%';")
   crash_env=os.environ.copy();crash_env.update(FCP_CRASH_TEST_URL=f'jdbc:mysql://127.0.0.1:33306/{crash_database}?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC',FCP_CRASH_TEST_USER=crash_user,FCP_CRASH_TEST_PASSWORD=crash_password,FCP_CRASH_TEST_DRIVER_CLASSPATH=str(driver))
   command(['bash',ROOT/'tests/runtime-crash/run.sh',ROOT/'reports/runtime-crash-mysql'],env=crash_env,timeout=240)
   crash_result=json.loads((ROOT/'reports/runtime-crash-mysql/acceptance.json').read_text())
   if crash_result.get('result')!='PASS' or crash_result.get('journalBackend')!='MYSQL' or crash_result.get('mysqlJournalCrashRecovery')!='PASS' or crash_result.get('crashCases')!=12 or crash_result.get('failedCases')!=0 or crash_result.get('databasePreflight',{}).get('emptyAtStart') is not True:raise RuntimeError('MySQL crash matrix did not produce the required genuine database evidence')
   report['runtimeCrashRecovery']={'status':'PASS','crashCases':crash_result['crashCases'],'databaseIsolation':'separate freshly-created CI database','permissions':'SELECT,INSERT,UPDATE on own state table only'}
  log_path=private_dir/'native-startup.log';started=time.monotonic()
  def start_host():
   nonlocal process
   with log_path.open('a') as log:
    process=subprocess.Popen(['java','-Xms128m','-Xmx1024m','-jar',str(jar),'--spring.profiles.active=prod,fcp'],cwd=private_dir,stdout=log,stderr=subprocess.STDOUT)
   ready=False;start=time.monotonic()
   while time.monotonic()-start<120 and process.poll() is None:
    try:
     with urllib.request.urlopen('http://127.0.0.1:18787/admin/',timeout=2) as response:html=response.read(2_000_000).decode()
     if '/admin/' in html and '<script' in html:ready=True;break
    except (OSError,UnicodeError):pass
    time.sleep(.5)
   if not ready:raise RuntimeError('Native JVM/static management frontend did not become ready')
  def restart():
   process.terminate()
   process.wait(timeout=35)
   start_host()
  start_host()
  with urllib.request.urlopen('http://127.0.0.1:18787/teacher/',timeout=5) as response:teacher=response.read(2_000_000).decode()
  if '/teacher/' not in teacher or '<script' not in teacher:raise RuntimeError('Native teacher production assets missing')
  report.update(status='PASS',nativeStartupSeconds=round(time.monotonic()-started,3),nativeJavaPid=process.pid,adminStatic='PASS',teacherStatic='PASS')
  if fixture:
   os.environ['FCP_ACCEPTANCE_DATABASE']=database
   result=fixture.verify('http://127.0.0.1:18787',report['build']['buildId'],restart)
   report.update(scope=result['scope'],browser=result['status'],ingestion=result['status'],nativeChecks=result['totalChecks'])
   def native_pid():return process.pid if process and process.poll() is None else None
   modules=[]
   if runtime_fixture:
    modules.append(NativeModule('runtimePlugins',lambda:runtime_fixture.verify('http://127.0.0.1:18787',report['build']['buildId'],native_pid),
     lambda value:{'status':value['status'],'totalChecks':value['totalChecks'],'browser':value['browser'],'backendPidUnchanged':value['backendPidUnchanged']}))
   if storage_lifecycle_fixture:
    modules.append(NativeModule('runtimeStorageLifecycle',lambda:storage_lifecycle_fixture.verify('http://127.0.0.1:18787',report['build']['buildId'],native_pid),
     lambda value:{'status':value['status'],'acceptance':'A11','totalChecks':value['totalChecks'],'snapshots':len(value['snapshots'])}))
   if failure_fixture:
    modules.append(NativeModule('runtimeFailureBoundaries',lambda:failure_fixture.verify('http://127.0.0.1:18787',report['build']['buildId'],native_pid),
     lambda value:{'status':value['status'],'totalChecks':value['totalChecks']}))
   if exchange_fixture:
    modules.append(NativeModule('runtimeExchange',lambda:exchange_fixture.verify('http://127.0.0.1:18787',report['build']['buildId'],native_pid),
     lambda value:{'status':value['status'],'totalChecks':value['totalChecks'],'authorizationManagement':value['authorizationManagement']}))
   if exchange_fixture:
    sys.path.insert(0,str(ROOT/'tests/runtime-portability'))
    from development_business import verify_development_business
    modules.append(NativeModule('developmentBusiness',lambda:verify_development_business(private_dir,url,runtime_user,runtime_password,driver,exchange_fixture.endpoint,exchange_fixture.report.get('sharedBusinessContract',{})),
     lambda value:{'status':value['status'],'sameNativeRows':value['sameNativeRows'],'jvmRestartPersistence':value['jvmRestartPersistence']}))
   run_native_modules(report,modules,prerequisites_passed=result.get('status')=='PASS' and bool(report.get('build',{}).get('buildId')),
    host_alive=lambda:process is not None and process.poll() is None,
    secrets_provider=lambda:[*fixture.diagnostic_secrets,fixture.password])

 except Exception as error:report.update(status=failure_status(error),error=type(error).__name__+': '+str(error))
 finally:
  if process and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=35);report['processShutdown']='GRACEFUL_EXIT'
   except subprocess.TimeoutExpired:process.kill();process.wait();report.update(status='FAIL',processShutdown='FORCED_KILL')
  if exchange_fixture:exchange_fixture.close()
  if fixture:fixture.close()
  output=ROOT/'reports';output.mkdir(exist_ok=True)
  if private_dir and (private_dir/'native-startup.log').exists():
   text=(private_dir/'native-startup.log').read_text(errors='replace')
   for config in private_dir.rglob('*.properties'):
    for line in config.read_text().splitlines():
     if '=' in line:
      value=line.split('=',1)[1]
      if len(value)>=8:text=text.replace(value,'[REDACTED_CI_CONFIGURATION]')
   if fixture:text=text.replace(fixture.password,'[REDACTED_FIXTURE_PASSWORD]')
   (output/'native-startup-redacted.log').write_text(text)
  (output/'ci-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  # Redacted diagnostics and original gate failure are persisted before scoped cleanup.
  record_private_cleanup(report,private_cleanup,process_stopped=process is None or process.poll() is not None)
  (output/'ci-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps(report,ensure_ascii=False,indent=2))
 return 0 if report['status']=='PASS' else 2
if __name__=='__main__':raise SystemExit(run())
