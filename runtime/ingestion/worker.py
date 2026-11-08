#!/usr/bin/env python3
"""Language-neutral FCP JSON-RPC/stdio entry point. Never write logs to stdout."""
import json
import csv
import http.client
import os
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "observability"))
from fcp_trace import scope
import attendance
import roster
from inputs import MAX_ROWS, from_api, from_csv, strict_json

MAX_FRAME = 1048576


def dispatch(entity, kind, params):
    if entity not in ('roster', 'attendance') or kind not in ('business', 'connector', 'adapter'):
        raise ValueError('Unknown installed plugin')
    if not isinstance(params, dict):
        raise ValueError('params must be an object')
    if kind == 'business':
        if set(params) != {'records', 'classId'}:
            raise ValueError('Business input requires records and classId only')
        records = params['records']
        if not isinstance(records, list) or len(records) > MAX_ROWS:
            raise ValueError('Business input exceeds 1000 records')
        records, summary = (roster if entity == 'roster' else attendance).normalize(records, params['classId'])
        return dict(records=records, summary=summary, pid=os.getpid())
    records = (from_api if kind == 'connector' else from_csv)(entity, params)
    return dict(records=records, count=len(records), pid=os.getpid())


def main():
    if len(sys.argv) != 3:
        raise SystemExit(2)
    entity, kind = sys.argv[1:]
    while True:
        raw = sys.stdin.buffer.readline(MAX_FRAME + 1)
        if not raw:
            return
        if len(raw) > MAX_FRAME or not raw.endswith(b'\n'):
            raise SystemExit(2)
        request_id = None
        try:
            request = strict_json(raw)
            if not isinstance(request, dict) or set(request) not in ({'jsonrpc', 'id', 'method', 'params'}, {'jsonrpc', 'id', 'method', 'params', 'traceparent'}):
                raise ValueError('Invalid JSON-RPC envelope')
            request_id = request['id']
            if request['jsonrpc'] != '2.0' or not isinstance(request_id, str) or request['method'] != 'run':
                raise ValueError('Unknown protocol or operation')
            with scope(request.get('traceparent')):
                result = dispatch(entity, kind, request['params'])
            reply = dict(jsonrpc='2.0', id=request_id, result=result)
        except (ValueError, TypeError, KeyError, OSError, http.client.HTTPException, csv.Error) as error:
            # Never echo source rows, URLs or credentials into an error response.
            safe = str(error) if isinstance(error, ValueError) and not isinstance(error, UnicodeError) else 'Input or platform transport failed'
            reply = dict(jsonrpc='2.0', id=request_id, error=dict(code=-32602, message=safe[:200], data={'code': 'INVALID_INPUT'}))
        encoded = json.dumps(reply, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8') + b'\n'
        if len(encoded) > MAX_FRAME:
            raise SystemExit(2)
        sys.stdout.buffer.write(encoded)
        sys.stdout.buffer.flush()



if __name__ == '__main__':
    main()
