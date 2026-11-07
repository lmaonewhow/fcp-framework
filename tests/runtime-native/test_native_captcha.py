"""Control-flow fake-clock tests only; they do not establish native login acceptance."""
import contextlib
import io
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'tools'))
from native_login import NativeCaptchaRateLimited, open_native_login, parse_native_captcha

BASE = 'http://127.0.0.1:18787'
CHALLENGE = dict(captchaEnabled=True, uuid='a'*32, img='synthetic-unit-image')


class Response:
    def __init__(self, payload, status=200, url=BASE+'/auth/code'):
        self.payload, self.status, self.url = payload, status, url
    def json(self):
        if isinstance(self.payload, Exception):
            raise self.payload
        return self.payload


def success():
    return Response(dict(code=200, data=dict(CHALLENGE)))


def limited(message='访问过于频繁，请稍候再试', code=500):
    return Response(dict(code=code, msg=message, data=None))


class Clock:
    def __init__(self):
        self.now, self.waits = 0.0, []
    def __call__(self):
        return self.now
    def sleep(self, seconds):
        self.waits.append(seconds)
        self.now += seconds


class Page:
    def __init__(self, responses, clock):
        self.responses, self.clock, self.navigations = list(responses), clock, []
    def expect_response(self, predicate, **kwargs):
        index = len(self.navigations)
        if index >= len(self.responses):
            raise AssertionError('Unexpected extra native challenge request')
        response = self.responses[index]
        class Event:
            value = response
            def __enter__(self):return self
            def __exit__(self, *exc):
                if not predicate(response):
                    raise TimeoutError('Expected native origin CAPTCHA response was absent')
        return Event()
    def goto(self, url, **kwargs):
        self.navigations.append((url, self.clock()))
    def locator(self, *_):
        raise AssertionError('The challenge helper must not submit credentials')


