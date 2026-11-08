"""Source/contract guard checks; they are not real MySQL/native evidence."""
import hashlib
import json
from pathlib import Path
import re
import unittest
ROOT=Path(__file__).resolve().parents[2]
class RosterSourceChecks(unittest.TestCase):
    def test_independent_explicit_grants_and_digest(self):
        lock=json.loads((ROOT/'contracts/education/roster-sources.lock.json').read_text())
        for name,digest in lock['sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),digest)
        self.assertEqual(lock['objects'][0]['name'],'ed_host_roster_role')
        sql=(ROOT/'sql/education/003__roster_authority.sql').read_text()
        self.assertIn('DEFAULT 0',sql)
        self.assertIn("purpose_key='school-roster-metadata-v1'",sql)
        self.assertNotRegex(sql,r'INSERT\s+INTO')
        menus=(ROOT/'sql/education/004__roster_menu.sql').read_text()
        self.assertNotIn('sys_role_menu',menus)
        self.assertNotIn('education:foundation',menus)
        self.assertNotIn('education:roster:read',menus)
        self.assertEqual({m['permission'] for m in lock['menus']},{'education:roster:view','education:roster:student:read','education:roster:teacher:read'})
    def test_same_connection_typed_resolver_no_pii_select(self):
        resolver=(ROOT/'core/src/main/java/fcp/education/RosterPersonResolver.java').read_text()
        self.assertIn('verify(Connection connection',resolver)
        self.assertNotIn('getConnection(',resolver)
        self.assertIn('SELECT id,status FROM ed_identity_person WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) FOR SHARE',resolver)
        for forbidden in ['private_ref','display_name','student_code','subject_code','change_reason','ed_identity_external_map','ed_identity_account_link','ed_identity_relation']:
            self.assertNotIn(forbidden,resolver)
            self.assertNotIn(forbidden,(ROOT/'core/src/main/java/fcp/education/RosterStore.java').read_text())
    def test_readonly_closed_http_boundary(self):
        support=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/RosterEndpointSupport.java').read_text()
        generated=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/generated/RosterGeneratedController.java').read_text()
        self.assertNotIn('@RestController',support)
        self.assertNotRegex(support,r'@(Get|Post|Put|Delete|Patch|Request)Mapping')
        self.assertIn('extends RosterEndpointSupport',generated)
        self.assertEqual(1,generated.count('@RestController'))
        controller=support+'\n'+generated
        self.assertNotRegex(controller,r'@(Post|Put|Delete|Patch)Mapping')
        self.assertEqual(controller.count('@GetMapping'),4)
        self.assertEqual(controller.count('.header("Cache-Control","no-store")'),2)
        self.assertIn('MultiValueMap<String,String>',controller)
        self.assertNotIn('@RequestParam Map<String,String> parameters',controller)
        self.assertIn('RosterStore.page(parameters,false)',controller)
        self.assertNotIn('/persons',controller)
    def test_exact_authority_and_scope_before_page(self):
        store=(ROOT/'core/src/main/java/fcp/education/RosterStore.java').read_text()
        for token in ['cap:education.roster','ed_core_access_epoch','ed_core_execution_grant','ed_core_tenant_binding','sys_user_role','sys_role_menu','sys_tenant_package',"g.purpose_key='school-roster-metadata-v1'",'FOR SHARE','manifest_digest=?','CAST(subject_key AS BINARY)=CAST(? AS BINARY)']:
            self.assertIn(token,store)
        self.assertNotIn('ed_host_education_role',store)
        self.assertNotIn('education:foundation:',store)
        self.assertIn('schoolScopeSql(schools.size(),all)+" ORDER BY id LIMIT ?',store)
        self.assertIn('RosterPersonResolver.verify(c,',store)
        self.assertNotRegex(store,r'(UPDATE|DELETE FROM|INSERT INTO) ed_(education|identity)_')
        self.assertNotIn('StpUtil',store)
        # Values remain strings at the API and are cast explicitly at numeric SQL comparisons.
        resolver=(ROOT/'core/src/main/java/fcp/education/RosterPersonResolver.java').read_text()
        for source in (store,resolver):
            self.assertNotRegex(source,r'(?<![\w])(?:school_|class_|person_|term_|enrollment_)?id[=>]\?')
            self.assertNotIn('Long.parseLong',source)
            self.assertNotIn('Double.parseDouble',source)
        self.assertIn('Collections.nCopies(schoolCount,"CAST(? AS UNSIGNED)")',store)
    def test_config_is_separately_opt_in(self):
        config=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/RosterConfiguration.java').read_text()
        self.assertIn('fcp.education.roster.enabled',config)
        self.assertIn('FCP_EDUCATION_ROSTER_MANIFEST_DIGEST',config)
        self.assertNotIn('matchIfMissing',config)
        self.assertIn('RosterArtifactIdentity.verifyCurrentArchive',config)
        filter_source=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/education/RosterNoStoreFilter.java').read_text()
        self.assertIn('@Order(Ordered.HIGHEST_PRECEDENCE)',filter_source)
        self.assertIn('path.startsWith("/fcp/education/roster/")',filter_source)
        self.assertIn('response.setHeader("Cache-Control","no-store")',filter_source)
if __name__=='__main__':unittest.main()
