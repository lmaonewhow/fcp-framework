"""Validate generated fixture text against locked column declarations, without executing SQL.
This is a source regression, not native MySQL acceptance.
"""
import importlib.util
import json
from pathlib import Path
import re
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('ci_education_seed',ROOT/'tools/ci_education.py')
ci=importlib.util.module_from_spec(spec);spec.loader.exec_module(ci)

# Extracted from pinned backend 8136a0191a2258c0e1b36a8146a1c5ebc070c139,
# script/sql/ry_vue_5.X.sql Git blob 3cd642b0be5916b2d2081b07282213de618aec0c.
# Re-review these declarations if that lock changes; never widen production DDL for fixtures.
NATIVE_COLUMNS={
    'sys_role':dict(role_id=None,tenant_id=20,role_name=30,role_key=100,role_sort=None,
                    data_scope=1,status=1,del_flag=1,create_by=None,create_time=None),
    'sys_user_role':dict(user_id=None,role_id=None),
    'sys_role_menu':dict(role_id=None,menu_id=None),
}

def split_sql_values(text):
    parts=[];start=0;depth=0;quoted=False;i=0
    while i<len(text):
        char=text[i]
        if char=="'":
            if quoted and i+1<len(text) and text[i+1]=="'":i+=2;continue
            quoted=not quoted
        elif not quoted:
            if char=='(':depth+=1
            elif char==')':depth-=1
            elif char==',' and depth==0:parts.append(text[start:i].strip());start=i+1
        i+=1
    if quoted or depth!=0:raise ValueError('Malformed generated fixture SQL')
    return parts+[text[start:].strip()]

class SeedLimits(unittest.TestCase):
    def test_pinned_native_source_is_unchanged(self):
        self.assertEqual('8136a0191a2258c0e1b36a8146a1c5ebc070c139',json.loads((ROOT/'upstreams.lock.json').read_text())['upstreams']['backend']['commit'])
        self.assertIn("'script/sql/ry_vue_5.X.sql':'3cd642b0be5916b2d2081b07282213de618aec0c'",(ROOT/'tools/native.py').read_text())
    def test_every_generated_seed_string_fits_its_locked_column(self):
        model=json.loads((ROOT/'design/education-platform/v4.2.1/database/model.json').read_text())
        columns={table['name']:{column['name']:(int(match[1]) if (match:=re.match(r'(?:VAR)?CHAR\((\d+)\)',column['type'])) else None)
                  for column in table['columns']} for table in model['tables']}
        columns.update(NATIVE_COLUMNS)
        extension=(ROOT/'sql/education/001__host_education_authority.sql').read_text()
        columns['ed_host_education_role']={name:(int(width) if kind=='VARCHAR' else None) for name,kind,width in re.findall(r'^  (\w+) (VARCHAR|BIGINT|TINYINT|DATETIME)(?:\((\d+)\))?',extension,re.M)}
        captured=[]
        fixture=ci.EducationFixture.__new__(ci.EducationFixture)
        fixture.plan=SimpleNamespace(database='fcp_ci_'+'1'*12)
        fixture.digest='a'*64;fixture.sql=captured.append;fixture.report={}
        fixture.seed()
        self.assertEqual(1,len(captured))
        checked=0
        for line in captured[0].splitlines():
            if not line.startswith('INSERT INTO '):continue
            match=re.fullmatch(r'INSERT INTO (\w+)\(([^)]+)\) VALUES(.+);',line)
            self.assertIsNotNone(match)
            table,fields,raw=match.groups();fields=fields.split(',')
            self.assertTrue(set(fields)<=set(columns[table]),'Unknown seed column: '+table)
            for row in split_sql_values(raw):
                self.assertTrue(row.startswith('(') and row.endswith(')'))
                values=split_sql_values(row[1:-1]);self.assertEqual(len(fields),len(values))
                for column,value in zip(fields,values):
                    if value.startswith("'"):
                        self.assertTrue(value.endswith("'"));text=value[1:-1].replace("''", "'")
                        width=columns[table][column]
                        if width is not None:
                            self.assertLessEqual(len(text),width,f'{table}.{column} exceeds locked width {width}')
                            checked+=1
        self.assertGreater(checked,100)
    def test_original_role_names_exceed_pinned_width(self):
        for name in ('tenant_admin','school_admin'):
            self.assertEqual(32,len('Synthetic education '+name))
            self.assertGreater(len('Synthetic education '+name),NATIVE_COLUMNS['sys_role']['role_name'])
if __name__=='__main__':unittest.main()
