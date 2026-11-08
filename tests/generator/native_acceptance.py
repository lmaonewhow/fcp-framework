"""Real pinned generator, authenticated browser, MySQL metadata, and source-file application.
Only the disposable ci_native fixture may invoke this. Default source application never executes SQL.
An explicit separate business gate may install strictly reviewed synthetic menu SQL in its owned DB.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
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
        fixture.sql(f"USE `{fixture.database}`; CREATE TABLE fcp_codegen_probe (id BIGINT NOT NULL PRIMARY KEY COMMENT '编号', tenant_id VARCHAR(20) NOT NULL DEFAULT '000000' COMMENT '租户', name VARCHAR(80) NOT NULL COMMENT '名称', event_time DATETIME NULL COMMENT '业务日期', tags VARCHAR(100) NULL COMMENT '复选标签', create_dept BIGINT NULL, create_by BIGINT NULL, create_time DATETIME NULL, update_by BIGINT NULL, update_time DATETIME NULL) COMMENT='生成器合成验收';")
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

    def verify(self, base, build_id, business_pipeline=None):
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

                metadata_submissions = {}
                def native(path, body=None):
                    if not path.startswith('/tool/gen'):
                        raise ValueError('Fixed native generator path required')
                    headers = {'Authorization': 'Bearer ' + token, 'clientid': admin.client_id, 'Content-Type': 'application/json'}
                    data = None if body is None else json.dumps(body).encode()
                    request = urllib.request.Request(base + path, headers=headers, data=data)
                    metadata_digest = None
                    if body is not None and path == '/tool/gen':
                        request.method = 'PUT'
                        metadata_digest = hashlib.sha256(data).hexdigest()
                        previous = metadata_submissions.get(metadata_digest)
                        remaining = 5.1 - (time.monotonic() - previous) if previous is not None else 0
                        if remaining > 0:
                            started = time.monotonic()
                            time.sleep(remaining)  # Respect native @RepeatSubmit's five-second same-payload guard.
                            self.report.setdefault('nativeRepeatSubmitWaits', []).append({'reason':'intentional restoration of identical generator metadata','seconds':round(time.monotonic()-started,3)})
                    with opener.open(request, timeout=30) as response: result = json.loads(response.read(2 * 1024 * 1024))
                    if result.get('code', 200) != 200: raise AssertionError('Native generator request rejected: ' + path.split('?')[0])
                    if metadata_digest: metadata_submissions[metadata_digest] = time.monotonic()
                    return result

                # A real incompatible schema must be rejected, not merely made compilable.
                self.fixture.sql(f"USE `{self.fixture.database}`; CREATE TABLE fcp_codegen_incomplete (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(80)) COMMENT='缺少审计列合成验收';")
                native('/tool/gen/importTable?tables=fcp_codegen_incomplete&dataName=master', {})
                incomplete = native('/tool/gen/list?tableName=fcp_codegen_incomplete')['rows']
                self.check('incomplete synthetic schema imports through the real generator', len(incomplete) == 1)
                status, rejected = admin.request('/fcp/generator/plans', {'tableIds': [str(incomplete[0]['tableId'])]})
                self.check('schema lacking native audit columns is rejected before source application',
                           (status >= 400 or rejected.get('code') != 200) and '审计字段' in rejected.get('msg', '')
                           and not re.search(r'RuoYi|若依|Soybean', rejected.get('msg', ''), re.I)
                           and not (self.root / 'integration').exists())
                native('/tool/gen/importTable?tables=fcp_codegen_probe&dataName=master', {})
                rows = native('/tool/gen/list?tableName=fcp_codegen_probe')['rows']
                self.check('real native schema import found exactly the synthetic table', len(rows) == 1)
                table_id = str(rows[0]['tableId'])
                metadata = native('/tool/gen/' + table_id)['data']
                table = metadata['info']
                table['columns'] = metadata['rows']
                columns = {column['columnName']: column for column in table['columns']}
                columns['name'].update(queryType='LIKE', isQuery='1', isInsert='1', isEdit='1', isList='1')
                columns['event_time'].update(htmlType='datetime', queryType='BETWEEN', isQuery='1', isInsert='1', isEdit='1', isList='1')
                columns['tags'].update(htmlType='checkbox', dictType='sys_normal_disable', isInsert='1', isEdit='1', isQuery='0', isList='1')
                table.update(packageName='org.dromara.fcp.generated', moduleName='generatorprobe', businessName='probe',
                             className='FcpCodegenProbe', functionName='生成器合成验收', functionAuthor='FCP CI')
                # Refuse unsupported template contracts before any source write.
                columns['tags']['javaType'] = 'Integer'
                native('/tool/gen', table)
                status, rejected = admin.request('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('non-string checkbox metadata is refused before applying source',
                           (status >= 400 or rejected.get('code') != 200) and not (self.root / 'integration').exists())
                columns['tags']['javaType'] = 'String'
                table['tplCategory'] = 'tree'
                original_params = table.get('params')
                table['params'] = {'treeCode': 'id', 'treeParentCode': 'id', 'treeName': 'name'}
                native('/tool/gen', table)
                status, rejected = admin.request('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('unverified tree template is refused before applying source',
                           (status >= 400 or rejected.get('code') != 200) and not (self.root / 'integration').exists())
                table['tplCategory'] = 'crud'
                table['params'] = {**(original_params or {}), 'parentMenuId': '3'}
                table['dataName'] = 'unconfigured_generator_source'
                native('/tool/gen', table)
                status, rejected = admin.request('/fcp/generator/plans', {'tableIds': [table_id]})
                self.check('non-primary generator source is rejected before connecting or writing source',
                           (status >= 400 or rejected.get('code') != 200) and '主数据源' in rejected.get('msg', '')
                           and not (self.root / 'integration').exists())
                table['dataName'] = 'master'
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
                from business_runtime import settled
                settled(page)
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
                self.check('real generator exercises business date range and checkbox templates',
                           'dateRangeEventTime' in vue.read_text() and 'el-checkbox-group' in vue.read_text())
                self.check('generated SQL is not executed', self.fixture.sql(f"USE `{self.fixture.database}`; SELECT COUNT(*) FROM sys_menu WHERE component='generatorprobe/probe/index';").strip() == '0')
                settled(page)
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
                if business_pipeline:
                    # A visible source adjustment is the only UI augmentation. CRUD remains generated.
                    from business_runtime import MANUAL_TITLE
                    table['functionName'] = '生成器合成验收'
                    native('/tool/gen', table)
                    text = vue.read_text()
                    anchor = '<div class="p-2">'
                    self.check('generated Vue has one manual-adjustment anchor', text.count(anchor) == 1)
                    text = text.replace(anchor, anchor + '\n    <el-alert data-testid="generator-business-note" title="' + MANUAL_TITLE + '" type="info" :closable="false" show-icon class="mb-4" />', 1)
                    vue.write_text(text)
                    preserved = admin.data('/fcp/generator/plans', {'tableIds': [table_id]})
                    self.check('real regeneration preserves visible manual Vue adjustment', any(file['path'].endswith('/probe/index.vue') and file['status'] == 'preserve_manual' for file in preserved['files']))
                    self.check('regeneration of manually adjusted business view is a no-op', admin.data('/fcp/generator/plans/' + preserved['planId'] + '/apply', {})['status'] == 'noop' and vue.read_text() == text)
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
            if business_pipeline:
                business_pipeline(self)
            self.report['status'] = 'PASS'
        except Exception as error:
            from ci_ingestion import safe_diagnostic
            if self.report['generatedBusinessRuntime'] == 'RUNNING': self.report['generatedBusinessRuntime'] = 'FAIL'
            if self.report['generatedSqlExecution'] == 'RUNNING': self.report['generatedSqlExecution'] = 'FAIL'
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
        self.generated_files = {}
        for source, target in ((self.root/'integration/ruoyi/src/main/java', 'backend/ruoyi-admin/src/main/java'), (self.root/'integration/ruoyi/src/main/resources', 'backend/ruoyi-admin/src/main/resources'), (self.root/'integration/plus-ui/src', 'admin/src')):
            for path in source.rglob('*'):
                if path.is_file(): self.generated_files[target+'/'+path.relative_to(source).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        for relative in ('integration', 'generated/sql'):
            shutil.copytree(self.root/relative, self.output/'generated-source'/relative)
        self.report['generatedSources'] = {str(p.relative_to(self.root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.root / 'integration').rglob('*') if p.is_file()}

        commands = [
            ('generated-java-compile', ['mvn', '-B', '-Pprod', '-pl', 'ruoyi-admin', '-am', '-DskipTests', 'compile'], backend),
            ('generated-admin-build', ['npm', 'run', 'build:prod'], admin),
            ('generated-admin-typecheck', ['npm', 'exec', '--', 'vue-tsc', '--noEmit'], admin),
        ]
        filtered = {k: v for k, v in os.environ.items() if not k.startswith(('FCP_', 'SPRING_', 'VITE_'))
                    and k not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'MAVEN_OPTS', 'MAVEN_ARGS'}}
        from native_generated_build import sanitized_log
        projects = {'admin': admin, 'teacher': ROOT/'work/ci-native/upstream/teacher'}
        for label, command, directory in commands:
            detail = {'name':label, 'command':command, 'directory':str(directory.relative_to(ROOT)), 'status':'RUNNING', 'exitCode':None}
            self.report.setdefault('generatedBuildCommands', []).append(detail)
            raw = ''
            try:
                result = subprocess.run(command, cwd=directory, env=filtered, timeout=600, capture_output=True, text=True)
                raw = result.stdout + result.stderr
                detail.update(exitCode=result.returncode,status='PASS' if result.returncode == 0 else 'FAIL')
            except subprocess.TimeoutExpired as error:
                parts = [value.decode(errors='replace') if isinstance(value,bytes) else value or '' for value in (error.stdout,error.stderr)]
                raw = ''.join(parts)
                detail.update(status='FAIL',reason='TIMEOUT')
                raise
            except Exception:
                detail.update(status='FAIL',reason='COMMAND_FAILED_TO_RUN')
                raise
            finally:
                (self.output / (label + '.log')).write_text(sanitized_log(raw if 'raw' in locals() else 'No output was produced.',self.fixture.private_dir/'fcp-runtime.properties',projects))
            self.check(label + ' against real locked upstream dependencies (exit ' + str(result.returncode) + ')', result.returncode == 0)
