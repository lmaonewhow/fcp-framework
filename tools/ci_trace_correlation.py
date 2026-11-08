"""Read-only correlation of one existing native UI import; never export private logs."""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import stat
import time

MAX_BYTES = 1_048_576
MAX_LINE = 16_384
POLL_SECONDS = 3.0
ID = re.compile(r'[0-9a-f]{32}')


def guard(fixture):
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', fixture.database)
            or os.environ.get('FCP_ACCEPTANCE_DATABASE') != fixture.database):
        raise RuntimeError('Trace correlation requires the exact disposable native CI database')


def _open(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    stream = os.fdopen(descriptor, 'rb')
    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
        stream.close()
        raise RuntimeError('Native trace log is not a regular file')
    return stream


def begin(fixture):
    guard(fixture)
    path = Path(fixture.private_dir) / 'native-startup.log'
    try:
        with _open(path) as stream:
            info = os.fstat(stream.fileno())
    except OSError:
        raise RuntimeError('Native startup log unavailable before the existing import') from None
    return dict(path=path, device=info.st_dev, inode=info.st_ino, offset=info.st_size)


def _identity(trace_id, request_id):
    if any(not isinstance(v, str) or not ID.fullmatch(v) or v == '0' * 32 for v in (trace_id, request_id)):
        raise AssertionError('Native import response lacks valid trace/request IDs')


def _selection(fixture, original, receipt):
    guard(fixture)
    request = original.get('input', {}) if isinstance(original, dict) else {}
    if not isinstance(request, dict) or not isinstance(receipt, dict):
        raise AssertionError('Invalid native import selection')
    key = request.get('requestKey')
    if (request.get('plugin') != 'roster.connector' or request.get('classId') != 'class-a'
            or not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{7,63}', key)
            or receipt.get('entity') != 'roster' or receipt.get('classId') != 'class-a'
            or type(receipt.get('version')) is not int or not 1 <= receipt['version'] <= 9007199254740991
            or receipt.get('replayed') is not False):
        raise AssertionError('Trace correlation requires the original successful roster connector receipt')
    return key, receipt['version']


def _audit(fixture, key, version, trace_id, request_id):
    # Caller validates all interpolation. Never select the raw event, subject or payload.
    query = f"""SELECT JSON_OBJECT('rows',COUNT(*),'auditId',MIN(audit_id),
      'traceId',MIN(JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.traceId'))),
      'requestId',MIN(JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.requestId'))))
      FROM `{fixture.database}`.fcp_dual_audit
      WHERE scope_id='tenant-000000' AND kind='ingest-roster' AND record_id='class-a'
      AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.operation'))='ingestion.import'
      AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.plugin'))='roster.connector'
      AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.requestKey'))='ingest:{key}'
      AND JSON_EXTRACT(event_json,'$.version')={version};"""
    raw = fixture.sql(query)
    if not isinstance(raw, str) or len(raw) > 2048:
        raise AssertionError('Unexpected bounded audit projection')
    try:
        row = json.loads(raw)
    except (ValueError, TypeError):
        raise AssertionError('Invalid audit projection') from None
    if (not isinstance(row, dict) or type(row.get('rows')) is not int or row['rows'] != 1
            or type(row.get('auditId')) is not int or row['auditId'] <= 0
            or row.get('traceId') != trace_id or row.get('requestId') != request_id):
        raise AssertionError('Original import must have one committed audit with the response trace/request IDs')
    return dict(status='PASS', matchedRows=1, auditId=row['auditId'], version=version)


def _rpc(fixture, cursor, trace_id, request_id):
    path = Path(fixture.private_dir) / 'native-startup.log'
    if cursor.get('path') != path or type(cursor.get('offset')) is not int or cursor['offset'] < 0:
        raise RuntimeError('Invalid native trace log cursor')
    deadline = time.monotonic() + POLL_SECONDS
    pending = b''
    read_bytes = 0
    position = cursor['offset']
    found, span_plugins = {}, {}
    matched_lines = 0
    while time.monotonic() < deadline:
        try:
            with _open(path) as stream:
                info = os.fstat(stream.fileno())
                if (info.st_dev, info.st_ino) != (cursor['device'], cursor['inode']) or info.st_size < position:
                    raise RuntimeError('Native startup log changed during the existing import')
                available = info.st_size - position
                if available > MAX_BYTES - read_bytes:
                    raise RuntimeError('Native trace correlation exceeded its bounded log window')
                stream.seek(position)
                chunk = stream.read(available)
        except OSError:
            raise RuntimeError('Native startup log unavailable during trace correlation') from None
        position += len(chunk)
        read_bytes += len(chunk)
        pending += chunk
        lines = pending.split(b'\n')
        pending = lines.pop()
        if len(pending) > MAX_LINE:
            raise RuntimeError('Native trace correlation encountered an oversized log line')
        for line in lines:
            if len(line) > MAX_LINE:
                raise RuntimeError('Native trace correlation encountered an oversized log line')
            if not line.startswith(b'{') or b'"plugin.rpc"' not in line:
                continue
            try:
                event = json.loads(line)
            except (ValueError, UnicodeError):
                continue  # Offset may bisect an unrelated line already being written.
            if not isinstance(event, dict) or event.get('event') != 'plugin.rpc' or event.get('traceId') != trace_id:
                continue
            if (event.get('requestId') != request_id or event.get('operation') != 'run'
                    or event.get('capability') != 'api' or event.get('outcome') != 'RETURNED'
                    or event.get('errorCode') != 'NONE'):
                raise AssertionError('Matching import RPC summary has an unexpected identity or outcome')
            plugin = event.get('pluginId')
            if plugin not in ('roster.connector', 'roster.business') or event.get('pluginVersion') != '0.6.0-dev.1':
                raise AssertionError('Matching import RPC summary has an unexpected plugin or pinned version')
            span = event.get('spanId')
            if not isinstance(span, str) or not re.fullmatch(r'[0-9a-f]{16}', span) or span == '0' * 16:
                raise AssertionError('Matching import RPC summary lacks a valid span')
            previous = span_plugins.get(span)
            if previous == plugin:
                raise AssertionError('Matching import RPC summary has a duplicate plugin/span')
            if previous is not None:
                raise AssertionError('Matching import RPC summary reused a span across plugins')
            span_plugins[span] = plugin
            found[plugin] = found.get(plugin, 0) + 1
            matched_lines += 1
            if matched_lines > 3:
                raise AssertionError('Matching import RPC summary count exceeds the existing invocation chain')
        if matched_lines == 3 and found.get('roster.connector') == 1 and found.get('roster.business') == 2:
            return dict(status='PASS', summaries=matched_lines, plugins=['roster.connector', 'roster.business'],
                        source='bounded private startup log projection', maxBytes=MAX_BYTES, pollSeconds=POLL_SECONDS)
        time.sleep(0.05)
    raise AssertionError('Missing connector and business RPC summaries for the original HTTP trace/request IDs')


def verify(fixture, cursor, trace_id, request_id, original, receipt):
    guard(fixture)
    _identity(trace_id, request_id)
    key, version = _selection(fixture, original, receipt)
    report = dict(status='RUNNING', traceId=trace_id, requestId=request_id,
                  sqlAudit=dict(status='NOT_RUN'), pluginRpc=dict(status='NOT_RUN'), replayAudit=dict(status='NOT_RUN'),
                  scope='existing real UI import; read-only SQL and bounded RPC metadata')
    fixture.report['traceCorrelation'] = report
    try:
        report['sqlAudit'] = _audit(fixture, key, version, trace_id, request_id)
        report['pluginRpc'] = _rpc(fixture, cursor, trace_id, request_id)
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        report['sqlAudit' if report['sqlAudit']['status'] == 'NOT_RUN' else 'pluginRpc'] = dict(status='FAIL')
        raise
    return dict(key=key, version=version, traceId=trace_id, requestId=request_id, auditId=report['sqlAudit']['auditId'])


def verify_replay(fixture, evidence):
    guard(fixture)
    _identity(evidence['traceId'], evidence['requestId'])
    key, version = _selection(fixture, {'input': dict(plugin='roster.connector', classId='class-a', requestKey=evidence['key'])},
                              dict(entity='roster', classId='class-a', version=evidence['version'], replayed=False))
    try:
        row = _audit(fixture, key, version, evidence['traceId'], evidence['requestId'])
        if row['auditId'] != evidence['auditId']:
            raise AssertionError('Idempotent replay changed the original audit row')
        fixture.report['traceCorrelation']['replayAudit'] = dict(status='PASS', originalAuditUnchanged=True)
    except Exception:
        fixture.report['traceCorrelation']['status'] = 'FAIL'
        fixture.report['traceCorrelation']['replayAudit'] = dict(status='FAIL')
        raise
