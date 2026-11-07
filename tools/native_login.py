"""Native acceptance login completion; no credential generation or response substitution.

Storage remains in memory. A context snapshot replaces evaluation in a document
which can be destroyed by the real admin-to-teacher post-login redirect.
"""
from __future__ import annotations
import re
import time
from native_client import require_loopback, NativeClient


CAPTCHA_RATE_WINDOW_SECONDS = 60.0
_RATE_LIMIT_MESSAGES = frozenset({
    '访问过于频繁，请稍候再试',
    'Visit too frequently, please try again later',
})


class NativeCaptchaRateLimited(RuntimeError):
    """An explicit native throttle response, never a generic missing captcha."""


def parse_native_captcha(response):
    """Validate the real /auth/code response without logging its challenge or image."""
    if response.status == 429:
        raise NativeCaptchaRateLimited('Native CAPTCHA endpoint returned HTTP 429')
    try:
        payload = response.json()
    except Exception:
        raise RuntimeError('Native CAPTCHA endpoint did not return a JSON envelope') from None
    if not isinstance(payload, dict):
        raise RuntimeError('Native CAPTCHA endpoint returned a non-object envelope')
    code = str(payload.get('code'))
    # Pinned RuoYi's ServiceException handler uses R.fail(message), commonly HTTP 200
    # with code=500 and data=null. Only the exact pinned translations identify throttle.
    message = payload.get('msg')
    if response.status in (200, 500) and code in ('429', '500') and isinstance(message, str) and message in _RATE_LIMIT_MESSAGES:
        raise NativeCaptchaRateLimited('Native CAPTCHA IP rate limit reached')
    captcha = payload.get('data')
    if response.status != 200 or code != '200' or not isinstance(captcha, dict):
        raise RuntimeError('Native CAPTCHA endpoint rejected or omitted the challenge')
    if captcha.get('captchaEnabled', True) is not True:
        raise AssertionError('Genuine native CAPTCHA must remain enabled')
    if not isinstance(captcha.get('uuid'), str) or not re.fullmatch(r'[a-f0-9]{32}', captcha['uuid']):
        raise RuntimeError('Native CAPTCHA response has no valid challenge identifier')
    return captcha


def open_native_login(page, base, workspace, *, wait_events=None, clock=None, sleep=None):
    """Load the genuine form, respecting the unchanged 10/IP/60s CAPTCHA limit.

    Only an explicit throttle response permits one new challenge request after a full
    60-second window. No password submission, token, session, IP, or Redis state is
    retried/modified here. Other errors fail immediately. There is no global session
    cache or host throttle state, so every login performs its own observed requests.
    """
    require_loopback(base)
    if workspace not in ('ingestion', 'plugins'):
        raise ValueError('Only explicit native acceptance workspaces are allowed')
    clock = clock or time.monotonic
    sleep = sleep or time.sleep
    origin = base.rstrip('/')
    for attempt in range(2):
        with page.expect_response(lambda r: r.url.split('?', 1)[0] == origin+'/auth/code', timeout=30000) as challenge:
            page.goto(origin+'/admin/login?redirect=/fcp/'+workspace, wait_until='domcontentloaded')
        try:
            return parse_native_captcha(challenge.value)
        except NativeCaptchaRateLimited:
            if attempt:
                raise NativeCaptchaRateLimited('Native CAPTCHA still limited after one complete 60-second window') from None
            # This delay comes from the exact pinned server policy, not an arbitrary
            # UI stabilization sleep. No retry occurs before the full window elapses.
            print('Native CAPTCHA rate limit: waiting the unchanged 60-second window before one new challenge request', flush=True)
            started = clock()
            sleep(CAPTCHA_RATE_WINDOW_SECONDS)
            waited = clock()-started
            if waited < CAPTCHA_RATE_WINDOW_SECONDS:
                raise RuntimeError('Native CAPTCHA rate-limit window did not fully elapse')
            if wait_events is not None:
                wait_events.append(dict(endpoint='/auth/code', nativeWindowSeconds=60,
                                        waitedSeconds=round(waited, 3), nextChallengeAttempt=2))
    raise AssertionError('Unreachable native CAPTCHA control flow')



