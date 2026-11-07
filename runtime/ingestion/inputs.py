"""Two platform API connectors and two export-file adapters, with bounded input."""
import base64
import csv
import http.client
import io
import ipaddress
import json
import socket
import ssl
import time
from urllib.parse import urlencode, urlsplit

MAX_BYTES = 131072
MAX_ROWS = 1000
FIELDS = {
    'roster': {'student_code': 'studentId', 'full_name': 'name', 'class_code': 'classId'},
    'attendance': {'student_code': 'studentId', 'class_code': 'classId', 'occurred_on': 'day', 'check_in': 'status'},
}
HEADERS = {
    'roster': ({'学号': 'studentId', '姓名': 'name', '班级': 'classId'}, FIELDS['roster']),
    'attendance': ({'学号': 'studentId', '班级': 'classId', '日期': 'day', '状态': 'status'}, FIELDS['attendance']),
}
STATUS = {'正常': 'present', '出勤': 'present', '迟到': 'late', '缺勤': 'absent', '请假': 'leave',
          'P': 'present', 'L': 'late', 'A': 'absent', 'E': 'leave',
          'present': 'present', 'late': 'late', 'absent': 'absent', 'leave': 'leave'}


def map_record(row, mapping, entity):
    if not isinstance(row, dict) or set(row) != set(mapping):
        raise ValueError('Source record fields do not match the declared platform format')
    if any(not isinstance(v, str) for v in row.values()):
        raise ValueError('Platform fields must be strings; numeric student IDs lose leading zeroes')
    result = {mapping[k]: v.strip() for k, v in row.items()}
    if entity == 'attendance':
        if result['status'] not in STATUS:
            raise ValueError('Unknown platform attendance status')
        result['status'] = STATUS[result['status']]
    return result


def from_csv(entity, params):
    if set(params) != {'content', 'encoding', 'classId'}:
        raise ValueError('CSV input requires content, encoding and classId only')
    encoding = params['encoding']
    if encoding not in ('utf-8-sig', 'gb18030'):
        raise ValueError('Supported CSV encodings: utf-8-sig, gb18030')
    try:
        raw = base64.b64decode(params['content'], validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError('Invalid base64 file') from error
    if len(raw) > MAX_BYTES or b'\x00' in raw:
        raise ValueError('CSV exceeds 128 KiB or contains NUL bytes')
    text = raw.decode(encoding, errors='strict')
    reader = csv.reader(io.StringIO(text, newline=''), strict=True)
    try:
        header = next(reader)
    except StopIteration as error:
        raise ValueError('CSV requires a header') from error
    header = [v.strip() for v in header]
    if len(header) != len(set(header)):
        raise ValueError('Duplicate CSV column')
    mapping = next((m for m in HEADERS[entity] if set(header) == set(m)), None)
    if mapping is None:
        raise ValueError('CSV header does not match a supported export format')
    rows = []
    for line, values in enumerate(reader, 2):
        if not values:
            continue
        if len(values) != len(header):
            raise ValueError(f'CSV line {line}: wrong column count')
        if len(rows) >= MAX_ROWS:
            raise ValueError('CSV exceeds 1000 records')
        rows.append(map_record(dict(zip(header, values)), mapping, entity))
    if not rows:
        raise ValueError('Empty import is not accepted')
    return rows


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    def constant(_):
        raise ValueError('Non-finite JSON number')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


def fetch_json(config, path, deadline):
    """Pin the validated resolved address; TLS still verifies the configured hostname.

    No environment proxy, redirect, cookie jar, arbitrary caller URL or TLS bypass.
    Explicit allowLoopback is intended only for the isolated demo API.
    """
    if not isinstance(config, dict) or not set(config) <= {'baseUrl', 'token', 'allowLoopback', 'propagateTrace'}:
        raise ValueError('Invalid host-owned connection configuration')
    parsed = urlsplit(config.get('baseUrl', ''))
    if (parsed.scheme not in ('https', 'http') or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        raise ValueError('Configure a plain HTTPS platform origin')
    allow_loopback = config.get('allowLoopback') is True
    if parsed.scheme != 'https' and not allow_loopback:
        raise ValueError('Plain HTTP requires explicit loopback demo mode')
    token = config.get('token', '')
    if not isinstance(token, str) or len(token) > 4096 or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise ValueError('Invalid credential configuration')
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    timeout = min(2.0, deadline - time.monotonic())
    if timeout <= 0:
        raise ValueError('Connector deadline exceeded')
    addresses = socket.getaddrinfo(parsed.hostname, port, type=socket.SOCK_STREAM)
    validated = []
    for family, socktype, proto, _, address in addresses:
        ip = ipaddress.ip_address(address[0])
        if not ip.is_global and not (allow_loopback and ip.is_loopback):
            raise ValueError('Private, link-local and metadata endpoints are forbidden')
        if parsed.scheme == 'http' and not ip.is_loopback:
            raise ValueError('Plain HTTP is restricted to loopback')
        validated.append((family, socktype, proto, address))
    if not validated:
        raise ValueError('Platform address is unavailable')
    family, socktype, proto, address = validated[0]
    sock = socket.socket(family, socktype, proto)
    connection = http.client.HTTPConnection(parsed.hostname, port, timeout=timeout)
    try:
        sock.settimeout(timeout)
        sock.connect(address)
        if parsed.scheme == 'https':
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=parsed.hostname)
        connection.sock = sock
        headers = {'Accept': 'application/json', 'Accept-Encoding': 'identity'}
        if config.get('propagateTrace') is True:
            from fcp_trace import outbound_headers
            headers.update(outbound_headers())
        if token:
            headers['Authorization'] = 'Bearer ' + token
        connection.request('GET', path, headers=headers)
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError(f'Platform returned HTTP {response.status}; redirects are not followed')
        if response.getheader('Content-Encoding', 'identity') != 'identity':
            raise ValueError('Compressed platform responses are unsupported')
        content_type = response.getheader('Content-Type', '').split(';', 1)[0].strip().lower()
        if content_type != 'application/json':
            raise ValueError('Platform must return application/json')
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError('Platform response exceeds 128 KiB')
        return strict_json(raw)
    finally:
        connection.close()
        sock.close()


def from_api(entity, params):
    if set(params) != {'connection', 'classId'}:
        raise ValueError('API input requires host-owned connection and classId only')
    cursor, seen, rows = '', set(), []
    deadline = time.monotonic() + 6
    for _ in range(10):
        query = urlencode({'classId': params['classId'], 'cursor': cursor})
        page = fetch_json(params['connection'], '/v1/' + entity + '?' + query, deadline)
        if not isinstance(page, dict) or set(page) != {'items', 'nextCursor'} or not isinstance(page['items'], list):
            raise ValueError('Platform response must contain items and nextCursor')
        rows.extend(map_record(row, FIELDS[entity], entity) for row in page['items'])
        if len(rows) > MAX_ROWS:
            raise ValueError('API import exceeds 1000 records')
        cursor = page['nextCursor']
        if cursor is None or cursor == '':
            if not rows:
                raise ValueError('Empty import is not accepted')
            return rows
        if not isinstance(cursor, str) or len(cursor) > 128 or cursor in seen:
            raise ValueError('Invalid or repeated pagination cursor')
        seen.add(cursor)
    raise ValueError('API pagination exceeds 10 pages')
