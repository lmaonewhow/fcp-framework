"""Pure guards/process-order tests. These do not represent native CRUD acceptance."""
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools'),str(ROOT/'tests/generator')]
from ci_native import parse_args
from ci_native_host import replace_host, stop_host
from business_database import COLUMNS, TITLE, require_owned, validate_menu_sql, install_generated_menu
from business_runtime import native_request


def menu_sql():
    ids = [str(990100+i) for i in range(6)]
    rows = []
    for index,(action,suffix) in enumerate(zip(('list','query','add','edit','remove','export'),('','查询','新增','修改','删除','导出'))):
        values = [ids[index], "'"+TITLE+suffix+"'", "'3'" if index==0 else ids[0], "'"+str(max(1,index))+"'", "'probe'" if index==0 else "'#'", "'generatorprobe/probe/index'" if index==0 else "''", '1','0',"'C'" if index==0 else "'F'","'0'","'0'","'generatorprobe:probe:"+action+"'","'#'",'103','1','sysdate()','null','null',"'"+TITLE+"菜单'" if index==0 else "''"]
        rows.append('insert into sys_menu ('+COLUMNS+') values('+','.join(values)+');')
    return '-- synthetic pinned menu test only\n'+'\n'.join(rows)


class GeneratedBusinessGuards(unittest.TestCase):
    def test_gate_requires_both_existing_native_fixture_flags(self):
        for args in (['--generator-business'],['--generator-business','--generator'],['--generator-business','--ingestion']):
            with self.subTest(args=args), self.assertRaises(SystemExit): parse_args(args)
        self.assertTrue(parse_args(['--generator-business','--generator','--ingestion']).generator_business)
        self.assertFalse(parse_args(['--generator','--ingestion']).generator_business)
    def test_exact_owned_disposable_database_only(self):
        with mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            require_owned('fcp_ci_123456abcdef','fcp_ci_123456abcdef')
            for db,owned in [('production','production'),('fcp_ci_123456abcdef','fcp_ci_654321abcdef'),('fcp_local_test','fcp_local_test')]:
                with self.assertRaises(RuntimeError): require_owned(db,owned)
        with mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}),self.assertRaises(RuntimeError):require_owned('fcp_ci_123456abcdef','fcp_ci_123456abcdef')
    def test_exact_six_menu_inserts(self):
        self.assertEqual(validate_menu_sql(menu_sql()),[str(990100+i) for i in range(6)])
    def test_rejects_sql_escalation_mixed_statements_and_contract_drift(self):
        source = menu_sql()
        attacks = [source+' DROP TABLE sys_user;',source.replace('sys_menu','sys_user'),source.replace("'generatorprobe:probe:add'","'*:*:*'"),source.replace("'3'","'0'",1),source.replace('990101','990100'),source.replace('sysdate()','sleep(1)',1),source.replace('insert into','replace into',1),source.replace("'probe'","'probe; DROP TABLE sys_user'",1),source.replace("'生成器合成验收'","'other'",1),source.replace('990100','9223372036854775808')]
        for attack in attacks:
            with self.subTest(attack=attack[:70]),self.assertRaises(RuntimeError):validate_menu_sql(attack)
    def test_preflight_collision_never_calls_backup_or_writes_sql(self):
        with tempfile.TemporaryDirectory() as tmp,mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}),mock.patch('business_database.subprocess.run') as execute:
            root=Path(tmp);source=root/'probeMenu.sql';source.write_text(menu_sql())
            sql=mock.Mock(side_effect=['fcp_ci_123456abcdef\n','1\n'])
            fixture=SimpleNamespace(database='fcp_ci_123456abcdef',sql=sql)
            with self.assertRaises(RuntimeError): install_generated_menu(fixture,source,root,root/'private.cnf',fixture.database)
            execute.assert_not_called()
            self.assertFalse(any('START TRANSACTION' in call.args[0] for call in sql.call_args_list))
    def test_backup_failure_does_not_execute(self):
        with tempfile.TemporaryDirectory() as tmp,mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}),mock.patch('business_database.subprocess.run',return_value=SimpleNamespace(returncode=1)):
            root=Path(tmp);source=root/'probeMenu.sql';source.write_text(menu_sql())
            sql=mock.Mock(side_effect=['fcp_ci_123456abcdef\n','0\n','0\n','1\n','1\n','0\n'])
            fixture=SimpleNamespace(database='fcp_ci_123456abcdef',sql=sql)
            with self.assertRaises(RuntimeError):install_generated_menu(fixture,source,root,root/'private.cnf',fixture.database)
            self.assertTrue((root/'menu-plan.json').is_file())
            self.assertEqual(sql.call_count,6)
            self.assertFalse(any('START TRANSACTION' in call.args[0] for call in sql.call_args_list))
    def test_sql_change_after_backup_aborts(self):
        with tempfile.TemporaryDirectory() as tmp,mock.patch.dict(os.environ,{'GITHUB_ACTIONS':'true'}):
            root=Path(tmp);source=root/'probeMenu.sql';source.write_text(menu_sql())
            preflight=['fcp_ci_123456abcdef\n','0\n','0\n','1\n','1\n','0\n']
            sql=mock.Mock(side_effect=preflight*2)
            def backup(*args,**kwargs):
                kwargs['stdout'].write('synthetic dump')
                source.write_text(source.read_text()+'\n-- changed after preview')
                return SimpleNamespace(returncode=0)
            fixture=SimpleNamespace(database='fcp_ci_123456abcdef',sql=sql)
            with mock.patch('business_database.subprocess.run',side_effect=backup),self.assertRaises(RuntimeError):install_generated_menu(fixture,source,root,root/'private.cnf',fixture.database)
            self.assertEqual(sql.call_count,12)
    def test_request_paths_reject_external_or_nonbusiness_targets(self):
        for path in ('https://example.com/generatorprobe/probe','/system/user/list','//example.com/generatorprobe/probe','/generatorprobe/probe#fragment'):
            with self.assertRaises(ValueError):native_request('http://127.0.0.1:18787','real-token-placeholder',path)
    def test_transition_stops_then_builds_then_owns_latest_jvm(self):
        calls=[];current={'process':'old'}
        def stop():calls.append('stop-old');current['process']=None
        def prepare():self.assertIsNone(current['process']);calls.append('build');return {'jar':'generated'}
        def start(value):self.assertIsNone(current['process']);calls.append('start-new');current['process']=value
        result=replace_host(stop,prepare,start)
        self.assertIs(result,current['process']);self.assertEqual(calls,['stop-old','build','start-new'])
    def test_no_restart_after_stop_or_package_failure(self):
        for stage in ('stop','prepare'):
            stop=mock.Mock();prepare=mock.Mock();start=mock.Mock()
            {'stop':stop,'prepare':prepare}[stage].side_effect=RuntimeError(stage)
            with self.assertRaises(RuntimeError):replace_host(stop,prepare,start)
            start.assert_not_called()
            if stage=='stop':prepare.assert_not_called()
    def test_termination_timeout_kills_and_reaps_then_fails_closed(self):
        process=mock.Mock();process.poll.return_value=None
        process.wait.side_effect=[subprocess.TimeoutExpired('java',35),0]
        with self.assertRaises(RuntimeError):stop_host(process)
        process.terminate.assert_called_once();process.kill.assert_called_once();self.assertEqual(process.wait.call_count,2)
    def test_stopped_process_is_never_terminated_again(self):
        process=mock.Mock();process.poll.return_value=0;stop_host(process)
        process.terminate.assert_not_called()
    def test_ci_tracks_latest_process_and_explicit_gate(self):
        code=(ROOT/'tools/ci_native.py').read_text()
        self.assertIn('generated_business_pipeline if a.generator_business else None',code)
        self.assertIn('nonlocal process',code)
        self.assertIn('process=subprocess.Popen',code)
        self.assertIn('Refusing overlapping native JVMs',code)
        self.assertIn('stop_host(process),prepare,launch',code)
        workflow=(ROOT/'.github/workflows/native-ingestion.yml').read_text()
        self.assertIn('--exchange --generator --generator-business',workflow)

if __name__=='__main__':unittest.main()
