"""Tool safety/unit fixtures only. These tests do not stand in for native HTTP/MySQL execution."""
import json,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from native_client import NativeClient,NoRedirect,require_loopback
import native

class LocalAcceptanceTests(unittest.TestCase):
 def evidence(self,database='fcp_local_review'):
  return dict(implementation='ruoyi-native-host-0.6.0-dev.1',backendCommit='8136a0191a2258c0e1b36a8146a1c5ebc070c139',springBoot='3.5.15',database='MySQL',buildId='a'*32,databaseName=database)
 def test_loopback_origins(self):
  for origin in ['http://localhost:8080','http://127.0.0.1:8080','http://[::1]:8080']:
   self.assertIsNotNone(require_loopback(origin))
 def test_remote_or_ambiguous_origins_denied(self):
  for origin in ['http://example.com','http://localhost.example.com','http://10.0.0.1','http://user:password@127.0.0.1','http://127.0.0.1/path','http://127.0.0.1:70000']:
   with self.subTest(origin=origin),self.assertRaises(ValueError):require_loopback(origin)
 def test_http_redirects_not_followed(self):
  self.assertIsNone(NoRedirect().redirect_request(None,None,302,'redirect',{},'https://example.com'))
 def test_endpoint_path_checked_before_network(self):
  client=NativeClient('http://127.0.0.1:8080','fixture-only','client')
  with patch.object(client.opener,'open') as opened:
   for path in ['https://example.com','//example.com','/auth/login','/fcp/\r\nHost: example.com']:
    with self.assertRaises(ValueError):client.request(path)
   opened.assert_not_called()
 def test_expected_database_required(self):
  client=NativeClient('http://127.0.0.1:8080','fixture-only','client')
  with patch.dict(os.environ,{'FCP_ACCEPTANCE_DATABASE':''}),patch.object(client,'data',return_value=self.evidence()),self.assertRaises(RuntimeError):client.evidence()
 def test_wrong_database_denied(self):
  client=NativeClient('http://127.0.0.1:8080','fixture-only','client')
  with patch.dict(os.environ,{'FCP_ACCEPTANCE_DATABASE':'fcp_local_expected'}),patch.object(client,'data',return_value=self.evidence()),self.assertRaises(RuntimeError):client.evidence()
 def test_exact_database_accepted(self):
  client=NativeClient('http://127.0.0.1:8080','fixture-only','client')
  with patch.dict(os.environ,{'FCP_ACCEPTANCE_DATABASE':'fcp_local_review'}),patch.object(client,'data',return_value=self.evidence()):self.assertEqual(client.evidence()['databaseName'],'fcp_local_review')
 def test_admin_lock_preserved_when_later_build_fails(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);projects={key:root/key for key in ['backend','admin','teacher']}
   for project in projects.values():project.mkdir()
   resources=projects['backend']/'ruoyi-admin/src/main/resources/fcp';resources.mkdir(parents=True)
   (resources/'native-assembly.json').write_text(json.dumps({'buildId':'fixture'}))
   lock='{"name":"unit-fixture","lockfileVersion":3}'
   def install(project,name,*args):
    if name=='teacher':raise native.BuildError('Deliberate unit fixture failure')
    (project/'package-lock.json').write_text(lock);(project/'dist').mkdir()
    return 'generated-npm-lock'
   with patch.object(native,'install_frontend',side_effect=install),patch.object(native,'run'),patch.object(native,'validate_dist'),self.assertRaises(native.BuildError):
    native.build(projects,root,True)
   self.assertEqual((root/'release/admin-package-lock.json').read_text(),lock)
   self.assertFalse((root/'release/BUILD-EVIDENCE.json').exists())

