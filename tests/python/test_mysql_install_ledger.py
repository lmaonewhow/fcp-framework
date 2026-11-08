"""Installer sequencing/fault tests. These are not live MySQL acceptance."""
import hashlib,json,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from mysql_install import checked_stages,install_stages
class InstallLedgerTests(unittest.TestCase):
 def stages(self):
  paths=sorted((ROOT/'sql/native').glob('V*.sql'))
  return checked_stages(paths,json.loads((ROOT/'sql/native/migrations.lock.json').read_text()))
 def test_all_real_locked_migrations_in_order(self):
  stages=self.stages();self.assertEqual([s['version'] for s in stages],list(range(1,10)))
  calls=[]
  def query(sql):calls.append(sql);return '0'
  install_stages(query,stages)
  for s in stages:
   running=next(i for i,q in enumerate(calls) if q.startswith('INSERT INTO fcp_migration_history') and f"VALUES({s['version']}," in q)
   applied=next(i for i,q in enumerate(calls) if q.startswith("UPDATE fcp_migration_history SET state='APPLIED'") and f"version_num={s['version']};" in q)
   self.assertLess(running,calls.index(s['sql']));self.assertLess(calls.index(s['sql']),applied)
   self.assertIn(s['sha256'],calls[running])
 def test_nonempty_refuses_all_writes(self):
  calls=[]
  def query(sql):calls.append(sql);return '1'
  with self.assertRaises(ValueError):install_stages(query,self.stages())
  self.assertEqual(len(calls),1)
 def test_every_script_failure_is_dirty_and_stops(self):
  stages=self.stages()
  for failed in stages:
   calls=[]
   def query(sql):
    calls.append(sql)
    if sql==failed['sql']:raise RuntimeError('injected script failure')
    return '0'
   with self.subTest(version=failed['version']),self.assertRaises(RuntimeError):install_stages(query,stages)
   self.assertTrue(any("fcp_migration_history SET state='FAILED'" in q and f"version_num={failed['version']};" in q for q in calls))
   self.assertFalse(any("fcp_migration_history SET state='APPLIED'" in q and f"version_num={failed['version']};" in q for q in calls))
   for later in stages[failed['version']:]:self.assertNotIn(later['sql'],calls)
 def test_failed_status_write_keeps_original_failure_and_running(self):
  stage=self.stages()[0];calls=[]
  def query(sql):
   calls.append(sql)
   if sql==stage['sql']:raise RuntimeError('original failure')
   if "state='FAILED'" in sql:raise ValueError('lost connection')
   return '0'
  with self.assertRaisesRegex(RuntimeError,'original failure'):install_stages(query,[stage])
  self.assertTrue(any("'RUNNING'" in q and 'fcp_migration_history' in q for q in calls))
  self.assertFalse(any("state='APPLIED'" in q for q in calls))
 def test_applied_marker_failure_marks_both_dirty(self):
  stage=self.stages()[0]
  for table in ['fcp_install_history','fcp_migration_history']:
   calls=[]
   def query(sql):
    calls.append(sql)
    if sql.startswith(f"UPDATE {table} SET state='APPLIED'"):raise RuntimeError('marker failure')
    return '0'
   with self.subTest(table=table),self.assertRaisesRegex(RuntimeError,'marker failure'):install_stages(query,[stage])
   for target in ['fcp_install_history','fcp_migration_history']:
    self.assertTrue(any(q.startswith(f"UPDATE {target} SET state='FAILED'") for q in calls))
 def test_checksum_missing_and_order_drift_rejected(self):
  paths=sorted((ROOT/'sql/native').glob('V*.sql'));lock=json.loads((ROOT/'sql/native/migrations.lock.json').read_text())
  for bad in [paths[:-1],list(reversed(paths)),paths+[paths[-1]]]:
   with self.assertRaises(ValueError):checked_stages(bad,lock)
  lock['files'][paths[0].name]='0'*64
  with self.assertRaises(ValueError):checked_stages(paths,lock)
 def test_unversioned_upstream_does_not_invent_migration_version(self):
  calls=[]
  def query(sql):calls.append(sql);return '0'
  install_stages(query,[dict(name='ry_workflow.sql',sha256='a'*64,sql='SELECT 1;',version=None)])
  self.assertFalse(any(q.startswith('INSERT INTO fcp_migration_history') for q in calls))
  self.assertTrue(any("fcp_install_history SET state='APPLIED'" in q for q in calls))
if __name__=='__main__':unittest.main()
