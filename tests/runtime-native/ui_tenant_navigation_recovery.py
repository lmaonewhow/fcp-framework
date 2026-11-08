"""CI-only, one bounded read-only navigation retry for proved native tenant limits.

No server/config/session changes. Unknown errors remain failures. The caller must
close its disposable context before this coordinator receives the exception.
"""
import re
import time

WINDOW_SECONDS = 60.0  # pinned AuthController: tenantList, 20/IP/60 seconds


class NativeTenantNavigationLimited(RuntimeError):
    def __init__(self, navigation):
        super().__init__('Proved native tenant-list throttle during CI navigation')
        self.navigation = navigation


def is_proved_tenant_limit(navigation):
    if (navigation.get('shell') != 'admin' or navigation.get('phase') != 'navigate' or
            navigation.get('pageErrorCount') != 1 or navigation.get('droppedEvents') != 0 or
            navigation.get('inflightApiCount') != 0 or navigation.get('requestTrackingOverflow') is not False or
            navigation.get('settleStatus') != 'COMPLETE'):
        return False
    events = navigation.get('events', [])
    errors = [event for event in events if event.get('kind') == 'pageerror']
    if len(errors) != 1 or errors[0].get('category') != 'native-rate-limit':
        return False
    trace = errors[0].get('traceId', '')
    if not isinstance(trace, str) or not re.fullmatch(r'[a-f0-9]{32}', trace) or trace == '0'*32:
        return False
    proved = []
    for event in events:
        if event.get('kind') == 'pageerror':
            continue
        if event.get('kind') != 'response':
            return False
        if event.get('bodyRead') in ('pending', 'unavailable', 'non-object'):
            return False
        status, code = event.get('status'), event.get('businessCode')
        if type(status) is not int:
            return False
        if event.get('endpoint') == '/auth/tenant/list' and event.get('traceId') == trace:
            if status == 429 or (status in (200, 500) and code in (429, 500) and
                                 event.get('category') == 'native-rate-limit'):
                proved.append(event)
                continue
        if status >= 400 or (event.get('bodyRead') == 'json' and code != 200):
            return False
    return len(proved) == 1


def run_with_tenant_limit_recovery(attempt, fixture, *, clock=time.monotonic, sleep=time.sleep):
    recovery = None
    for number in range(2):
        try:
            result = attempt(number)
            if recovery is not None:
                recovery['status'] = 'PASS_AFTER_WINDOW'
                fixture._write()
            return result
        except NativeTenantNavigationLimited as error:
            # Defensive double-check: no other producer can widen the retry rule.
            if number or not is_proved_tenant_limit(error.navigation):
                if recovery is not None:
                    recovery['status'] = 'FAIL_RETRY'
                    fixture._write()
                raise
            event = dict(shell='admin', stage=error.navigation['stage'],
                         firstAttempt='FAIL_NATIVE_TENANT_RATE_LIMIT', nextAttempt=2,
                         traceId=next(e['traceId'] for e in error.navigation['events'] if e['kind']=='pageerror'),
                         nativeWindowSeconds=60, status='WAITING')
            recovery = event
            fixture.report.setdefault('uiNavigationRecovery', []).append(event)
            fixture._write()
            started = clock()
            sleep(WINDOW_SECONDS)
            waited = clock()-started
            event['waitedSeconds'] = round(waited, 3)
            if waited < WINDOW_SECONDS:
                event['status'] = 'FAIL_INCOMPLETE_WINDOW'
                fixture._write()
                raise RuntimeError('Native tenant-list window did not fully elapse')
            event['status'] = 'RETRY_STARTED'
            fixture._write()
        except Exception:
            if recovery is not None:
                recovery['status'] = 'FAIL_RETRY'
                fixture._write()
            raise
    raise AssertionError('Unreachable native navigation retry flow')
