"""W3C correlation helper for trusted stdio plugins. No exporter and no business payload logging.
The host owns authentication. A trace header is never an authorization credential.
"""
import asyncio
import inspect
import contextlib
import contextvars
import re
import secrets

_current = contextvars.ContextVar('fcp_trace', default=None)
_HEADER = re.compile(r'00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}\Z')


def _parse(value):
    match = _HEADER.fullmatch(value) if isinstance(value, str) and len(value) == 55 else None
    return match if match and int(match[1], 16) and int(match[2], 16) else None


@contextlib.contextmanager
def scope(traceparent):
    """Each message owns a child span; reset even if business execution fails."""
    parsed = _parse(traceparent)
    trace_id, parent_id = (parsed[1], parsed[2]) if parsed else (secrets.token_hex(16), '')
    token = _current.set({'traceId': trace_id, 'spanId': secrets.token_hex(8), 'parentSpanId': parent_id})
    try:
        yield _current.get()
    finally:
        _current.reset(token)


def current():
    value = _current.get()
    return dict(value) if value else None


def outbound_headers():
    """Use only on a host-configured, approved downstream endpoint. No baggage/identity."""
    value = _current.get()
    return {'traceparent': '00-%s-%s-00' % (value['traceId'], value['spanId'])} if value else {}


def capture(function):
    """Explicit thread/async handoff. Async execution, not merely coroutine creation, is scoped."""
    context = contextvars.copy_context()
    if inspect.iscoroutinefunction(function):
        async def run(*args, **kwargs):
            # Task creation copies the captured Context; awaiting elsewhere cannot replace it.
            task = context.copy().run(lambda: asyncio.create_task(function(*args, **kwargs)))
            return await task
        return run
    return lambda *args, **kwargs: context.copy().run(function, *args, **kwargs)
