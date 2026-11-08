"""Native JDBC registry and two upstream UI acceptance, not deployment/gray acceptance."""
import hashlib

def verify_system_release(fixture,admin,viewer,browser,states,base):
    from playwright.sync_api import expect
    from ui_boundaries import is_rejected
    status,body=admin.request('/fcp/releases/versions',token=False)
    fixture.check('system release API rejects absent native session',is_rejected(status,body))
    state=admin.data('/fcp/releases/state')
    fixture.check('native release controller starts empty with explicit configured capability',state.get('phase')=='EMPTY' and state.get('capabilities')==['DEPLOY'])
    artifact=dict(id='ci-host-release',kind='HOST_BACKEND',component='native-host',version='ci1',sha256=hashlib.sha256(b'component-registry-fixture-not-deployment').hexdigest(),sizeBytes=42,location='artifact:ci-host-fixture',compatibility={},metadata={'purpose':'registry-test-not-deployed'})
    registered=admin.data('/fcp/releases/artifacts',artifact)
    fixture.check('system artifact registered through real native authorization and JDBC',registered==artifact)
    release=dict(id='ci-system-release',version='ci1',components={'host':'ci-host-release'},compatibility={'schemaMin':'1','schemaMax':'7'},metadata={'purpose':'registry-test-not-deployed'})
    fixture.check('system release composition registered through real JDBC',admin.data('/fcp/releases/versions',release)==release)
    fixture.check('same immutable release registration is idempotent',admin.data('/fcp/releases/versions',release)==release)
    status,body=admin.request('/fcp/releases/artifacts',{**artifact,'sha256':'0'*64})
    fixture.check('native system artifact refuses label overwrite',status==409)
    result=viewer.data('/fcp/releases/versions')
    fixture.check('explicit native release viewer can read JDBC versions',any(x.get('id')==release['id'] for x in result['releases']))
    status,body=viewer.request('/fcp/releases/artifacts',artifact)
    fixture.check('native release viewer cannot write registry',is_rejected(status,body))
    # Confirm fresh backend grant checks, not cached SaToken role permissions.
    fixture.sql(f"USE `{fixture.database}`; UPDATE sys_menu SET status='1' WHERE menu_id=942030;")
    try:
        status,body=viewer.request('/fcp/releases/versions')
        fixture.check('revoked native release view permission takes effect immediately',is_rejected(status,body))
    finally:
        fixture.sql(f"USE `{fixture.database}`; UPDATE sys_menu SET status='0' WHERE menu_id=942030;")
    fixture.check('native admin session projects explicit release grants independently of wildcard',
                  set(admin.data('/fcp/ui/session').get('releasePermissions',[]))=={'fcp:release:view','fcp:release:manage'})
    fixture.check('native teacher session projects view without management',
                  viewer.data('/fcp/ui/session').get('releasePermissions')==['fcp:release:view'])
    for role,shell in [('admin','admin'),('teacher','teacher')]:
        context=browser.new_context(storage_state=states[role],viewport={'width':1440,'height':960})
        try:
            page=context.new_page();page.goto(base+'/'+shell+'/fcp/releases',wait_until='domcontentloaded')
            expect(page.get_by_role('heading',name='系统发布、版本与灰度')).to_be_visible(timeout=30000)
            expect(page.get_by_text('ci-system-release',exact=False).first).to_be_visible(timeout=30000)
            fixture.check(shell+' real release console shows registered system version',True)
            if role=='teacher':
                fixture.check('teacher release console does not offer management controls',page.get_by_role('button',name='检查并预览',exact=True).count()==0)
            path=fixture.output/(shell+'-system-release.png');page.screenshot(path=str(path),full_page=True);fixture.report['screenshots'].append(str(path.relative_to(fixture.output.parents[1])))
        except Exception:
            if 'page' in locals():
                try:
                    path=fixture.output/(shell+'-system-release-failure.png');page.screenshot(path=str(path),full_page=True)
                    fixture.report['screenshots'].append(str(path.relative_to(fixture.output.parents[1])))
                except Exception:pass
            raise
        finally:context.close()
    fixture.report['systemRelease']={'registry':'PASS','ui':'PASS','deployment':'NOT_RUN','grayRouting':'NOT_RUN','production':'NOT_ACCEPTED'}
