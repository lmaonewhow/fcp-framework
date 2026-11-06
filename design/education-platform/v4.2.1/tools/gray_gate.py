"""Pure fail-closed canary decision function. It does not query metrics or route traffic.
Input counters must be supplied by trusted, independently reconciled collectors.
"""
from datetime import datetime, timezone
import math
import re
from common import canonical_hash

DEFAULT_POLICY={
 'minimum_requests_per_group':1000,'minimum_operations_per_group':500,
 'minimum_window_seconds':900,'maximum_sample_age_seconds':90,
 'maximum_error_rate':0.005,'maximum_error_rate_increase':0.002,
 'maximum_p95_ms':500,'maximum_p95_ratio':1.20,'minimum_coverage':0.99,
 'minimum_observation_seconds':1800,
 'maximum_denial_rate':0.02,'maximum_denial_rate_increase':0.01,
}

def _time(x):
    if not isinstance(x,str):raise ValueError('timestamp')
    d=datetime.fromisoformat(x.replace('Z','+00:00'))
    if d.tzinfo is None:raise ValueError('timezone')
    return d

def _number(x):
    # Bounded JSON counters/policy values avoid integer-to-float overflow.
    return type(x) in (int,float) and -9007199254740991<=x<=9007199254740991 and (type(x)is int or math.isfinite(x))