class NativeCaptchaTests(unittest.TestCase):
    def run_login(self, responses, *, workspace='plugins', clock=None):
        clock = clock or Clock()
        page = Page(responses, clock)
        events = []
        with contextlib.redirect_stdout(io.StringIO()):
            captcha = open_native_login(page, BASE, workspace, wait_events=events, clock=clock, sleep=clock.sleep)
        return captcha, page, clock, events

    def test_success_requests_real_form_once_without_sleep(self):
        captcha, page, clock, events = self.run_login([success()])
        self.assertEqual(captcha, CHALLENGE)
        self.assertEqual(page.navigations, [(BASE+'/admin/login?redirect=/fcp/plugins', 0.0)])
        self.assertEqual(clock.waits, [])
        self.assertEqual(events, [])

    def test_exact_native_limit_translations_wait_full_window_before_second_request(self):
        for message in ('访问过于频繁，请稍候再试', 'Visit too frequently, please try again later'):
            for code in (500, 429):
                with self.subTest(message=message, code=code):
                    _, page, clock, events = self.run_login([limited(message, code), success()])
                    self.assertEqual(clock.waits, [60.0])
                    self.assertEqual([at for _, at in page.navigations], [0.0, 60.0])
                    self.assertEqual(events, [dict(endpoint='/auth/code', nativeWindowSeconds=60, waitedSeconds=60.0, nextChallengeAttempt=2)])

    def test_explicit_http_429_does_not_need_a_json_error_body(self):
        _, page, clock, _ = self.run_login([Response(ValueError('not JSON'), status=429), success()])
        self.assertEqual(len(page.navigations), 2)
        self.assertEqual(clock.waits, [60.0])

    def test_second_throttle_fails_without_third_attempt_or_second_wait(self):
        clock = Clock();page = Page([limited(), limited()], clock)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(NativeCaptchaRateLimited, 'one complete 60-second window'):
            open_native_login(page, BASE, 'plugins', clock=clock, sleep=clock.sleep)
        self.assertEqual(len(page.navigations), 2)
        self.assertEqual(clock.waits, [60.0])

    def test_null_error_envelope_does_not_imply_throttle(self):
        responses = [
            Response(dict(code=200, data=None)),
            Response(dict(code=500, msg='Database unavailable', data=None)),
            Response(dict(code=500, msg='服务器限流异常，请稍候再试', data=None)),
            Response(dict(code=500, msg=['访问过于频繁，请稍候再试'], data=None)),
            Response(dict(code=401, msg='访问过于频繁，请稍候再试', data=None), status=401),
            Response(dict(code=403, msg='Forbidden', data=None), status=403),
            Response(None), Response([]), Response(ValueError('malformed JSON')),
        ]
        for response in responses:
            with self.subTest(response=response.payload):
                clock = Clock();page = Page([response], clock)
                with self.assertRaises(RuntimeError):
                    open_native_login(page, BASE, 'plugins', clock=clock, sleep=clock.sleep)
                self.assertEqual(len(page.navigations), 1)
                self.assertEqual(clock.waits, [])

    def test_disabled_or_malformed_challenge_fails_without_retry(self):
        for data in (dict(captchaEnabled=False, uuid='a'*32), dict(captchaEnabled=None, uuid='a'*32), {}, dict(uuid=None), dict(uuid=[]), dict(uuid='wrong')):
            with self.subTest(data=data):
                clock = Clock();page = Page([Response(dict(code=200, data=data))], clock)
                with self.assertRaises((RuntimeError, AssertionError)):
                    open_native_login(page, BASE, 'plugins', clock=clock, sleep=clock.sleep)
                self.assertEqual(len(page.navigations), 1)
                self.assertEqual(clock.waits, [])

    def test_no_retry_if_fake_wait_did_not_advance_full_window(self):
        clock = Clock();page = Page([limited(), success()], clock)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'did not fully elapse'):
            open_native_login(page, BASE, 'plugins', clock=clock, sleep=lambda _: None)
        self.assertEqual(len(page.navigations), 1)

    def test_generic_error_after_wait_is_not_retried(self):
        clock = Clock();page = Page([limited(), Response(dict(code=500, msg='broken', data=None))], clock)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'rejected or omitted'):
            open_native_login(page, BASE, 'plugins', clock=clock, sleep=clock.sleep)
        self.assertEqual(len(page.navigations), 2)
        self.assertEqual(clock.waits, [60.0])

    def test_each_new_login_still_requests_a_fresh_challenge(self):
        clock = Clock();page = Page([success(), success()], clock)
        for workspace in ('plugins', 'ingestion'):
            open_native_login(page, BASE, workspace, clock=clock, sleep=clock.sleep)
        self.assertEqual(len(page.navigations), 2)
        self.assertEqual(clock.waits, [])
        self.assertTrue(page.navigations[1][0].endswith('/fcp/ingestion'))

    def test_wrong_origin_cannot_supply_the_challenge(self):
        clock = Clock();page = Page([Response(dict(code=200, data=CHALLENGE), url='https://other.invalid/auth/code')], clock)
        with self.assertRaises(TimeoutError):
            open_native_login(page, BASE, 'plugins', clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.waits, [])

    def test_invalid_destination_fails_before_browser_access(self):
        with self.assertRaises(ValueError):open_native_login(None, 'https://remote.invalid', 'plugins')
        with self.assertRaises(ValueError):open_native_login(None, BASE, 'unexpected')

    def test_all_three_login_paths_use_the_shared_real_challenge_helper(self):
        root = Path(__file__).resolve().parents[2]
        for file in ('tools/ci_ingestion.py', 'tests/runtime-native/failure_boundaries.py', 'tests/runtime-native/ui_boundaries.py'):
            text = (root/file).read_text()
            helper = 'open_native_tenant_login' if file.endswith('/ui_boundaries.py') else 'open_native_login'
            self.assertIn('captcha = '+helper+'(', text)
            self.assertNotIn("captcha = code_response.value.json()['data']", text)
            self.assertNotIn("captcha = response.value.json()['data']", text)
        source = (root/'tools/native_login.py').read_text()
        self.assertNotIn('X-Forwarded-For', source)
        self.assertNotIn('FLUSH', source)
        self.assertNotIn('redis_get', source)


if __name__ == '__main__':
    unittest.main()
