"""V009 semantic regression using SQLite with explicit MySQL syntax adaptations.
This exercises the shipped SQL, not a separate cleanup algorithm. It does not
replace a real MySQL 8 acceptance run (regex/collation/transaction semantics).
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'sql/native/V009__remove_upstream_seed_menus.sql'
SEEDS = json.loads((ROOT / 'tests/seed-menu/seeds.json').read_text())
NUMERIC = {'menu_id', 'parent_id', 'order_num', 'is_frame', 'is_cache', 'create_dept', 'create_by', 'update_by'}


def mysql_subset(statement):
    statement = statement.replace('START TRANSACTION', 'BEGIN').replace('CREATE TEMPORARY', 'CREATE TEMP').replace('DROP TEMPORARY', 'DROP')
    statement = re.sub(r"BINARY ('[^']*')", r'\1 COLLATE BINARY', statement).replace('<=>', 'IS')
    statement = statement.replace("TRIM(BOTH ',' FROM ", 'TRIM(')
    # MySQL trim has an extra argument in SQLite. Only the shipped package update uses it.
    statement = statement.replace("(?=,)', ''))", "(?=,)', ''), ',')")
    if statement.startswith('DELETE c FROM'):
        statement = 'DELETE FROM fcp_v009_candidates WHERE menu_id IN (SELECT c.menu_id FROM' + statement[len('DELETE c FROM'):] + ')'
    elif statement.startswith('DELETE rm FROM'):
        statement = 'DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM fcp_v009_candidates)'
    elif statement.startswith('DELETE m FROM'):
        statement = 'DELETE FROM sys_menu WHERE menu_id IN (SELECT menu_id FROM fcp_v009_candidates)'
    elif statement.startswith('UPDATE sys_tenant_package p JOIN'):
        m = re.fullmatch(r'UPDATE sys_tenant_package p JOIN fcp_v009_candidates c ON c.menu_id = (\d+)\s+SET p.menu_ids = (.*?)\s+WHERE (.*)', statement, re.S)
        assert m, statement
        statement = f'UPDATE sys_tenant_package AS p SET menu_ids = {m[2]} WHERE EXISTS (SELECT 1 FROM fcp_v009_candidates WHERE menu_id = {m[1]}) AND {m[3]}'
    return statement


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.create_function('REGEXP_LIKE', 3, lambda text, pattern, flags: bool(re.search(pattern, text)))
        self.db.create_function('REGEXP_REPLACE', 3, lambda text, pattern, replacement: re.sub(pattern, replacement, text))
        self.db.create_function('FIND_IN_SET', 2, lambda needle, text: text is not None and needle in text.split(','))
        self.db.create_function('CONCAT', -1, lambda *parts: None if None in parts else ''.join(parts))
        fields = list(SEEDS[0])
        self.db.execute('CREATE TABLE sys_menu (' + ','.join(k + (' INTEGER' if k in NUMERIC else ' TEXT') + (' PRIMARY KEY' if k == 'menu_id' else '') for k in fields) + ')')
        self.db.executescript('CREATE TABLE sys_role_menu(role_id INTEGER,menu_id INTEGER); CREATE TABLE sys_tenant_package(package_id INTEGER,menu_ids TEXT); CREATE TABLE sys_user(user_id INTEGER); INSERT INTO sys_user VALUES(1); CREATE TABLE test_demo(id INTEGER); INSERT INTO test_demo VALUES(42);')
        for row in SEEDS:
            values = [None if v == 'null' else '2026-01-01' if v == 'sysdate()' else v.strip("'") for v in row.values()]
            self.db.execute('INSERT INTO sys_menu VALUES (' + ','.join('?' for _ in fields) + ')', values)
            self.db.execute('INSERT INTO sys_role_menu VALUES(3,?)', (int(row['menu_id'].strip("'")),))
        self.db.execute("INSERT INTO sys_tenant_package VALUES(1,'4,5,1500,1501,1502,1503,1504,1505,1506,1507,1508,1509,1510,1511,9999')")
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def run_sql(self):
        self.db.commit()
        sql = re.sub(r'(?m)^--.*$', '', SQL.read_text())
        for statement in sql.split(';'):
            if statement.strip():
                self.db.execute(mysql_subset(statement.strip()))

    def ids(self):
        return {r[0] for r in self.db.execute('SELECT menu_id FROM sys_menu')}

    def test_pristine_and_rerun(self):
        self.run_sql()
        self.assertEqual(self.ids(), set())
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sys_role_menu').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT menu_ids FROM sys_tenant_package').fetchone()[0], '9999')
        self.assertEqual(self.db.execute('SELECT * FROM sys_user').fetchall(), [(1,)])
        self.assertEqual(self.db.execute('SELECT * FROM test_demo').fetchall(), [(42,)])
        self.run_sql()
        self.assertEqual(self.ids(), set())

    def test_every_static_identity_field_protects_reused_id(self):
        for field in SEEDS[0]:
            if field in ('menu_id', 'create_time'):
                continue
            with self.subTest(field=field):
                self.db.execute('SAVEPOINT identity_case')
                self.db.execute(f'UPDATE sys_menu SET {field}=? WHERE menu_id=1501', (98765 if field in NUMERIC else 'custom',))
                # BEGIN is intentionally omitted inside this savepoint.
                sql = re.sub(r'(?m)^--.*$', '', SQL.read_text())
                for stmt in sql.split(';'):
                    if stmt.strip() and stmt.strip() not in ('START TRANSACTION', 'COMMIT'):
                        self.db.execute(mysql_subset(stmt.strip()))
                self.assertIn(1501, self.ids())
                self.db.execute('ROLLBACK TO identity_case')
                self.db.execute('RELEASE identity_case')

    def test_custom_child_protects_all_ancestors_and_refs(self):
        self.db.execute("INSERT INTO sys_menu(menu_id,parent_id,menu_name) VALUES(9998,1501,'school business')")
        self.run_sql()
        self.assertEqual(self.ids(), {5,1500,1501,9998})
        self.assertEqual(self.db.execute('SELECT menu_id FROM sys_role_menu ORDER BY menu_id').fetchall(), [(5,), (1500,), (1501,)])
        self.assertEqual(self.db.execute('SELECT menu_ids FROM sys_tenant_package').fetchone()[0], '5,1500,1501,9999')

    def test_custom_and_system_menus_and_grants_survive(self):
        self.db.execute("INSERT INTO sys_menu(menu_id,parent_id,menu_name,path,component,perms) VALUES(9997,0,'测试单表','demo','demo/demo/index','demo:demo:list')")
        self.db.execute("INSERT INTO sys_menu(menu_id,parent_id,menu_name,path) VALUES(1,0,'系统管理','system')")
        self.db.execute('INSERT INTO sys_role_menu VALUES(1,1)')
        self.db.execute('INSERT INTO sys_role_menu VALUES(7,9997)')
        self.db.execute("INSERT INTO sys_tenant_package VALUES(2,'1,9997')")
        self.run_sql()
        self.assertEqual(self.ids(), {1,9997})
        self.assertEqual(self.db.execute('SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id').fetchall(), [(1,1),(7,9997)])
        self.assertEqual(self.db.execute('SELECT menu_ids FROM sys_tenant_package WHERE package_id=2').fetchone()[0], '1,9997')

    def test_reused_root_preserves_descendant_identity(self):
        self.db.execute("UPDATE sys_menu SET path='school' WHERE menu_id=5")
        self.run_sql()
        self.assertEqual(self.ids(), {5, *range(1500,1512)})

    def test_case_and_whitespace_are_not_original_identity(self):
        self.db.execute("UPDATE sys_menu SET path='Demo' WHERE menu_id=5")
        self.db.execute("UPDATE sys_menu SET menu_name=menu_name || ' ' WHERE menu_id=4")
        self.run_sql()
        self.assertEqual(len(self.ids()), 14)

    def test_csv_exact_tokens_duplicates_null_and_empty(self):
        self.db.execute("UPDATE sys_tenant_package SET menu_ids='4,4,1500,1500,15000,41500,15004,9999,4'")
        self.db.execute('INSERT INTO sys_tenant_package VALUES(2,NULL)')
        self.db.execute("INSERT INTO sys_tenant_package VALUES(3,'')")
        self.run_sql()
        self.assertEqual(self.db.execute('SELECT menu_ids FROM sys_tenant_package ORDER BY package_id').fetchall(), [('15000,41500,15004,9999',), (None,), ('',)])

    def test_malformed_csv_preserves_all(self):
        self.db.execute("UPDATE sys_tenant_package SET menu_ids='4, 5,01500'")
        self.run_sql()
        self.assertEqual(len(self.ids()), 14)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sys_role_menu').fetchone()[0], 14)

    def test_trailing_newline_csv_preserves_all(self):
        self.db.execute('UPDATE sys_tenant_package SET menu_ids=?', ('4,5,1500\n',))
        self.run_sql()
        self.assertEqual(len(self.ids()), 14)
        self.assertEqual(self.db.execute('SELECT menu_ids FROM sys_tenant_package').fetchone()[0], '4,5,1500\n')

    def test_lock_and_installer_include_nine_stages(self):
        spec = importlib.util.spec_from_file_location('mysql_install', ROOT / 'tools/mysql_install.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        lock = json.loads((ROOT / 'sql/native/migrations.lock.json').read_text())
        stages = module.checked_stages(sorted((ROOT / 'sql/native').glob('V*.sql')), lock)
        self.assertEqual(len(stages), 9)
        self.assertEqual(stages[-1]['sha256'], hashlib.sha256(SQL.read_bytes()).hexdigest())
        self.assertIn("ROOT/'sql/native/" + SQL.name + "'", (ROOT / 'tools/mysql_install.py').read_text())

if __name__ == '__main__':
    unittest.main()
