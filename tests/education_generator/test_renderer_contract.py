"""Source/API closed-boundary checks. These are not native Spring/browser acceptance."""
import hashlib
import json
from pathlib import Path
import re
import unittest
ROOT=Path(__file__).resolve().parents[2]
class EducationRendererContract(unittest.TestCase):
    def test_resource_pin_inventory(self):
        resource=ROOT/'core/src/main/resources/education-generator'
        source=(ROOT/'core/src/main/java/dev/fcp/generator/EducationGeneratorSources.java').read_text()
        actual={str(p.relative_to(resource)):hashlib.sha256(p.read_bytes()).hexdigest() for p in resource.rglob('*') if p.is_file()}
        pinned=dict(re.findall(r'Map.entry\("([^"\n]+)","([a-f0-9]{64})"\)',source))
        self.assertEqual(7,len(actual))
        for path,digest in actual.items():self.assertEqual(digest,pinned[path])
    def test_web_request_is_closed_and_reuses_safe_apply(self):
        s=(ROOT/'integration/ruoyi/src/main/java/org/dromara/fcp/generator/FcpGeneratorController.java').read_text()
        for text in ['request.keySet().equals(Set.of("profileId", "version", "modules"))',
                     'workspace.plan(owner(), rendered.zipBySource(), rendered.workspaceInputDigests())',
                     '@SaCheckPermission("tool:gen:code")','StpUtil.checkLogin()',
                     'request.getHeader("Authorization")','!LoginHelper.isSuperAdmin()',
                     '@ConditionalOnProperty(name = "fcp.generator.enabled", havingValue = "true")',
                     'startsWith("ed_")','GeneratedSchemaRequirements.requireAuditColumns']:
            self.assertIn(text,s)
        self.assertLess(s.index('startsWith("ed_")'),s.index('GeneratedSchemaRequirements.requireAuditColumns'))
    def test_rules_are_exact_safe_domain_projection(self):
        rules=json.loads((ROOT/'core/src/main/resources/education-generator/rules.json').read_text())
        self.assertEqual(rules['profileId'],'education-p042-v1')
        self.assertEqual(rules['personDisplayPolicy'],'PENDING')
        modules={m['id']:m for m in rules['modules']}
        self.assertEqual(set(modules),{'foundation','roster'})
        self.assertEqual({r['id'] for r in modules['foundation']['resources']},{'schools','organizations','classes','terms'})
        self.assertEqual({r['id'] for r in modules['roster']['resources']},{'enrollments','memberships','assignments'})
        for r in modules['roster']['resources']:
            self.assertFalse(r['create']);self.assertFalse(r['rename'])
        terms=next(r for r in modules['foundation']['resources'] if r['id']=='terms')
        self.assertFalse(terms['create']);self.assertFalse(terms['rename'])
        for m in modules.values():
            for resource in m['resources']:
                for mode in ['list','detail','create','rename']:
                    for f in resource[mode]:self.assertNotIn(f['column'],['person_id','display_name','private_ref','student_code','subject_code','tenant_id'])
            for endpoint in m['clientEndpoints']:
                self.assertTrue(endpoint['path'].startswith(m['basePath']+'/'))
                self.assertIn(endpoint['method'],['GET','POST'])
        for endpoint in modules['roster']['clientEndpoints']:self.assertEqual(endpoint['method'],'GET')
    def test_renderer_has_no_execution_or_mapper_path(self):
        s=(ROOT/'core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java').read_text()
        for text in ['ProcessBuilder','Runtime.getRuntime','DriverManager','DataSource','java.sql','main/resources/mapper/']:
            self.assertNotIn(text,s)
        self.assertIn('Arrays.equals(reconstructed,sourceBytes.get(SQL))',s)
        self.assertIn('getResourceAsStream(RESOURCE+name)',s)
        self.assertIn('Files.isSymbolicLink',s)
if __name__=='__main__':unittest.main()
