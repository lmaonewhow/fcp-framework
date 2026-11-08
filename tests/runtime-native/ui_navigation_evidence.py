"""Bounded, allowlisted evidence for real release-shell navigations, not retries.

No raw URLs, query strings, bodies, headers, error messages or stacks are saved.
Unknown error text remains unknown rather than being scrubbed heuristically.
"""
import re
import time
from urllib.parse import urlsplit

ENDPOINTS = frozenset({
    '/system/user/getInfo', '/system/menu/getRouters', '/auth/code',
    '/auth/tenant/list', '/auth/logout', '/resource/sse', '/resource/sse/close',
    '/fcp/ui/session', '/fcp/ui/catalog', '/fcp/ui/releases/bootstrap',
})
RATE_MESSAGES = frozenset({'访问过于频繁，请稍候再试',
                           'Visit too frequently, please try again later'})
ERROR_NAMES = frozenset({'Error', 'TypeError', 'ReferenceError', 'SyntaxError',
                         'RangeError', 'URIError', 'EvalError', 'AggregateError'})
FAILURES = frozenset({'net::ERR_ABORTED', 'net::ERR_FAILED', 'net::ERR_CONNECTION_RESET',
                      'net::ERR_CONNECTION_REFUSED', 'net::ERR_TIMED_OUT'})


def message_category(message):
    if isinstance(message, str) and message in RATE_MESSAGES:
        return 'native-rate-limit'
    if message in ('Network Error', 'Failed to fetch'):
        return 'network-error'
    if isinstance(message, str) and re.fullmatch(r'Request failed with status code [1-5][0-9]{2}', message):
        return 'http-' + message[-3:]
    return 'unclassified-redacted'


def numeric_code(value):
    # Reject booleans, arbitrary objects and token-shaped strings.
    if type(value) is int and 0 <= value <= 999999:
        return value
    if isinstance(value, str) and re.fullmatch(r'[0-9]{1,6}', value):
        return int(value)
    return None


class NavigationEvidence:
    def __init__(self, page, fixture, base, shell, stage, clock=time.monotonic):
        self.page = page
        self.fixture, self.origin, self.clock = fixture, urlsplit(base), clock
        self.shell, self.stage = shell, stage
        self.current = None
        page.on('pageerror', self.page_error)
        page.on('response', self.response)
        page.on('requestfailed', self.request_failed)

    def begin(self, phase):
        self.started = self.clock()
        self.current = dict(shell=self.shell, stage=self.stage, phase=phase,
                            events=[], pageErrorCount=0, droppedEvents=0)
        self.fixture.report.setdefault('uiNavigations', []).append(self.current)

    def endpoint(self, url):
        parsed = urlsplit(url)
        if (parsed.scheme, parsed.netloc) != (self.origin.scheme, self.origin.netloc):
            return None
        if parsed.path in ENDPOINTS:
            return parsed.path
        if parsed.path in ('/admin/fcp/plugins', '/teacher/fcp/plugins',
                           '/admin/login', '/teacher/login', '/admin/', '/teacher/'):
            return parsed.path
        # Immutable artifact paths may contain user-controlled filenames. Report
        # only the fixed category; never copy any part of the path.
        if parsed.path.startswith('/fcp/ui/assets/'):
            return 'immutable-ui-asset'
        return None

    def append(self, kind, **values):
        if self.current is None:
            return
        if len(self.current['events']) >= 200:
            self.current['droppedEvents'] += 1
            return
        self.current['events'].append(dict(kind=kind,
            elapsedMs=round((self.clock()-self.started)*1000), **values))

    def page_error(self, error):
        if self.current is None:
            return
        self.current['pageErrorCount'] += 1
        name = getattr(error, 'name', '')
        self.append('pageerror', name=name if name in ERROR_NAMES else 'OtherError',
                    category=message_category(getattr(error, 'message', '')))

    def response(self, response):
        endpoint = self.endpoint(response.url)
        if endpoint is None:
            return
        status = response.status
        if endpoint == 'immutable-ui-asset' and status < 400:
            return
        event = dict(endpoint=endpoint, status=status)
        for header, key in [('x-trace-id', 'traceId'), ('x-request-id', 'requestId')]:
            value = response.header_value(header)
            if isinstance(value, str) and re.fullmatch(r'[a-f0-9]{32}', value):
                event[key] = value
        # Read bodies only for finite JSON APIs, never SSE, documents or assets.
        if endpoint in ENDPOINTS - {'/resource/sse'}:
            try:
                body = response.json()
                event['bodyRead'] = 'json' if isinstance(body, dict) else 'non-object'
                if isinstance(body, dict):
                    event['businessCode'] = numeric_code(body.get('code'))
                    if event['businessCode'] not in (None, 200):
                        event['category'] = message_category(body.get('msg'))
            except Exception:
                event['bodyRead'] = 'unavailable'
        self.append('response', **event)

    def request_failed(self, request):
        endpoint = self.endpoint(request.url)
        if endpoint is not None:
            failure = request.failure
            self.append('requestfailed', endpoint=endpoint,
                        failure=failure if failure in FAILURES else 'other-redacted')

    def persist(self):
        if self.current is not None:
            self.current['location'] = self.endpoint(self.page.url) or 'other-redacted'
        self.fixture._write()

    def finish(self):
        self.persist()
        # Teardown aborts belong to closing the context, not to navigation.
        self.current = None

    def check_clean(self, label):
        # The failing check throws before Fixture.check writes. Persist first.
        self.persist()
        self.fixture.check(label, self.current['pageErrorCount'] == 0)
