"""Real native TASK adapter acceptance, called only with genuine logged-in clients.
No SQL state writes, fabricated task tokens, scheduler stubs or direct routing setter.
"""
from datetime import datetime, timedelta, timezone
import time
from pathlib import Path
from ui_boundaries import is_rejected


def definitions():
    return {'format': 1, 'tasks': [dict(id=name, tenant=tenant, principal=principal,
            plugin='ci.python', capability='echo') for name, tenant, principal in [
                ('ci-native-task', '000000', 'ci-native-task-principal'),
                ('ci-other-task', '000000', 'ci-other-task-principal'),
                ('ci-foreign-task', '960001', 'ci-native-task-principal')]]}


def verify_native_tasks(fixture, admin, teacher, foreign, packages, plan):
    f = fixture
    f.report['taskAffinity'] = {'status': 'RUNNING', 'scope': 'Native operator-triggered fixed TASK definitions; no unattended scheduler'}
    f._write()
    base = '/fcp/release-tasks/'
    def grant(artifact, allow=True, tenant='000000', principal='ci-native-task-principal', lifetime=3600):
        target = dict(pluginId=artifact['component'], version=artifact['version'], digest=artifact['sha256'],
                      targetTenant=tenant, dataScope='tenant:'+tenant, principalKind='TASK', principalId=principal)
        options = admin.data('/fcp/plugin-releases/grant-options', target)
        option = next(o for o in options['options'] if o['capability'] == 'echo')
        expires = (datetime.now(timezone.utc)+timedelta(seconds=lifetime)).isoformat()
        return admin.data('/fcp/plugin-releases/grants', dict(**target, confirmedDigest=artifact['sha256'],
                    capability='echo', operation='invoke', allow=allow, expiresAt=expires, expectedRevision=option['revision']))
    def pin(definition, run, message):
        return admin.data(base+definition+'/'+run+'/pin', {'input': {'message': message}})
    def run(definition, run_id):
        return admin.data(base+definition+'/'+run_id+'/run', {})
    def denied(client, path, body, label):
        status, payload = client.request(path, body)
        f.check(label, is_rejected(status, payload))
    state = admin.data('/fcp/releases/state')
    admin.data('/fcp/releases/plans', dict(expectedRevision=state['revision'], operationId='native-task-plan', plan=plan))
    f.operation(admin, 'DEPLOY', 0, 'READY')
    stable, candidate = packages[('python', '1.0.0')], packages[('python', '1.1.0')]
    grant(stable)
    # A USER package grant cannot impersonate TASK; neither another task nor tenant inherits it.
    denied(admin, base+'ci-other-task/no-exact-grant/pin', {'input': {}}, 'other registered TASK principal cannot inherit exact grant')
    denied(admin, base+'ci-foreign-task/no-tenant-grant/pin', {'input': {}}, 'same TASK principal cannot inherit grant across tenant')
    denied(teacher, base+'ci-native-task/viewer-denied/pin', {'input': {}}, 'native viewer cannot trigger TASK')
    denied(foreign, base+'ci-native-task/tenant-denied/pin', {'input': {}}, 'non-platform native account cannot trigger platform TASK')
    denied(admin, base+'ci-native-task/forged-identity/pin', {'input': {}, 'principal': 'fcp-control-recovery'}, 'HTTP cannot supply TASK identity')
    denied(admin, base+'unregistered/arbitrary/pin', {'input': {}}, 'HTTP cannot register arbitrary TASK')
    grant(stable, principal='ci-other-task-principal')
    pin('ci-other-task', 'pinned-stable', 'native-other-task')
    other_result = run('ci-other-task', 'pinned-stable')['result']
    f.check('separate deployed principal is preserved through native process', other_result['principalId'] == 'ci-other-task-principal' and other_result['tenantId'] == '000000')
    f.grant(admin, stable, tenant='960001')
    grant(stable, tenant='960001')
    pin('ci-foreign-task', 'pinned-stable', 'native-foreign-task')
    foreign_result = run('ci-foreign-task', 'pinned-stable')['result']
    f.check('registered foreign TASK uses its real isolated tenant', foreign_result['tenantId'] == '960001' and foreign_result['principalKind'] == 'TASK' and foreign_result['principalId'] == 'ci-native-task-principal')
    pinned = pin('ci-native-task', 'pinned-stable', 'native-task-stable')
    f.check('native durable task pins actual stable digest', pinned['state'] == 'PINNED' and pinned['pinned']['digest'] == stable['sha256'])
    f.check('native task status excludes stored input', 'input' not in admin.data(base+'ci-native-task/pinned-stable'))
    denied(admin, base+'ci-native-task/pinned-stable/pin', {'input': {'message': 'changed'}}, 'native task run ID rejects changed input')
    f.operation(admin, 'CANARY', 2500, 'CANARY')
    f.operation(admin, 'PROMOTE', 10000, 'FULL')
    denied(admin, base+'ci-native-task/candidate-before-grant/pin', {'input': {}}, 'stable TASK grant does not authorize candidate digest')
    grant(candidate)
    current = pin('ci-native-task', 'pinned-candidate', 'native-task-candidate')
    f.check('new native TASK pins candidate independently', current['pinned']['digest'] == candidate['sha256'])
    grant(stable, False)
    denied(admin, base+'ci-native-task/pinned-stable/run', {}, 'revoked stable TASK grant blocks already pinned work')
    f.check('pre-execution revocation preserves PINNED state', admin.data(base+'ci-native-task/pinned-stable')['state'] == 'PINNED')
    grant(stable, lifetime=2)
    time.sleep(3)
    denied(admin, base+'ci-native-task/pinned-stable/run', {}, 'expired exact TASK approval blocks pinned execution')
    grant(stable)
    f.grant(admin, stable, False)
    denied(admin, base+'ci-native-task/pinned-stable/run', {}, 'platform package revocation overrides valid exact TASK grant')
    f.grant(admin, stable)
    old = run('ci-native-task', 'pinned-stable')
    result = old['result']
    f.check('TASK executes real old process after full rollout', old['state'] == 'COMPLETE' and result['packageVersion'] == '1.0.0' and
            result['principalKind'] == 'TASK' and result['principalId'] == 'ci-native-task-principal' and result['tenantId'] == '000000')
    f.assert_child(result['pluginPid'], f.pid, 'native stable TASK process')
    f.check('completed TASK replay returns exact durable result', run('ci-native-task', 'pinned-stable') == old)
    for reason in ('revoked', 'expired'):
        grant(stable, reason != 'revoked', lifetime=2 if reason == 'expired' else 3600)
        if reason == 'expired':
            time.sleep(3)
        metadata = admin.data(base+'ci-native-task/pinned-stable')
        replay = pin('ci-native-task', 'pinned-stable', 'native-task-stable')
        f.check(reason+' completed TASK status/pin never disclose business result',
                metadata == replay and set(metadata) == {'definition', 'runId', 'taskId', 'state', 'pinned'} and
                'result' not in metadata['pinned'] and 'input' not in metadata['pinned'])
        denied(admin, base+'ci-native-task/pinned-stable/run', {}, reason+' approval blocks completed TASK business result')
    grant(stable)
    f.operation(admin, 'ROLLBACK', 0, 'ROLLED_BACK')
    state = admin.data('/fcp/releases/state')
    command = dict(expectedRevision=state['revision'], operationId='native-task-retire', action='RETIRE', basisPoints=0)
    waiting = admin.data('/fcp/releases/operations', command)
    f.check('real RETIRE remains pending while candidate TASK is pinned', 'pending' in waiting and waiting['phase'] != 'RETIRED')
    status = admin.data('/fcp/plugin-releases/'+plan['candidateRelease']+'/status')
    f.check('RETIRE closes admissions but preserves task drain', status['draining'] and not status['admission'] and status['pendingTasks'] == 1)
    candidate_pids = [p['pid'] for p in status['processes'].values()]
    # Independent lifecycle stop must not erase pending task state.
    denied(admin, '/fcp/plugin-releases/'+plan['candidateRelease']+'/stop', {}, 'pending TASK prevents direct runtime stop')
    new = run('ci-native-task', 'pinned-candidate')['result']
    f.check('draining TASK retains candidate identity after rollback', new['packageVersion'] == '1.1.0' and
            new['principalKind'] == 'TASK' and new['principalId'] == 'ci-native-task-principal' and new['pluginPid'] != result['pluginPid'])
    finished = admin.data('/fcp/releases/reconcile', {})
    f.check('RETIRE completes only after actual pinned TASK finishes', finished['phase'] == 'RETIRED' and 'pending' not in finished)
    retired = admin.data('/fcp/plugin-releases/'+plan['candidateRelease']+'/status')
    f.check('retired task version has zero pending tasks and stopped processes', retired['pendingTasks'] == 0 and retired['activationState'] == 'STOPPED')
    f.check('task drain retirement really exits candidate OS children', all(not Path('/proc', str(pid)).exists() for pid in candidate_pids))
    f.report['operations'].append(dict(action='RETIRE', phase=finished['phase'], generation=finished['generation'], revision=finished['revision'], observation=finished['observed']))
    f.report['taskAffinity'] = dict(status='PASS', scope='Native operator-triggered TASK, real JDBC/digest approvals/Python processes, durable pin, live revocation, tenant/principal isolation, pending drain and retirement; unattended scheduling and JVM crash recovery not covered')
    f._write()
