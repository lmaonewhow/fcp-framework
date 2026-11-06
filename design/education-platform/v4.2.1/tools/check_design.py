"""Strict model/DDL consistency checks. Never claims native database execution."""
import argparse
import re
from pathlib import Path
from common import load_json

IDENT = re.compile(r'^[a-z][a-z0-9_]{0,63}$')
TYPE = re.compile(r'^(?:BIGINT UNSIGNED|INT UNSIGNED|TINYINT UNSIGNED|DATETIME\(6\)|DATE|TEXT|JSON|DECIMAL\(\d+,\d+\)|(?:VAR)?CHAR\(\d+\)(?: CHARACTER SET (?:ascii|utf8mb4))?(?: COLLATE (?:ascii_bin|utf8mb4_0900_bin|utf8mb4_0900_ai_ci))?)$')

def _validate(model):
    errors=[]
    if not isinstance(model,dict) or not isinstance(model.get('tables'),list) or not model['tables']:
        return ['model.tables must be a nonempty array']
    if model.get('default_collation') != 'utf8mb4_0900_bin': errors.append('identity collation not fixed to NO PAD')
    tables=model['tables']; names={}
    for t in tables:
        if not isinstance(t,dict) or not isinstance(t.get('name'),str) or not IDENT.fullmatch(t['name']):
            errors.append('invalid table declaration');continue
        if t['name'] in names:errors.append(t['name']+': duplicate table')
        names[t['name']]=t
    def key_ok(k,cols):
        return isinstance(k,list) and bool(k) and all(isinstance(c,str) and c in cols for c in k) and len(set(k))==len(k)
    for name,t in names.items():
        fail=lambda msg: errors.append(name+': '+msg)
        raw=t.get('columns')
        if not isinstance(raw,list) or not raw or any(not isinstance(c,dict) for c in raw):fail('invalid columns');continue
        cols={}
        for c in raw:
            n=c.get('name')
            if not isinstance(n,str) or not IDENT.fullmatch(n):fail('invalid column name');continue
            if n in cols:fail('duplicate column')
            cols[n]=c
            if not isinstance(c.get('type'),str) or len(c['type'])>128 or not TYPE.fullmatch(c['type']):
                fail('unsupported type '+n);continue
            char = re.match(r'^(VARCHAR|CHAR)\((\d+)\)', c['type'])
            if char:
                width=int(char.group(2));multiplier=1 if 'CHARACTER SET ascii' in c['type'] else 4
                if (char.group(1)=='CHAR' and width>255) or (char.group(1)=='VARCHAR' and (width>65535 or width*multiplier+(1 if width*multiplier<=255 else 2)>65535)):
                    fail('invalid MySQL string width '+n)
            dec=re.fullmatch(r'DECIMAL\((\d+),(\d+)\)',c.get('type',''))
            if dec:
                precision,scale=map(int,dec.groups())
                if not 1<=precision<=65 or not 0<=scale<=min(30,precision):fail('invalid MySQL DECIMAL precision/scale '+n)
            if type(c.get('nullable')) is not bool:fail('nullable must be boolean '+n)
            d=c.get('default')
            if d is not None and (not isinstance(d,str) or not re.fullmatch(r"(?:CURRENT_TIMESTAMP\(6\)|[0-9]+|'-?[A-Za-z0-9_.:-]+')",d)):fail('unsupported default '+n)
        # SQL-layer row-size lower bound: declared string payloads + VARCHAR prefixes.
        # If it already exceeds 65535, the DDL cannot be accepted unchanged.
        # A pass is NOT a proof of InnoDB physical row-size compatibility.
        text_lower_bound=0
        for col in cols.values():
            ty=col.get('type','')
            cm=re.match(r'^(VARCHAR|CHAR)\((\d{1,6})\)',ty) if isinstance(ty,str) else None
            if cm:
                b=int(cm.group(2))*(1 if 'CHARACTER SET ascii' in ty else 4)
                text_lower_bound+=b+(1 if b<=255 else 2) if cm.group(1)=='VARCHAR' else b
        if text_lower_bound>65535:fail('declared string storage alone exceeds SQL row-size bound')
        pk=t.get('primary_key');uni=t.get('unique');ix=t.get('indexes');fks=t.get('foreign_keys')
        if not all(isinstance(x,list) for x in [pk,uni,ix,fks]):fail('invalid key arrays');continue
        if not key_ok(pk,cols) or pk[0]!='tenant_id':fail('PK must start with tenant_id')
        if any(cols.get(c,{}).get('nullable',True) for c in pk):fail('nullable primary key')
        if cols.get('tenant_id',{}).get('type')!='VARCHAR(100) COLLATE utf8mb4_0900_bin':fail('tenant identity type/collation mismatch')
        shape=t.get('key_shape')
        if shape=='TENANT_SINGLETON' and pk!=['tenant_id']:fail('singleton PK invalid')
        elif shape=='TENANT_ENTITY' and pk!=['tenant_id','id']:fail('entity PK invalid')
        elif shape not in ('TENANT_ENTITY','TENANT_SINGLETON','TENANT_NATURAL'):fail('undeclared key shape')
        keys=[pk,*uni,*ix]
        valid=[]
        for k in keys:
            if not key_ok(k,cols):fail('key references invalid/duplicate column');continue
            if tuple(k) in valid:fail('duplicate key '+str(k))
            valid.append(tuple(k))
            size=0
            for n in k:
                ty=cols[n].get('type','');s=re.search(r'(?:VAR)?CHAR\((\d+)\)',ty)
                if s:size+=int(s.group(1))*(1 if 'ascii' in ty else 4)
                elif ty.startswith(('TEXT','JSON')):fail('unbounded indexed field '+n)
                else:size+=8
            if size>3072:fail('index exceeds declared 3072-byte budget')
        valid_fk=[]
        for f in fks:
            if not isinstance(f,dict):fail('invalid FK object');continue
            target=names.get(f.get('target'));cs=f.get('columns');ds=f.get('target_columns')
            if not target:fail('unknown FK target');continue
            tc={c.get('name'):c for c in target.get('columns',[]) if isinstance(c,dict)}
            if not key_ok(cs,cols) or not key_ok(ds,tc):fail('FK unknown column');continue
            if len(cs)!=len(ds):fail('FK arity mismatch');continue
            if cs[0]!='tenant_id' or ds[0]!='tenant_id':fail('FK lacks tenant scope')
            if target.get('owner')!=t.get('owner'):fail('cross-owner physical FK')
            if ds not in [target.get('primary_key'),*target.get('unique',[])]:fail('FK target must be an explicit exact unique key')
            if not any(k[:len(cs)]==cs for k in keys if isinstance(k,list)):fail('FK lacks explicit supporting index')
            for a,b in zip(cs,ds):
                if cols[a].get('type')!=tc[b].get('type'):fail('FK type or collation mismatch')
            token=(tuple(cs),f['target'],tuple(ds))
            if token in valid_fk:fail('duplicate FK')
            valid_fk.append(token)
        for n,c in cols.items():
            target=c.get('reference');mode=c.get('reference_enforcement')
            if target:
                if target not in names:fail('reference target missing '+n);continue
                if mode=='COMPOSITE_FK':
                    if not any(f.get('target')==target and n in f.get('columns',[]) and len(f.get('columns',[]))==len(f.get('target_columns',[])) and f['target_columns'][f['columns'].index(n)]=='id' for f in fks if isinstance(f,dict)):
                        fail('reference declaration lacks matching FK '+n)
                elif mode=='OWNER_API_CHECK_AND_RECONCILIATION':
                    resolver=model.get('reference_contracts',{}).get(c.get('resolver'))
                    if not isinstance(resolver,dict) or resolver.get('target')!=target or not resolver.get('on_missing'):fail('logical reference lacks resolver '+n)
                    if c.get('type')!='BIGINT UNSIGNED':fail('logical internal reference type mismatch '+n)
                else:fail('unknown reference enforcement '+n)
            elif mode:fail('reference enforcement without target '+n)
            if c.get('polymorphic_resolver') and c['polymorphic_resolver'] not in model.get('polymorphic_contracts',{}):fail('unknown polymorphic resolver '+n)
        # Overlap must be intentional and tied to a retained query, not automatically generated.
        for x in ix:
            if not key_ok(x,cols):continue
            bigger=[k for k in [pk,*uni,*ix] if isinstance(k,list) and k!=x and k[:len(x)]==x]
            if bigger and ','.join(x) not in t.get('index_waivers',{}):fail('unreviewed index prefix overlap '+str(x))
        if not isinstance(t.get('checks'),list) or any(not isinstance(c,str) or ';' in c or '--' in c for c in t.get('checks',[])):fail('invalid check expression')
        for expr in t.get('checks',[]):
            if not isinstance(expr,str):continue
            stripped=re.sub(r"'[^']*'",'',expr)
            tokens=re.findall(r'[A-Za-z_][A-Za-z0-9_]*',stripped)
            if any(k not in cols and k.upper() not in {'IS','NOT','NULL','OR','AND','IN','BETWEEN','TRUE','FALSE'} for k in tokens):fail('check expression references unknown token')
        if not isinstance(t.get('domain_invariants'),list):fail('domain_invariants must be array')
        for inv in t.get('domain_invariants',[]):
            if inv not in model.get('invariant_contracts',{}):fail('unregistered domain invariant '+inv)
    return errors

def validate(model):
    try:
        return _validate(model)
    except (KeyError, IndexError, TypeError, AttributeError):
        return ['invalid nested model schema shape']

def main():
    import json
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);a=p.parse_args()
    try:
        m=load_json(a.root/'database/model.json');e=validate(m)
        if not e:
            from generate_schema import render
            if (a.root/'database/sql/P042__fresh_isolated_schema.sql').read_text(encoding='utf-8-sig')!=render(m):e.append('generated SQL differs from model')
        print(json.dumps({'status':'FAIL' if e else 'PASS_STATIC_ONLY','tables':len(m.get('tables',[])),'errors':e,'native_mysql_executed':False},ensure_ascii=True,indent=2));return 1 if e else 0
    except (OSError,ValueError,TypeError) as e:
        print(json.dumps({'status':'ERROR','error':str(e)},ensure_ascii=True));return 1
if __name__=='__main__':raise SystemExit(main())
