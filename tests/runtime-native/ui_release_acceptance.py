"""Helpers for genuine two-shell release CI. No synthetic login or replacement frontend.

Variants are actual pinned Vite rebuilds with a changed compiled build identity.
Feature source is intentionally unchanged; this proves real artifact/entry selection.
"""
from pathlib import Path
import hashlib,json,os,re,sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from ui_release_bundle import verify_bundle

def prepare_ui_versions(evidence_path,variant_path,bundle_root):
    if os.environ.get('GITHUB_ACTIONS')!='true':raise RuntimeError('Native release versions only run on disposable CI')
    baseline=json.loads(Path(evidence_path).read_text());variant=json.loads(Path(variant_path).read_text());root=Path(bundle_root).resolve()
    if baseline.get('nativeBuild')!='PASS' or variant.get('baselineBuildId')!=baseline.get('buildId'):raise RuntimeError('Matching actual pinned native and variant builds required')
    result={'artifacts':[],'components':{'stable':{},'candidate':{}},'digests':{'stable':{},'candidate':{}},'sourceBuildId':baseline['buildId'],'scope':'Actual pinned Vue rebuilds, same feature source with distinct compiled build identities'}
    for shell,kind in [('admin','ADMIN_UI'),('teacher','TEACHER_UI')]:
        for stage,evidence in [('stable',baseline),('candidate',variant)]:
            info=evidence['frontend'][shell]
            if info.get('productionBuild')!='PASS' or info.get('typecheck')!='PASS':raise RuntimeError('Actual UI build and typecheck required')
            manifest=verify_bundle(root/info['uiBundle']['digest']/shell,info['uiBundle']['digest']);artifact_id='ci-ui-'+shell+'-'+stage
            result['artifacts'].append(dict(id=artifact_id,kind=kind,component='ci-ui-'+shell,version=evidence['buildId'],sha256=manifest['digest'],sizeBytes=sum(f['sizeBytes'] for f in manifest['files'].values()),location='bundle:'+manifest['digest'],compatibility={'hostContract':'fcp.host/1'},metadata={'format':'fcp.ui-bundle/1','sourceBuildId':evidence['buildId']}))
            result['components'][stage][shell+'-ui']=artifact_id;result['digests'][stage][shell]=manifest['digest']
        if result['digests']['stable'][shell]==result['digests']['candidate'][shell]:raise RuntimeError('Actual compiled variants must differ')
    return result

def verify_ui_current(fixture,browser,states,base,clients,release_id,bundles,stage):
    from playwright.sync_api import expect
    import urllib.request,urllib.error
    if os.environ.get('GITHUB_ACTIONS')!='true' or not re.fullmatch(r'fcp_ci_[a-f0-9]{12}',fixture.database):raise RuntimeError('Disposable genuine native CI required')
    for role,shell in [('admin','admin'),('teacher','teacher')]:
        client=clients[role];manifest=client.data('/fcp/ui/releases/bootstrap?shell='+shell)
        fixture.check(shell+' '+stage+' authenticated bootstrap release',manifest['releaseId']==release_id and manifest['bundleDigest']==bundles[shell])
        request=urllib.request.Request(base+'/fcp/ui/releases/bootstrap?shell='+shell,headers={'Authorization':'Bearer '+client.token,'clientid':client.client_id})
        with client.opener.open(request,timeout=20) as response:
            fixture.check(shell+' personalized bootstrap is private no-store','no-store' in response.headers.get('Cache-Control','') and 'private' in response.headers.get('Cache-Control',''))
        with client.opener.open(base+manifest['indexUrl'],timeout=20) as response:
            html=response.read();fixture.check(shell+' static index content matches authenticated digest',hashlib.sha256(html).hexdigest()==manifest['indexDigest'])
            fixture.check(shell+' immutable index publicly cacheable without token','immutable' in response.headers.get('Cache-Control',''))
        forged=urllib.request.Request(base+'/fcp/ui/releases/bootstrap?shell='+shell,headers={'Authorization':'Bearer '+client.token,'clientid':client.client_id,'X-FCP-Release-Affinity':'forged-release-choice'})
        try:
            with client.opener.open(forged,timeout=20):raise AssertionError('Forged release affinity accepted')
        except urllib.error.HTTPError as failure:
            fixture.check(shell+' forged release selection denied',failure.code in (400,401,403,409))
        context=browser.new_context(storage_state=states[role],viewport={'width':1440,'height':960})
        try:
            page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(type(error).__name__))
            page.goto(base+'/'+shell+'/fcp/plugins',wait_until='domcontentloaded')
            expect(page.get_by_role('heading',name='配置插件工作台',exact=True)).to_be_visible(timeout=30000)
            expect(page.locator('meta[name="fcp-ui-bundle"]')).to_have_attribute('content',bundles[shell])
            fixture.check(shell+' '+stage+' actual Vue shell retained deep link',page.url.split('?',1)[0]==base+'/'+shell+'/fcp/plugins')
            fixture.check(shell+' '+stage+' actual Vue shell no uncaught errors',not errors)
            page.reload(wait_until='domcontentloaded')
            expect(page.get_by_role('heading',name='配置插件工作台',exact=True)).to_be_visible(timeout=30000)
            fixture.check(shell+' '+stage+' deep-link refresh selects same bundle',True)
            target=fixture.output/(shell+'-ui-release-'+stage+'.png');page.screenshot(path=str(target),full_page=True)
            fixture.report.setdefault('screenshots',[]).append(str(target.relative_to(ROOT)))
        finally:context.close()
