"""Bounded, allowlisted evidence for real release-shell navigations, not retries.

No raw URLs, query strings, bodies, headers, error messages or stacks are saved.
Unknown error text remains unknown rather than being scrubbed heuristically.
"""
import hashlib
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


def error_evidence(error):
    message = getattr(error, 'message', '')
    message = message if isinstance(message, str) else ''
    # Exact suffix emitted by frontend-shared/trace.ts. No substring extraction.
    suffix = re.fullmatch(r'(.*) \(Trace ID: ([a-f0-9]{32})\)', message, re.DOTALL)
    values = dict(category=message_category(message),
                  messageSha256=hashlib.sha256(message.encode('utf-8')).hexdigest())
    if suffix and suffix[2] != '0'*32:
        values.update(category=message_category(suffix[1]), traceId=suffix[2])
    return values


def safe_stack_positions(stack, origin):
    if not isinstance(stack, str):
        return []
    result = []
    for line in stack.splitlines()[1:9]:
        match = re.search(r'(https?://[^\s()]+):([0-9]{1,9}):([0-9]{1,9})\)?$', line)
        if not match:
            continue
        parsed = urlsplit(match[1])
        if (parsed.scheme, parsed.netloc) != (origin.scheme, origin.netloc):
            continue
        if not re.fullmatch(r'/fcp/ui/assets/[a-f0-9]{64}/(?:admin|teacher)/assets/[^/]+\.js', parsed.path):
            continue
        result.append(dict(sourcePathSha256=hashlib.sha256(parsed.path.encode()).hexdigest(),
                           line=int(match[2]), column=int(match[3])))
    return result


