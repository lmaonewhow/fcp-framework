"""Native acceptance login completion; no credential generation or response substitution.

Storage remains in memory. A context snapshot replaces evaluation in a document
which can be destroyed by the real admin-to-teacher post-login redirect.
"""
from __future__ import annotations
import json
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
    return _validate_native_captcha(captcha)


def _validate_native_captcha(captcha):
    if not isinstance(captcha, dict):
        raise RuntimeError('Native CAPTCHA endpoint omitted the challenge')
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



def _failure_kind(error):
    """Fixed categories only. Never return exception messages, URLs or class names."""
    if isinstance(error, UnicodeDecodeError):
        return 'invalid-utf8'
    if isinstance(error, json.JSONDecodeError):
        return 'json-syntax'
    # Known Chromium protocol failure is diagnostic only, NEVER retry permission.
    if any(text in str(error) for text in ('No resource with given identifier found', 'No data found for resource with given identifier')):
        return 'resource-unavailable'
    if 'Target page, context or browser has been closed' in str(error):
        return 'target-closed'
    if isinstance(error, TimeoutError) or type(error).__name__ == 'TimeoutError':
        return 'timeout'
    if type(error).__module__.startswith('playwright.'):
        return 'playwright-error'
    return 'other-error'


def _response_metadata(response):
    """Cached response headers only, with a closed, credential-free schema."""
    headers = response.headers
    content_type = headers.get('content-type', '').split(';', 1)[0].strip().lower()
    category = ('missing' if not content_type else 'json' if content_type == 'application/json' or content_type.endswith('+json')
                else 'html' if content_type == 'text/html' else 'text' if content_type.startswith('text/') else 'other')
    trace = headers.get('x-trace-id', '')
    trace = trace.lower() if isinstance(trace, str) and re.fullmatch(r'[0-9a-fA-F]{32}', trace) and trace != '0'*32 else None
    return dict(contentType=category, traceId=trace, bodyState='not-read', bodyBytes=None)


class NativeLoginPreparationError(RuntimeError):
    """Safe, bounded diagnostics only; never retain server messages or challenge data."""
    def __init__(self, endpoint, stage, status=None, business_code=None, limited=False, *, metadata=None):
        self.diagnostic = dict(endpoint=endpoint, stage=stage, status=status,
                               businessCode=business_code)
        if metadata is not None:
            self.diagnostic.update(metadata)
        self.limited = limited
        super().__init__('Native login preparation failed: '+endpoint+' '+stage)


def _login_envelope(response, endpoint, *, diagnostic=None):
    status = response.status if type(response.status) is int and 100 <= response.status <= 599 else None
    metadata = _response_metadata(response)
    if diagnostic is not None:
        diagnostic.update(endpoint=endpoint, status=status, businessCode=None, **metadata)
    def failed(stage, code=None, limited=False):
        return NativeLoginPreparationError(endpoint, stage, status, code, limited, metadata=metadata)
    if status == 429:
        raise failed('rate-limited', limited=True)
    # Separate transport/body availability from JSON parsing. response.json() hides
    # this distinction, and rereading a body after navigation can lose the resource.
    try:
        body = response.body()
    except Exception as error:
        metadata.update(bodyState='unavailable', failureKind=_failure_kind(error))
        raise failed('body-read-failed') from None
    metadata.update(bodyState='read', bodyBytes=len(body))
    try:
        payload = json.loads(body.decode('utf-8'))
    except Exception as error:
        metadata['failureKind'] = _failure_kind(error)
        raise failed('invalid-json') from None
    if not isinstance(payload, dict):
        raise failed('invalid-envelope')
    raw_code = payload.get('code')
    code = str(raw_code) if type(raw_code) in (int, str) and re.fullmatch(r'[0-9]{3}', str(raw_code)) else None
    limited = status in (200, 500) and code in ('429', '500') and isinstance(payload.get('msg'), str) and payload['msg'] in _RATE_LIMIT_MESSAGES
    if limited or status != 200 or code != '200':
        raise failed('rate-limited' if limited else 'rejected', code, limited)
    if diagnostic is not None:
        diagnostic.update(businessCode=code, **metadata)
    return payload.get('data')


