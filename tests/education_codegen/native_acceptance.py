"""Real native education generator UI and source workspace gate, after legacy generator.

The prebuild clone was genuinely regenerated before building the running JAR. This
module writes a DIFFERENT CI-owned development workspace using that JAR's renderer.
SHA equality links them; a Web click does not hot-reload the already running JAR.
No response interception, fake tokens, CSS injection, SQL execution or limiter reset.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from ci_education_codegen import (PROFILE, SOURCE_INPUTS, TARGETS, OUTPUT, no_links,
                                  safe_file, sha256, validate_prebuild_report)

PREFIX = '/fcp/generator'
PROFILE_PATH = PREFIX + '/profiles/' + PROFILE
VIEWPORT = dict(width=1440, height=1080)
SQL_PATH = 'design/education-platform/v4.2.1/database/sql/P042__fresh_isolated_schema.sql'
MODEL_PATH = 'design/education-platform/v4.2.1/database/model.json'


def wait_login_window():
    started = time.monotonic()
    time.sleep(60)
    elapsed = time.monotonic() - started
    if elapsed < 60:
        raise RuntimeError('The complete native login window did not elapse')
    return dict(stage='education-generator', waitSeconds=round(elapsed, 3), nativeRateLimitChanged=False)


def file_digests(root, paths):
    return {name: sha256(safe_file(root, name)) if (Path(root)/name).exists() else None for name in sorted(paths)}


def initialize_workspace(generator_fixture, prebuild, *, source=ROOT):
    host = generator_fixture.fixture
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}', host.database)
            or generator_fixture.report.get('status') != 'PASS'):
        raise ValueError('Completed ordinary generator acceptance in the current isolated CI database is required')
    root = no_links(Path(generator_fixture.root))
    if root != no_links(Path(host.private_dir))/'generator-source' or not root.is_dir():
        raise ValueError('The existing owned generator fixture workspace is required')
    files = {**prebuild['workspaceInputDigests'], **prebuild['outputDigests']}
    if set(files) != SOURCE_INPUTS | set(TARGETS.values()):
        raise ValueError('Unexpected education workspace inventory')
    # Verify the full source/destination batch before making any fixture copy.
    for relative, digest in files.items():
        if sha256(safe_file(source, relative)) != digest:
            raise ValueError('Prebuild source changed before native generator acceptance')
        if safe_file(root, relative, must_exist=False).exists():
            raise ValueError('Refusing to overwrite an existing generator fixture file')
    for relative in sorted(files):
        target = safe_file(root, relative, must_exist=False)
        target.parent.mkdir(parents=True, exist_ok=True)
        safe_file(root, relative, must_exist=False)
        with target.open('xb') as stream:
            stream.write(safe_file(source, relative).read_bytes())
    return root


def _geometry(page):
    return page.evaluate("""() => {
      const rect = n => { if (!n) return null; const r=n.getBoundingClientRect();
        return {x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height}; };
      const dialog=document.querySelector('[data-testid="generator-apply"]');
      return {width:innerWidth,height:innerHeight,scrollX,scrollY,
        documentWidth:document.documentElement.scrollWidth,
        documentClientWidth:document.documentElement.clientWidth,
        dialog:rect(dialog),files:rect(document.querySelector('[data-testid="generator-plan-files"]'))};
    }""")


def _settled(page):
    page.evaluate("""async () => {
      await document.fonts.ready;
      let previous='', stable=0, started=performance.now();
      await new Promise((resolve,reject) => {
        const sample=() => {
          const nodes=[document.documentElement,...document.querySelectorAll('.el-dialog,.el-overlay-dialog')];
          const value=JSON.stringify(nodes.map(n=>{const r=n.getBoundingClientRect();return [r.x,r.y,r.width,r.height,n.scrollTop]}));
          const moving=nodes.some(n=>n.getAnimations().some(a=>a.playState==='running'&&a.effect?.getTiming().iterations!==Infinity));
          stable=value===previous&&!moving?stable+1:0;previous=value;
          if(stable>=4)return resolve();
          if(performance.now()-started>10000)return reject(new Error('Generator layout did not settle'));
          requestAnimationFrame(sample);
        };requestAnimationFrame(sample);
      });
    }""")


def verify_education_codegen(generator_fixture, base, build_id, *, prebuild_report=ROOT/OUTPUT/'prebuild.json'):
    from playwright.sync_api import sync_playwright, expect
    from native_client import NativeClient, require_loopback
    from native_login import native_token
    from ci_ingestion import safe_diagnostic

    require_loopback(base)
    if Path(prebuild_report).absolute() != (ROOT/OUTPUT/'prebuild.json').absolute():
        raise ValueError('Native generator evidence must belong to the actual build checkout')
    prebuild = validate_prebuild_report(ROOT)
    if not re.fullmatch('[a-f0-9]{32}', build_id):
        raise ValueError('Exact native build ID is required')
    host = generator_fixture.fixture
    output = ROOT/'reports/education-codegen-native'
    output.mkdir(parents=True, exist_ok=False)
    report = dict(status='RUNNING', completed=False, sourceCommit=prebuild['sourceCommit'], buildId=build_id,
                  scope='REAL_NATIVE_EDUCATION_GENERATOR_UI_AND_SEPARATE_OWNED_SOURCE_WORKSPACE',
                  prebuildReceipt=prebuild['receipt'], prebuildOutputDigests=prebuild['outputDigests'],
                  sourceWorkspace='separate CI-owned development workspace, not the running build checkout',
                  buildWorkspace='prebuild exact-commit generated clone', hotReload='NOT_SUPPORTED',
                  sqlExecution='NOT_RUN', production='BLOCKED', screenshots=[], checks=[],
                  educationChangedTemplateConflict='NOT_RUN_FIXED_CLASSPATH_TEMPLATES',
                  changedTemplateConflictCoverage='existing generic native generator and pure-Java profile regression',
                  browserErrors=[])
    root = None
    restorations = {}
    owned_edits = {}

    def edit_owned(path, value):
        owned_edits.setdefault(path, set()).add(value)
        path.write_bytes(value)

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        report['checks'].append(dict(name=name, status='PASS'))

    def rejected(result):
        status, body = result
        return status in (400, 401, 403, 409) or (status == 200 and isinstance(body, dict) and body.get('code') in (400, 401, 403, 409, 500))

    def assert_plan(plan, statuses=None):
        check('Web plan candidates equal the prebuild renderer output SHA',
              {entry['path']: entry['candidateSha256'] for entry in plan['files']} == prebuild['outputDigests'])
        check('Web plan uses the exact running profile and full source provenance',
              plan['profileId'] == PROFILE and plan['version'] == prebuild['profileVersion']
              and plan['inputDigests'] == prebuild['inputDigests'])
        if statuses is not None:
            check('Web plan has the expected guarded source statuses', {entry['status'] for entry in plan['files']} == statuses)
        return plan

    def source_snapshot():
        return file_digests(root, prebuild['outputDigests'])

    def db_snapshot():
        database = host.database
        # Schema/row-count evidence contains no individual education records.
        schema = host.sql("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'NULL') "
                          "FROM information_schema.columns WHERE TABLE_SCHEMA='" + database + "' ORDER BY TABLE_NAME,ORDINAL_POSITION;")
        counts = host.sql(f"USE `{database}`; SELECT COUNT(*) FROM sys_menu;")
        for table in ('ed_education_org_unit','ed_education_term','ed_education_class','ed_education_enrollment','ed_education_membership','ed_education_assignment'):
            counts += host.sql(f"USE `{database}`; SELECT COUNT(*) FROM `{table}`;")
        return dict(schemaSha256=hashlib.sha256(schema.encode()).hexdigest(),
                    menuAndEducationRowCountSha256=hashlib.sha256(counts.encode()).hexdigest())

    def screenshot(page, name):
        check(name + ' captures only the authenticated native generator page', urlsplit(page.url).path == '/admin/tool/gen')
        _settled(page)
        check(name + ' retains the fixed viewport', page.viewport_size == VIEWPORT)
        visible_text = page.locator('body').inner_text()
        secrets = [*host.diagnostic_secrets, str(root), str(host.private_dir), str(ROOT)]
        check(name + ' has no credentials or private absolute workspace paths',
              not any(secret and secret in visible_text for secret in secrets)
              and not re.search(r'Bearer\s+[A-Za-z0-9._~-]{12,}', visible_text))
        before = _geometry(page)
        check(name + ' has no horizontal page overflow', abs(before['scrollX']) <= 1
              and before['documentWidth'] <= before['documentClientWidth'] + 1)
        dialog = before['dialog']
        check(name + ' dialog has visible finite in-viewport horizontal bounds', dialog and dialog['width'] > 0
              and dialog['height'] > 0 and dialog['x'] >= -1 and dialog['right'] <= VIEWPORT['width'] + 1)
        image = output/(name + '.png')
        page.screenshot(path=str(image), full_page=False)
        after = _geometry(page)
        check(name + ' screenshot did not alter the layout', before == after)
        report['screenshots'].append(dict(path=image.relative_to(ROOT).as_posix(), sha256=sha256(image),
                                          viewport=VIEWPORT, geometry=before, privacy='PASS'))

    try:
        root = initialize_workspace(generator_fixture, prebuild)
        report['workspaceInputDigests'] = file_digests(root, SOURCE_INPUTS)
        report['loginWindow'] = wait_login_window()
        before_db = db_snapshot()
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch()
            report['browser'] = browser.version
            try:
                state = host.login(browser, base, 'admin')
                admin = NativeClient(base, native_token(state, base), 'e5cd7e4891bf95d1d19206ce24a7b32e')
                context = browser.new_context(storage_state=state, viewport=VIEWPORT)
                page = context.new_page()
                page.on('pageerror', lambda error: report['browserErrors'].append(type(error).__name__))
                try:
                    page.goto(base + '/admin/tool/gen', wait_until='domcontentloaded')
                    opener = page.get_by_test_id('education-generator-open')
                    expect(opener).to_be_visible(timeout=30000)

                    def open_profile():
                        with page.expect_response(lambda response: urlsplit(response.url).path == PROFILE_PATH and response.request.method == 'GET') as response:
                            opener.click()
                        body = response.value.json()
                        check('education profile loads through a genuine native HTTP envelope', response.value.status == 200 and body.get('code') == 200)
                        profile = body['data']
                        expect(page.get_by_test_id('education-generator-profile')).to_be_visible()
                        check('SQL/model/rules/templates digest metadata equals prebuild evidence', profile['inputDigests'] == prebuild['inputDigests'])
                        check('profile proves model-to-DDL byte parity for all 54 P042 tables', profile['provenance']['sqlVerification'] == 'MODEL_DDL_BYTE_PARITY_ALL_54_TABLES')
                        check('only the fixed foundation and roster sources are exposed',
                              {module['sourceId'] for module in profile['modules']} == {'education:p042:foundation','education:p042:roster'})
                        expect(page.get_by_test_id('education-generator-version')).to_contain_text(PROFILE)
                        expect(page.get_by_test_id('education-generator-profile')).to_contain_text('PENDING')
                        expect(page.get_by_test_id('education-generator-digests')).to_contain_text(prebuild['workspaceInputDigests'][SQL_PATH])
                        expect(page.get_by_test_id('education-generator-digests')).to_contain_text(prebuild['workspaceInputDigests'][MODEL_PATH])
                        return profile

                    def preview():
                        with page.expect_response(lambda response: urlsplit(response.url).path == PROFILE_PATH + '/plans') as response:
                            page.get_by_test_id('generator-refresh').click()
                        body = response.value.json()
                        check('UI preview uses real native profile plan endpoint', response.value.status == 200 and body.get('code') == 200)
                        return body['data']

                    def apply_ui(plan):
                        page.get_by_test_id('generator-apply-confirm').click()
                        confirm = page.locator('.el-message-box')
                        expect(confirm).to_be_visible()
                        with page.expect_response(lambda response: urlsplit(response.url).path == PREFIX + '/plans/' + plan['planId'] + '/apply') as response:
                            confirm.get_by_role('button', name='确认', exact=True).click()
                        body = response.value.json()
                        check('explicit UI confirmation applies its exact plan', response.value.status == 200 and body.get('code') == 200
                              and body['data']['planId'] == plan['planId'] and body['data']['status'] == 'applied')
                        expect(page.get_by_test_id('generator-refresh')).to_be_enabled()
                        return body['data']

                    def plan_api():
                        return admin.data(PROFILE_PATH + '/plans', dict(profileId=PROFILE, version=prebuild['profileVersion'], modules=['foundation','roster']))

                    profile = open_profile()
                    screenshot(page, '01-locked-profile')
                    original = source_snapshot()
                    plan = assert_plan(preview(), {'adopt_generated'})
                    expect(page.get_by_test_id('generator-plan-files')).to_contain_text('登记已生成文件，不改源码')
                    check('preview performs no source writes', source_snapshot() == original)
                    page.get_by_test_id('generator-apply').get_by_role('button', name='关闭', exact=True).click()
                    expect(page.get_by_test_id('generator-apply')).not_to_be_visible()
                    check('closing preview performs no source writes', source_snapshot() == original)
                    open_profile()
                    # Real selection changes invalidate old plans; use the native module subset.
                    page.get_by_test_id('education-generator-roster').click()
                    subset = preview()
                    check('module selection limits the native plan to five foundation files', len(subset['files']) == 5
                          and all('roster.' not in entry['path'] and '/roster/' not in entry['path'] and 'RosterGeneratedController' not in entry['path'] for entry in subset['files']))
                    page.get_by_test_id('education-generator-roster').click()
                    expect(page.get_by_test_id('generator-apply-confirm')).to_be_disabled()
                    plan = assert_plan(preview(), {'adopt_generated'})
                    screenshot(page, '02-exact-adoption-preview')
                    page.get_by_test_id('generator-apply-confirm').click()
                    expect(page.locator('.el-message-box')).to_be_visible()
                    page.locator('.el-message-box').get_by_role('button', name='取消', exact=True).click()
                    expect(page.get_by_test_id('generator-apply-confirm')).to_be_enabled()
                    check('canceling explicit application confirmation preserves source bytes', source_snapshot() == original)

                    target = root/'integration/plus-ui/src/views/fcp/education/generated/Workspace.vue'
                    baseline = target.read_bytes()
                    restorations[target] = baseline
                    edit_owned(target, baseline + b'\n<!-- CI-owned first-file difference -->\n')
                    conflict = assert_plan(plan_api())
                    snapshot = source_snapshot()
                    check('different first file remains an unowned blocking conflict', not conflict['applicable']
                          and any(item['path'] == target.relative_to(root).as_posix() and item['status'] == 'conflict' for item in conflict['files']))
                    check('first-file conflict rejects all candidate application', rejected(admin.request(PREFIX + '/plans/' + conflict['planId'] + '/apply', {})) and source_snapshot() == snapshot)
                    target.write_bytes(baseline)
                    plan = assert_plan(preview(), {'adopt_generated'})
                    adopted = apply_ui(plan)
                    report['adoptionReceipt'] = {key: adopted[key] for key in ('operationId','planId','status','files')}
                    check('fresh-clone adoption only registers baseline without source changes', source_snapshot() == original)
                    check('retrying confirmed application returns the same operation', admin.data(PREFIX + '/plans/' + plan['planId'] + '/apply', {})['operationId'] == adopted['operationId'])
                    assert_plan(plan_api(), {'unchanged'})
                    screenshot(page, '03-adopted-source-baseline')

                    manual = baseline + b'\n<!-- preserved CI-owned manual adjustment -->\n'
                    edit_owned(target, manual)
                    preserved = assert_plan(plan_api())
                    check('identical candidate preserves a later manual adjustment',
                          any(entry['path'] == target.relative_to(root).as_posix() and entry['status'] == 'preserve_manual' for entry in preserved['files']))
                    check('manual preservation applies as a no-op', admin.data(PREFIX + '/plans/' + preserved['planId'] + '/apply', {})['status'] == 'noop' and target.read_bytes() == manual)
                    check('adoption rollback refuses to overwrite a later manual edit',
                          rejected(admin.request(PREFIX + '/operations/' + adopted['operationId'] + '/rollback', {})) and target.read_bytes() == manual)
                    target.write_bytes(baseline)

                    stale = plan_api()
                    edit_owned(target, manual)
                    snapshot = source_snapshot()
                    check('source edit after preview rejects stale apply with no source mutation',
                          rejected(admin.request(PREFIX + '/plans/' + stale['planId'] + '/apply', {})) and source_snapshot() == snapshot)
                    target.write_bytes(baseline)
                    source = root/SQL_PATH
                    source_before = source.read_bytes()
                    restorations[source] = source_before
                    stale = plan_api()
                    edit_owned(source, source_before + b'\n-- CI-owned stale input\n')
                    snapshot = source_snapshot()
                    check('SQL input change after preview rejects stale application',
                          rejected(admin.request(PREFIX + '/plans/' + stale['planId'] + '/apply', {})) and source_snapshot() == snapshot)
                    check('changed locked SQL is rejected before another plan',
                          rejected(admin.request(PROFILE_PATH + '/plans', dict(profileId=PROFILE,version=prebuild['profileVersion'],modules=['foundation','roster']))))
                    source.write_bytes(source_before)
                    for body in (dict(profileId=PROFILE, version='unaccepted-version', modules=['foundation']),
                                 dict(profileId=PROFILE, version=prebuild['profileVersion'], modules=['unknown']),
                                 dict(profileId=PROFILE, version=prebuild['profileVersion'], modules=['foundation'], sql='SELECT 1')):
                        check('unreviewed profile version/module/SQL input is refused', rejected(admin.request(PROFILE_PATH + '/plans', body)))

                    # The genuine native history UI rolls back only the adopted baseline,
                    # retaining the original tracked source bytes and ordinary generator history.
                    page.get_by_role('tab', name='操作历史与撤销', exact=True).click()
                    page.get_by_role('button', name='刷新历史', exact=True).click()
                    undo = page.get_by_test_id('generator-rollback-' + adopted['operationId'])
                    expect(undo).to_be_visible(timeout=15000)
                    screenshot(page, '04-real-operation-history')
                    undo.click()
                    with page.expect_response(lambda response: urlsplit(response.url).path == PREFIX + '/operations/' + adopted['operationId'] + '/rollback') as response:
                        page.locator('.el-message-box').get_by_role('button', name='确认', exact=True).click()
                    check('UI rollback undoes adoption without deleting existing source', response.value.json()['data']['status'] == 'rolled_back' and source_snapshot() == original)
                    check('operation history retains the rolled-back adoption', any(operation['operationId'] == adopted['operationId'] and operation['status'] == 'rolled_back' for operation in admin.data(PREFIX + '/operations')['operations']))
                    assert_plan(plan_api(), {'adopt_generated'})

                    # Only these copied, verified CI-owned outputs are removed. The running
                    # build checkout, hand-written hooks, SQL and manual legacy fixture stay intact.
                    check('owned Web fixture is byte-identical before its CREATE scenario', source_snapshot() == original)
                    for name in prebuild['outputDigests']:
                        safe_file(root, name).unlink()
                    page.get_by_role('tab', name='变更预览', exact=True).click()
                    created_plan = assert_plan(preview(), {'create'})
                    created = apply_ui(created_plan)
                    report['createReceipt'] = {key: created[key] for key in ('operationId','planId','status','files')}
                    check('real Web source writes equal every prebuild output SHA', source_snapshot() == prebuild['outputDigests'])
                    assert_plan(plan_api(), {'unchanged'})
                    screenshot(page, '05-real-created-source-files')
                    check('native generator page has no uncaught browser exceptions', not report['browserErrors'])
                finally:
                    context.close()

                # Each is a real encrypted native identity, never a constructed token.
                teacher_state = host.login(browser, base, 'teacher')
                teacher = NativeClient(base, native_token(teacher_state, base), admin.client_id)
                snapshot = source_snapshot()
                body = dict(profileId=PROFILE,version=prebuild['profileVersion'],modules=['foundation','roster'])
                for path, payload in ((PROFILE_PATH, None), (PROFILE_PATH+'/plans', body),
                                      (PREFIX+'/plans/'+created_plan['planId']+'/apply', {}),
                                      (PREFIX+'/operations/'+created['operationId']+'/rollback', {})):
                    check('unauthorized teacher cannot access education generator operation ' + path.split('/')[-1], rejected(teacher.request(path,payload)))
                check('anonymous education generation is denied', rejected(admin.request(PROFILE_PATH+'/plans',body,token=False)))
                check('authorization denials did not modify generated source', source_snapshot() == snapshot)
            finally:
                browser.close()
        after_db = db_snapshot()
        check('source preview/apply/adopt/rollback did not execute DDL or mutate education/menu row counts', before_db == after_db)
        report.update(databaseReadOnlyEvidence=dict(before=before_db, after=after_db), sqlAutoExecution='NOT_PERFORMED_VERIFIED',
                      webOutputDigests=source_snapshot(), prebuildWebOutputEquality='PASS', status='PASS')
    except BaseException as error:
        report.update(status='FAIL', errorType=type(error).__name__,
                      error=safe_diagnostic(str(error), [*host.diagnostic_secrets, str(root or ''), str(ROOT), str(host.private_dir)]))
        raise
    finally:
        # Restore only the exact synthetic byte changes this test owns. Do not touch
        # unknown files, generator history or any user workspace on failure.
        for path, value in restorations.items():
            if path.is_file() and not path.is_symlink() and path.read_bytes() in owned_edits.get(path, set()):
                path.write_bytes(value)
        report.update(completed=True, totalChecks=len(report['checks']))
        (output/'result.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return report
