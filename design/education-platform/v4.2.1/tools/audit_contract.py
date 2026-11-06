"""Validate this package's audit-v2 contract. Input is constructed by trusted server code.
No authorization, persistence or protected-diff access is provided by this validator.
"""
from datetime import datetime, timezone
from pathlib import Path
import re
from common import load_json
ROOT=Path(__file__).resolve().parents[1]
class AuditRejected(ValueError):pass

def validate_audit_event(event):
    schema=load_json(ROOT/'contracts/audit-event.schema.json');props=schema['properties']
    if not isinstance(event,dict) or set(event)-set(props) or set(schema['required'])-set(event):raise AuditRejected('INVALID_AUDIT_FIELDS')
    for k,v in event.items():
        p=props[k]
        if 'const' in p and v!=p['const']:raise AuditRejected('INVALID_AUDIT_CONSTANT')
        if p.get('type')=='string':
            if not isinstance(v,str) or not p.get('minLength',1)<=len(v)<=p.get('maxLength',100):raise AuditRejected('INVALID_AUDIT_TEXT')
            if 'pattern' in p and not re.fullmatch(p['pattern'],v):raise AuditRejected('INVALID_AUDIT_PATTERN')
            if p.get('format')=='uuid' and not re.fullmatch('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',v):raise AuditRejected('INVALID_AUDIT_UUID')
            if p.get('format')=='date-time':
                if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)',v):
                    raise AuditRejected('INVALID_AUDIT_UTC')
                try:
                    t=datetime.fromisoformat(v.replace('Z','+00:00'))
                    if t.tzinfo is None or t.utcoffset()!=timezone.utc.utcoffset(t):raise ValueError('UTC required')
                    # Use MySQL's documented supported range, not Python's broader calendar.
                    if t < datetime(1000,1,1,tzinfo=timezone.utc) or t > datetime(9999,12,31,23,59,59,499999,tzinfo=timezone.utc):
                        raise ValueError('outside supported DATETIME(6) range')
                except ValueError:raise AuditRejected('INVALID_AUDIT_UTC') from None
    trace=event.get('trace_id')
    if trace is not None and (not isinstance(trace,str) or not re.fullmatch('[0-9a-f]{32}',trace) or int(trace,16)==0):raise AuditRejected('INVALID_AUDIT_TRACE')
    changes=event['changes_json'];cp=props['changes_json']['properties']
    if not isinstance(changes,dict) or set(changes)-set(cp) or 'changed_fields' not in changes:raise AuditRejected('INVALID_CHANGE_METADATA')
    fields=changes['changed_fields']
    if not isinstance(fields,list) or len(fields)>64 or any(not isinstance(v,str) or not re.fullmatch('[a-z][a-z0-9_]{0,63}',v) for v in fields) or len(set(fields))!=len(fields):raise AuditRejected('INVALID_CHANGED_FIELDS')
    for key,v in changes.items():
        if key=='changed_fields':continue
        rule=cp[key]
        if not isinstance(v,str) or not 1<=len(v)<=rule.get('maxLength',64) or not re.fullmatch(rule['pattern'],v):raise AuditRejected('INVALID_CHANGE_REFERENCE')
    return event
