"""Relational test adapter. SQLite preserves PK/UQ/FK/CHECK relations only.
It does not emulate MySQL collation, DECIMAL, JSON typing, unsigned range, locking or DDL.
Optional native path requires a separately provisioned empty acceptance schema.
"""
import json
import os
from pathlib import Path
import sqlite3
from mysql_support import initialize_session, NativeConstraintViolation, CONSTRAINT_ERROR_CODES

class NativeResult:
    """Normalize result containers; rows remain tuples, fetchall always returns a list."""
    def __init__(self, cursor):self.cursor=cursor
    def fetchone(self):return self.cursor.fetchone()
    def fetchall(self):return list(self.cursor.fetchall())
    def __getattr__(self, key):return getattr(self.cursor,key)

ROOT=Path(__file__).resolve().parents[1]
MODEL=json.loads((ROOT/'database/model.json').read_text(encoding='utf-8-sig'))
TABLES={t['name']:t for t in MODEL['tables']}

class DB:
    def __init__(self,raw,kind='sqlite'):
        self.raw,self.kind=raw,kind
        if kind=='sqlite':self.integrity_errors=(sqlite3.IntegrityError,)
        else:
            import pymysql
            self.driver=pymysql
            self.integrity_errors=(NativeConstraintViolation,)
    def execute(self,sql,args=()):
        if self.kind=='sqlite':return self.raw.execute(sql,args)
        cur=self.raw.cursor()
        try:
            cur.execute(sql.replace('?','%s'),args)
        except self.driver.MySQLError as exc:
            cur.close()
            code=exc.args[0] if exc.args else None
            if type(code) is int and code in CONSTRAINT_ERROR_CODES:
                raise NativeConstraintViolation(code) from None
            raise
        return NativeResult(cur)
    def close(self):self.raw.close()
    def commit(self):self.raw.commit()
    def rollback(self):self.raw.rollback()

def schema_sqlite(model=MODEL):
    out=['PRAGMA foreign_keys=ON;']
    for t in model['tables']:
        cs=[]
        for c in t['columns']:
            ty=c['type'];sqlty='INTEGER' if 'INT' in ty else 'NUMERIC' if ty.startswith('DECIMAL') else 'TEXT'
            s='`'+c['name']+'` '+sqlty+('' if c['nullable'] else ' NOT NULL')
            d=c.get('default')
            if d is not None:s+=' DEFAULT '+("'2026-11-06 00:00:00.000000'" if d=='CURRENT_TIMESTAMP(6)' else str(d))
            cs.append(s)
        quote=lambda seq:','.join('`'+k+'`' for k in seq)
        cs.append('PRIMARY KEY ('+quote(t['primary_key'])+')')
        cs += ['UNIQUE ('+quote(u)+')' for u in t['unique']]
        cs += ['FOREIGN KEY ('+quote(f['columns'])+') REFERENCES `'+f['target']+'` ('+quote(f['target_columns'])+') ON UPDATE RESTRICT ON DELETE RESTRICT' for f in t['foreign_keys']]
        cs += ['CHECK ('+s+')' for s in t['checks']]
        out.append('CREATE TABLE `'+t['name']+'` ('+',\n'.join(cs)+');')
    return '\n'.join(out)

def open_db():
    schema=os.environ.get('EDU_MYSQL_TEST_SCHEMA')
    if schema:
        import re,pymysql
        if not re.fullmatch('fcp_design_verify_[a-z0-9_]+',schema):raise ValueError('unsafe native test schema')
        raw=pymysql.connect(host='127.0.0.1',port=int(os.environ.get('EDU_MYSQL_PORT','3306')),user=os.environ['EDU_MYSQL_USER'],password=os.environ['EDU_MYSQL_PASSWORD'],database=schema,charset='utf8mb4',autocommit=False,connect_timeout=5,read_timeout=10,write_timeout=10)
        try:initialize_session(raw)
        except BaseException:
            raw.close();raise
        db=DB(raw,'mysql')
        # Native runner restores empty schema per test by transaction rollback. Fixture inserts are never committed.
        return db
    raw=sqlite3.connect(':memory:');raw.executescript(schema_sqlite());return DB(raw)

def values(name,**override):
    result={};t=TABLES[name]
    for c in t['columns']:
        n,ty=c['name'],c['type']
        if n in override:result[n]=override[n];continue
        if c.get('default') is not None:continue
        if c['nullable']:result[n]=None;continue
        if n=='tenant_id':v='t1'
        elif n=='id':v=1
        elif 'INT' in ty:v=1
        elif ty.startswith('DECIMAL'):v='0.0000'
        elif ty.startswith('DATETIME'):v='2026-11-06 00:00:00.000000'
        elif ty=='DATE':v='2026-11-06'
        elif ty=='JSON':v='{}'
        elif ty.startswith('CHAR(64)'):v='a'*64
        else:v='test'
        result[n]=v
    result.update(override);return result

def insert(db,name,**override):
    row=values(name,**override);cols=','.join('`'+n+'`' for n in row)
    return db.execute('INSERT INTO `'+name+'` ('+cols+') VALUES ('+','.join('?' for _ in row)+')',tuple(row.values()))

def seed(db):
    for tid in ('t1','t2'):
        insert(db,'ed_core_tenant_binding',tenant_id=tid,host_tenant_key=tid,status='ACTIVE')
    for sid in (100,200):
        insert(db,'ed_education_org_unit',id=sid,org_code=str(sid),org_kind='SCHOOL',parent_id=None,valid_to=None)
        insert(db,'ed_education_term',id=sid+10,school_id=sid,term_code='2026-1',starts_on='2026-09-01',ends_on='2027-01-31')
        insert(db,'ed_education_class',id=sid+11,school_id=sid,term_id=sid+10,class_code='01')
        insert(db,'ed_education_enrollment',id=sid+12,school_id=sid,person_id=sid,student_code='0001',admission_key='2026')
    insert(db,'ed_eval_rubric',id=400)
    insert(db,'ed_eval_indicator',id=410,rubric_id=400)
    for oid in (420,421):insert(db,'ed_eval_observation',id=oid,subject_kind='PERSON',subject_id=100,class_id=111,extensions=None)
    insert(db,'ed_eval_judgement_series',id=430,observation_id=420,indicator_id=410,judge_key='teacher1',purpose_key='formal',current_judgement_id=None)
    insert(db,'ed_eval_judgement',id=440,series_id=430,observation_id=420,indicator_id=410,revision_no=1,supersedes_id=None,score='1.0000')
    db.execute('UPDATE ed_eval_judgement_series SET current_judgement_id=440 WHERE tenant_id=? AND id=430',('t1',))
    insert(db,'ed_workflow_definition',id=490)
    for cid in (500,600):
        insert(db,'ed_workflow_case',id=cid,definition_id=490)
        insert(db,'ed_workflow_task',id=cid+10,case_id=cid)
    for aid in (700,800):
        insert(db,'ed_reward_account',id=aid,person_id=aid,balance='100.0000',unit_scale=0)
        insert(db,'ed_reward_entry',id=aid+1,account_id=aid,business_key='initial',amount='100.0000',entry_type='AWARD',reverses_entry_id=None)
    for rid,dep in ((900,'d1'),(901,'d1'),(902,'d2')):
        insert(db,'ed_ops_release_binding',id=rid,deployment_key=dep,status='REGISTERED')
