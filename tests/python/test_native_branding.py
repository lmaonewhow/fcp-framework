import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
import native_branding as branding


class BrandingTests(unittest.TestCase):
    def test_pinned_backend_welcome_patch_only_changes_product_text(self):
        relative='ruoyi-admin/src/main/java/org/dromara/web/controller/AuthController.java'
        manifest=json.loads((ROOT/'integration/branding/manifest.json').read_text())
        entry=manifest['upstreams']['backend']['files'][relative]
        original=(ROOT/'tests/fixtures/upstream/AuthController.java.txt').read_bytes()
        self.assertEqual(hashlib.sha256(original).hexdigest(),entry['before'])
        self.assertEqual(entry['before'],'c9f974254573fc8db83c6490e33d53776b8f16be513832b591fe1c73d9082071')
        old='好，欢迎登录 RuoYi-Vue-Plus 后台管理系统'.encode()
        new='好，欢迎登录 智慧云校'.encode()
        self.assertEqual(original.count(old),1)
        with tempfile.TemporaryDirectory() as directory:
            project=Path(directory);target=project/relative;target.parent.mkdir(parents=True)
            target.write_bytes(original)
            subprocess.run(['git','apply','--whitespace=error','--include='+relative,
                            str(ROOT/'integration/branding/backend.patch')],cwd=project,check=True)
            after=target.read_bytes()
            self.assertEqual(after,original.replace(old,new,1))
            self.assertEqual(hashlib.sha256(after).hexdigest(),entry['after'])
            self.assertIn(b'}, 5, TimeUnit.SECONDS);',after)
            self.assertIn(b'SseMessageUtils.publishMessage(dto);',after)
            self.assertIn(b'IAuthStrategy.login(body, client, grantType)',after)

    def test_dynamic_login_brand_requires_the_expected_welcome(self):
        from ci_branding import login_notice_uses_product_name
        for greeting in ('早上','上午','中午','下午','晚上'):
            self.assertTrue(login_notice_uses_product_name('消息\n'+greeting+'好，欢迎登录 智慧云校'))
        for text in ('', '智慧云校', '消息\n欢迎回来', '好，欢迎登录 RuoYi-Vue-Plus 后台管理系统',
                     '好，欢迎登录 智慧云校 RuoYi', '好，欢迎登录 智慧云校 若依', '好，欢迎登录 智慧云校 Soybean'):
            with self.subTest(text=text):self.assertFalse(login_notice_uses_product_name(text))

    def test_native_notice_observer_checks_both_shells_and_never_retries(self):
        # Control-flow doubles, not browser evidence. Actual SSE remains a native CI gate.
        from ci_branding import verify_login_notice
        from types import SimpleNamespace
        class Timeout(Exception):pass
        for shell,text,missing in [('admin','中午好，欢迎登录 智慧云校',False),
                                   ('teacher','中午好，欢迎登录 智慧云校',False),
                                   ('admin','中午好，欢迎登录 RuoYi-Vue-Plus 后台管理系统',False),
                                   ('teacher','',True)]:
            events=[];base='http://127.0.0.1:8080'
            class Notice:
                def filter(self,**kwargs):
                    self_test.assertTrue(kwargs['has_text'].search('欢迎登录'));return self
                @property
                def first(self):return self
                def wait_for(self,**kwargs):
                    self_test.assertEqual(kwargs,{'state':'visible','timeout':15000});events.append('wait')
                    if missing:raise Timeout()
                def inner_text(self):return text
            class Page:
                url=base+'/'+shell+'/index'
                def locator(self,selector):
                    self_test.assertEqual(selector,'.el-notification' if shell=='admin' else '.n-notification')
                    return Notice()
                def screenshot(self,**kwargs):
                    self_test.assertEqual(kwargs,{'path':'/synthetic-output/'+shell+'-login-notification.png','full_page':True})
                    events.append('screenshot')
                def goto(self,*args,**kwargs):raise AssertionError('Observer must not navigate')
            class Expect:
                def __init__(self,page):self.page=page
                def to_have_url(self,pattern,**kwargs):self_test.assertTrue(pattern.fullmatch(self.page.url))
            def check(name,value):
                if not value:raise AssertionError(name)
                events.append('check')
            self_test=self
            session={'subject':'1' if shell=='admin' else '950001','scope':'tenant-000000',
                     'defaultShell':shell,'allowedShells':[shell]}
            module=SimpleNamespace(expect=Expect,TimeoutError=Timeout)
            with patch.dict(sys.modules,{'playwright.sync_api':module}):
                if missing:
                    with self.assertRaisesRegex(AssertionError,'notification was not observed'):
                        verify_login_notice(Page(),session,base,shell,Path('/synthetic-output'),check)
                    self.assertNotIn('screenshot',events)
                elif 'RuoYi' in text:
                    with self.assertRaisesRegex(AssertionError,'notification uses product name'):
                        verify_login_notice(Page(),session,base,shell,Path('/synthetic-output'),check)
                    self.assertIn('screenshot',events)
                else:
                    verify_login_notice(Page(),session,base,shell,Path('/synthetic-output'),check)
                    self.assertEqual(events,['check','wait','screenshot','check'])
            self.assertEqual(events.count('wait'),1)

    def test_reviewed_manifest_and_patch_integrity(self):
        bundle = ROOT/'integration/branding'
        manifest = json.loads((bundle/'manifest.json').read_text())
        pins = json.loads((ROOT/'upstreams.lock.json').read_text())['upstreams']
        for name, entry in manifest['upstreams'].items():
            self.assertEqual(entry['commit'], pins[name]['commit'])
            data = (bundle/f'{name}.patch').read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry['patchSha256'])
            self.assertNotIn('LICENSE', entry['files'])
            self.assertIn('public/third-party-notices.txt', entry['files']) if name != 'backend' else None
            paths = subprocess.check_output(['git','apply','--numstat',str(bundle/f'{name}.patch')], text=True)
            self.assertEqual({line.split('\t',2)[2] for line in paths.splitlines()},set(entry['files']))

    def test_three_supported_generator_preimages_only(self):
        self.assertEqual(len(branding.GENERATOR_PREIMAGES),3)
        self.assertEqual(len(branding.GENERATOR_POSTIMAGES),3)
        for value in branding.GENERATOR_PREIMAGES | branding.GENERATOR_POSTIMAGES:
            self.assertRegex(value,r'^[a-f0-9]{64}$')

    def test_exact_education_entry_branding_and_all_drift_guards(self):
        # Exact pinned file fixture; Git identity is mocked for component tests only.
        # Actual pinned checkout reproduction is required separately before updating hashes.
        import native_generator_frontend as generator
        original=(ROOT/'tests/fixtures/upstream/GeneratorIndex.vue.txt').read_bytes()
        self.assertEqual(hashlib.sha256(original).hexdigest(),generator.PREIMAGE)
        compatible=original.replace(b"const dateRange = ref<[DateModelType, DateModelType]>(['', '']);",
                                    b"const dateRange = ref<[string, string]>(['', '']);",1)
        self.assertEqual(hashlib.sha256(compatible).hexdigest(),generator.COMPAT_PREIMAGE)
        expected_before='3c727c50f5793aa8bd69294128e56fab136164f399cbe22b5fd60eab37d7a99e'
        expected_after='e95334eaa436f4d4a756ba828777e6865d059adc6f1fb1d5430951122e64e5f2'
        with tempfile.TemporaryDirectory() as directory:
            project=Path(directory);target=project/generator.TARGET
            target.parent.mkdir(parents=True);target.write_bytes(original)
            with patch.object(generator.subprocess,'check_output',return_value=generator.PIN+'\n'):
                generator.apply_generator_frontend(project)
                raw=target.read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(),'e08c0b50710da7adcd5ce13a66bd62b09e854e59a291b04a34f54b3b897ebcd5')
                with self.assertRaisesRegex(RuntimeError,'preimage drift'):
                    branding.apply_generator_branding(project)
                self.assertEqual(target.read_bytes(),raw)
                target.write_bytes(compatible);generator.apply_generator_frontend(project)
                before=target.read_bytes()
                self.assertEqual(hashlib.sha256(before).hexdigest(),expected_before)
                # An altered file, wrong pinned identity or missing approved postimage
                # must never change the target, even if the old filename is present.
                target.write_bytes(before+b'\n')
                with self.assertRaisesRegex(RuntimeError,'preimage drift'):
                    branding.apply_generator_branding(project)
                self.assertEqual(target.read_bytes(),before+b'\n')
                target.write_bytes(before)
                with patch.object(branding.subprocess,'check_output',return_value='wrong-head\n'):
                    with self.assertRaisesRegex(RuntimeError,'preimage drift'):
                        branding.apply_generator_branding(project)
                self.assertEqual(target.read_bytes(),before)
                with patch.object(branding,'GENERATOR_POSTIMAGES',branding.GENERATOR_POSTIMAGES-{expected_after}):
                    with self.assertRaisesRegex(RuntimeError,'postimage drift'):
                        branding.apply_generator_branding(project)
                self.assertEqual(target.read_bytes(),before)
                branding.apply_generator_branding(project)
                after=target.read_bytes()
                self.assertEqual(after,before.replace(b"'ruoyi.zip'",b"'smart-school-code.zip'",1))
                self.assertEqual(hashlib.sha256(after).hexdigest(),expected_after)
                self.assertIn(b'data-testid="education-generator-open"',after)
                self.assertIn(b'generatorApplyRef?.showEducation()',after)
                with self.assertRaisesRegex(RuntimeError,'preimage drift'):
                    branding.apply_generator_branding(project)
                self.assertEqual(target.read_bytes(),after)

    def test_generator_renaming_runs_after_native_integration(self):
        source=(ROOT/'tools/native.py').read_text()
        self.assertLess(source.index('apply_branding(projects)'),source.index("copy_files(ROOT/f'integration/{overlay}/src'"))
        self.assertGreater(source.index('apply_generator_branding(admin)'),source.index("copy_files(ROOT/f'integration/{overlay}/src'"))
        self.assertIn('<title>智慧云校</title>',source)

    def test_failure_does_not_write_preceding_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); bundle=root/'integration/branding';bundle.mkdir(parents=True)
            projects={};pins={};manifest={'format':1,'upstreams':{}}
            for name in ('backend','admin','teacher'):
                project=root/name;project.mkdir(); projects[name]=project
                subprocess.run(['git','init','-q'],cwd=project,check=True)
                subprocess.run(['git','-c','user.name=Test','-c','user.email=test@example.invalid','commit','--allow-empty','-qm','fixture'],cwd=project,check=True)
                commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip();pins[name]={'commit':commit}
                target=project/'title.txt';target.write_text('old\n')
                data=b'diff --git a/title.txt b/title.txt\n--- a/title.txt\n+++ b/title.txt\n@@ -1 +1 @@\n-old\n+new\n'
                (bundle/f'{name}.patch').write_bytes(data)
                manifest['upstreams'][name]={'commit':commit,'patchSha256':hashlib.sha256(data).hexdigest(),'files':{'title.txt':{'before':hashlib.sha256(b'old\n').hexdigest(),'after':hashlib.sha256(b'new\n').hexdigest()}}}
            (root/'upstreams.lock.json').write_text(json.dumps({'upstreams':pins}))
            (bundle/'manifest.json').write_text(json.dumps(manifest))
            (projects['teacher']/'title.txt').write_text('changed\n')
            with patch.object(branding,'ROOT',root),self.assertRaisesRegex(RuntimeError,'preimage drift'):
                branding.apply_branding(projects)
            self.assertEqual((projects['admin']/'title.txt').read_text(),'old\n')
            self.assertEqual((projects['backend']/'title.txt').read_text(),'old\n')
            (projects['teacher']/'title.txt').write_text('old\n')
            with patch.object(branding,'ROOT',root):branding.apply_branding(projects)
            self.assertTrue(all((p/'title.txt').read_text()=='new\n' for p in projects.values()))
            with patch.object(branding,'ROOT',root),self.assertRaisesRegex(RuntimeError,'preimage drift'):
                branding.apply_branding(projects)

    def test_native_menu_inspection_uses_browser_response_and_rejects_bad_envelopes(self):
        from ci_branding import menu_response_data
        from types import SimpleNamespace
        self.assertEqual(menu_response_data(SimpleNamespace(status=200,json=lambda:{'code':200,'data':[]})),[])
        for status,body in [(401,{'code':401,'data':[]}),(200,{'code':500,'data':[]}),(200,{'code':200,'data':{}}),(200,[])]:
            with self.subTest(status=status,body=body),self.assertRaises(AssertionError):
                menu_response_data(SimpleNamespace(status=status,json=lambda:body))
        source=(ROOT/'tools/ci_branding.py').read_text()
        self.assertIn('with page.expect_response(',source)
        self.assertNotIn('NativeClient(',source)
        self.assertNotIn('page.route(',source)
        self.assertNotIn('route.fulfill(',source)
        self.assertIn('toolbar has no GitHub or Gitee shortcut icons',source)
        self.assertIn("page.locator('a[href]')",source)
        self.assertIn("shell+'-toolbar.png'",source)
        self.assertIn('fixture.login(browser,base,role,observe_login=',source)
        self.assertIn("shell+'-login-notification.png'",source)

    def test_branding_navigation_waits_full_native_window_and_runs_last(self):
        from ci_branding import wait_for_native_tenant_window
        for elapsed in (59.999,60.0):
            with self.subTest(elapsed=elapsed):
                report={};times=iter((100,100+elapsed));sleeps=[]
                if elapsed < 60:
                    with self.assertRaises(RuntimeError):
                        wait_for_native_tenant_window(report,clock=lambda:next(times),sleep=sleeps.append)
                else:
                    wait_for_native_tenant_window(report,clock=lambda:next(times),sleep=sleeps.append)
                self.assertEqual(sleeps,[60])
                self.assertEqual(report['nativeTenantWindow']['requiredSeconds'],60)
        source=(ROOT/'tools/ci_native.py').read_text()
        self.assertGreater(source.index("NativeModule('branding'"),source.index("NativeModule('developmentBusiness'"))
        self.assertLess(source.index("NativeModule('branding'"),source.index('   run_native_modules('))

    def test_real_authentication_not_replaced(self):
        changes=(ROOT/'integration/branding/admin.patch').read_text()+(ROOT/'integration/branding/teacher.patch').read_text()
        self.assertIn("+  username: ''",changes)
        self.assertIn("+  password: ''",changes)
        for name in ('admin','teacher'):
            entry=json.loads((ROOT/'integration/branding/manifest.json').read_text())['upstreams'][name]
            self.assertFalse(any('api/login' in path or 'service/api/auth' in path or 'utils/auth' in path for path in entry['files']))
        self.assertNotIn('skipTests',changes)

if __name__=='__main__':unittest.main()
