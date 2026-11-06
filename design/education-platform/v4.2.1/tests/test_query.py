import sqlite3
import unittest
from query_compiler import Scope,Rejected,compile_query
from reference.relational_model import schema_sqlite,DB,insert

class QueryTests(unittest.TestCase):
    def setUp(self):
        self.scope=Scope('t1','teacher1',frozenset({'timeline.read'}),('111',),'teacher.timeline')
        # Use the actual relational model, not a hand-written reduced table fixture.
        self.db=sqlite3.connect(':memory:');self.db.executescript(schema_sqlite());adapter=DB(self.db)
        for tenant,id,person,cls,summary,purpose,visible in [
            ('t1',1,100,111,'safe','teacher.timeline',1),('t2',2,100,111,'other tenant','teacher.timeline',1),
            ('t1',3,100,211,'other class','teacher.timeline',1),('t1',4,100,111,'private','support.private',1),
            ('t1',5,100,111,'withdrawn','teacher.timeline',0)]:
            insert(adapter,'ed_reporting_timeline',tenant_id=tenant,id=id,person_id=person,class_id=cls,
                   occurred_at='2026-01-01 00:00:00.000000',event_type='observation',redacted_summary=summary,
                   purpose_key=purpose,is_visible=visible,source_domain='evaluation',source_type='observation',source_id=str(id))
    def tearDown(self):self.db.close()
    def rows(self,request):
        sql,args=compile_query(request,self.scope,'sqlite');return self.db.execute(sql,args).fetchall()
    def reject(self,r):
        with self.assertRaises(Rejected):compile_query(r,self.scope)
    def test_exact_scope(self):self.assertEqual(self.rows({'fields':['id']}),[(1,)])
    def test_event_injection_parameterized(self):self.assertEqual(self.rows({'filters':{'event_type':"x' OR 1=1 --"}}),[])
    def test_person_id_rejects_injection(self):self.reject({'filters':{'person':"100' OR 1=1 --"}})
    def test_mysql_bind_count(self):
        sql,args=compile_query({},self.scope);self.assertEqual(sql.count('%s'),len(args));self.assertNotIn('teacher1',sql)
    def test_raw_sql(self):self.reject({'sql':'SELECT * FROM x'})
    def test_tenant_input(self):self.reject({'tenant_id':'t2'})
    def test_fields_expression(self):self.reject({'fields':['SLEEP(10)']})
    def test_unknown_filter(self):self.reject({'filters':{'where':'1=1'}})
    def test_sort_all_json_wrong_shapes(self):
        for v in (None,True,[],{},1,1.5,'newest;DROP TABLE x','x'*10000):
            with self.subTest(v=type(v).__name__):self.reject({'sort':v})
    def test_limit_all_bad_shapes(self):
        for v in (None,True,[],{},0,201,'10'):self.reject({'limit':v})
    def test_duplicate_fields(self):self.reject({'fields':['id','id']})
    def test_empty_fields(self):self.reject({'fields':[]})
    def test_no_capability(self):
        with self.assertRaises(Rejected):compile_query({},Scope('t1','a',frozenset(),('111',),'teacher.timeline'))
    def test_purpose_not_permission(self):
        with self.assertRaises(Rejected):compile_query({},Scope('t1','a',frozenset({'timeline.read'}),('111',),'support.private'))
    def test_no_scope(self):
        with self.assertRaises(Rejected):compile_query({},Scope('t1','a',frozenset({'timeline.read'}),(),'teacher.timeline'))
    def test_ids_are_canonical(self):
        for v in ('0111','-1','0','18446744073709551616','１','111 '):
            with self.assertRaises(Rejected):compile_query({},Scope('t1','a',frozenset({'timeline.read'}),(v,),'teacher.timeline'))
    def test_scope_duplicate_removed(self):
        sql,args=compile_query({},Scope('t1','a',frozenset({'timeline.read'}),('111','111'),'teacher.timeline'));self.assertEqual(args.count(111),1)
    def test_unknown_dialect_shape(self):
        with self.assertRaises(Rejected):compile_query({},self.scope,[])
    def test_invalid_root_shape(self):
        for r in (None,[],True,1,'x'):self.reject(r)
