"""Synthetic loopback service for actual process deployment tests, not a production host."""
import base64, hashlib, hmac, json, os, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
VALUE_REQUESTS = 0
KEY = b'x' * 32  # Public synthetic fixture key, never production credentials.
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self): self.do_GET()
    def do_GET(self):
        global VALUE_REQUESTS
        if self.path == '/fcp/health':
            result = dict(releaseId=os.environ['FCP_RELEASE_ID'], manifestDigest=os.environ['FCP_RELEASE_DIGEST'], instanceId=os.environ['FCP_RELEASE_INSTANCE'], pid=os.getpid())
        elif self.path == '/fcp/count':
            result = dict(requests=VALUE_REQUESTS)
        elif self.path == '/value':
            VALUE_REQUESTS += 1
            token = self.headers.get('X-FCP-Release-Context', '')
            try:
                payload, signature = token.split('.')
                actual = base64.urlsafe_b64decode(signature + '=' * (-len(signature) % 4))
                if not hmac.compare_digest(hmac.new(KEY, payload.encode(), hashlib.sha256).digest(), actual): raise ValueError()
                identity = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
                if identity['release'] != os.environ['FCP_RELEASE_ID'] or identity['expiry'] <= time.time() * 1000 or identity['path'] != self.path or identity['method'] != self.command: raise ValueError()
                result = dict(version=os.environ['FCP_RELEASE_ID'], tenant=identity['tenant'])
            except Exception:
                self.send_response(403); self.end_headers(); return
        else:
            self.send_response(404); self.end_headers(); return
        body = json.dumps(result).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.send_header('X-FCP-Release', os.environ['FCP_RELEASE_ID']); self.end_headers(); self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1', int(os.environ['FCP_RELEASE_PORT'])), Handler).serve_forever()
