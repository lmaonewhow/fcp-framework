"""Disposable native acceptance: real encrypted UI login, MySQL, Redis, Python and restart.

Only ci_native.py may construct this fixture, in its newly created fcp_ci_* database.
Captcha answers are read from that isolated test Redis, never from a third-party service.
No fake backend, intercepted response, disabled captcha or fabricated token is used.
"""
from __future__ import annotations
import base64
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import uuid
from native_client import NativeClient, envelope
from native_login import authenticated_storage_state, native_token, require_encrypted_login, verify_native_identity, open_native_login

ROOT = Path(__file__).resolve().parents[1]


def fixture_password() -> str:
    # 144 random bits encoded as 24 URL-safe characters; native DTO allows 5..30.
    return secrets.token_urlsafe(18)


def safe_diagnostic(value, secrets_to_hide=()):
    text = str(value)
    for secret in sorted(secrets_to_hide, key=len, reverse=True):
        if secret:
            text = text.replace(secret, '[REDACTED]')
    text = re.sub(r'(https?://[^\s?#]+)\?[^\s]*', r'\1?[REDACTED_QUERY]', text)
    text = re.sub(r'Bearer\s+[A-Za-z0-9._~-]+', 'Bearer [REDACTED]', text, flags=re.IGNORECASE)
    return text[:600]


def redis_get(password: str, key: str):
    with socket.create_connection(('127.0.0.1', 36379), timeout=5) as sock:
        stream = sock.makefile('rb')
        def command(*words):
            parts = [str(word).encode() for word in words]
            sock.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(v)).encode()+b'\r\n'+v+b'\r\n' for v in parts))
            line = stream.readline(8192)
            if line.startswith(b'+'): return line[1:-2]
            if line.startswith(b'$'):
                size = int(line[1:-2])
                if size < 0: return None
                if size > 65536: raise RuntimeError('Unexpected fixture Redis value size')
                value = stream.read(size)
                if stream.read(2) != b'\r\n': raise RuntimeError('Invalid fixture Redis framing')
                return value
            raise RuntimeError('Isolated Redis fixture request failed')
        command('AUTH', password)
        return command('GET', key)


