import ast
import copy
import json
from pathlib import Path
import tempfile
import unittest
from check_design import validate
from common import load_json
from generate_schema import render
ROOT=Path(__file__).resolve().parents[1]

class DesignTests(unittest.TestCase):
    def setUp(self):
        self.m=load_json(ROOT/'database/model.json');self.by={t['name']:t for t in self.m['tables']}
    def bad(self):self.assertTrue(validate(self.m))
    def test_baseline(self):self.assertEqual(validate(self.m),[])
    def test_ddl_generated_exactly(self):self.assertEqual(render(self.m),(ROOT/'database/sql/P042__fresh_isolated_schema.sql').read_text(encoding='utf-8-sig'))
    def test_fk_arity(self):self.by['ed_identity_account_link']['foreign_keys'][0]['target_columns']=['id'];self.bad()
    def test_fk_missing_target_unique(self):
        t=self.by['ed_identity_person'];t['columns'].append(dict(t['columns'][1],name='other_id'))
        self.by['ed_identity_account_link']['foreign_keys'][0]['target_columns']=['tenant_id','other_id'];self.bad()
    def test_declared_fk_missing(self):self.by['ed_identity_account_link']['foreign_keys']=[];self.bad()
    def test_fk_missing_column(self):self.by['ed_identity_account_link']['foreign_keys'][0]['columns']=['tenant_id','missing'];self.bad()
    def test_fk_collation_mismatch(self):self.by['ed_identity_person']['columns'][0]['type']='VARCHAR(100) COLLATE utf8mb4_0900_ai_ci';self.bad()
    def test_cross_owner_fk(self):self.by['ed_identity_account_link']['owner']='different';self.bad()
    def test_duplicate_table(self):self.m['tables'].append(copy.deepcopy(self.m['tables'][0]));self.bad()
    def test_duplicate_column(self):self.m['tables'][0]['columns'].append(copy.deepcopy(self.m['tables'][0]['columns'][0]));self.bad()
    def test_bad_key_column(self):self.m['tables'][0]['indexes'].append(['absent']);self.bad()
    def test_tenant_singleton_supported(self):self.assertEqual(self.by['ed_core_tenant_binding']['primary_key'],['tenant_id']);self.assertEqual(validate(self.m),[])
    def test_pk_loses_scope(self):self.m['tables'][0]['primary_key']=['host_kind'];self.bad()
    def test_resolver_required(self):self.m['reference_contracts']={};self.bad()
    def test_duplicate_fk(self):self.by['ed_identity_account_link']['foreign_keys']*=2;self.bad()
    def test_redundant_index_rejected(self):self.by['ed_reward_entry']['indexes'].append(['tenant_id','account_id']);self.bad()
    def test_unsupported_type(self):self.m['tables'][0]['columns'][0]['type']='VARCHAR(100); DROP TABLE x';self.bad()
    def test_index_byte_budget(self):self.by['ed_identity_person']['columns'][2]['type']='VARCHAR(1000)';self.by['ed_identity_person']['indexes'].append(['tenant_id','display_name']);self.bad()
    def test_unknown_invariant(self):self.m['tables'][0]['domain_invariants']=['UNDECLARED'];self.bad()
    def test_malformed_model(self):
        for m in (None,[],{}, {'tables':[]}):self.assertTrue(validate(m))
    def test_utf8_bom(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'中文模型.json';p.write_text(json.dumps(self.m,ensure_ascii=False),encoding='utf-8-sig');self.assertEqual(validate(load_json(p)),[])
    def test_all_runtime_text_io_explicit_encoding(self):
        missing=[]
        for folder in ('tools','reference','tests'):
            for p in (ROOT/folder).glob('*.py'):
                for n in ast.walk(ast.parse(p.read_text(encoding='utf-8-sig'))):
                    if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr in ('read_text','write_text') and not any(k.arg=='encoding' for k in n.keywords):missing.append(str(p)+':'+str(n.lineno))
        self.assertEqual(missing,[])

class MalformedDesignTests(unittest.TestCase):
    def test_wrong_nested_shapes_fail_closed(self):
        original=load_json(ROOT/'database/model.json')
        for key in ('columns','primary_key','unique','indexes','foreign_keys','checks','domain_invariants'):
            for value in (None,True,1,'x',{},[{}]):
                m=copy.deepcopy(original);m['tables'][0][key]=value
                with self.subTest(key=key,value=type(value).__name__):self.assertTrue(validate(m))
    def test_unrecognized_default_rejected(self):
        m=load_json(ROOT/'database/model.json');m['tables'][0]['columns'][0]['default']="'x'; DROP TABLE foo";self.assertTrue(validate(m))
    def test_check_missing_column_rejected(self):
        m=load_json(ROOT/'database/model.json');m['tables'][0]['checks'].append('unknown_field > 0');self.assertTrue(validate(m))
