#!/usr/bin/env python3
"""Isolated, synthetic Campus-v1 platform fixture. This is NOT a third-party integration."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlsplit(self.path)
        params = parse_qs(parsed.query)
        class_id = params.get('classId', ['class-a'])[0]
        mode = self.headers.get('Authorization', '').removeprefix('Bearer ')
        if mode not in ('demo', 'loop', 'foreign', 'missing', 'redirect'):
            self.send_response(401); self.end_headers(); return
        if mode == 'redirect':
            self.send_response(302); self.send_header('Location', 'http://169.254.169.254/'); self.end_headers(); return
        if mode == 'foreign':
            class_id = 'class-b'
        if parsed.path == '/v1/roster':
            rows = [dict(student_code=f'{n:03}', full_name=name, class_code=class_id)
                    for n, name in enumerate(('示例学生甲', '示例学生乙', '示例学生丙'), 1)]
        elif parsed.path == '/v1/attendance':
            rows = [dict(student_code=f'{n:03}', class_code=class_id, occurred_on='2026-11-03', check_in=status)
                    for n, status in enumerate(('P', 'L', 'A'), 1)]
            if mode == 'missing':
                rows.append(dict(student_code='999', class_code=class_id, occurred_on='2026-11-03', check_in='P'))
        else:
            self.send_response(404); self.end_headers(); return
        second = params.get('cursor', [''])[0] == 'page2'
        page = dict(items=rows[2:] if second else rows[:2], nextCursor=None if second else 'page2')
        if mode == 'loop':
            page['nextCursor'] = 'page2'
        raw = json.dumps(page, ensure_ascii=False).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=0)
    args = parser.parse_args()
    with ThreadingHTTPServer(('127.0.0.1', args.port), Handler) as server:
        print(server.server_address[1], flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