class Fixture:
    def __init__(self, database, sql, private_dir, redis_password, redis_prefix):
        self.ui_bootstrap_reason = "DISABLED"
        if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', database):
            raise RuntimeError('This fixture only accepts a freshly created disposable CI database')
        import bcrypt
        self.database, self.sql, self.private_dir = database, sql, private_dir
        self.redis_password, self.redis_prefix = redis_password, redis_prefix
        self.password = fixture_password()
        self.diagnostic_secrets = [self.password]
        hashed = bcrypt.hashpw(self.password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()
        sql(f"USE `{database}`; UPDATE sys_user SET password='{hashed}' WHERE user_id=1; INSERT INTO sys_user_role VALUES(1,942101);")
        for uid, name, role in [(950001, 'fcp_teacher_ci', 942102), (950002, 'fcp_viewer_ci', 942103)]:
            sql(f"USE `{database}`; INSERT INTO sys_user(user_id,tenant_id,dept_id,user_name,nick_name,password,create_time) VALUES({uid},'000000',103,'{name}','Synthetic CI account','{hashed}',NOW()); INSERT INTO sys_user_role VALUES({uid},{role}); INSERT INTO fcp_eval_assignment VALUES('tenant-000000','{uid}','class-a');")
        self.platform = subprocess.Popen([sys.executable, '-B', str(ROOT/'tests/ingestion/platform.py')], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        port = self.platform.stdout.readline().strip()
        if not port.isdecimal():
            self.close()
            raise RuntimeError('Synthetic platform fixture did not start')
        connection = dict(baseUrl='http://127.0.0.1:'+port, token='demo', allowLoopback=True)
        self.connection_file = private_dir/'connections.json'
        self.connection_file.write_text(json.dumps(dict(format=1, tenants={'tenant-000000': {'roster.connector': connection, 'attendance.connector': connection}})))
        self.connection_file.chmod(0o600)
        self.output = ROOT/'reports/native-ingestion'
        self.output.mkdir(parents=True, exist_ok=True)
        self.report = dict(status='RUNNING', scope='genuine RuoYi 5.6.2 + both Vue shells + MySQL + Redis + real Python',
            platform='synthetic Campus-v1 fixture, not a commercial platform', production='NOT_ACCEPTED',
            browserPath='Playwright on isolated CI loopback; connected Browser exposes read-only tabs and cannot control this runner', checks=[], roles=[])

    def close(self):
        process = getattr(self, 'platform', None)
        if process and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()

    def check(self, name, result):
        if not result: raise AssertionError(name)
        self.report['checks'].append(dict(name=name, status='PASS'))

    def login(self, browser, base, role, *, observe_login=None):
        from playwright.sync_api import expect
        context = browser.new_context(viewport=dict(width=1440, height=960))
        page = context.new_page()
        username = {'admin': 'admin', 'teacher': 'fcp_teacher_ci', 'viewer': 'fcp_viewer_ci'}[role]
        try:
            captcha = open_native_login(page, base, 'ingestion', wait_events=self.report.setdefault('captchaRateLimitWaits', []))
            self.check(role+' native captcha remains enabled', captcha.get('captchaEnabled', True) and bool(captcha.get('uuid')))
            key = self.redis_prefix+':global:captcha_codes:'+captcha['uuid']
            raw = redis_get(self.redis_password, key)
            if raw is None: raise RuntimeError('Native captcha not found in isolated Redis namespace')
            answer = json.loads(raw)
            if not isinstance(answer, str): raise RuntimeError('Unexpected native captcha codec')
            page.get_by_placeholder(re.compile(r'^(用户名|Username)$')).fill(username)
            page.locator('input[type=password]').fill(self.password)
            page.get_by_placeholder(re.compile(r'^(验证码|Verification Code)$')).fill(answer)
            with page.expect_response(lambda r: r.url.endswith('/auth/login'), timeout=30000) as response:
                page.locator('.login-form button.el-button--primary').click()
            require_encrypted_login(response.value)
            state, token = authenticated_storage_state(page, context, base, 'ingestion',
                                                       before_workspace_navigation=observe_login)
            self.diagnostic_secrets.append(token)
            client = NativeClient(base, token, 'e5cd7e4891bf95d1d19206ce24a7b32e')
            verify_native_identity(client, {'admin':'1','teacher':'950001','viewer':'950002'}[role], '000000')
            self.check(role+' uses native encrypted login and verified server identity', True)
            # The genuine login state remains in memory only, never in an artifact or log.
            return state
        finally: context.close()

    def verify(self, base, build_id, restart):
        from playwright.sync_api import sync_playwright, expect
        started = time.monotonic()
        admin = None
        contexts = []
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                self.report['browser'] = browser.version
                try:
                    states = {role: self.login(browser, base, role) for role in ('admin', 'teacher', 'viewer')}
                    for role, shell in [('admin', 'admin'), ('teacher', 'teacher'), ('viewer', 'teacher')]:
                        context = browser.new_context(storage_state=states[role], viewport=dict(width=1440, height=960), record_video_dir=str(self.output/'raw'), record_video_size=dict(width=1440, height=960))
                        contexts.append(context)
                        page = context.new_page()
                        errors, console_errors = [], []
                        self.report.setdefault('browserDiagnostics', {})[role] = dict(pageErrors=errors, consoleErrors=console_errors)
                        def watch_page(current_page):
                            current_page.on('pageerror', lambda error: errors.append(safe_diagnostic(error, self.diagnostic_secrets)))
                            current_page.on('console', lambda message: console_errors.append(dict(
                                text=safe_diagnostic(message.text, self.diagnostic_secrets),
                                location=safe_diagnostic(message.location.get('url', ''), self.diagnostic_secrets))) if message.type == 'error' else None)
                        watch_page(page)
                        page.goto(base+'/'+shell+'/fcp/ingestion', wait_until='domcontentloaded')
                        marker = page.get_by_test_id('fcp-ingestion')
                        expect(marker).to_be_visible(timeout=30000)
                        expect(marker).to_have_attribute('aria-busy', 'false')
                        self.check(role+' exact native build rendered', marker.get_attribute('data-build-id') == build_id and marker.get_attribute('data-shell') == shell)
                        self.check(role+' page title and framework health', bool(page.title()) and not page.locator('vite-error-overlay').count())
                        token = native_token(context.storage_state(), base)
                        client = NativeClient(base, token, 'e5cd7e4891bf95d1d19206ce24a7b32e')
                        status, body = client.request('/fcp/ui/releases/bootstrap?shell='+shell)
                        self.check(role+' initial UI bootstrap is authenticated explicit embedded success',
                                   status == 200 and body.get('code') == 200 and body.get('data') ==
                                   dict(mode='EMBEDDED', shell=shell, reason=self.ui_bootstrap_reason))
                        if role == 'teacher':
                            denied_status, denied_body = client.request('/fcp/ui/releases/bootstrap?shell=admin')
                            self.check('embedded bootstrap cannot grant teacher the admin shell',
                                       denied_status == 403 and denied_body.get('code') == 403)
                        if role == 'admin':
                            denied_status, denied_body = client.request('/fcp/ui/releases/bootstrap?shell=admin', token=False)
                            self.check('embedded bootstrap still requires native login',
                                       denied_status in (401, 403) or denied_body.get('code') in (401, 403))
                        def snapshot(): return client.data('/fcp/ingestion/bootstrap')
                        def dataset(entity): return client.data('/fcp/ingestion/data/'+entity+'?classId=class-a')
                        def select(entity):
                            page.get_by_test_id('ingestion-entity').select_option(entity)
                            expect(marker).to_have_attribute('aria-busy', 'false')
                            expect(page.get_by_test_id('plugin-'+entity+'.business')).to_be_visible()
                        def toggle_ui(plugin):
                            with page.expect_response(lambda r: r.url.endswith('/fcp/ingestion/state')) as response:
                                page.get_by_test_id('toggle-'+plugin).click()
                            self.check('UI toggle '+plugin, response.value.json().get('code') == 200)
                            expect(marker).to_have_attribute('aria-busy', 'false')
                        trace_evidence = None
                        def import_ui(kind, correlate=False):
                            nonlocal trace_evidence
                            if correlate:
                                from ci_trace_correlation import begin, verify
                                trace_cursor = begin(self)
                            before = time.monotonic()
                            with page.expect_response(lambda r: r.url.endswith('/fcp/ingestion/imports'), timeout=20000) as response:
                                page.get_by_test_id('sync-api' if kind == 'connector' else 'import-csv').click()
                            result = response.value.json()
                            self.check('UI '+kind+' import accepted', result.get('code') == 200)
                            expect(marker).to_have_attribute('aria-busy', 'false')
                            self.report.setdefault('importSeconds', []).append(round(time.monotonic()-before, 3))
                            original = response.value.request.post_data_json
                            if correlate:
                                trace_evidence = verify(self, trace_cursor,
                                    response.value.header_value('X-Trace-Id') or '',
                                    response.value.header_value('X-Request-Id') or '', original, result['data'])
                            return result['data'], original
                        if role == 'admin':
                            admin = client
                            self.check('genuine native MySQL host evidence', client.evidence()['buildId'] == build_id)
                            self.check('all six plugins disabled by default', len(snapshot()['plugins']) == 6 and not any(p['enabled'] for p in snapshot()['plugins']))
                            for entity in ('roster', 'attendance'):
                                select(entity)
                                for kind in ('business', 'connector', 'adapter'): toggle_ui(entity+'.'+kind)
                            select('roster')
                            receipt, original = import_ui('connector', correlate=True)
                            self.check('paginated native API import', receipt['inserted'] == 3 and len(dataset('roster')['records']) == 3)
                            replay = client.data('/fcp/ingestion/imports', original)
                            self.check('native transaction idempotent replay', replay['replayed'] and replay['version'] == receipt['version'])
                            from ci_trace_correlation import verify_replay
                            verify_replay(self, trace_evidence)
                            page.get_by_test_id('csv-file').set_input_files(ROOT/'fixtures/ingestion/roster.csv')
                            receipt, _ = import_ui('adapter')
                            self.check('roster CSV updates and inserts', receipt['inserted'] == 1 and receipt['updated'] == 1 and len(dataset('roster')['records']) == 4)
                            select('attendance')
                            receipt, _ = import_ui('connector')
                            self.check('Python summary after API input', receipt['summary']['attendanceRate'] == 66.67)
                            page.get_by_test_id('csv-file').set_input_files(ROOT/'fixtures/ingestion/attendance.csv')
                            receipt, _ = import_ui('adapter')
                            self.check('Python summary after CSV reconciliation', receipt['summary']['attendanceRate'] == 100 and len(dataset('attendance')['records']) == 4)
                            self.check('MySQL contains exactly two committed datasets', self.sql(f"USE `{self.database}`; SELECT COUNT(*) FROM fcp_dual_record WHERE scope_id='tenant-000000' AND kind IN ('ingest-roster','ingest-attendance');").strip() == '2')
                            select('roster')
                            bad = dict(plugin='roster.adapter', classId='class-a', expectedVersion=dataset('roster')['version'], requestKey=str(uuid.uuid4()), payload=dict(content=base64.b64encode('学号,姓名,班级\n005,Foreign,class-b\n'.encode()).decode(), encoding='utf-8-sig'))
                            _, rejected = client.request('/fcp/ingestion/imports', envelope(snapshot(), bad))
                            self.check('native bad batch rejected without partial data', rejected.get('code') == 403 and len(dataset('roster')['records']) == 4)
                            pid = dataset('roster')['pid']
                            self.check('real Python process exists before stop', Path('/proc/'+str(pid)).exists())
                            toggle_ui('roster.business')
                            self.check('stop terminates Python child', not Path('/proc/'+str(pid)).exists())
                            self.check('stop blocks dependent input plugins', all(not p['effective'] for p in snapshot()['plugins'] if p['entity'] == 'roster'))
                            self.check('admin no JavaScript exceptions before planned restart', not errors)
                            self.check('admin no console errors before planned restart', not console_errors)
                            # Close the live SSE client before intentionally stopping its server.
                            # Retain all diagnostics, then verify a fresh page against the restarted host.
                            before_video = page.video
                            page.close()
                            if before_video: before_video.save_as(str(self.output/'admin-before-restart.webm'))
                            restart()
                            self.check('JVM restart preserves stopped state', not next(p for p in snapshot()['plugins'] if p['id'] == 'roster.business')['enabled'])
                            self.check('JVM restart preserves MySQL attendance', len(dataset('attendance')['records']) == 4)
                            page = context.new_page(); watch_page(page)
                            page.goto(base+'/admin/fcp/ingestion', wait_until='domcontentloaded')
                            marker = page.get_by_test_id('fcp-ingestion')
                            expect(marker).to_be_visible(timeout=30000)
                            expect(marker).to_have_attribute('aria-busy', 'false')
                            self.check('same native build after restart', marker.get_attribute('data-build-id') == build_id)
                            toggle_ui('roster.business')
                            self.check('re-enable restores MySQL roster', len(dataset('roster')['records']) == 4)
                        else:
                            self.check(role+' cannot manage plugins', page.locator('[data-testid^="toggle-"]').count() == 0)
                            _, denied = client.request('/fcp/ingestion/data/roster?classId=class-b')
                            self.check(role+' is limited to assigned class', denied.get('code') == 403)
                            s = snapshot(); p = s['plugins'][0]
                            _, denied = client.request('/fcp/ingestion/state', envelope(s, dict(plugin=p['id'], enabled=False, revision=p['revision'])))
                            self.check(role+' cannot bypass UI management restriction', denied.get('code') == 403)
                            if role == 'viewer':
                                self.check('viewer has no import controls', page.get_by_test_id('sync-api').count() == 0)
                                _, denied = client.request('/fcp/ingestion/imports', envelope(s, {}))
                                self.check('viewer import denied by native authorization', denied.get('code') == 403)
                            else:
                                receipt, _ = import_ui('connector')
                                self.check('teacher imports assigned class through real UI', receipt['imported'] == 3)
                        page.screenshot(path=str(self.output/(role+'-desktop.png')), full_page=True)
                        page.set_viewport_size(dict(width=390, height=844))
                        page.wait_for_function("""() => {
                            const root = document.querySelector('[data-testid="fcp-ingestion"]');
                            if (!root || document.documentElement.scrollWidth > innerWidth + 2) return false;
                            return [root, ...root.querySelectorAll('.panel, button, input, select')].every(element => {
                                const box = element.getBoundingClientRect();
                                return !box.width || (box.left >= -2 && box.right <= innerWidth + 2);
                            });
                        }""", timeout=5000)
                        self.check(role+' mobile panels and controls fit viewport', True)
                        page.screenshot(path=str(self.output/(role+'-mobile.png')), full_page=True)
                        self.check(role+' no JavaScript exceptions', not errors)
                        self.check(role+' no browser console errors', not console_errors)
                        self.report['roles'].append(dict(role=role, shell=shell, url=page.url, title=page.title(), status='PASS'))
                        video = page.video
                        context.close(); contexts.remove(context)
                        if video: video.save_as(str(self.output/(role+'.webm')))
                    from ci_trace_frontend import verify as verify_trace_frontend
                    verify_trace_frontend(self, browser, base, states['admin'], build_id)
                    self.report['status'] = 'PASS'
                finally:
                    for context in contexts: context.close()
                    browser.close()
        except Exception as error:
            self.report.update(status='FAIL', error=type(error).__name__+': '+str(error).replace(self.password, '[REDACTED]')[:800])
            raise
        finally:
            self.report['elapsedSeconds'] = round(time.monotonic()-started, 3)
            self.report['totalChecks'] = len(self.report['checks'])
            (self.output/'acceptance.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2)+'\n')
        return self.report
