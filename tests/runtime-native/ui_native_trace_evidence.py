"""Project only structural completion fields for failed navigation/limit traces.

The private log is never copied. Unknown native route templates are hashed, not
published. Missing/rotated/dropped records stay missing, never inferred success.
"""
import hashlib
import os
import stat
import re
from pathlib import Path
from ui_navigation_evidence import ENDPOINTS

TRACE = r'[a-f0-9]{32}'
THROTTLE = re.compile(r'\[traceId=('+TRACE+r') spanId=[a-f0-9]{16} requestId='+TRACE+
                      r'\] - (?:访问过于频繁，请稍候再试|Visit too frequently, please try again later)$')
COMPLETION = re.compile(r'\[traceId=('+TRACE+r') spanId=[a-f0-9]{16} requestId=('+TRACE+
    r')\] - request_complete method=(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|OTHER) route=([^ ]{1,256}) '
    r'status=([0-9]{3}) durationMs=([0-9]{1,12}) failed=(true|false) '
    r'businessCode=(none|[0-9]{1,6}) businessFailed=(none|true|false) droppedTotal=([0-9]{1,12})$')
ANSI = re.compile(r'\x1b\[[0-9;]*m')


def bounded_lines(path, limit=32*1024*1024):
    """No links/FIFOs; fixed initial tail window; at most 16 KiB per line."""
    directory = fd = None
    try:
        # Walk directories with no-follow too, including the private logs directory.
        parts = Path(path).absolute().parts
        if '..' in parts:
            return
        directory = os.open(parts[0], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            return
        size = info.st_size
        start = max(0, size-min(limit, 32*1024*1024))
        os.lseek(fd, start, os.SEEK_SET)
        remaining = size-start
        buffer = b''
        dropping = start > 0  # Discard a potentially partial first record.
        while remaining > 0:
            chunk = os.read(fd, min(16384, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            pieces = chunk.split(b'\n')
            for index, piece in enumerate(pieces):
                if not dropping:
                    if len(buffer)+len(piece) > 16384:
                        buffer = b''
                        dropping = True
                    else:
                        buffer += piece
                if index < len(pieces)-1:
                    if not dropping:
                        yield buffer.decode('utf-8', errors='replace').rstrip('\r')
                    buffer = b''
                    dropping = False
        if buffer and not dropping:
            yield buffer.decode('utf-8', errors='replace').rstrip('\r')
    except OSError:
        return
    finally:
        if fd is not None:
            os.close(fd)
        if directory is not None:
            os.close(directory)


def project_native_trace_evidence(private_dir, report):
    targets=set()
    for navigation in report.get('uiNavigations',[]):
        for event in navigation.get('events',[]):
            if event.get('kind')=='pageerror' or event.get('status',0)>=400 or event.get('businessCode',200) not in (None,200):
                value=event.get('traceId','')
                if isinstance(value,str) and re.fullmatch(TRACE,value) and value!='0'*32:
                    targets.add(value)
    startup=Path(private_dir)/'native-startup.log'
    if startup.is_file():
        for line in bounded_lines(startup):
            match=THROTTLE.search(ANSI.sub('',line))
            if match and match[1]!='0'*32 and len(targets)<512:targets.add(match[1])
    path=Path(private_dir)/'logs/fcp-request.log'
    result=dict(source='private native completion log after shutdown',available=path.is_file(),
                targetCount=len(targets),records=[],missingTraceIds=sorted(targets))
    if not path.is_file():return result
    for line in bounded_lines(path):
        match=COMPLETION.search(line)
        if not match or match[1] not in targets or len(result['records'])>=512:continue
        trace,request,method,route,status,duration,failed,code,business_failed,dropped=match.groups()
        result['records'].append(dict(traceId=trace,requestId=request,method=method,
            endpoint=route if route in ENDPOINTS else 'other-native-route',
            routeSha256=hashlib.sha256(route.encode()).hexdigest(),status=int(status),
            durationMs=int(duration),failed=failed=='true',businessCode=None if code=='none' else int(code),
            businessFailed=None if business_failed=='none' else business_failed=='true',droppedTotal=int(dropped)))
    found={r['traceId'] for r in result['records']}
    result['missingTraceIds']=sorted(targets-found)
    return result
