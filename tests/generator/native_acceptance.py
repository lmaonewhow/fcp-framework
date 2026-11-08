"""Real pinned generator, authenticated browser, MySQL metadata, and source-file application.
Only the disposable ci_native fixture may invoke this. No SQL from generated output is executed.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.request
import urllib.error
import urllib.parse

from native_client import NativeClient, NoRedirect
from native_login import native_token

ROOT = Path(__file__).resolve().parents[2]


class GeneratorFixture:
    def __init__(self, fixture):
        if os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', fixture.database):
            raise RuntimeError('Generator acceptance requires its fresh isolated native CI database')
        self.fixture = fixture
        self.root = fixture.private_dir / 'generator-source'
        self.root.mkdir()
        for relative in ('upstreams.lock.json', 'tools/native.py', 'AGENTS.md'):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        fixture.sql(f"USE `{fixture.database}`; CREATE TABLE fcp_codegen_probe (id BIGINT NOT NULL PRIMARY KEY COMMENT '编号', tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户', name VARCHAR(80) NOT NULL COMMENT '名称', create_time DATETIME NULL, update_time DATETIME NULL) COMMENT='生成器合成验收';")
        self.output = ROOT / 'reports/generator-native'
        self.output.mkdir(parents=True, exist_ok=True)
        self.report = dict(status='RUNNING', scope='genuine RuoYi generator and native browser, real disposable MySQL and files',
                           production='NOT_RUN', generatedSqlExecution='NOT_RUN', generatedBusinessRuntime='NOT_RUN', checks=[])

    def properties(self):
        return '\nfcp.generator.enabled=true\nfcp.generator.workspace=' + str(self.root) + '\n'

    def check(self, label, result):
        if not result:
            raise AssertionError(label)
        self.report['checks'].append(dict(name=label, status='PASS'))

    def verify(self, base, build_id):
        from playwright.sync_api import sync_playwright, expect
        try:
            self.report['buildId'] = build_id
            self.report['sourceCommit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
            with sync_playwright() as p:
                browser = p.chromium.launch()
                self.report['browser'] = browser.version
                state = self.fixture.login(browser, base, 'admin')
                token = native_token(state, base)
                admin = NativeClient(base, token, 'e5cd7e4891bf95d1d19206ce24a7b32e')
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

                def native(path, body=None):
                    if not path.startswith('/tool/gen'):
                        raise ValueError('Fixed native generator path required')
                    headers = {'Authorization': 'Bearer ' + token, 'clientid': admin.client_id, 'Content-Type': 'application/json'}
                    data = None if body is None else json.dumps(body).encode()
                    request = urllib.request.Request(base + path, headers=headers, data=data)
                    if body is not None and path == '/tool/gen': request.method = 'PUT'
                    with opener.open(request, timeout=30) as response: result = json.loads(response.read(2 * 1024 * 1024))
                    if result.get('code', 200) != 200: raise AssertionError('Native generator request rejected: ' + path.split('?')[0])
                    return result

                native('/tool/gen/importTable?tables=fcp_codegen_probe&dataName=master', {})
                rows = native('/tool/gen/list?tableName=fcp_codegen_probe')['rows']
                self.check('real native schema import found exactly the synthetic table', len(rows) == 1)
                table_id = str(rows[0]['tableId'])
                table = native('/tool/gen/' + table_id)['data']['info']
                table.update(packageName='org.dromara.fcp.generated', moduleName='generatorprobe', businessName='probe',
                             className='FcpCodegenProbe', functionName='生成器合成验收', functionAuthor='FCP CI')
                native('/tool/gen', table)
                config = admin.data('/fcp/generator/config')
                self.check('explicit source workspace is enabled without exposing an absolute path', config['enabled'] and str(self.root) not in json.dumps(config))
                context = browser.new_context(storage_state=state, viewport=dict(width=1440, height=1000))
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda error: errors.append(type(error).__name__))
                page.goto(base + '/admin/tool/gen', wait_until='domcontentloaded')
                open_button = page.get_by_test_id('generator-open-' + table_id)
                expect(open_button).to_be_visible(timeout=30000)
                with page.expect_response(lambda r: r.url.endswith('/fcp/generator/plans')) as response:
                    open_button.click()
                plan = response.value.json()['data']
                self.check('browser native template plan contains twelve files and is applicable', plan['applicable'] and len(plan['files']) == 12)
                self.check('preview creates no application source files', not (self.root / 'integration').exists())
                page.screenshot(path=str(self.output / '01-real-generator-plan.png'), full_page=True)
                # Close/cancel causes no application write, then re-open from the same generator row.
                page.get_by_test_id('generator-apply').get_by_role('button', name='关闭', exact=True).click()
                self.check('closing a preview does not apply files', not (self.root / 'integration').exists())
                with page.expect_response(lambda r: r.url.endswith('/fcp/generator/plans')) as response:
                    open_button.click()
                plan = response.value.json()['data']
                page.get_by_test_id('generator-apply-confirm').click()
                with page.expect_response(lambda r: r.url.endswith('/apply')) as response:
                    page.locator('.el-message-box').get_by_role('button', name='确认', exact=True).click()
                applied = response.value.json()['data']
                self.check('browser explicit confirmation applies the exact preview', applied['status'] == 'applied' and applied['planId'] == plan['planId'])
                self.check('repeated apply returns same operation', admin.data('/fcp/generator/plans/' + plan['planId'] + '/apply', {})['operationId'] == applied['operationId'])
                java = self.root / 'integration/ruoyi/src/main/java/org/dromara/fcp/generated/domain/FcpCodegenProbe.java'
                xml = self.root / 'integration/ruoyi/src/main/resources/mapper/generatorprobe/FcpCodegenProbeMapper.xml'
                vue = self.root / 'integration/plus-ui/src/views/generatorprobe/probe/index.vue'
                sql = self.root / 'generated/sql/probeMenu.sql'
                self.check('actual native Java, MyBatis XML, plus-ui Vue and menu SQL mapped to source paths', all(path.is_file() for path in (java, xml, vue, sql)))
                self.check('native Velocity generated real tenant entity source', 'class FcpCodegenProbe extends TenantEntity' in java.read_text())
                self.check('generated SQL is not executed', self.fixture.sql(f"USE `{self.fixture.database}`; SELECT COUNT(*) FROM sys_menu WHERE component='generatorprobe/probe/index';").strip() == '0')
                page.screenshot(path=str(self.output / '02-real-generator-applied.png'), full_page=True)
                stable = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('unchanged native regeneration is stable including menu IDs', all(file['status'] == 'unchanged' for file in stable['files']))
                original = java.read_bytes()
                original_sql = sql.read_bytes(); manual_sql = original_sql + b'\n-- reviewed manual SQL note\n'; sql.write_bytes(manual_sql)
                manual = original + b'\n// preserved manual adjustment\n'
                java.write_bytes(manual)
                preserved = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('unchanged generation preserves a manual source adjustment', any(file['path'].endswith('/domain/FcpCodegenProbe.java') and file['status'] == 'preserve_manual' for file in preserved['files']))
                self.check('unchanged native regeneration preserves reviewed menu SQL', any(file['path'].endswith('Menu.sql') and file['status'] == 'preserve_manual' for file in preserved['files']))
                self.check('preserving manual adjustments is a no-op', admin.data('/fcp/generator/plans/' + preserved['planId'] + '/apply', {})['status'] == 'noop')
                self.check('manual adjustment remains byte-identical after apply', java.read_bytes() == manual and sql.read_bytes() == manual_sql)
                table['functionName'] = '生成器新版验收'
                native('/tool/gen', table)
                conflict = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('both source and generator changes yield a blocking conflict', not conflict['applicable'] and bool(conflict['conflicts']))
                before = {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.root / 'integration').rglob('*') if p.is_file()}
                status, rejected = admin.request('/fcp/generator/plans/' + conflict['planId'] + '/apply', {})
                after = {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.root / 'integration').rglob('*') if p.is_file()}
                self.check('conflict rejects the entire batch without modifying any source', (status >= 400 or rejected.get('code') != 200) and before == after)
                java.write_bytes(original); sql.write_bytes(original_sql)
                stale = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                vue_original = vue.read_bytes(); vue.write_bytes(vue_original + b'\n<!-- newer edit -->\n')
                status, rejected = admin.request('/fcp/generator/plans/' + stale['planId'] + '/apply', {})
                self.check('source edit after preview rejects a stale apply', (status >= 400 or rejected.get('code') != 200) and java.read_bytes() == original)
                vue.write_bytes(vue_original)
                update = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                changed = admin.data('/fcp/generator/plans/' + update['planId'] + '/apply', {})
                self.check('unmodified generated source updates without manual copying', '生成器新版验收' in java.read_text())
                # Real UI history and rollback button, not only API calls.
                page.get_by_role('tab', name='操作历史与撤销', exact=True).click()
                page.get_by_role('button', name='刷新历史', exact=True).click()
                undo = page.get_by_test_id('generator-rollback-' + changed['operationId'])
                expect(undo).to_be_visible(timeout=15000)
                undo.click()
                with page.expect_response(lambda r: r.url.endswith('/rollback')) as response:
                    page.locator('.el-message-box').get_by_role('button', name='确认', exact=True).click()
                self.check('browser rollback restores previous source bytes', response.value.json()['data']['status'] == 'rolled_back' and java.read_bytes() == original)
                self.check('history retains applied and rolled-back operation records', len(admin.data('/fcp/generator/operations')['operations']) >= 3)
                self.check('native generator UI has no uncaught browser exceptions', not errors)
                context.close()
                teacher_state = self.fixture.login(browser, base, 'teacher')
                teacher = NativeClient(base, native_token(teacher_state, base), admin.client_id)
                status, denied = teacher.request('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('teacher cannot generate into the global source workspace', status >= 400 or denied.get('code') != 200)
                status, denied = admin.request('/fcp/generator/plans', {'tableIds': [table_id]}, token=False)
                self.check('anonymous request cannot write source workspace', status >= 400 or denied.get('code') != 200)
                browser.close()
            self.compile_generated()
            self.report['status'] = 'PASS'
        except Exception as error:
            from ci_ingestion import safe_diagnostic
            self.report.update(status='FAIL', error=safe_diagnostic(type(error).__name__ + ': ' + str(error), self.fixture.diagnostic_secrets))
            raise
        finally:
            self.report['totalChecks'] = len(self.report['checks'])
            (self.output / 'acceptance.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + '\n')
        return self.report

    def compile_generated(self):
        from native import copy_files
        backend = ROOT / 'work/ci-native/upstream/backend'
        admin = ROOT / 'work/ci-native/upstream/admin'
        # This is the same source-overlay mapping used by the assembler. Never update the running JAR.
        copy_files(self.root / 'integration/ruoyi/src/main/java', backend / 'ruoyi-admin/src/main/java')
        copy_files(self.root / 'integration/ruoyi/src/main/resources', backend / 'ruoyi-admin/src/main/resources')
        copy_files(self.root / 'integration/plus-ui/src', admin / 'src')
        commands = [
            ('generated-java-compile', ['mvn', '-B', '-Pprod', '-pl', 'ruoyi-admin', '-am', '-DskipTests', 'compile'], backend),
            ('generated-admin-build', ['npm', 'run', 'build:prod'], admin),
            ('generated-admin-typecheck', ['npm', 'exec', '--', 'vue-tsc', '--noEmit'], admin),
        ]
        filtered = {k: v for k, v in os.environ.items() if not k.startswith(('FCP_', 'SPRING_', 'VITE_'))
                    and k not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS', 'MAVEN_ARGS'}}
        for label, command, directory in commands:
            result = subprocess.run(command, cwd=directory, env=filtered, timeout=600, capture_output=True, text=True)
            (self.output / (label + '.log')).write_text(result.stdout + result.stderr)
            self.check(label + ' against real locked upstream dependencies (exit ' + str(result.returncode) + ')', result.returncode == 0)
        self.report['generatedSources'] = {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.root / 'integration').rglob('*') if p.is_file()}
