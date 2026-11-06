"""Tests of the harness parser/templates, not a native MySQL acceptance run."""
from pathlib import Path
import unittest
from common import load_json
from mysql_acceptance import split_sql
from release_gate import check
from gray_gate import decide
ROOT=Path(__file__).resolve().parents[1]
class HarnessAndTemplateTests(unittest.TestCase):
    def test_split_generated_statements(self):
        source=(ROOT/'database/sql/P042__fresh_isolated_schema.sql').read_text(encoding='utf-8-sig')
        stmts=split_sql(source)
        self.assertEqual(sum(s.startswith('CREATE TABLE ') for s in stmts),54)
        self.assertEqual(sum(s.startswith('ALTER TABLE ') for s in stmts),40)
    def test_split_quoted_semicolon_and_doubled_quote(self):
        self.assertEqual(split_sql("-- header\nSELECT 'a; b''c';SELECT 2;-- end"),["SELECT 'a; b''c'","SELECT 2"])
    def test_split_comment_inside_string(self):
        self.assertEqual(split_sql("SELECT '--text';"),["SELECT '--text'"])
    def test_split_unclosed_quote_rejected(self):
        with self.assertRaises(ValueError):split_sql("SELECT 'bad;")
    def test_production_template_remains_blocked(self):
        m=load_json(ROOT/'contracts/release-manifest.NOT_BUILT.json')
        e=load_json(ROOT/'contracts/production-evidence.NOT_RUN.json')
        errors=check(e,ROOT,m,ROOT,'8.4.2')
        self.assertTrue(errors)
        self.assertIn('not native product acceptance',errors)
    def test_missing_gray_template_pauses(self):
        self.assertEqual(decide(load_json(ROOT/'contracts/gray-input.MISSING.json'))['outcome'],'PAUSE')
    def test_fresh_schema_does_not_disable_fk_validation(self):
        sql=(ROOT/'database/sql/P042__fresh_isolated_schema.sql').read_text(encoding='utf-8-sig').upper()
        self.assertNotIn('FOREIGN_KEY_CHECKS=0',sql.replace(' ',''))
        self.assertNotIn('DROP TABLE',sql)
    def test_migration_template_is_not_approved(self):
        m=load_json(ROOT/'contracts/migration-plan.NOT_APPROVED.json')
        self.assertEqual(m['status'],'NOT_APPROVED');self.assertIsNone(m['source_fingerprint'])