class NativeLoginPreparationError(RuntimeError):
    """Safe, bounded diagnostics only; never retain server messages or challenge data."""
    def __init__(self, endpoint, stage, status=None, business_code=None, limited=False):
        self.diagnostic = dict(endpoint=endpoint, stage=stage, status=status,
                               businessCode=business_code)
        self.limited = limited
        super().__init__('Native login preparation failed: '+endpoint+' '+stage)


def _login_envelope(response, endpoint):
    status = response.status if type(response.status) is int else None
    if status == 429:
        raise NativeLoginPreparationError(endpoint, 'rate-limited', status, limited=True)
    try:
        payload = response.json()
    except Exception:
        raise NativeLoginPreparationError(endpoint, 'invalid-json', status) from None
    if not isinstance(payload, dict):
        raise NativeLoginPreparationError(endpoint, 'invalid-envelope', status)
    raw_code = payload.get('code')
    code = str(raw_code) if type(raw_code) in (int, str) and re.fullmatch(r'[0-9]{3}', str(raw_code)) else None
    limited = status == 429 or (status in (200, 500) and code in ('429', '500')
                                and isinstance(payload.get('msg'), str)
                                and payload['msg'] in _RATE_LIMIT_MESSAGES)
    if limited or status != 200 or code != '200':
        raise NativeLoginPreparationError(endpoint, 'rate-limited' if limited else 'rejected', status, code, limited)
    return payload.get('data')


def parse_native_tenant_list(response):
    """Require the genuine seeded T2 before operating the native selector."""
    endpoint = '/auth/tenant/list'
    data = _login_envelope(response, endpoint)
    if not isinstance(data, dict) or data.get('tenantEnabled') is not True:
        raise NativeLoginPreparationError(endpoint, 'tenant-disabled-or-invalid', 200, '200')
    tenants = data.get('voList')
    if not isinstance(tenants, list) or not any(isinstance(t, dict) and
            t.get('tenantId') == '960001' and t.get('companyName') == 'Synthetic CI tenant' for t in tenants):
        raise NativeLoginPreparationError(endpoint, 'target-tenant-missing', 200, '200')


def open_native_tenant_login(page, base, *, events=None, clock=None, sleep=None):
    """Observe both real responses from each navigation, retry only explicit throttle.

    A single full-window reload refreshes BOTH responses. Never retry missing options,
    missing responses, authorization failures or invalid data. No state/limit changes.
    """
    require_loopback(base)
    origin = base.rstrip('/')
    clock, sleep = clock or time.monotonic, sleep or time.sleep
    endpoints = ('/auth/code', '/auth/tenant/list')
    for attempt in range(2):
        requests, responses = [], {}
        def requested(request):
            if request.method == 'GET' and request.url.split('?', 1)[0] in [origin+e for e in endpoints]:
                requests.append(request)
        def matches(response, endpoint):
            return response.request in requests and response.url.split('?', 1)[0] == origin+endpoint
        def observed(response):
            for endpoint in endpoints:
                if matches(response, endpoint):
                    responses[endpoint] = response
        page.on('request', requested)
        page.on('response', observed)
        try:
            with page.expect_response(lambda r: matches(r, endpoints[0]), timeout=30000) as challenge, \
                 page.expect_response(lambda r: matches(r, endpoints[1]), timeout=30000) as tenants:
                page.goto(origin+'/admin/login?redirect=/fcp/plugins', wait_until='domcontentloaded')
            responses = {endpoints[0]: challenge.value, endpoints[1]: tenants.value}
        except Exception:
            missing = next((e for e in endpoints if e not in responses), '/auth/tenant/list')
            raise NativeLoginPreparationError(missing, 'response-not-observed') from None
        finally:
            page.remove_listener('request', requested)
            page.remove_listener('response', observed)
        failures = []
        captcha = None
        for endpoint in endpoints:
            try:
                if endpoint == '/auth/tenant/list':
                    parse_native_tenant_list(responses[endpoint])
                else:
                    _login_envelope(responses[endpoint], endpoint)
                    try:
                        captcha = parse_native_captcha(responses[endpoint])
                    except Exception:
                        raise NativeLoginPreparationError(endpoint, 'invalid-challenge', 200, '200') from None
            except NativeLoginPreparationError as error:
                failures.append(error)
        if not failures:
            return captcha
        if events is not None:
            events.extend(dict(error.diagnostic, attempt=attempt+1) for error in failures)
        # A concurrent non-throttle failure must never be hidden by another throttle.
        fatal = next((error for error in failures if not error.limited), None)
        if fatal is not None or attempt:
            raise fatal or failures[0]
        started = clock()
        sleep(CAPTCHA_RATE_WINDOW_SECONDS)
        waited = clock()-started
        if waited < CAPTCHA_RATE_WINDOW_SECONDS:
            raise NativeLoginPreparationError(failures[0].diagnostic['endpoint'], 'incomplete-rate-window')
        if events is not None:
            events.append(dict(endpoint=failures[0].diagnostic['endpoint'], stage='rate-window-wait',
                               waitedSeconds=round(waited, 3), nativeWindowSeconds=60, nextAttempt=2))
    raise AssertionError('Unreachable native tenant login control flow')

