"""Restricted teacher-timeline compiler. Host constructs Scope after CURRENT authorization.
No network/DB execution, arbitrary SQL, tenant selection, or expression input.
"""
from dataclasses import dataclass
import re
from typing import Any

class Rejected(ValueError):
    """Safe user-visible category, never includes submitted values."""

@dataclass(frozen=True)
class Scope:
    tenant_id: str
    actor_key: str
    capabilities: frozenset[str]
    class_ids: tuple[str, ...]
    purpose_key: str

COLUMNS={'id':'id','person':'person_id','class':'class_id','occurred_at':'occurred_at','event_type':'event_type','summary':'redacted_summary'}
FILTERS={'person':'person_id','event_type':'event_type'}
# Fixed to the approved projection. Clients cannot select shadow/rebuild generations.
APPROVED_TEACHER_PROJECTION = 'timeline-v1'

def internal_id(v):
    if not isinstance(v,str) or not re.fullmatch(r'[1-9][0-9]{0,19}',v) or int(v)>18446744073709551615:
        raise Rejected('INVALID_INTERNAL_ID')
    return int(v)

def compile_query(request: dict[str,Any], scope: Scope, dialect='mysql'):
    if not isinstance(request,dict) or any(not isinstance(k,str) for k in request) or set(request)-{'fields','filters','limit','sort'}:
        raise Rejected('INVALID_REQUEST_KEYS')
    if not isinstance(dialect,str) or dialect not in ('mysql','sqlite'):raise Rejected('INVALID_DIALECT')
    if (not isinstance(scope,Scope) or not isinstance(scope.tenant_id,str) or not 1<=len(scope.tenant_id)<=100
            or not isinstance(scope.actor_key,str) or not 1<=len(scope.actor_key)<=100 or scope.purpose_key!='teacher.timeline'):
        raise Rejected('INVALID_TRUSTED_SCOPE')
    if not isinstance(scope.capabilities,frozenset) or 'timeline.read' not in scope.capabilities:raise Rejected('CAPABILITY_DENIED')
    if not isinstance(scope.class_ids,tuple) or not 1<=len(scope.class_ids)<=100:raise Rejected('SCOPE_DENIED')
    classes=tuple(dict.fromkeys(internal_id(x) for x in scope.class_ids))
    fields=request.get('fields',['id','occurred_at','summary'])
    if not isinstance(fields,list) or not 1<=len(fields)<=len(COLUMNS) or any(not isinstance(x,str) or x not in COLUMNS for x in fields):raise Rejected('INVALID_FIELDS')
    if len(set(fields))!=len(fields):raise Rejected('DUPLICATE_FIELD')
    limit=request.get('limit',100)
    if type(limit)is not int or not 1<=limit<=200:raise Rejected('INVALID_LIMIT')
    sort=request.get('sort','newest')
    if not isinstance(sort,str) or sort not in ('newest','oldest'):raise Rejected('INVALID_SORT')
    filters=request.get('filters',{})
    if not isinstance(filters,dict) or any(not isinstance(k,str) for k in filters) or set(filters)-set(FILTERS):raise Rejected('INVALID_FILTERS')
    marker='%s' if dialect=='mysql' else '?'
    predicates=[f'`tenant_id` = {marker}',f'`purpose_key` = {marker}',f'`projection_kind` = {marker}',f'`class_id` IN ({", ".join([marker]*len(classes))})','`is_visible` = 1']
    args=[scope.tenant_id,scope.purpose_key,APPROVED_TEACHER_PROJECTION,*classes]
    for key in sorted(filters):
        value=filters[key]
        if key=='person':value=internal_id(value)
        elif not isinstance(value,str) or not 1<=len(value)<=64:raise Rejected('INVALID_EVENT_TYPE')
        predicates.append(f'`{FILTERS[key]}` = {marker}');args.append(value)
    direction='DESC' if sort=='newest' else 'ASC'
    sql='SELECT '+', '.join('`'+COLUMNS[f]+'`' for f in fields)+' FROM `ed_reporting_timeline` WHERE '+' AND '.join(predicates)+f' ORDER BY `occurred_at` {direction}, `id` {direction} LIMIT {marker}'
    return sql,tuple([*args,limit])
