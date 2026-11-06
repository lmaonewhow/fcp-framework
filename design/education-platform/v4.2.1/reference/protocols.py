"""Executable protocol specification, SQLite-only. NOT a production FCP adapter.
BEGIN IMMEDIATE serializes writes globally. It demonstrates commit ordering, not
MySQL shared-lock scalability or final Java/Python authorization integration.
"""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
import hashlib
import json
import sqlite3
import re

class ManagedConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()

class Conflict(ValueError):pass
class Denied(ValueError):pass
class Invalid(ValueError):pass

@dataclass(frozen=True)
class Ticket:
    tenant: str
    actor: str
    capability: str
    release: str
    policy_epoch: int
    actor_epoch: int
    release_epoch: int
    scope_key: str = 'tenant'
    tenant_release_epoch: int = 0

SCHEMA='''
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS policy(tenant TEXT,capability TEXT,epoch INTEGER,enabled INTEGER,PRIMARY KEY(tenant,capability));
CREATE TABLE IF NOT EXISTS actor(tenant TEXT,actor TEXT,epoch INTEGER,enabled INTEGER,PRIMARY KEY(tenant,actor));
CREATE TABLE IF NOT EXISTS executor(tenant TEXT,scope_key TEXT,capability TEXT,release TEXT,epoch INTEGER,enabled INTEGER,PRIMARY KEY(tenant,scope_key,capability,release));
CREATE TABLE IF NOT EXISTS receipt(tenant TEXT,capability TEXT,intent TEXT,actor TEXT,fingerprint TEXT,result TEXT,first_contract TEXT,scope_key TEXT NOT NULL,PRIMARY KEY(tenant,capability,intent));
CREATE TABLE IF NOT EXISTS effects(id INTEGER PRIMARY KEY AUTOINCREMENT,tenant TEXT,capability TEXT,intent TEXT,payload TEXT,scope_key TEXT NOT NULL,UNIQUE(tenant,capability,intent));
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,tenant TEXT,intent TEXT,result TEXT,release TEXT);
CREATE TABLE IF NOT EXISTS denial(id INTEGER PRIMARY KEY AUTOINCREMENT,tenant TEXT,intent TEXT,code TEXT);
CREATE TABLE IF NOT EXISTS series(tenant TEXT,id TEXT,head INTEGER NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(tenant,id));
CREATE TABLE IF NOT EXISTS revision(tenant TEXT,series_id TEXT,revision INTEGER,parent INTEGER,body TEXT,PRIMARY KEY(tenant,series_id,revision));
CREATE TABLE IF NOT EXISTS account(tenant TEXT,id TEXT,balance_units INTEGER,scale INTEGER,PRIMARY KEY(tenant,id));
CREATE TABLE IF NOT EXISTS ledger(tenant TEXT,account_id TEXT,business_key TEXT,amount_units INTEGER,entry_type TEXT,reverses TEXT,PRIMARY KEY(tenant,account_id,business_key));
'''

def canonical_scope(value):
    if value=='tenant':return value
    if not isinstance(value,str) or not re.fullmatch(r'school:[1-9][0-9]{0,19}',value):raise Invalid('INVALID_SCOPE')
    if int(value.split(':')[1])>18446744073709551615:raise Invalid('INVALID_SCOPE')
    return value

def canonical_intent(payload):
    if not isinstance(payload,dict) or set(payload)!={'student_id','score'}:raise Invalid('INVALID_INTENT')
    sid=payload['student_id'];v=payload['score']
    if not isinstance(sid,str) or not 1<=len(sid)<=20 or not sid.isascii() or not sid.isdecimal() or not 0<int(sid)<=18446744073709551615 or str(int(sid))!=sid:raise Invalid('INVALID_STUDENT')
    if not isinstance(v,str) or len(v)>32:raise Invalid('INVALID_SCORE')
    try:
        d=Decimal(v)
        if not d.is_finite() or abs(d)>Decimal('99999999.9999'):raise Invalid('SCORE_RANGE')
        fixed=d.quantize(Decimal('0.0001'))
        if d!=fixed:raise Invalid('PRECISION_LOSS')
    except InvalidOperation:raise Invalid('INVALID_DECIMAL') from None
    return json.dumps({'score':format(abs(fixed) if fixed==0 else fixed,'.4f'),'student_id':sid},sort_keys=True,separators=(',',':'))