def native_token(state, base):
    require_loopback(base)
    origin = base.rstrip('/')
    tokens = [entry.get('value') for item in state.get('origins', [])
              if item.get('origin') == origin for entry in item.get('localStorage', [])
              if entry.get('name') == 'FCP-Host-Token']
    if len(tokens) != 1 or not isinstance(tokens[0], str) or not tokens[0]:
        raise RuntimeError('Expected exactly one genuine native token in browser-context state')
    return tokens[0]


def authenticated_storage_state(page, context, base, workspace, timeout_ms=30000):
    """Respect native login's home, then navigate through the real FCP route guard.

    Native login may choose /admin/index instead of preserving a redirect query.
    No page.evaluate, token injection, arbitrary sleep, retry or cookie/token log.
    The caller still verifies the exact expected authenticated user and tenant.
    """
    require_loopback(base)
    markers = {'ingestion':'fcp-ingestion', 'plugins':'fcp-plugin-workspace'}
    if workspace not in markers:
        raise ValueError('Only explicit native acceptance workspaces are allowed')
    origin = base.rstrip('/')
    home = re.compile(re.escape(origin)+r'/(?:admin|teacher)/(?!login(?:[/;?#]|$))[^?#]+(?:[?#].*)?$')
    page.wait_for_url(home, wait_until='domcontentloaded', timeout=timeout_ms)
    initial_token = native_token(context.storage_state(), base)
    client = NativeClient(base, initial_token, 'e5cd7e4891bf95d1d19206ce24a7b32e')
    session = client.data('/fcp/ui/session')
    shell = session.get('defaultShell')
    if shell not in ('admin', 'teacher') or shell not in session.get('allowedShells', []):
        raise AssertionError('Native session did not authorize a known default shell')
    target = origin+'/'+shell+'/fcp/'+workspace
    page.goto(target, wait_until='domcontentloaded', timeout=timeout_ms)
    destination = re.compile(re.escape(target)+r'/?(?:[?#].*)?$')
    page.wait_for_url(destination, wait_until='domcontentloaded', timeout=timeout_ms)
    page.get_by_test_id(markers[workspace]).wait_for(state='visible',timeout=timeout_ms)
    state = context.storage_state()
    token = native_token(state, base)
    if token != initial_token:
        raise AssertionError('Native authenticated session changed during workspace navigation')
    return state, token


def require_encrypted_login(response):
    """Only navigation-safe metadata; Chromium may discard the auth response body."""
    if response.status != 200 or not response.request.headers.get('encrypt-key'):
        raise AssertionError('Expected HTTP 200 from genuine encrypted native login')


def verify_native_identity(client, expected_user, expected_tenant):
    """A fresh authenticated server request proves success, not a discarded auth body."""
    session = client.data('/fcp/ui/session')
    if str(session.get('subject')) != str(expected_user) or session.get('scope') != 'tenant-'+expected_tenant:
        raise AssertionError('Authenticated native identity differs from requested synthetic user/tenant')
    return session
