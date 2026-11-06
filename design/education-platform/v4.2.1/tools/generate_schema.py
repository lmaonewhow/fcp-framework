"""Deterministic fresh-schema SQL. Never use this as an in-place FCP migration."""
from pathlib import Path
import argparse
import csv
import io
from common import load_json
from check_design import validate

def q(n):return '`'+n+'`'
def qs(xs):return ', '.join(map(q,xs))
def render(model):
    out=['-- Education platform schema 4.2. FRESH ISOLATED DATABASE ONLY.',
         '-- This is not a migration for an existing FCP database. Runtime users must not have DDL rights.',
         '-- Native MySQL 8.4 acceptance is a separate, mandatory gate.',
         "SET NAMES utf8mb4 COLLATE utf8mb4_0900_bin;", "SET SESSION time_zone = '+00:00';", '']
    for t in model['tables']:
        lines=[]
        for c in t['columns']:
            s='  '+q(c['name'])+' '+c['type']+(' NULL' if c['nullable'] else ' NOT NULL')
            if c.get('default') is not None:s+=' DEFAULT '+str(c['default'])
            s+=" COMMENT '"+c['description'].replace('\\','\\\\').replace("'","''").replace('\n',' ')+"'"
            lines.append(s)
        lines.append('  PRIMARY KEY ('+qs(t['primary_key'])+')')
        lines += ['  UNIQUE KEY '+q('uq_'+str(i+1))+' ('+qs(k)+')' for i,k in enumerate(t['unique'])]
        lines += ['  KEY '+q('ix_'+str(i+1))+' ('+qs(k)+')' for i,k in enumerate(t['indexes'])]
        lines += ['  CONSTRAINT '+q('ck_'+t['name'][3:]+'_'+str(i+1))+' CHECK ('+c+')' for i,c in enumerate(t['checks'])]
        out += ['CREATE TABLE '+q(t['name'])+' (',',\n'.join(lines),') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;','']
    # Deferred creation of constraints permits cyclic series/head references, not deferred checking.
    for t in model['tables']:
        for i,f in enumerate(t['foreign_keys']):
            out.append('ALTER TABLE '+q(t['name'])+' ADD CONSTRAINT '+q('fk_'+t['name'][3:]+'_'+str(i+1))+' FOREIGN KEY ('+qs(f['columns'])+') REFERENCES '+q(f['target'])+' ('+qs(f['target_columns'])+') ON UPDATE RESTRICT ON DELETE RESTRICT;')
    return '\n'.join(out)+'\n'

def export_catalog(root,m):
    for filename,header,rows in [
        ('fields.csv',['table','owner','column','type','nullable','reference','enforcement','description'],[[t['name'],t['owner'],c['name'],c['type'],c['nullable'],c.get('reference'),c.get('reference_enforcement'),c['description']] for t in m['tables'] for c in t['columns']]),
        ('tables.csv',['table','owner','phase','primary_key','purpose'],[[t['name'],t['owner'],t['phase'],','.join(t['primary_key']),t['purpose']] for t in m['tables']])]:
        with (root/'database'/filename).open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.writer(f);w.writerow(header);w.writerows(rows)

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--write',action='store_true');a=p.parse_args();m=load_json(a.root/'database/model.json');errors=validate(m)
    if errors:raise SystemExit('\n'.join(errors))
    dest=a.root/'database/sql/P042__fresh_isolated_schema.sql';sql=render(m)
    if a.write:dest.write_text(sql,encoding='utf-8');export_catalog(a.root,m)
    elif not dest.exists() or dest.read_text(encoding='utf-8-sig')!=sql:raise SystemExit('SQL drift detected')
    print('PASS_GENERATION_PARITY_ONLY')
if __name__=='__main__':main()
