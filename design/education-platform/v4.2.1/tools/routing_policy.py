"""Deterministic server-side cohort helper. This is not a gateway or authorizer."""
import hashlib
import hmac
import json
import re

class RouteRejected(ValueError):pass

def bucket(secret:bytes,rollout_id:str,tenant_id:str,subject_id:str,unit:str):
    if not isinstance(secret,bytes) or len(secret)<32:raise RouteRejected('SECRET_TOO_SHORT')
    if unit not in ('tenant','actor','school'):raise RouteRejected('INVALID_UNIT')
    if any(not isinstance(s,str) or not 1<=len(s)<=100 for s in (rollout_id,tenant_id,subject_id)):raise RouteRejected('INVALID_ID')
    if unit=='school' and (not re.fullmatch(r'[1-9][0-9]{0,19}',subject_id) or int(subject_id)>18446744073709551615):raise RouteRejected('INVALID_SCHOOL_ID')
    # Keep 4.1 tenant/actor hashes unchanged; only the new school unit gets a new domain tag.
    source=['school-v1',rollout_id,tenant_id,subject_id] if unit=='school' else [rollout_id,tenant_id,subject_id if unit=='actor' else None]
    data=json.dumps(source,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    return int.from_bytes(hmac.new(secret,data,hashlib.sha256).digest()[:8],'big')%10000

def choose(secret,rollout_id,tenant_id,subject_id,unit,basis_points,*,eligible=True,emergency_stop=False):
    if type(basis_points)is not int or not 0<=basis_points<=10000 or type(eligible)is not bool or type(emergency_stop)is not bool:raise RouteRejected('INVALID_POLICY')
    if emergency_stop:return 'BLOCKED'
    rank=bucket(secret,rollout_id,tenant_id,subject_id,unit)
    return 'candidate' if eligible and rank<basis_points else 'stable'
