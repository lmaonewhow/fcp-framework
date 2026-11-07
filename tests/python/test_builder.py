import importlib.util,json,os,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from native import BuildError,check_blob,git_blob,replace_once,read_build_env,bundle_runtime,validate_dist,BLOBS

class BuilderTests(unittest.TestCase):
 def test_single_anchor(self):self.assertEqual(replace_once('abc','b','x'),'axc')
 def test_missing_anchor_fails(self):
  with self.assertRaises(BuildError):replace_once('abc','z','x')
 def test_ambiguous_anchor_fails(self):
  with self.assertRaises(BuildError):replace_once('abbc','b','x')
 def test_verified_plus_auth_fixture(self):
  # Complete upstream file as read via the GitHub connector; its Git blob hash proves exact bytes.
  original="""const TokenKey = 'Admin-Token';

const tokenStorage = useStorage<null | string>(TokenKey, null);

export const getToken = () => tokenStorage.value;

export const setToken = (access_token: string) => (tokenStorage.value = access_token);

export const removeToken = () => (tokenStorage.value = null);
"""
  self.assertEqual(git_blob(original.encode()),BLOBS['admin']['src/utils/auth.ts'])
  transformed=replace_once(original,"const TokenKey = 'Admin-Token';","const TokenKey = 'FCP-Host-Token';")
  self.assertIn("'FCP-Host-Token'",transformed);self.assertIn('useStorage<null | string>',transformed)
 def test_verified_soybean_storage_fixture(self):
  original="""import { createLocalforage, createStorage } from '@sa/utils';

const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';

export const localStg = createStorage<StorageType.Local>('local', storagePrefix);

export const sessionStg = createStorage<StorageType.Session>('session', storagePrefix);

export const localforage = createLocalforage<StorageType.Local>('local');
"""
  self.assertEqual(git_blob(original.encode()),BLOBS['teacher']['src/utils/storage.ts'])
  transformed=replace_once(original,"export const localStg = createStorage<StorageType.Local>('local', storagePrefix);",
   "export const localStg = bridgeStorage(createStorage<StorageType.Local>('local', storagePrefix));")
  self.assertIn('export const sessionStg = createStorage',transformed)
 def test_verified_soybean_route_fixture(self):
  original="""import type { ElegantConstRoute } from '@elegant-router/types';
import { request } from '../request';

/** get routes */
export function fetchGetRoutes() {
  return request<ElegantConstRoute[]>({ url: '/system/menu/getRouters' });
}
"""
  self.assertEqual(git_blob(original.encode()),BLOBS['teacher']['src/service/api/route.ts'])
  self.assertIn('/fcp/ui/soybean-routes',replace_once(original,"url: '/system/menu/getRouters'","url: '/fcp/ui/soybean-routes'"))
 def test_drift_fails_closed(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'source';p.write_text('modified')
   with self.assertRaises(BuildError):check_blob(p,'0'*40)
 def test_build_env_rejects_placeholder(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'env';p.write_text('FCP_CLIENT_ID=REPLACE_ME\n')
   with self.assertRaises(BuildError):read_build_env(p)
 def test_build_env_rejects_command_content(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'env';p.write_text('FCP_CLIENT_ID=abc;echo bad\n')
   with self.assertRaises(BuildError):read_build_env(p)
 def test_bundle_has_index_and_python(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d)/'bundle';lines=bundle_runtime(root)
   self.assertTrue(any(x.endswith('  plugins/statistics.py') for x in lines))
   self.assertTrue((root/'bundle.sha256').is_file())
 def test_bundle_refuses_overwrite(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d)/'bundle';bundle_runtime(root)
   with self.assertRaises(BuildError):bundle_runtime(root)
 def test_missing_dist_fails(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(BuildError):validate_dist(Path(d),'admin')
 def test_dev_index_fails(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'index.html').write_text('<script src="/admin/src/main.ts"></script>');(root/'main.js').write_text('')
   with self.assertRaises(BuildError):validate_dist(root,'admin')
 def test_no_api_authentication_exclusion(self):
  profile=(ROOT/'config/application-fcp.yml').read_text()
  self.assertNotIn('- /fcp/',profile);self.assertIn('  enable: true',profile)
  controller=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/school/FcpSchoolController.java').read_text()
  self.assertNotIn('@SaIgnore',controller)
 def test_configuration_uses_native_datasource_only(self):
  source=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/school/FcpSchoolConfiguration.java').read_text()
  self.assertIn('new JdbcRecordStore(dataSource)',source);self.assertNotIn('FileRecordStore',source)
  self.assertIn('verifySchema(dataSource)',source)
 def test_no_reference_server_in_source(self):
  # Inspect delivered source, not disposable upstream checkouts and dependency
  # caches under work/. The assembler's source roots are checked in full.
  java=[p for tree in ['core','business','host','integration','deploy','bootstrap']
        for p in (ROOT/tree).rglob('*.java') if 'build' not in p.parts]
  self.assertFalse(any('com.sun.net.httpserver' in p.read_text() for p in java))
  # Development HTTP and test connector fixtures are outside production assembly.
  assembler=(ROOT/'tools/native.py').read_text()
  self.assertNotIn("copy_files(ROOT/'development-host'",assembler)
  self.assertNotIn("copy_files(ROOT/'tests'",assembler)
 def test_roles_separate_manage_and_read(self):
  sql=(ROOT/'sql/native/V002__fcp_permissions.sql').read_text()
  self.assertIn('(942102,942005)',sql);self.assertNotIn('(942102,942006)',sql)
  self.assertNotIn('(942103,942004)',sql)
 def test_native_entrypoint_explicit(self):
  source=(ROOT/'tools/native.py').read_text()
  self.assertIn('<mainClass>org.dromara.DromaraApplication</mainClass>',source)
  self.assertIn('active: @profiles.active@,fcp',source);self.assertNotIn('include: fcp',source)

 def test_main_class_matches_verified_upstream_source(self):
  original=(ROOT/'tests/fixtures/upstream/DromaraApplication.java.txt').read_bytes()
  self.assertEqual(git_blob(original),BLOBS['backend']['ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java'])
  self.assertIn(b'public class DromaraApplication',original)
 def test_production_build_does_not_skip_tests(self):
  source=(ROOT/'tools/native.py').read_text()
  self.assertIn("'-Pprod'",source)
  self.assertIn("'-DskipTests=false'",source)
  self.assertIn("'-Dmaven.test.skip=false'",source)
  self.assertNotIn("'-DskipTests'",source)
 def test_missing_surefire_reports_are_not_pass(self):
  from native import collect_java_tests
  with tempfile.TemporaryDirectory() as d:
   self.assertEqual(collect_java_tests(Path(d))['status'],'NOT_FULLY_VERIFIED')

if __name__=='__main__':unittest.main(verbosity=2)