class TestRuntimeConfigurationTests(unittest.TestCase):
 def write_config(self,root,**changes):
  values=dict(FCP_JDBC_URL='jdbc:mysql://127.0.0.1:33306/fcp_local_review?serverTimezone=UTC',
   FCP_DB_USER='fcp_test_runtime',FCP_DB_PASSWORD='unit-fixture-only',FCP_REDIS_PASSWORD='unit-fixture-only',
   FCP_JWT_SECRET='unit-fixture-only',FCP_MONITOR_PASSWORD='unit-fixture-only',
   FCP_REQUEST_PRIVATE_KEY='unit-fixture-only',FCP_RESPONSE_PUBLIC_KEY='unit-fixture-only',
   FCP_REDIS_PREFIX='fcp_local_fixture')
  values.update(changes);path=Path(root)/'fcp-runtime.properties'
  path.write_text(''.join(key+'='+value+'\n' for key,value in values.items()));path.chmod(0o600)
  return path
 def test_explicit_complete_private_runtime(self):
  with tempfile.TemporaryDirectory() as root:
   path=self.write_config(root);self.assertEqual(native.validate_test_runtime(path),path.resolve())
 def test_missing_runtime_and_placeholders_rejected(self):
  with self.assertRaises(native.BuildError):native.validate_test_runtime(None)
  with tempfile.TemporaryDirectory() as root:
   for key in ['FCP_DB_PASSWORD','FCP_REDIS_PASSWORD','FCP_REDIS_PREFIX']:
    with self.subTest(key=key),self.assertRaises(native.BuildError):native.validate_test_runtime(self.write_config(root,**{key:'REPLACE_ME'}))
 @unittest.skipIf(os.name=='nt','POSIX file permissions only; Windows requires ACL review')
 def test_world_readable_runtime_rejected(self):
  with tempfile.TemporaryDirectory() as root:
   path=self.write_config(root);path.chmod(0o644)
   with self.assertRaises(native.BuildError):native.validate_test_runtime(path)
 def test_remote_and_wrong_database_rejected(self):
  with tempfile.TemporaryDirectory() as root:
   for url in ['jdbc:mysql://example.com/fcp_local_review','jdbc:mysql://127.0.0.1/production',
    'jdbc:mysql://user:pass@127.0.0.1/fcp_local_review','jdbc:mysql://127.0.0.1/fcp_local_review#fragment']:
    with self.subTest(url=url),self.assertRaises(native.BuildError):native.validate_test_runtime(self.write_config(root,FCP_JDBC_URL=url))
 def test_redis_destination_and_namespace_rejected(self):
  with tempfile.TemporaryDirectory() as root:
   for change in [dict(FCP_REDIS_HOST='example.com'),dict(FCP_REDIS_PREFIX='default'),dict(FCP_REDIS_PREFIX='bad/prefix')]:
    with self.subTest(change=change),self.assertRaises(native.BuildError):native.validate_test_runtime(self.write_config(root,**change))
 def test_existing_redis_no_password_is_explicit_loopback_and_fresh_namespace_only(self):
  with tempfile.TemporaryDirectory() as root:
   valid=dict(FCP_REDIS_HOST='127.0.0.1',FCP_REDIS_PORT='6379',FCP_REDIS_PASSWORD='',FCP_REDIS_PREFIX='fcp_accept_'+'a'*32)
   path=self.write_config(root,**valid)
   with self.assertRaises(native.BuildError):native.validate_test_runtime(path)
   self.assertEqual(native.validate_test_runtime(path,existing_redis_no_password=True),path.resolve())
   for change in [dict(FCP_REDIS_HOST='example.com'),dict(FCP_REDIS_PREFIX='fcp_local_fixture'),dict(FCP_REDIS_PORT=''),dict(FCP_REDIS_PASSWORD='REPLACE_ME')]:
    with self.subTest(change=change),self.assertRaises(native.BuildError):
     native.validate_test_runtime(self.write_config(root,**(valid|change)),existing_redis_no_password=True)
 def test_maven_environment_does_not_inherit_other_project_runtime(self):
  import subprocess
  env={'FCP_DB_PASSWORD':'wrong-fixture','SPRING_APPLICATION_JSON':'wrong-fixture','JAVA_TOOL_OPTIONS':'wrong-fixture','MAVEN_ARGS':'wrong-fixture','PATH':'fixture-path'}
  with patch.dict(os.environ,env,clear=True),patch.object(native.shutil,'which',return_value='/fixture/mvn'),patch.object(native.subprocess,'run',return_value=subprocess.CompletedProcess([],0)) as runner:
   native.run(['mvn','verify'],clean_runtime=True,env_overrides={'SPRING_CONFIG_ADDITIONAL_LOCATION':'file:///fixture/private.properties','SPRING_PROFILES_ACTIVE':'prod,fcp'})
  actual=runner.call_args.kwargs['env']
  self.assertFalse(set(['FCP_DB_PASSWORD','SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','MAVEN_ARGS'])&actual.keys())
  self.assertEqual(actual['SPRING_PROFILES_ACTIVE'],'prod,fcp');self.assertEqual(actual['PATH'],'fixture-path')
 def test_ci_source_orders_schema_before_context_tests(self):
  # Static ordering guard only. Actual MySQL and Maven still need the native runner.
  source=(ROOT/'tools/ci_native.py').read_text()
  self.assertLess(source.index("ROOT/'tools/mysql_install.py'"),source.index('command(build)'))
  self.assertLess(source.index("private(private_dir/'fcp-runtime.properties',props)"),source.index('command(build)'))
  self.assertIn("'--test-runtime-config',private_dir/'fcp-runtime.properties'",source)

if __name__=='__main__':unittest.main()