def decide(data, policy=None, now=None):
    policy=DEFAULT_POLICY.copy() if policy is None else policy
    reasons=[]
    def result(outcome):
        return {'outcome':outcome,'reason_codes':reasons,'policy_hash':canonical_hash(policy) if isinstance(policy,dict) and all(_number(v) for v in policy.values()) else None,'trust_boundary':'trusted independent inputs and reviewed policy required'}
    # Trusted confirmed harm has higher priority than configuration or metric errors.
    # PAUSE only prevents expansion; ABORT requests stopping the affected candidate.
    if isinstance(data,dict) and data.get('confirmed_invariant_breach') is True:
        reasons.append('CONFIRMED_BUSINESS_OR_SECURITY_INVARIANT_BREACH');return result('ABORT')
    if not isinstance(data,dict) or not isinstance(policy,dict) or set(policy)!=set(DEFAULT_POLICY) or any(not _number(v) or v<0 or (v==0 and k not in ('maximum_denial_rate','maximum_denial_rate_increase')) for k,v in policy.items()):
        reasons.append('INVALID_POLICY_OR_INPUT');return result('PAUSE')
    for k in ('minimum_requests_per_group','minimum_operations_per_group','minimum_window_seconds','maximum_sample_age_seconds','minimum_observation_seconds'):
        if type(policy[k])is not int:reasons.append('INVALID_POLICY');return result('PAUSE')
    if not 0<policy['minimum_coverage']<=1 or not 0<policy['maximum_error_rate']<1 or not 0<policy['maximum_error_rate_increase']<1 or policy['maximum_p95_ratio']<1 or not 0<=policy['maximum_denial_rate']<1 or not 0<=policy['maximum_denial_rate_increase']<1:
        reasons.append('INVALID_POLICY');return result('PAUSE')
    if data.get('confirmed_invariant_breach') is not False:reasons.append('MISSING_INVARIANT_CHECK')
    if data.get('aa_verified') is not True:reasons.append('AA_NOT_VERIFIED')
    if data.get('real_user_path_verified') is not True:reasons.append('USER_PATH_NOT_VERIFIED')
    if type(data.get('observed_seconds'))is not int or data['observed_seconds']<policy['minimum_observation_seconds']:reasons.append('OBSERVATION_TOO_SHORT')
    groups={}
    for name in ('stable','candidate'):
        g=data.get(name)
        if not isinstance(g,dict):reasons.append(name+':MISSING');continue
        try:
            nowdt=_time(now) if isinstance(now,str) else (now or datetime.now(timezone.utc))
            if nowdt.tzinfo is None:raise ValueError('now timezone')
            start,end=_time(g['window_start']),_time(g['window_end']);observed=_time(g['observed_at'])
            if not start<end<=observed<=nowdt:raise ValueError('order')
            if (end-start).total_seconds()<policy['minimum_window_seconds']:reasons.append(name+':SHORT_WINDOW')
            if (nowdt-observed).total_seconds()>policy['maximum_sample_age_seconds'] or (nowdt-end).total_seconds()>policy['maximum_sample_age_seconds']:reasons.append(name+':STALE')
            counts=('edge_requests','executed_requests','failed_requests','assigned_operations','committed_operations','failed_operations','denied_operations','pending_operations','telemetry_operations')
            if any(type(g.get(k))is not int or not 0<=g[k]<=9007199254740991 for k in counts):raise ValueError('count types')
            if g['edge_requests']<policy['minimum_requests_per_group'] or g['assigned_operations']<policy['minimum_operations_per_group']:reasons.append(name+':LOW_SAMPLE')
            if g['executed_requests']!=g['edge_requests']:reasons.append(name+':REQUEST_COUNT_MISMATCH')
            if g['failed_requests']>g['edge_requests']:raise ValueError('fail count')
            if sum(g[k] for k in ('committed_operations','failed_operations','denied_operations','pending_operations'))!=g['assigned_operations']:reasons.append(name+':OPERATION_RECONCILIATION_MISMATCH')
            if g['pending_operations']>0:reasons.append(name+':UNRESOLVED_OPERATIONS')
            if not g['assigned_operations'] or not policy['minimum_coverage']<=g['telemetry_operations']/g['assigned_operations']<=1:reasons.append(name+':INSUFFICIENT_OR_DUPLICATE_TELEMETRY')
            p95=g.get('p95_ms')
            if not _number(p95) or p95<=0:raise ValueError('percentile')
            if g.get('p95_source')!='trusted_histogram':reasons.append(name+':INVALID_PERCENTILE_SOURCE')
            expected=data.get('expected_'+name+'_manifest');actual=g.get('actual_manifest_digest')
            if not isinstance(expected,str) or not re.fullmatch('[0-9a-f]{64}',expected) or actual!=expected:reasons.append(name+':VERSION_MISMATCH')
            if any(not isinstance(g.get(k),str) or not g[k] for k in ('metric_schema','histogram_schema','stratum')):reasons.append(name+':MISSING_METRIC_SCHEMA')
            groups[name]=g
        except (ValueError,TypeError,KeyError,AttributeError,OverflowError):reasons.append(name+':INVALID_DATA')
    if len(groups)==2:
        a,b=groups['stable'],groups['candidate']
        if any(a.get(k)!=b.get(k) for k in ('window_start','window_end','metric_schema','histogram_schema','stratum')):reasons.append('INCOMPARABLE_GROUPS')
    if reasons:return result('PAUSE')
    a,b=groups['stable'],groups['candidate'];sa=a['failed_requests']/a['edge_requests'];ca=b['failed_requests']/b['edge_requests']
    sd=a['denied_operations']/a['assigned_operations'];cd=b['denied_operations']/b['assigned_operations']
    # Rates apply only within the same reviewed business stratum. Expected-denial security tests
    # require their own approved policy; never relabel DENIED as COMMITTED to pass this gate.
    if ca>policy['maximum_error_rate'] or ca-sa>policy['maximum_error_rate_increase']:reasons.append('ERROR_REGRESSION')
    if b['p95_ms']>policy['maximum_p95_ms'] or b['p95_ms']/a['p95_ms']>policy['maximum_p95_ratio']:reasons.append('LATENCY_REGRESSION')
    if cd>policy['maximum_denial_rate'] or cd-sd>policy['maximum_denial_rate_increase']:reasons.append('DENIAL_REGRESSION')
    if b['failed_operations']>0:reasons.append('FAILED_BUSINESS_OPERATIONS')
    if sa>policy['maximum_error_rate'] or a['p95_ms']>policy['maximum_p95_ms'] or a['failed_operations']>0 or sd>policy['maximum_denial_rate']:
        reasons.append('BASELINE_UNHEALTHY');return result('PAUSE')
    return result('ABORT' if reasons else 'CONTINUE')
