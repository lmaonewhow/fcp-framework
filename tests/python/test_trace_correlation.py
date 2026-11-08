"""Safety/selection tests with actual temporary JSON logs, not native/database acceptance."""
import ast
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ci_trace_correlation', ROOT/'tools/ci_trace_correlation.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
TRACE, REQUEST, SECRET = '1'*32, '2'*32, 'synthetic-private-secret-canary'
DB = 'fcp_ci_0123456789ab'


class CorrelationSafetyTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        env = patch.dict(os.environ, {'GITHUB_ACTIONS':'true', 'FCP_ACCEPTANCE_DATABASE':DB}, clear=True)
        env.start(); self.addCleanup(env.stop)
        self.path = Path(directory.name)/'native-startup.log'; self.path.write_text(SECRET+'\n')
        self.queries = []
        def sql(query):
            self.queries.append(query)
            return json.dumps(dict(rows=1, auditId=42, traceId=TRACE, requestId=REQUEST))
        self.fixture = SimpleNamespace(database=DB, private_dir=Path(directory.name), report={}, sql=sql)
        self.original = {'input':dict(plugin='roster.connector', classId='class-a', requestKey='synthetic-key-123', payload={'secret':SECRET})}
        self.receipt = dict(entity='roster', classId='class-a', version=1, replayed=False, summary={'private':SECRET})

    def append(self, plugin, span, **changes):
        event = dict(event='plugin.rpc', traceId=TRACE, requestId=REQUEST, pluginId=plugin,
                     pluginVersion='0.6.0-dev.1', capability='api', operation='run', outcome='RETURNED',
                     errorCode='NONE', spanId=span*16, private=SECRET)
        event.update(changes)
        with self.path.open('a') as stream: stream.write(json.dumps(event)+'\n')

    def chain(self):
        self.append('roster.connector','3'); self.append('roster.business','4'); self.append('roster.business','5')

    def verify(self, cursor):
        return m.verify(self.fixture, cursor, TRACE, REQUEST, self.original, self.receipt)

    def test_projection_and_replay_are_read_only_without_secret_export(self):
        cursor=m.begin(self.fixture); self.append('unrelated','6',traceId='9'*32); self.chain()
        evidence=self.verify(cursor); m.verify_replay(self.fixture,evidence)
        report=self.fixture.report['traceCorrelation']
        self.assertEqual(report['sqlAudit']['matchedRows'],1)
        self.assertEqual(report['pluginRpc']['summaries'],3)
        self.assertTrue(report['replayAudit']['originalAuditUnchanged'])
        for private in (SECRET,'synthetic-key-123'): self.assertNotIn(private,json.dumps(report))
        self.assertEqual(len(self.queries),2)
        for query in self.queries:
            self.assertTrue(query.lstrip().startswith('SELECT ')); self.assertEqual(query.count(';'),1)
            for expected in ("='ingest:synthetic-key-123'","'$.version')=1","kind='ingest-roster'","record_id='class-a'","scope_id='tenant-000000'"):
                self.assertIn(expected,query)
            self.assertNotIn(SECRET,query)
            self.assertNotRegex(query,r'(?i)\b(?:insert|update|delete|truncate|drop|use)\b')

    def test_guard_requires_ci_and_exact_acceptance_database(self):
        for env in ({}, {'GITHUB_ACTIONS':'false','FCP_ACCEPTANCE_DATABASE':DB},
                    {'GITHUB_ACTIONS':'true'}, {'GITHUB_ACTIONS':'true','FCP_ACCEPTANCE_DATABASE':'fcp_ci_aaaaaaaaaaaa'}):
            with patch.dict(os.environ,env,clear=True):
                with self.assertRaisesRegex(RuntimeError,'exact disposable'): m.begin(self.fixture)
        self.fixture.database='production'
        with self.assertRaises(RuntimeError): m.begin(self.fixture)
        self.assertEqual(self.queries,[])

    def test_selection_injection_and_bad_receipt_fail_before_sql(self):
        cursor=m.begin(self.fixture)
        for key in ["x'; DELETE FROM sys_user;--",'x\nsecret','x'*65,'short']:
            self.original['input']['requestKey']=key
            with self.assertRaises(AssertionError): self.verify(cursor)
        self.original['input']['requestKey']='synthetic-key-123'
        for version in [True,0,-1,9007199254740992,1.5]:
            self.receipt['version']=version
            with self.assertRaises(AssertionError): self.verify(cursor)
        self.receipt['version']=1; self.receipt['replayed']=True
        with self.assertRaises(AssertionError): self.verify(cursor)
        self.assertEqual(self.queries,[])

    def test_wrong_plugin_class_and_entity_fail_before_sql(self):
        for target,key,value in [(self.original['input'],'plugin','roster.adapter'),(self.original['input'],'classId','class-b'),(self.receipt,'entity','attendance')]:
            old=target[key]; target[key]=value
            with self.assertRaises(AssertionError): self.verify(m.begin(self.fixture))
            target[key]=old
        self.assertEqual(self.queries,[])

    def test_invalid_response_ids_fail_before_sql(self):
        for value in ['', '0'*32,'x'*32,'A'*32,SECRET]:
            for trace,request in [(value,REQUEST),(TRACE,value)]:
                with self.assertRaises(AssertionError): m.verify(self.fixture,m.begin(self.fixture),trace,request,self.original,self.receipt)
        self.assertEqual(self.queries,[])

    def test_missing_duplicate_wrong_audit_is_not_leaked(self):
        for row in [dict(rows=0),dict(rows=2,auditId=42,traceId=TRACE,requestId=REQUEST),
                    dict(rows=True,auditId=42,traceId=TRACE,requestId=REQUEST),dict(rows=1,auditId=42,traceId=SECRET,requestId=REQUEST)]:
            self.fixture.sql=lambda query:json.dumps(row)
            with self.assertRaises(AssertionError) as error: self.verify(m.begin(self.fixture))
            self.assertNotIn(SECRET,str(error.exception))
            self.assertEqual(self.fixture.report['traceCorrelation']['sqlAudit']['status'],'FAIL')

    def test_malformed_or_large_audit_projection_is_bounded(self):
        for raw in [SECRET, 'x'*2049, [], json.dumps(dict(rows=1,auditId=True,traceId=TRACE,requestId=REQUEST))]:
            self.fixture.sql=lambda query:raw
            with self.assertRaises(AssertionError) as error: self.verify(m.begin(self.fixture))
            self.assertNotIn(SECRET,str(error.exception))

    def test_pre_offset_records_do_not_satisfy_current_import(self):
        self.chain(); cursor=m.begin(self.fixture)
        with patch.object(m,'POLL_SECONDS',0.01):
            with self.assertRaisesRegex(AssertionError,'Missing connector'): self.verify(cursor)
        self.assertEqual(self.fixture.report['traceCorrelation']['sqlAudit']['status'],'PASS')
        self.assertEqual(self.fixture.report['traceCorrelation']['pluginRpc']['status'],'FAIL')

    def test_wrong_rpc_identity_or_outcome_rejected(self):
        for changes in [dict(requestId='8'*32),dict(outcome='ERROR'),dict(pluginVersion='wrong'),dict(capability='wrong'),dict(operation='invoke'),dict(errorCode='BUSY')]:
            cursor=m.begin(self.fixture); self.append('roster.connector','3',**changes)
            with self.assertRaisesRegex(AssertionError,'unexpected'): self.verify(cursor)

    def test_invalid_span_rejected(self):
        for span in ['0'*16,'A'*16,'abc',SECRET]:
            cursor=m.begin(self.fixture); self.append('roster.connector','3',spanId=span)
            with self.assertRaisesRegex(AssertionError,'valid span'): self.verify(cursor)

    def test_duplicate_json_line_rejected(self):
        cursor=m.begin(self.fixture); self.chain(); self.append('roster.business','5')
        with self.assertRaisesRegex(AssertionError,'duplicate plugin/span'): self.verify(cursor)

    def test_cross_plugin_span_reuse_rejected(self):
        cursor=m.begin(self.fixture)
        self.append('roster.connector','3'); self.append('roster.business','3'); self.append('roster.business','4')
        with self.assertRaisesRegex(AssertionError,'span across plugins'): self.verify(cursor)

    def test_extra_distinct_line_rejected(self):
        cursor=m.begin(self.fixture); self.chain(); self.append('roster.business','6')
        with self.assertRaisesRegex(AssertionError,'count exceeds'): self.verify(cursor)

    def test_bounded_window_and_line(self):
        cursor=m.begin(self.fixture)
        with self.path.open('a') as stream: stream.write('x'*100)
        with patch.object(m,'MAX_BYTES',50):
            with self.assertRaisesRegex(RuntimeError,'bounded log window'): self.verify(cursor)
        for suffix in ['', '\n']:
            cursor=m.begin(self.fixture)
            with self.path.open('a') as stream: stream.write('x'*(m.MAX_LINE+1)+suffix)
            with self.assertRaisesRegex(RuntimeError,'oversized log line'): self.verify(cursor)

    def test_replaced_or_truncated_file_rejected(self):
        cursor=m.begin(self.fixture); other=self.path.with_suffix('.new'); other.write_text(''); other.replace(self.path)
        with self.assertRaisesRegex(RuntimeError,'changed during'): self.verify(cursor)
        self.path.write_text('padding'); cursor=m.begin(self.fixture); self.path.write_text('')
        with self.assertRaisesRegex(RuntimeError,'changed during'): self.verify(cursor)

    def test_missing_symlink_and_fifo_rejected(self):
        self.path.unlink()
        with self.assertRaisesRegex(RuntimeError,'unavailable before'): m.begin(self.fixture)
        other=self.path.with_suffix('.other'); other.write_text(SECRET); self.path.symlink_to(other)
        with self.assertRaisesRegex(RuntimeError,'unavailable before'): m.begin(self.fixture)
        self.path.unlink(); os.mkfifo(self.path)
        with self.assertRaisesRegex(RuntimeError,'not a regular file'): m.begin(self.fixture)

    def test_replay_preserves_original_audit_and_ids(self):
        cursor=m.begin(self.fixture); self.chain(); evidence=self.verify(cursor)
        self.assertEqual(self.fixture.report['traceCorrelation']['replayAudit']['status'],'NOT_RUN')
        self.fixture.sql=lambda query:json.dumps(dict(rows=1,auditId=99,traceId=TRACE,requestId=REQUEST))
        with self.assertRaisesRegex(AssertionError,'changed the original'): m.verify_replay(self.fixture,evidence)
        self.assertEqual(self.fixture.report['traceCorrelation']['replayAudit']['status'],'FAIL')

    def test_one_existing_hook_and_no_external_writes(self):
        tree=ast.parse((ROOT/'tools/ci_ingestion.py').read_text())
        enabled=[n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='import_ui'
                 and any(k.arg=='correlate' and isinstance(k.value,ast.Constant) and k.value.value is True for k in n.keywords)]
        self.assertEqual(len(enabled),1); self.assertEqual(enabled[0].args[0].value,'connector')
        source=(ROOT/'tools/ci_trace_correlation.py').read_text(); helper=ast.parse(source)
        calls={n.func.attr for n in ast.walk(helper) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute)}
        self.assertFalse(calls & {'post','put','delete','route','fulfill','storage_state','request','execute_script'})
        self.assertNotIn('SELECT event_json',source)


if __name__ == '__main__': unittest.main()
