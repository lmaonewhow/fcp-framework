"""Source/plan guards only. This is not a native host or database test."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('ci_education',ROOT/'tools/ci_education.py')
ci=importlib.util.module_from_spec(spec);spec.loader.exec_module(ci)

class NativeContract(unittest.TestCase):
    def test_rejects_local_and_existing_target(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            with self.assertRaises(ValueError):ci.schema_plan('fcp_ci_'+'1'*12)
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            for name in ('production','fcp_ci_existing','fcp_ci_'+'1'*12+';'):
                with self.assertRaises(ValueError):ci.schema_plan(name)
    def test_empty_schema_is_required(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            with self.assertRaises(ValueError):
                ci.prepare_schema('fcp_ci_'+'1'*12,lambda _: '1',None,Path('/unused'))
    def test_plan_hash_binds_sql(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            plan=ci.schema_plan('fcp_ci_'+'1'*12)
        self.assertEqual(3,len(plan['sources']))
        self.assertEqual(64,len(plan['approvalDigest']))
        self.assertFalse(plan['existingDatabaseMigration'])
    def test_identity_is_native_and_not_caller_supplied(self):
        source=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/NativeFoundationIdentity.java').read_text()
        self.assertIn('StpUtil.checkLogin()',source)
        self.assertIn('LoginHelper.getTenantId()',source)
        self.assertIn('LoginHelper.getUserIdStr()',source)
        self.assertNotIn('ScopeNames',source)
        self.assertNotIn('fcp_eval_assignment',source)
        self.assertIn('CAST(u.tenant_id AS BINARY)',source)
        self.assertIn('actor.tenant().equals(rows.getString(1))',source)
    def test_no_delete_or_status_endpoint(self):
        source=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/FoundationController.java').read_text()
        for bad in ('@DeleteMapping','@PutMapping','@PatchMapping','privateRef','@RequestHeader'):
            self.assertNotIn(bad,source)
        self.assertIn('identity.current()',source)
        self.assertIn('body.containsKey("id")',source)
    def test_disabled_by_default(self):
        source=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/FoundationConfiguration.java').read_text()
        self.assertIn('@ConditionalOnProperty(name="fcp.education.enabled",havingValue="true")',source)
        self.assertNotIn('matchIfMissing',source)
    def test_no_automatic_p042_migration(self):
        source=(ROOT/'tools/native.py').read_text()
        self.assertNotIn('P042',source)
        self.assertNotIn('001__host_education_authority',source)
if __name__=='__main__':unittest.main()