def parse_native_tenant_list(response, *, diagnostic=None):
    """Require the genuine seeded T2 before operating the native selector."""
    endpoint = '/auth/tenant/list'
    diagnostic = diagnostic if diagnostic is not None else {}
    data = _login_envelope(response, endpoint, diagnostic=diagnostic)
    metadata = {k: diagnostic[k] for k in ('contentType', 'traceId', 'bodyState', 'bodyBytes')}
    if not isinstance(data, dict) or data.get('tenantEnabled') is not True:
        raise NativeLoginPreparationError(endpoint, 'tenant-disabled-or-invalid', 200, '200', metadata=metadata)
    tenants = data.get('voList')
    if not isinstance(tenants, list) or not any(isinstance(t, dict) and
            t.get('tenantId') == '960001' and t.get('companyName') == 'Synthetic CI tenant' for t in tenants):
        raise NativeLoginPreparationError(endpoint, 'target-tenant-missing', 200, '200', metadata=metadata)


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
        except Exception as cause:
            missing = next((e for e in endpoints if e not in responses), '/auth/tenant/list')
            error = NativeLoginPreparationError(missing, 'response-not-observed',
                                                metadata=dict(failureKind=_failure_kind(cause)))
            if events is not None:
                for endpoint, response in responses.items():
                    events.append(dict(endpoint=endpoint, stage='response-observed', status=response.status,
                                       attempt=attempt+1, **_response_metadata(response)))
                events.append(dict(error.diagnostic, attempt=attempt+1))
            raise error from None
        finally:
            page.remove_listener('request', requested)
            page.remove_listener('response', observed)
        failures = []
        captcha = None
        for endpoint in endpoints:
            diagnostic = {}
            try:
                if endpoint == '/auth/tenant/list':
                    parse_native_tenant_list(responses[endpoint], diagnostic=diagnostic)
                else:
                    data = _login_envelope(responses[endpoint], endpoint, diagnostic=diagnostic)
                    try:
                        captcha = _validate_native_captcha(data)
                    except Exception:
                        metadata = {k: diagnostic[k] for k in ('contentType', 'traceId', 'bodyState', 'bodyBytes')}
                        raise NativeLoginPreparationError(endpoint, 'invalid-challenge', 200, '200', metadata=metadata) from None
                if events is not None:
                    events.append(dict(diagnostic, stage='validated', attempt=attempt+1))
            except NativeLoginPreparationError as error:
                failures.append(error)
                if events is not None:
                    events.append(dict(error.diagnostic, attempt=attempt+1))
        if not failures:
            return captcha
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

def wait_native_logout(page, response, origin, *, events=None, timeout_ms=30000):
    """Wait for the locked upstream's logout cleanup, without another navigation.

    HTTP headers precede fetch/axios body completion and native token/router cleanup.
    This establishes a UI completion condition, not proof of server invalidation.
    """
    require_loopback(origin)
    if response.url.split('?', 1)[0] != origin+'/auth/logout':
        raise NativeLoginPreparationError('/auth/logout', 'logout-origin-mismatch')
    metadata = _response_metadata(response)
    endpoint = '/auth/logout'
    try:
        login = re.compile(re.escape(origin)+r'/(?:admin|teacher)/login(?:/[^?#]*)?(?:[?#].*)?$')
        page.wait_for_url(login, wait_until='domcontentloaded', timeout=timeout_ms)
        state = page.context.storage_state()
        if any(entry.get('name') == 'FCP-Host-Token' and entry.get('value')
               for item in state.get('origins', []) if item.get('origin') == origin
               for entry in item.get('localStorage', [])):
            raise NativeLoginPreparationError(endpoint, 'logout-token-retained', response.status, metadata=metadata)
    except NativeLoginPreparationError as error:
        if events is not None:
            events.append(error.diagnostic)
        raise
    except Exception as cause:
        metadata['failureKind'] = _failure_kind(cause)
        error = NativeLoginPreparationError(endpoint, 'logout-ui-incomplete', response.status, metadata=metadata)
        if events is not None:
            events.append(error.diagnostic)
        raise error from None
    if events is not None:
        events.append(dict(endpoint=endpoint, stage='logout-ui-complete', status=response.status, **metadata))


def native_token(state, base):
    require_loopback(base)
    origin = base.rstrip('/')
    tokens = [entry.get('value') for item in state.get('origins', [])
              if item.get('origin') == origin for entry in item.get('localStorage', [])
              if entry.get('name') == 'FCP-Host-Token']
    if len(tokens) != 1 or not isinstance(tokens[0], str) or not tokens[0]:
        raise RuntimeError('Expected exactly one genuine native token in browser-context state')
    return tokens[0]


def authenticated_storage_state(page, context, base, workspace, timeout_ms=30000, *, before_workspace_navigation=None):
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
    # Observe one-shot native login notifications before the explicit reload below.
    # The observer receives verified server session metadata, never login credentials.
    if before_workspace_navigation is not None:
        before_workspace_navigation(page, session)
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
