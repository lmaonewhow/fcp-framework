#!/usr/bin/env python3
"""Assemble genuine pinned upstream projects in disposable new branches. Never edits user checkouts.
No mock API, reference frontend, generated Spring stub, or dependency substitution is used.
Python is a BUILD tool here; the final application entry point remains the native Java JAR.
"""
from __future__ import annotations
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
import urllib.parse
import zipfile
from native_trace_frontend import apply_frontend_trace
from native_generator_frontend import apply_generator_frontend
from native_generator_templates import apply_generator_templates
from native_branding import apply_branding, apply_generator_branding

ROOT = Path(__file__).resolve().parents[1]
PINS = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams']
BLOBS = {
 'backend': {
  'pom.xml':'cab4edaaef76e8b1c8df98fa53dc3ec6ea3b156b',
  'ruoyi-admin/src/test/java/org/dromara/test/TagUnitTest.java':'b50afa6cc18c390d538b82fa732e5aa54eef161f',
  'ruoyi-admin/src/main/java/org/dromara/DromaraApplication.java':'8ef33fe5f0cf8636f696d2510301a4012d809d8f',
  'ruoyi-admin/src/main/resources/application.yml':'6c18132d95b2318c8b7ed90eeb280f412ff2fc3a',
  'ruoyi-admin/src/main/java/org/dromara/web/controller/IndexController.java':'cdcfed62ad9c3ca74afb479b15af377aefde7183',
  'ruoyi-admin/pom.xml':'9e9780464e786af432e990e9ae00df20986efd82',
  'script/sql/ry_vue_5.X.sql':'3cd642b0be5916b2d2081b07282213de618aec0c',
  'script/sql/ry_workflow.sql':'f8b37ecc50a45e87611e2b31f4115cb3661b0ac3'},
 'admin': {
  'vite.config.ts':'070846c2138a9457b83dd3188f26e2961c6de173',
  'src/utils/auth.ts':'db50ac93614d13cc3733ff36cc32950d66de82c7',
  'src/permission.ts':'fb60b07c6c5572212e00015af2b96a607cd5cd0e',
  'src/router/index.ts':'a6497dbe1fd24d368e3c2c345ec5376911273a01',
  'package.json':'32766149caf7848ef44955992728de9923430b58'},
 'teacher': {
  'src/hooks/business/download.ts':'b3b70c14858177c9ddf5907a390b1bde92c00a1b',
  'vite.config.ts':'45c2f978a66613c6364154814767ef8da966c4f3',
  'src/locales/langs/en-us.ts':'3928cbae3fdb3b6bc2a723fc6d492f081d27feed',
  'src/locales/langs/zh-cn.ts':'449a839c10724474f43b4268c21057c00d20db84',
  'src/utils/storage.ts':'2ab44c28635fd207950ecfe6e5066480cbd61593',
  'src/service/api/route.ts':'30c7fb67ab43ed51dbd441b08dc3302bb3e1138a',
  'src/store/modules/route/index.ts':'14ff0f8a098fc6ffcfbe863d9de8f5a9a11c9ccb',
  'package.json':'7ec81fff77cb17d3cfaa6394b75af2075873cdb9'}
}

class BuildError(RuntimeError): pass

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def git_blob(data: bytes) -> str:
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

def run(args: list[str], cwd: Path|None=None, timeout: int=1200, capture: bool=False, clean_vite: bool=False, clean_runtime: bool=False, env_overrides: dict[str,str]|None=None) -> str:
    # Resolve .cmd/.exe wrappers on Windows without concatenating a user-controlled shell string.
    executable = shutil.which(args[0])
    if executable is None: raise BuildError(f'Required executable missing: {args[0]}')
    environment={k:v for k,v in os.environ.items() if not clean_vite or not k.startswith('VITE_')}
    if clean_runtime:
        # The explicit isolated test configuration must not inherit another project's credentials.
        jvm_options={'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','MAVEN_OPTS','MAVEN_ARGS'}
        environment={k:v for k,v in environment.items() if not k.startswith(('FCP_','SPRING_')) and k not in jvm_options}
    environment.update(env_overrides or {})
    cp = subprocess.run([executable,*args[1:]], cwd=cwd, check=False, timeout=timeout,
        text=True, encoding='utf-8', errors='replace', capture_output=capture,env=environment)
    if cp.returncode:
        detail = cp.stderr[-2000:] if capture else 'See the preceding tool output.'
        raise BuildError(f'{args[0]} exited {cp.returncode}. {detail}')
    return cp.stdout.strip() if capture else ''

def check_blob(path: Path, expected: str) -> None:
    if not path.is_file() or path.is_symlink(): raise BuildError(f'Missing/unsafe upstream file: {path}')
    actual = git_blob(path.read_bytes())
    if actual != expected: raise BuildError(f'Upstream file drift: {path.name}; expected {expected}, got {actual}')