class Store:
    def __init__(self,path):
        self.path=str(path)
        with self.connect() as db:db.executescript(SCHEMA)
    def connect(self):
        db=sqlite3.connect(self.path,timeout=10,isolation_level=None,factory=ManagedConnection);db.execute('PRAGMA foreign_keys=ON');return db
    def seed(self,tenant='t1',actor='teacher1',capability='evaluation',scope_key='tenant'):
        # Explicit test provisioning only. Business entrypoints NEVER call seed.
        scope_key=canonical_scope(scope_key)
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO policy VALUES (?,?,0,1)',(tenant,capability));db.execute('INSERT OR IGNORE INTO actor VALUES (?,?,0,1)',(tenant,actor))
            for scope in dict.fromkeys(('tenant',scope_key)):
                for release in ('stable','candidate'):db.execute('INSERT OR IGNORE INTO executor VALUES (?,?,?,?,0,1)',(tenant,scope,capability,release))
    def prepare(self,tenant='t1',actor='teacher1',capability='evaluation',release='stable',scope_key='tenant'):
        scope_key=canonical_scope(scope_key)
        with self.connect() as db:
            p=db.execute('SELECT epoch,enabled FROM policy WHERE tenant=? AND capability=?',(tenant,capability)).fetchone()
            a=db.execute('SELECT epoch,enabled FROM actor WHERE tenant=? AND actor=?',(tenant,actor)).fetchone()
            g=db.execute('SELECT epoch,enabled FROM executor WHERE tenant=? AND scope_key=? AND capability=? AND release=?',(tenant,'tenant',capability,release)).fetchone()
            r=db.execute('SELECT epoch,enabled FROM executor WHERE tenant=? AND scope_key=? AND capability=? AND release=?',(tenant,scope_key,capability,release)).fetchone()
            if not all(x and x[1] for x in (p,a,g,r)):raise Denied('NOT_ALLOWED')
            return Ticket(tenant,actor,capability,release,p[0],a[0],r[0],scope_key,g[0])
    def disable_release(self,release,tenant='t1',capability='evaluation',scope_key='tenant'):
        scope_key=canonical_scope(scope_key)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE');db.execute('UPDATE executor SET enabled=0,epoch=epoch+1 WHERE tenant=? AND scope_key=? AND capability=? AND release=?',(tenant,scope_key,capability,release));db.commit()
    def revoke_actor(self,tenant='t1',actor='teacher1'):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE');db.execute('UPDATE actor SET enabled=0,epoch=epoch+1 WHERE tenant=? AND actor=?',(tenant,actor));db.commit()
    def disable_policy(self,tenant='t1',capability='evaluation'):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE');db.execute('UPDATE policy SET enabled=0,epoch=epoch+1 WHERE tenant=? AND capability=?',(tenant,capability));db.commit()
    def commit(self,ticket,intent,payload,contract='v1',fail_before_commit=False):
        if not isinstance(ticket,Ticket):raise Invalid('TRUSTED_TICKET_REQUIRED')
        if not isinstance(intent,str) or not 1<=len(intent)<=100:raise Invalid('INVALID_INTENT_ID')
        scope=canonical_scope(ticket.scope_key);body=canonical_intent(payload)
        normalized=json.dumps({'scope_key':scope,'payload':json.loads(body)},sort_keys=True,separators=(',',':'))
        fingerprint=hashlib.sha256(normalized.encode('utf-8')).hexdigest();db=self.connect()
        try:
            db.execute('BEGIN IMMEDIATE')
            p=db.execute('SELECT epoch,enabled FROM policy WHERE tenant=? AND capability=?',(ticket.tenant,ticket.capability)).fetchone()
            a=db.execute('SELECT epoch,enabled FROM actor WHERE tenant=? AND actor=?',(ticket.tenant,ticket.actor)).fetchone()
            g=db.execute('SELECT epoch,enabled FROM executor WHERE tenant=? AND scope_key=? AND capability=? AND release=?',(ticket.tenant,'tenant',ticket.capability,ticket.release)).fetchone()
            r=db.execute('SELECT epoch,enabled FROM executor WHERE tenant=? AND scope_key=? AND capability=? AND release=?',(ticket.tenant,scope,ticket.capability,ticket.release)).fetchone()
            if not all(x and x[1] for x in (p,a,g,r)) or (p[0],a[0],g[0],r[0])!=(ticket.policy_epoch,ticket.actor_epoch,ticket.tenant_release_epoch,ticket.release_epoch):raise Denied('FENCE_REJECTED')
            old=db.execute('SELECT actor,fingerprint,result,scope_key FROM receipt WHERE tenant=? AND capability=? AND intent=?',(ticket.tenant,ticket.capability,intent)).fetchone()
            if old:
                if old[0]!=ticket.actor or old[3]!=scope:raise Denied('INTENT_OWNER_OR_SCOPE_MISMATCH')
                if old[1]!=fingerprint:raise Conflict('INTENT_CONTENT_CONFLICT')
                db.commit();return old[2]
            cur=db.execute('INSERT INTO effects(tenant,capability,intent,payload,scope_key) VALUES (?,?,?,?,?)',(ticket.tenant,ticket.capability,intent,body,scope));result='effect:'+str(cur.lastrowid)
            db.execute('INSERT INTO receipt VALUES (?,?,?,?,?,?,?,?)',(ticket.tenant,ticket.capability,intent,ticket.actor,fingerprint,result,contract,scope))
            db.execute('INSERT INTO audit(tenant,intent,result,release) VALUES (?,?,?,?)',(ticket.tenant,intent,'COMMITTED',ticket.release))
            if fail_before_commit:raise Invalid('INJECTED_ROLLBACK')
            db.commit();return result
        except (Denied,Conflict,Invalid) as exc:
            db.rollback()
            # Independent transaction; minimal stable error code only, no payload serialization.
            db.execute('INSERT INTO denial(tenant,intent,code) VALUES (?,?,?)',(ticket.tenant,intent,str(exc)));raise
        except Exception:
            db.rollback();raise
        finally:db.close()
    def count(self,table):
        if table not in ('effects','audit','denial','receipt','revision','ledger'):raise ValueError('unknown count table')
        with self.connect() as db:return db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]
    def seed_series(self,series_id='s1'):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE');db.execute('INSERT INTO series VALUES (?,?,1,0)',('t1',series_id));db.execute('INSERT INTO revision VALUES (?,?,1,NULL,?)',('t1',series_id,'root'));db.commit()
    def correct(self,expected_head,expected_version,body,series_id='s1'):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT head,version FROM series WHERE tenant=? AND id=?',('t1',series_id)).fetchone()
            if row!=(expected_head,expected_version):db.rollback();raise Conflict('HEAD_CONFLICT')
            new_head=expected_head+1;db.execute('INSERT INTO revision VALUES (?,?,?,?,?)',('t1',series_id,new_head,expected_head,body))
            cur=db.execute('UPDATE series SET head=?,version=version+1 WHERE tenant=? AND id=? AND head=? AND version=?',(new_head,'t1',series_id,expected_head,expected_version))
            if cur.rowcount!=1:db.rollback();raise Conflict('HEAD_CONFLICT')
            db.commit();return new_head
    def seed_account(self,id='a1',balance='100',scale=0):
        units=money_units(balance,scale)
        if units<0:raise Invalid('NEGATIVE_BALANCE')
        with self.connect() as db:db.execute('INSERT INTO account VALUES (?,?,?,?)',('t1',id,units,scale))
    def reward(self,key,amount,entry_type,account='a1',reverses=None):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT balance_units,scale FROM account WHERE tenant=? AND id=?',('t1',account)).fetchone()
            if not row:db.rollback();raise Invalid('ACCOUNT_MISSING')
            units=money_units(amount,row[1])
            if units==0 or entry_type not in ('AWARD','SPEND','EXPIRE','REFUND','ADJUSTMENT','REVERSAL'):db.rollback();raise Invalid('ENTRY_TYPE')
            if (entry_type in ('AWARD','REFUND') and units<0) or (entry_type in ('SPEND','EXPIRE') and units>0):db.rollback();raise Invalid('ENTRY_SIGN')
            old=db.execute('SELECT amount_units,entry_type,reverses FROM ledger WHERE tenant=? AND account_id=? AND business_key=?',('t1',account,key)).fetchone()
            if old:
                db.rollback()
                if old!=(units,entry_type,reverses):raise Conflict('LEDGER_KEY_CONFLICT')
                return False
            if (entry_type=='REVERSAL')!=(reverses is not None):db.rollback();raise Invalid('REVERSAL_REFERENCE')
            if entry_type=='REVERSAL':
                original=db.execute('SELECT amount_units,entry_type FROM ledger WHERE tenant=? AND account_id=? AND business_key=?',('t1',account,reverses)).fetchone()
                if not original or original[1]=='REVERSAL' or original[0]*units>=0:db.rollback();raise Invalid('INVALID_ORIGINAL')
                used=db.execute('SELECT COALESCE(SUM(ABS(amount_units)),0) FROM ledger WHERE tenant=? AND account_id=? AND reverses=?',('t1',account,reverses)).fetchone()[0]
                if used+abs(units)>abs(original[0]):db.rollback();raise Invalid('OVER_REVERSAL')
            balance=row[0]+units
            if not 0<=balance<=9999999999999999:db.rollback();raise Invalid('BALANCE_RANGE')
            db.execute('INSERT INTO ledger VALUES (?,?,?,?,?,?)',('t1',account,key,units,entry_type,reverses));db.execute('UPDATE account SET balance_units=? WHERE tenant=? AND id=?',(balance,'t1',account));db.commit();return True

def money_units(value,scale):
    if type(scale)is not int or not 0<=scale<=4 or not isinstance(value,str) or len(value)>32:raise Invalid('INVALID_MONEY')
    try:
        d=Decimal(value)
        if not d.is_finite() or abs(d)>Decimal('999999999999.9999'):raise Invalid('MONEY_RANGE')
        q=d.quantize(Decimal(1).scaleb(-scale))
        if d!=q:raise Invalid('MONEY_PRECISION')
        return int(q*10000)
    except InvalidOperation:raise Invalid('INVALID_MONEY') from None