class NavigationEvidence:
    def __init__(self, page, fixture, base, shell, stage, clock=time.monotonic):
        self.page = page
        self.fixture, self.origin, self.clock = fixture, urlsplit(base), clock
        self.shell, self.stage = shell, stage
        self.current = None
        self.requests = {}
        page.on('pageerror', self.page_error)
        page.on('response', self.response)
        page.on('requestfailed', self.request_failed)
        page.on('request', self.request_started)
        page.on('requestfinished', self.request_finished)

    def begin(self, phase):
        self.started = self.clock()
        self.current = dict(shell=self.shell, stage=self.stage, phase=phase,
                            events=[], pageErrorCount=0, droppedEvents=0,
                            apiRequests=[], inflightApiCount=0, requestTrackingOverflow=False,
                            streamRequestCount=0)
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
        event = dict(kind=kind, elapsedMs=round((self.clock()-self.started)*1000), **values)
        self.current['events'].append(event)
        return event

    def page_error(self, error):
        if self.current is None:
            return
        self.current['pageErrorCount'] += 1
        name = getattr(error, 'name', '')
        self.append('pageerror', name=name if name in ERROR_NAMES else 'OtherError',
                    **error_evidence(error),
                    frames=safe_stack_positions(getattr(error, 'stack', ''), self.origin))
        self.persist()

    def request_started(self, request):
        if self.current is None:
            return
        parsed = urlsplit(request.url)
        if (parsed.scheme, parsed.netloc) != (self.origin.scheme, self.origin.netloc):
            return
        if parsed.path == '/resource/sse':
            self.current['streamRequestCount'] += 1
            return
        if getattr(request, 'resource_type', '') not in ('xhr', 'fetch'):
            return
        if id(request) in self.requests:
            return
        if len(self.requests) >= 512:
            self.current['requestTrackingOverflow'] = True
            self.persist()
            return
        record = dict(endpoint=self.endpoint(request.url) or 'other-same-origin-api', state='inflight')
        self.requests[id(request)] = (request, self.current, record)
        self.current['apiRequests'].append(record)
        self.current['inflightApiCount'] += 1
        self.persist()

    def request_finished(self, request):
        self.complete_request(request, 'finished')

    def complete_request(self, request, state):
        tracked = self.requests.get(id(request))
        if self.current is None or tracked is None or tracked[1] is not self.current:
            return
        record = tracked[2]
        if record['state'] == 'inflight':
            record['state'] = state
            self.current['inflightApiCount'] -= 1
            self.persist()

    def pending(self):
        return (self.current['inflightApiCount'] != 0 or
                self.current['requestTrackingOverflow'] or
                any(e.get('bodyRead') == 'pending' for e in self.current['events']))

    def settle(self, *, timeout_ms=2000, pump=None, clock=time.monotonic):
        """Pump existing Playwright callbacks only; never start requests or clear errors."""
        navigation = self.current
        if navigation is None:
            return False
        pump = pump or self.page.wait_for_timeout
        deadline = clock() + timeout_ms/1000
        pump(0)  # Deliver already queued request-start/finish/body callbacks.
        for _ in range(40):  # Also bounded if an injected/test clock stalls.
            if self.current is not navigation:
                return False
            if not self.pending():
                break
            remaining = deadline-clock()
            if remaining <= 0:
                break
            pump(min(50, remaining*1000))
        if self.current is not navigation:
            return False
        navigation['settleStatus'] = 'TIMEOUT' if self.pending() else 'COMPLETE'
        self.persist()
        return navigation['settleStatus'] == 'COMPLETE'

    def response(self, response):
        if self.current is None:
            return
        tracked = self.requests.get(id(response.request))
        if tracked is not None and tracked[1] is not self.current:
            return
        endpoint = self.endpoint(response.url)
        parsed = urlsplit(response.url)
        same_origin = (parsed.scheme, parsed.netloc) == (self.origin.scheme, self.origin.netloc)
        unknown_api = endpoint is None and same_origin and response.request.resource_type in ('xhr', 'fetch')
        if endpoint is None and not unknown_api:
            return
        status = response.status
        if endpoint == 'immutable-ui-asset' and status < 400:
            return
        # Register BEFORE header_value/json: synchronous Playwright calls can yield
        # to the main navigation task. Retain this event in its original phase.
        navigation = self.current
        event = self.append('response', endpoint=endpoint or 'other-same-origin-api',
                            status=status, bodyRead='pending')
        if event is None:
            return
        self.persist()
        for header, key in [('x-trace-id', 'traceId'), ('x-request-id', 'requestId')]:
            try:
                value = response.header_value(header)
            except Exception:
                if self.current is not navigation:
                    return
                continue
            if self.current is not navigation:
                return
            if isinstance(value, str) and re.fullmatch(r'[a-f0-9]{32}', value) and value != '0'*32:
                event[key] = value
                self.persist()
        if self.current is not navigation:
            return
        # Never read SSE, documents or assets. Unknown same-origin XHR/fetch gets
        # only code/category, not its path, response text or data.
        if endpoint in ENDPOINTS - {'/resource/sse'} or unknown_api:
            try:
                body = response.json()
                if self.current is not navigation:
                    return
                event['bodyRead'] = 'json' if isinstance(body, dict) else 'non-object'
                if isinstance(body, dict):
                    event['businessCode'] = numeric_code(body.get('code'))
                    if event['businessCode'] not in (None, 200):
                        event['category'] = message_category(body.get('msg'))
            except Exception:
                if self.current is not navigation:
                    return
                event['bodyRead'] = 'unavailable'
        else:
            event['bodyRead'] = 'not-read'
        self.persist()

    def request_failed(self, request):
        if self.current is None:
            return
        tracked = self.requests.get(id(request))
        if tracked is not None and tracked[1] is not self.current:
            return
        self.complete_request(request, 'failed')
        endpoint = self.endpoint(request.url)
        parsed = urlsplit(request.url)
        if (endpoint is None and (parsed.scheme, parsed.netloc) == (self.origin.scheme, self.origin.netloc)
                and getattr(request, 'resource_type', '') in ('xhr', 'fetch')):
            endpoint = 'other-same-origin-api'
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
        self.requests.clear()

    def check_clean(self, label):
        # Wait only for callbacks of observed traffic; preserve every pageerror.
        if 'settleStatus' not in self.current:
            self.settle()
        # The failing check throws before Fixture.check writes. Persist first.
        self.persist()
        self.fixture.check(label+' complete finite API evidence',
                           self.current['settleStatus']=='COMPLETE' and not self.pending())
        self.fixture.check(label, self.current['pageErrorCount'] == 0)