def apply_compatibility_patch(project: Path, bundle: Path, expected_commit: str) -> None:
    """Apply reviewed local compatibility changes only to their exact pinned preimages."""
    manifest=json.loads((bundle/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format')!=1 or manifest.get('upstreamCommit')!=expected_commit:
        raise BuildError('Compatibility patch does not identify the pinned upstream')
    if run(['git','rev-parse','HEAD'],cwd=project,capture=True)!=expected_commit:
        raise BuildError('Compatibility patch target commit changed')
    if manifest.get('patchFile')!='types.patch':raise BuildError('Unexpected compatibility patch filename')
    patch=bundle/'types.patch'
    if patch.is_symlink() or not patch.is_file() or patch.stat().st_size>2_097_152:
        raise BuildError('Unsafe compatibility patch')
    if sha(patch.read_bytes())!=manifest.get('sha256'):
        raise BuildError('Compatibility patch checksum changed')
    preimages=manifest.get('preimages',{})
    if not preimages:raise BuildError('Compatibility patch has no pinned preimages')
    for relative,blob in preimages.items():
        if not re.fullmatch(r'src/[A-Za-z0-9_./-]+\.(?:ts|vue)',relative) or '..' in Path(relative).parts:
            raise BuildError('Unsafe compatibility target path')
        check_blob(project/relative,blob)
    changes=run(['git','apply','--numstat',str(patch.resolve())],cwd=project,capture=True)
    paths={line.split('\t',2)[2] for line in changes.splitlines()}
    if paths!=set(preimages):raise BuildError('Compatibility patch target set changed')
    run(['git','apply','--check','--whitespace=error',str(patch.resolve())],cwd=project)
    run(['git','apply','--whitespace=error',str(patch.resolve())],cwd=project)

def apply_native_trace_patch(backend: Path) -> None:
    bundle=ROOT/'integration/ruoyi/trace-patch'
    manifest=json.loads((bundle/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format')!=1 or manifest.get('upstreamCommit')!=PINS['backend']['commit']:
        raise BuildError('Native trace patch upstream mismatch')
    if run(['git','rev-parse','HEAD'],cwd=backend,capture=True)!=PINS['backend']['commit']:
        raise BuildError('Native trace target commit mismatch')
    patch=bundle/'native.patch'
    if patch.is_symlink() or sha(patch.read_bytes())!=manifest.get('sha256'):
        raise BuildError('Native trace patch checksum mismatch')
    before,after=manifest['preimages'],manifest['postimages']
    if not before or set(before)!=set(after):raise BuildError('Native trace patch target mismatch')
    for relative,digest in before.items():
        if not re.fullmatch(r'ruoyi-[A-Za-z0-9_./-]+\.(?:java|xml)',relative) or '..' in Path(relative).parts:
            raise BuildError('Unsafe native trace target')
        target=backend/relative
        if target.is_symlink() or sha(target.read_bytes())!=digest:raise BuildError('Native trace preimage drift: '+relative)
    changed=run(['git','apply','--numstat',str(patch)],cwd=backend,capture=True)
    if {line.split('\t',2)[2] for line in changed.splitlines()}!=set(before):raise BuildError('Native trace target set drift')
    run(['git','apply','--check','--whitespace=error',str(patch)],cwd=backend)
    run(['git','apply','--whitespace=error',str(patch)],cwd=backend)
    for relative,digest in after.items():
        if sha((backend/relative).read_bytes())!=digest:raise BuildError('Native trace postimage drift: '+relative)

def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1: raise BuildError(f'Expected one adaptation anchor; found {text.count(old)}: {old[:70]}')
    return text.replace(old,new,1)

def edit(path: Path, old: str, new: str) -> None:
    path.write_text(replace_once(path.read_text(encoding='utf-8'),old,new),encoding='utf-8')

def copy_files(source: Path, destination: Path) -> None:
    for path in sorted(source.rglob('*')):
        if path.is_symlink(): raise BuildError(f'Refusing symlink: {path}')
        if path.is_file():
            target = destination/path.relative_to(source)
            if target.exists(): raise BuildError(f'Refusing to overwrite an existing upstream file: {target}')
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(path,target)

def copy_core_resources(root: Path, destination: Path) -> None:
    """Package reviewed generator templates as classpath data, never scripts."""
    source = root / 'core/src/main/resources'
    generator = root / 'core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java'
    if source.is_symlink():
        raise BuildError('Core resource root must not be a symlink')
    if generator.is_file() and not (source / 'education-generator').is_dir():
        raise BuildError('Education generator classpath resources are required')
    copy_files(source, destination)

def doctor(check_network: bool=True) -> dict:
    checks=[]
    for name in ['git','java','javac','node','npm','pnpm','mvn']:
        found=shutil.which(name)
        checks.append({'name':name,'ok':bool(found),'path':found})
    if check_network:
        for host in ['github.com','repo.maven.apache.org','registry.npmjs.org']:
            try:
                socket.getaddrinfo(host,443)
                checks.append({'name':'dns:'+host,'ok':True})
            except OSError as exc:
                checks.append({'name':'dns:'+host,'ok':False,'detail':str(exc)})
    return {'scope':'build prerequisites, not application verification',
            'status':'READY' if all(v['ok'] for v in checks) else 'BLOCKED','checks':checks}

def fetch(work: Path, local_sources: Path|None=None) -> dict[str,Path]:
    sources=work/'upstream'
    if sources.exists(): raise BuildError('Use a new workspace: upstream directory already exists. Nothing was deleted.')
    sources.mkdir(parents=True)
    result={}
    for name,pin in PINS.items():
        dest=sources/name
        if local_sources:
            source=local_sources/name
            run(['git','-c','core.autocrlf=false','clone','--no-hardlinks',str(source.resolve()),str(dest)])
            run(['git','config','core.autocrlf','false'],cwd=dest)
            run(['git','checkout','--detach',pin['commit']],cwd=dest)
        else:
            run(['git','-c','core.autocrlf=false','clone','--depth','1','--branch',pin['tag'],pin['repo'],str(dest)])
        actual=run(['git','rev-parse','HEAD'],cwd=dest,capture=True)
        if actual != pin['commit']: raise BuildError(f'{name} tag no longer matches the pinned commit; stopping.')
        run(['git','config','core.autocrlf','false'],cwd=dest)
        # A local assembled checkout already owns the old fixed branch name.
        # Keep all inherited refs intact and create a new disposable branch.
        run(['git','switch','-c','fcp/production-hardening-0.5.0-'+uuid.uuid4().hex],cwd=dest)
        for path,blob in BLOBS[name].items(): check_blob(dest/path,blob)
        result[name]=dest
    return result

def read_build_env(path: Path|None) -> dict[str,str]:
    values=dict(os.environ)
    if path:
        for line in path.read_text(encoding='utf-8').splitlines():
            line=line.strip()
            if not line or line.startswith('#'): continue
            if '=' not in line: raise BuildError('Invalid build env line')
            k,v=line.split('=',1)
            if not re.fullmatch(r'FCP_[A-Z0-9_]+',k): raise BuildError('Unexpected build configuration key')
            values[k]=v.strip().strip('"\'')
    out={}
    for key in ['FCP_CLIENT_ID','FCP_REQUEST_PUBLIC_KEY','FCP_RESPONSE_PRIVATE_KEY']:
        value=values.get(key,'')
        if not value or value.startswith('REPLACE_') or not re.fullmatch('[A-Za-z0-9+/=_-]+',value):
            raise BuildError(f'{key} must be set explicitly. Generate fresh matching RSA material before building.')
        out[key]=value
    return out

def validate_test_runtime(path: Path|None, *, existing_redis_no_password: bool=False) -> Path:
    """Require explicit private configuration before a Spring-context Maven test can run."""
    if path is None or not path.is_file() or path.is_symlink():
        raise BuildError('Build requires --test-runtime-config after initializing a dedicated local MySQL/Redis test environment')
    if os.name!='nt' and path.stat().st_mode & 0o077:
        raise BuildError('Test runtime configuration must have permissions 0600')
    values={}
    for raw in path.read_text(encoding='utf-8').splitlines():
        line=raw.strip()
        if not line or line.startswith(('#','!')):continue
        if '=' not in line:raise BuildError('Use the generated FCP key=value properties format')
        key,value=line.split('=',1);values[key.strip()]=value.strip()
    required=['FCP_JDBC_URL','FCP_DB_USER','FCP_DB_PASSWORD','FCP_REDIS_PASSWORD','FCP_JWT_SECRET',
              'FCP_MONITOR_PASSWORD','FCP_REQUEST_PRIVATE_KEY','FCP_RESPONSE_PUBLIC_KEY','FCP_REDIS_PREFIX']
    for key in required:
        if key=='FCP_REDIS_PASSWORD' and existing_redis_no_password and values.get(key) == '':continue
        if not values.get(key) or values[key].startswith('REPLACE_'):
            raise BuildError('Test runtime configuration is incomplete: '+key)
    url=values['FCP_JDBC_URL']
    if not url.startswith('jdbc:mysql://'):raise BuildError('Test configuration must use a local MySQL JDBC URL')
    parsed=urllib.parse.urlsplit(url[5:])
    def local(host):
        try:return ipaddress.ip_address(host or '').is_loopback
        except ValueError:return host=='localhost'
    if not local(parsed.hostname) or parsed.username or parsed.password or parsed.fragment:
        raise BuildError('Build tests refuse a non-loopback or ambiguous database endpoint')
    if parsed.port is not None and not 1<=parsed.port<=65535:raise BuildError('Invalid test database port')
    if not re.fullmatch(r'/fcp_(?:ci_[a-z0-9_]+|local_[a-z0-9_]+|acceptance(?:_[a-z0-9_]+)?)',parsed.path):
        raise BuildError('Use a dedicated fcp_ci_*, fcp_local_* or fcp_acceptance test database')
    if not local(values.get('FCP_REDIS_HOST','127.0.0.1')):
        raise BuildError('Build tests refuse a non-loopback Redis endpoint')
    if not re.fullmatch(r'[A-Za-z0-9_-]{8,80}',values['FCP_REDIS_PREFIX']):
        raise BuildError('Use a unique explicit test Redis prefix of 8..80 safe characters')
    if existing_redis_no_password:
        if values.get('FCP_REDIS_PASSWORD') != '':raise BuildError('Existing no-password Redis mode requires an explicitly empty password')
        if not re.fullmatch(r'fcp_accept_[a-f0-9]{32}',values['FCP_REDIS_PREFIX']):
            raise BuildError('Shared Redis tests require a fresh fcp_accept_<32 hex UUID> namespace')
        if not values.get('FCP_REDIS_PORT','').isdigit() or not 1<=int(values['FCP_REDIS_PORT'])<=65535:
            raise BuildError('Shared Redis test port must be explicit')
    return path.resolve()

def bundle_runtime(destination: Path) -> list[str]:
    # Do not ship interpreter caches produced by local verification; hash only distributable sources.
    source=ROOT/'runtime'
    for path in sorted(source.rglob('*')):
        if path.is_symlink():raise BuildError(f'Refusing runtime symlink: {path}')
        relative=path.relative_to(source)
        if '__pycache__' in relative.parts or path.suffix in {'.pyc','.pyo'}:continue
        if path.is_file():
            target=destination/relative
            if target.exists():raise BuildError(f'Refusing to overwrite runtime file: {target}')
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(path,target)
    lines=[]
    for path in sorted(destination.rglob('*')):
        if path.is_file(): lines.append(sha(path.read_bytes())+'  '+path.relative_to(destination).as_posix())
    (destination/'bundle.sha256').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return lines

def redact_auth_failure_log(backend: Path) -> None:
    path=backend/'ruoyi-common/ruoyi-common-satoken/src/main/java/org/dromara/common/satoken/handler/SaTokenExceptionHandler.java'
    if sha(path.read_bytes())!='d19418bbc67c7d97e176ffa22f18f66752d69426894bf085a56def43397521f3':
        raise BuildError('Authentication handler pinned preimage changed')
    edit(path,'log.error("请求地址\'{}\',认证失败\'{}\',无法访问系统资源", requestURI, e.getMessage());',
         'log.warn("Authentication rejected uri={} exception={}", requestURI, e.getClass().getSimpleName());')

def invalidate_deleted_auth_cache(backend: Path) -> None:
    path=backend/'ruoyi-common/ruoyi-common-satoken/src/main/java/org/dromara/common/satoken/core/dao/PlusSaTokenDao.java'
    if sha(path.read_bytes()) not in {'174ce6dc2edaffe9ce2416664b80782930acb38ffb967ea666071cf802466c2a',
                                      '8f11489dbbd3ac05970376115014aade478eb0875d5b82d93a5daf527d3ab318'}:
        raise BuildError('Session DAO pinned preimage changed')
    text=path.read_text(encoding='utf-8')
    for method in ['delete','deleteObject']:
        before='    public void '+method+'(String key) {\n        RedisUtils.deleteObject(key);\n    }'
        if before not in text:
            before='    public void '+method+'(String key) {\n        if (RedisUtils.deleteObject(key)) {\n            CAFFEINE.invalidate(key);\n        }\n    }'
        after='    public void '+method+'(String key) {\n        try {\n            RedisUtils.deleteObject(key);\n        } finally {\n            CAFFEINE.invalidate(key);\n        }\n    }'
        text=replace_once(text,before,after)
    path.write_bytes(text.encode('utf-8'))

def adapt_projects(projects: dict[str,Path], env: dict[str,str]) -> dict:
    backend,admin,teacher=(projects[n] for n in ['backend','admin','teacher'])
    build_id=uuid.uuid4().hex
    # Each edit has a verified pinned-blob precondition, before *any* project edits.
    for name,project in projects.items():
        for path,blob in BLOBS[name].items(): check_blob(project/path,blob)
    apply_generator_templates(backend)
    apply_native_trace_patch(backend)
    redact_auth_failure_log(backend)
    invalidate_deleted_auth_cache(backend)
    apply_compatibility_patch(admin,ROOT/'integration/plus-ui/compat-5.6',PINS['admin']['commit'])
    apply_branding(projects)
    java=backend/'ruoyi-admin/src/main/java'
    for tree in ['core','business','host']:
        copy_files(ROOT/tree/'src/main/java',java)
    copy_files(ROOT/'integration/ruoyi/src/main/java',java)
    copy_files(ROOT/'integration/ruoyi/src/test/java',backend/'ruoyi-admin/src/test/java')
    resources=backend/'ruoyi-admin/src/main/resources'
    copy_core_resources(ROOT, resources)
    copy_files(ROOT/'integration/ruoyi/src/main/resources', resources)
    if (resources/'application-fcp.yml').exists(): raise BuildError('fcp profile already exists')
    shutil.copy2(ROOT/'config/application-fcp.yml',resources/'application-fcp.yml')
    edit(resources/'application.yml','    active: @profiles.active@', '    active: @profiles.active@,fcp')
    bundle_runtime(resources/'fcp/runtime')
    edit(backend/'ruoyi-admin/pom.xml','                <version>${spring-boot.version}</version>',
         '                <version>${spring-boot.version}</version>\n                <configuration><mainClass>org.dromara.DromaraApplication</mainClass></configuration>')
    index=java/'org/dromara/web/controller/IndexController.java'
    edit(index,'import org.springframework.web.bind.annotation.RestController;',
         'import org.springframework.stereotype.Controller;')
    edit(index,'@RestController','@Controller')
    edit(index,'return StringUtils.format("欢迎使用{}后台管理框架，请通过前端地址访问。", SpringUtils.getApplicationName());',
         'return "redirect:/admin/";')
    for name,project,overlay in [('admin',admin,'plus-ui'),('teacher',teacher,'soybean')]:
        copy_files(ROOT/f'integration/{overlay}/src', project/'src')
        if name == 'admin': apply_generator_frontend(project)
        for file in ['trace.ts','release-session.ts','session.ts','session-sync.ts','env.d.ts','useIngestion.ts','IngestionWorkspace.vue','usePlugins.ts','PluginWorkspace.vue','usePluginManager.ts','PluginManager.vue','useVersionManager.ts','VersionManager.vue','pluginReleaseManager.ts','PluginReleaseManager.vue']:
            target=project/'src/fcp'/file
            if target.exists(): raise BuildError('FCP session module already present')
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(ROOT/'integration/frontend-shared'/file,target)
        copy_files(ROOT/'integration/frontend-runtime', project/'src/fcp/runtime')
        apply_frontend_trace(project,name)
        copy_files(ROOT/'integration/frontend-release', project/'src/fcp/release')
        edit(project/'vite.config.ts','base: env.VITE_APP_CONTEXT_PATH,' if name=='admin' else 'base: viteEnv.VITE_BASE_URL,',
             "base: command === 'build' ? './' : env.VITE_APP_CONTEXT_PATH," if name=='admin' else "base: configEnv.command === 'build' ? './' : viteEnv.VITE_BASE_URL,")
        router=project/'src/router/index.ts'
        router_source=router.read_text(encoding='utf-8')
        router_source="import { fcpPluginRoutes } from '@/views/fcp/plugins/routes';\n"+router_source
        router_source=replace_once(router_source, 'routes: constantRoutes,' if name=='admin' else 'routes: createBuiltinVueRoutes()',
                                   'routes: [...constantRoutes, ...fcpPluginRoutes],' if name=='admin' else 'routes: [...createBuiltinVueRoutes(), ...fcpPluginRoutes]')
        router.write_text(router_source,encoding='utf-8')
        main=project/'src/main.ts'
        if not main.is_file(): raise BuildError(f'{name} main.ts missing')
        main.write_text("import '@/fcp/session-sync';\n"+main.read_text(encoding='utf-8'),encoding='utf-8')
    edit(admin/'src/utils/auth.ts',"const TokenKey = 'Admin-Token';","const TokenKey = 'FCP-Host-Token';")
    perm=admin/'src/permission.ts'
    s=perm.read_text(encoding='utf-8')
    s="import { ensureShell } from '@/fcp/session';\n"+s
    s=replace_once(s,'          isRelogin.show = false;', '''          isRelogin.show = false;
          try {
            if (await ensureShell('admin', import.meta.env.VITE_APP_CLIENT_ID || '', '/admin' + to.path)) { next(false); NProgress.done(); return; }
          } catch (error) {
            ElMessage.error(error instanceof Error ? error.message : '界面授权失败');
            next(false); NProgress.done(); return;
          }''')
    perm.write_text(s,encoding='utf-8')
    edit(teacher/'src/hooks/business/download.ts',"StreamSaver.mitm = '/streamsaver/mitm.html?version=2.0.0';",
         "StreamSaver.mitm = new URL('streamsaver/mitm.html?version=2.0.0', document.baseURI).href;")
    storage=teacher/'src/utils/storage.ts'
    s="import { bridgeStorage } from '@/fcp/session';\n"+storage.read_text(encoding='utf-8')
    s=replace_once(s,"export const localStg = createStorage<StorageType.Local>('local', storagePrefix);",
                  "export const localStg = bridgeStorage(createStorage<StorageType.Local>('local', storagePrefix));")
    storage.write_text(s,encoding='utf-8')
    edit(teacher/'src/service/api/route.ts',"url: '/system/menu/getRouters'","url: '/fcp/ui/soybean-routes'")
    for language, labels in [('en-us', ('School', 'Student evaluation', 'Data ingestion', 'Configuration plugins')),
                             ('zh-cn', ('学校业务', '学生评价', '数据接入', '配置插件'))]:
        add_school_locale(teacher/'src/locales/langs'/f'{language}.ts', *labels)
    apply_generator_branding(admin)
    common=f"VITE_FCP_BUILD_ID={build_id}\nVITE_APP_CLIENT_ID='{env['FCP_CLIENT_ID']}'\nVITE_APP_RSA_PUBLIC_KEY='{env['FCP_REQUEST_PUBLIC_KEY']}'\nVITE_APP_RSA_PRIVATE_KEY='{env['FCP_RESPONSE_PRIVATE_KEY']}'\n"
    (admin/'.env.production.local').write_text(common+"VITE_APP_CONTEXT_PATH=/admin/\nVITE_APP_BASE_API=\nVITE_APP_ENCRYPT=true\nVITE_APP_SSE=true\n",encoding='utf-8')
    (teacher/'.env.prod.local').write_text(common+"VITE_BASE_URL=/teacher/\nVITE_APP_BASE_API=\nVITE_SERVICE_BASE_URL=\nVITE_APP_ENCRYPT=Y\nVITE_ROUTE_HOME=fcp_school\nVITE_AUTH_ROUTE_MODE=dynamic\nVITE_HTTP_PROXY=N\nVITE_AUTOMATICALLY_DETECT_UPDATE=N\n",encoding='utf-8')
    # Never copy the reference HTTP server or mock UI. Verify classpath resources against this marker later.
    manifest={'kind':'native-source-assembly','packageVersion':'0.6.0-dev.1','pins':PINS,
              'runtimeEntry':'org.dromara.DromaraApplication','ui':['/admin/','/teacher/'],
              'buildId':build_id,'sourceAssembly':True,'realHostVerified':False}
    (resources/'fcp/native-assembly.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    return manifest

def add_school_locale(path: Path, parent_label: str, school_label: str, ingestion_label: str='Data ingestion', plugins_label: str='Configuration plugins') -> None:
    """Keep generated FCP route keys covered by Soybean's typed locale maps."""
    if re.search(r'^\s*fcp(?:_school)?:', path.read_text(encoding='utf-8'), re.MULTILINE):
        raise BuildError('FCP route locale already exists; refusing duplicate adaptation')
    edit(path, '  route: {\n',
         "  route: {\n    fcp_releases: '系统版本与发布',\n    fcp: "+repr(parent_label)+",\n    fcp_school: "+repr(school_label)+",\n    fcp_ingestion: "+repr(ingestion_label)+",\n    fcp_plugins: "+repr(plugins_label)+",\n")

def install_frontend(project: Path, name: str, resolve_admin_lock: bool, admin_lock: Path|None=None) -> str:
    if name=='teacher':
        if not (project/'pnpm-lock.yaml').is_file(): raise BuildError('Soybean lockfile missing')
        run(['pnpm','install','--frozen-lockfile'],cwd=project)
        return 'upstream-pnpm-lock'
    if admin_lock:
        if not admin_lock.is_file() or admin_lock.is_symlink(): raise BuildError('Invalid saved admin lockfile')
        document=json.loads(admin_lock.read_text(encoding='utf-8'))
        if document.get('name') != json.loads((project/'package.json').read_text())['name'] or document.get('lockfileVersion') not in (2,3):
            raise BuildError('Saved admin lockfile does not identify the expected project')
        target=project/'package-lock.json'
        if target.exists(): raise BuildError('Refusing to overwrite an upstream lockfile')
        shutil.copy2(admin_lock,target)
    if not (project/'package-lock.json').is_file():
        if not resolve_admin_lock:
            raise BuildError('Pinned plus-ui has no npm lock. Use --resolve-admin-lock once; preserve the generated lock for subsequent builds.')
        run(['npm','install','--package-lock-only','--ignore-scripts','--no-audit','--no-fund'],cwd=project)
    run(['npm','ci','--no-audit','--no-fund'],cwd=project)
    return 'restored-npm-lock' if admin_lock else 'generated-npm-lock'

def validate_dist(dist: Path, shell: str) -> None:
    index=dist/'index.html'
    if not index.is_file(): raise BuildError(f'Missing real {shell} production index')
    html=index.read_text(encoding='utf-8')
    if not re.search(r'(?:src|href)=[\"\']\./',html) or not list(dist.rglob('*.js')):
        raise BuildError(f'{shell} asset base is invalid')
    if 'localhost:' in html or '/src/main.ts' in html: raise BuildError('Development index cannot be packaged')

def collect_java_tests(project: Path) -> dict:
    """Collect actual Surefire/Failsafe reports; a successful build alone is insufficient."""
    import xml.etree.ElementTree as ET
    totals={'tests':0,'failures':0,'errors':0,'skipped':0,'reports':0}
    for pattern in ['**/target/surefire-reports/TEST-*.xml','**/target/failsafe-reports/TEST-*.xml']:
        for path in project.glob(pattern):
            document=ET.parse(path).getroot()
            suites=[document] if document.tag=='testsuite' else list(document.findall('testsuite'))
            for suite in suites:
                for key in ['tests','failures','errors','skipped']:
                    totals[key]+=int(suite.attrib.get(key,0))
                totals['reports']+=1
    totals['selection']='upstream prod-tag selection with exclude-tag omitted; not the full upstream test inventory'
    totals['status']='PASS' if totals['tests']>0 and not any(totals[k] for k in ['failures','errors','skipped']) else 'NOT_FULLY_VERIFIED'
    return totals

def build(projects: dict[str,Path], work: Path, resolve_admin_lock: bool, admin_lock: Path|None=None, test_runtime_config: Path|None=None, *, existing_redis_no_password: bool=False) -> dict:
    packaged_manifest=json.loads((projects['backend']/'ruoyi-admin/src/main/resources/fcp/native-assembly.json').read_text())
    evidence={'buildId':packaged_manifest['buildId'],'nativeBuild':'RUNNING','runtimeVerification':'NOT_RUN','frontend':{}}
    if existing_redis_no_password:evidence['localTestOnlyReasons']=['Existing loopback Redis has no password; connection-only local functional acceptance, not a production security baseline']
    resources=projects['backend']/'ruoyi-admin/src/main/resources'
    output=work/'release'; output.mkdir(exist_ok=True)
    for name in ['admin','teacher']:
        project=projects[name]
        policy=install_frontend(project,name,resolve_admin_lock,admin_lock)
        # Preserve resolved inputs even if a later type check or backend build fails.
        lock=project/('package-lock.json' if name=='admin' else 'pnpm-lock.yaml')
        shutil.copy2(lock,output/f'{name}-{lock.name}')
        # Vite first generates auto-import/Elegant files; then the real project's type checker runs.
        run(['npm','run','build:prod'] if name=='admin' else ['pnpm','run','build'],cwd=project,clean_vite=True)
        run(['npm','exec','--','vue-tsc','--noEmit'] if name=='admin' else ['pnpm','run','typecheck'],cwd=project,clean_vite=True)
        run(['node',ROOT/'tests/frontend-runtime/verify-field-label.mjs',project,name],cwd=ROOT,clean_vite=True)
        if name == 'admin':
            run(['node',ROOT/'tests/generator/ui-state.test.mjs',project/'node_modules/@vue/compiler-sfc',project/'node_modules/typescript'],cwd=ROOT)
            run(['node',ROOT/'tests/generator/template-contract.test.mjs',project/'node_modules/typescript'],cwd=ROOT)
        dist=project/'dist'; validate_dist(dist,name)
        from ui_release_bundle import build_bundle
        bundle=build_bundle(dist,output/'ui-bundles',name)
        copy_files(dist,resources/'static'/name)
        for suffix in ('.gz','.br'):
            (resources/'static'/name/('index.html'+suffix)).unlink(missing_ok=True)
        original=(dist/'index.html').read_text(encoding='utf-8')
        if '<base' in original.lower() or '<head>' not in original: raise BuildError('Unexpected UI base template')
        embedded=original.replace('<head>',f'<head><base href="/{name}/"><meta name="fcp-ui-bundle" content="embedded">',1)
        (resources/'static'/name/'embedded-index.html').write_text(embedded,encoding='utf-8')
        # Client ID comes from the already-validated build env, not a password or user credential.
        build_env=(project/('.env.production.local' if name=='admin' else '.env.prod.local')).read_text()
        match=re.search(r"(?m)^VITE_APP_CLIENT_ID='([^']+)'$",build_env)
        if not match: raise BuildError('Missing fixed native client ID')
        launcher=(ROOT/'integration/frontend-shared/ui-launcher.mjs').read_text().replace('__FCP_SHELL__',json.dumps(name)).replace('__FCP_CLIENT_ID__',json.dumps(match[1]))
        (resources/'static'/name/'release-loader.mjs').write_text(launcher,encoding='utf-8')
        # Fixed same-origin module: identical response-only diagnostics as the dev host.
        shutil.copy2(ROOT/'development-host/src/main/resources/dev-ui/trace.js',resources/'static'/name/'release-trace.mjs')
        (resources/'static'/name/'index.html').write_text(f'<!doctype html><html><head><meta charset="UTF-8"><title>智慧云校</title></head><body><p>正在校验系统版本…</p><script type="module" src="/{name}/release-loader.mjs"></script></body></html>',encoding='utf-8')
        lock=project/('package-lock.json' if name=='admin' else 'pnpm-lock.yaml')
        evidence['frontend'][name]={'lockPolicy':policy,'lockSha256':sha(lock.read_bytes()),'typecheck':'PASS','productionBuild':'PASS','uiBundle':bundle['manifest']}
    test_runtime_config=validate_test_runtime(test_runtime_config,existing_redis_no_password=existing_redis_no_password)
    # WarmFlow retains a static application-context reference. Separate test-class
    # JVMs keep real random-port integration and upstream contexts independent.
    run(['mvn','-B','-Pprod','-pl','ruoyi-admin','-am','-DskipTests=false','-Dmaven.test.skip=false','-DreuseForks=false','verify'],
        cwd=projects['backend'],timeout=2400,clean_runtime=True,
        env_overrides={'SPRING_CONFIG_ADDITIONAL_LOCATION':test_runtime_config.as_uri(),'SPRING_PROFILES_ACTIVE':'prod,fcp'})
    jar=projects['backend']/'ruoyi-admin/target/ruoyi-admin.jar'
    with zipfile.ZipFile(jar) as archive:
        names=set(archive.namelist())
        required={'BOOT-INF/classes/org/dromara/DromaraApplication.class',
                  'BOOT-INF/classes/org/dromara/fcp/school/FcpSchoolController.class',
                  'BOOT-INF/classes/static/admin/index.html',
                  'BOOT-INF/classes/static/teacher/index.html',
                  'BOOT-INF/classes/static/admin/release-trace.mjs',
                  'BOOT-INF/classes/static/teacher/release-trace.mjs',
                  'BOOT-INF/classes/fcp/runtime/plugins/statistics.py'}
        if not required<=names: raise BuildError('Incomplete native application JAR')
        manifest=archive.read('META-INF/MANIFEST.MF').decode()
        if 'Start-Class: org.dromara.DromaraApplication' not in manifest: raise BuildError('Unexpected main application')
    output=work/'release'; output.mkdir(exist_ok=True)
    shutil.copy2(jar,output/'ruoyi-admin.jar')
    for name,project in projects.items():
        for filename in ['LICENSE','LICENSE.md']:
            if (project/filename).is_file(): shutil.copy2(project/filename,output/f'LICENSE-{name}.txt'); break
    for name in ['admin','teacher']:
        lock=projects[name]/('package-lock.json' if name=='admin' else 'pnpm-lock.yaml')
        shutil.copy2(lock,output/f'{name}-{lock.name}')
    evidence.update(nativeBuild='PASS',javaUnitTests=collect_java_tests(projects['backend']),jarSha256=sha(jar.read_bytes()),
                    applicationGate='BLOCKED_UNTIL_REAL_API_AND_BROWSER_VERIFICATION')
    (output/'BUILD-EVIDENCE.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return evidence

def build_ui_variant(projects: dict[str,Path], work: Path, label: str='candidate') -> dict:
    """Rebuild both actual locked Vue projects with a distinct compiled build identity.
    Reuses installed locked dependencies; does not replace the already-built host JAR.
    This is an integration variant, not a claim of different product functionality.
    """
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,31}',label): raise BuildError('Unsafe UI variant label')
    from ui_release_bundle import build_bundle
    output=work/'release'
    original=json.loads((output/'BUILD-EVIDENCE.json').read_text())
    if original.get('nativeBuild')!='PASS': raise BuildError('Build a genuine native baseline before UI variant')
    build_id=original['buildId']+'-'+label
    evidence={'buildId':build_id,'baselineBuildId':original['buildId'],'scope':'Actual pinned Vue rebuild, same feature source with changed compiled build identity','frontend':{}}
    for name in ['admin','teacher']:
        project=projects[name];env=project/('.env.production.local' if name=='admin' else '.env.prod.local')
        before=env.read_bytes()
        try:
            text=before.decode('utf-8')
            if len(re.findall(r'(?m)^VITE_FCP_BUILD_ID=.*$',text))!=1: raise BuildError('Missing UI build identity')
            env.write_text(re.sub(r'(?m)^VITE_FCP_BUILD_ID=.*$','VITE_FCP_BUILD_ID='+build_id,text),encoding='utf-8')
            run(['npm','run','build:prod'] if name=='admin' else ['pnpm','run','build'],cwd=project,clean_vite=True)
            run(['npm','exec','--','vue-tsc','--noEmit'] if name=='admin' else ['pnpm','run','typecheck'],cwd=project,clean_vite=True)
            validate_dist(project/'dist',name)
            bundle=build_bundle(project/'dist',output/'ui-bundles',name)
            if bundle['manifest']['digest']==original['frontend'][name]['uiBundle']['digest']: raise BuildError('UI variant did not change compiled artifact')
            evidence['frontend'][name]={'productionBuild':'PASS','typecheck':'PASS','uiBundle':bundle['manifest']}
        finally:
            env.write_bytes(before)
    (output/('UI-VARIANT-'+label+'.json')).write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return evidence

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['doctor','assemble','build'])
    parser.add_argument('--work',type=Path,default=ROOT/'work')
    parser.add_argument('--local-sources',type=Path,help='Optional parent containing three genuine Git repos named backend/admin/teacher')
    parser.add_argument('--build-env',type=Path)
    parser.add_argument('--test-runtime-config',type=Path,help='Private FCP properties for already initialized isolated MySQL/Redis; required for build')
    parser.add_argument('--existing-loopback-redis-no-password',action='store_true',help='Explicit local-test-only connection to an existing loopback Redis with no password; never changes server config or production eligibility')
    parser.add_argument('--resolve-admin-lock',action='store_true')
    parser.add_argument('--admin-lock',type=Path,help='Restore a reviewed package-lock.json from an earlier native build')
    args=parser.parse_args()
    report={'startedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'status':'BLOCKED'}
    try:
        if args.command=='doctor':
            report=doctor(); print(json.dumps(report,ensure_ascii=False,indent=2)); return 0 if report['status']=='READY' else 2
        env=read_build_env(args.build_env)
        if args.command=='build':validate_test_runtime(args.test_runtime_config,existing_redis_no_password=args.existing_loopback_redis_no_password)
        work=args.work.resolve()
        # Refuse package/source parent and sensitive roots as a disposable workspace.
        if work==ROOT or work in ROOT.parents or work==Path.home(): raise BuildError('Unsafe workspace location')
        projects=fetch(work,args.local_sources)
        assembly=adapt_projects(projects,env)
        report.update(status='ASSEMBLED_NOT_BUILT',assembly=assembly)
        if args.command=='build': report.update(build(projects,work,args.resolve_admin_lock,args.admin_lock,args.test_runtime_config,existing_redis_no_password=args.existing_loopback_redis_no_password)); report['status']='BUILT_NOT_RUNTIME_VERIFIED'
        print(json.dumps(report,ensure_ascii=False,indent=2)); return 0
    except (BuildError,OSError,ValueError,subprocess.TimeoutExpired,zipfile.BadZipFile) as exc:
        report['error']=str(exc); print(json.dumps(report,ensure_ascii=False,indent=2),file=sys.stderr); return 2
    finally:
        out=ROOT/'reports';out.mkdir(exist_ok=True)
        (out/f'{args.command}-last.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__': raise SystemExit(main())
