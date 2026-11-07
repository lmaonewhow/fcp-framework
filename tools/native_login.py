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
