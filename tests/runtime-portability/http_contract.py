"""A35: one unchanged business-semantic suite, executed through two real HTTP adapters.

Authentication setup and grant persistence belong to the host adapter. Missing-login responses
are deliberately checked as denial rather than equating Sa-Token and development auth codes.
All authenticated failures below assert the same data.fcpCode, not a host-specific message/status.
"""
from __future__ import annotations
import hashlib
import io
import json
import re
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PLUGINS = ('portability.python', 'portability.node')
# Explicit diagnostic vocabulary, never infer safe text from its character shape.
# New semantic codes require review before they can appear in shared reports.
SAFE_FCP_CODES = frozenset({
    'HOST_IMPLEMENTATION_DEPENDENCY', 'NOT_INSTALLED', 'CAPABILITY_NOT_GRANTED',
    'INVALID_ARGUMENT', 'REVISION_CONFLICT', 'CONFIRMATION_REQUIRED',
    'INVALID_INPUT', 'VERSION_CONFLICT', 'PERMISSION_DENIED', 'PLUGIN_DISABLED',
    'UNAUTHENTICATED', 'OPERATION_PENDING', 'VERSION_OPERATION_PENDING',
    'GRANT_REVISION_CONFLICT', 'NOT_READY', 'RESTART_BACKOFF', 'DATA_ACCESS',
})


def response_evidence(status, reply):
    """Allowlisted error metadata only: never response messages, input or identity."""
    reply = reply if isinstance(reply, dict) else {}
    data = reply.get('data')
    data = data if isinstance(data, dict) else {}
    code = reply.get('code')
    fcp_code = data.get('fcpCode')
    trace = data.get('traceId')
    result = dict(httpStatus=status if type(status) is int and 100 <= status <= 599 else None,
                  code=int(code) if type(code) in (str, int) and re.fullmatch(r'[1-5][0-9]{2}', str(code)) else None,
                  fcpCode=fcp_code if isinstance(fcp_code, str) and fcp_code in SAFE_FCP_CODES else None)
    if isinstance(trace, str) and re.fullmatch(r'[0-9a-f]{32}', trace) and trace != '0'*32:
        result['traceId'] = trace
    return result


class ContractAssertion(AssertionError):
    def __init__(self, name, checks, evidence=None):
        self.report = dict(status='FAIL', contract='A35 shared HTTP semantics v1',
                           checks=list(checks), failedCheck=name)
        if evidence is not None:
            self.report['response'] = evidence
        suffix = '' if evidence is None else ' '+json.dumps(evidence, sort_keys=True)
        super().__init__('A35 shared HTTP contract: '+name+suffix)


def exported(plugin):
    return 'e' + hashlib.sha256(json.dumps([plugin, 'echo'], separators=(',', ':')).encode()).hexdigest()[:40]


def package_bytes(language):
    if language not in ('python', 'node'):
        raise ValueError('Python and Node fixtures only')
    entry = 'runtime.py' if language == 'python' else 'runtime.mjs'
    capability = dict(id='echo', version='1.0.0', permission='echo', sideEffects=False,
                      idempotent=True, timeoutMillis=10000,
                      inputSchema=dict(type='object', properties=dict(message=dict(type='string'), dependency=dict(type='boolean')), additionalProperties=False),
                      outputSchema=dict(type='object'))
    manifest = dict(manifestVersion=1, pluginId='portability.'+language, version='1.0.0',
                    publisher='test', contractVersion='1.0', runtime=dict(type='local', entry=entry, argv=[]),
                    provides=[capability], requires=[capability] if language == 'node' else [],
                    dependencies=[dict(pluginId=PLUGINS[0], versionRange='1.0.0')] if language == 'node' else [],
                    ui=dict(contractVersion='1.0', pages=[dict(id='contract', title='Shared HTTP contract',
                        permissions=['echo'], capabilities=['echo'], components=['button@1'],
                        content=dict(type='button', text='Echo', capabilityId='echo'))], slots=[]))
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in [('plugin.json', json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()),
                           (entry, (ROOT/'tests/runtime-native/plugins'/entry).read_bytes())]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return output.getvalue(), manifest


def run_http_contract(adapter):
    """adapter: request, upload_request, set_grant, tenant_id, principal_id, auth_transport.

    Every check sends HTTP to the caller's actual host. Never supplies a token, substitutes
    responses, changes auth implementation, or grants platform permissions implicitly.
    """
    checks = []
    def check(name, condition, evidence=None):
        if not condition:
            raise ContractAssertion(name, checks, evidence)
        checks.append(dict(name=name, status='PASS'))
    def data(path, body=None):
        status, reply = adapter.request(path, body)
        check('success '+path, status < 400 and str(reply.get('code')) == '200')
        return reply['data']
    def error(name, expected, path, body):
        status, reply = adapter.request(path, body)
        evidence = response_evidence(status, reply)
        check(name, evidence['httpStatus'] is not None and status >= 400 and evidence['fcpCode'] == expected, evidence)
    def current():
        return data('/fcp/plugins')['revision']
    def plan(plugin, action):
        return data('/fcp/plugins/'+plugin+'/plans', dict(action=action, expectedRevision=current()))
    def confirm(proposal, key):
        return data('/fcp/plans/'+proposal['planId']+'/confirm', dict(digest=proposal['digest'],
                    affectedPlugins=proposal['affectedPlugins'], idempotencyKey=key))
    def catalog():
        return data('/fcp/ui/catalog')['catalog']
    def pages(value):
        return {page['pluginId'] for page in value['pages']} & set(PLUGINS)

    status, reply = adapter.request('/fcp/plugins', token=False)
    check('authentication required using '+adapter.auth_transport,
          status >= 400 or str(reply.get('code')) != '200')
    archives = {language: package_bytes(language)[0] for language in ('python', 'node')}
    def install(language):
        archive = archives[language]; digest = hashlib.sha256(archive).hexdigest()
        status, result = adapter.upload_request(archive, digest, 'a35-install-'+language, current())
        check(language+' install success', status < 400 and str(result.get('code')) == '200')
        check(language+' installed identity and digest', result['data']['state'] == 'INSTALLED'
              and result['data']['pluginId'] == 'portability.'+language and result['data']['digest'] == digest)
        return result['data']

    # Invalid installation is rejected with the same semantic key before any publication.
    forbidden = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(archives['node'])) as source, zipfile.ZipFile(forbidden, 'w', zipfile.ZIP_DEFLATED) as target:
        for name in source.namelist():
            target.writestr(name, source.read(name))
        target.writestr('requirements.txt', 'ruoyi-system==5.6.2\n')
    revision = current(); invalid_archive = forbidden.getvalue()
    status, rejected = adapter.upload_request(invalid_archive, hashlib.sha256(invalid_archive).hexdigest(), 'a35-forbidden-install', revision)
    check('install dependency policy semantic code', status >= 400 and rejected.get('data', {}).get('fcpCode') == 'HOST_IMPLEMENTATION_DEPENDENCY')
    check('rejected install does not publish lifecycle state', current() == revision)
    # Same dependency error is exercised before any platform capability approval.
    install('node')
    error('missing dependency semantic code', 'NOT_INSTALLED', '/fcp/plugins/'+PLUGINS[1]+'/plans',
          dict(action='ENABLE', expectedRevision=current()))
    install('python')
    status, replay = adapter.upload_request(archives['python'], hashlib.sha256(archives['python']).hexdigest(), 'a35-install-python', 0)
    check('idempotent package receipt', status < 400 and replay.get('data', {}).get('replayed') is True)
    check('installed but disabled catalog empty', not pages(catalog()))
    error('enable requires explicit capability approval', 'CAPABILITY_NOT_GRANTED', '/fcp/plugins/'+PLUGINS[1]+'/plans',
          dict(action='ENABLE', expectedRevision=current()))
    for plugin in PLUGINS:
        adapter.set_grant(plugin, True)
    error('invalid action semantic code', 'INVALID_ARGUMENT', '/fcp/plugins/'+PLUGINS[0]+'/plans',
          dict(action='INVALID', expectedRevision=current()))
    error('stale revision semantic code', 'REVISION_CONFLICT', '/fcp/plugins/'+PLUGINS[0]+'/plans',
          dict(action='ENABLE', expectedRevision=current()-1))
    proposal = plan(PLUGINS[1], 'ENABLE')
    check('dependency closure contains both plugins', set(proposal['affectedPlugins']) == set(PLUGINS))
    error('partial closure rejected', 'CONFIRMATION_REQUIRED', '/fcp/plans/'+proposal['planId']+'/confirm',
          dict(digest=proposal['digest'], affectedPlugins=[PLUGINS[1]], idempotencyKey='a35-partial'))
    operation = confirm(proposal, 'a35-enable')
    check('enable completed atomically', operation['outcome'] == 'APPLIED' and operation['phase'] == 'COMPLETE')
    check('confirmation replay returns same operation', confirm(proposal, 'a35-enable')['operationId'] == operation['operationId'])
    published = catalog()
    check('both plugin pages published', pages(published) == set(PLUGINS))
    for language in ('python', 'node'):
        result = data('/fcp/capabilities/'+exported('portability.'+language)+'/invoke',
                      dict(input=dict(message='same-contract'), expectedRevision=published['revision'], idempotencyKey='a35-echo-'+language))
        check(language+' invoke preserves server identity and actual process', result.get('language') == language
              and result.get('message') == 'same-contract' and result.get('tenantId') == adapter.tenant_id
              and str(result.get('principalId')) == adapter.principal_id and isinstance(result.get('pluginPid'), int))
    callback = data('/fcp/capabilities/'+exported(PLUGINS[1])+'/invoke',
                    dict(input=dict(message='dependency', dependency=True), expectedRevision=published['revision'], idempotencyKey='a35-dependency'))
    check('approved dependency traverses capability engine', callback.get('language') == 'python' and callback.get('callerLanguage') == 'node')
    error('invalid input semantic code', 'INVALID_INPUT', '/fcp/capabilities/'+exported(PLUGINS[0])+'/invoke',
          dict(input=dict(message=123), expectedRevision=published['revision']))
    error('stale catalog semantic code', 'VERSION_CONFLICT', '/fcp/capabilities/'+exported(PLUGINS[0])+'/invoke',
          dict(input={}, expectedRevision=published['revision']-1))
    # Revoke a provider with no requires. Revoking node's required echo approval
    # also invalidates enablement: native recovery correctly cuts off tenant admissions.
    # This check isolates live invocation authorization, not platform enablement failure.
    adapter.set_grant(PLUGINS[0], False)
    revoked = catalog()
    error('live permission revocation semantic code', 'PERMISSION_DENIED', '/fcp/capabilities/'+exported(PLUGINS[0])+'/invoke',
          dict(input={}, expectedRevision=revoked['revision']))
    check('revocation removes only unauthorized page', pages(revoked) == {PLUGINS[1]})
    disable = plan(PLUGINS[0], 'DISABLE')
    check('disable reports dependent impact', set(disable['affectedPlugins']) == set(PLUGINS))
    check('disable complete', confirm(disable, 'a35-disable')['outcome'] == 'APPLIED')
    disabled = catalog()
    check('disabled catalog no longer projects pages', not pages(disabled))
    error('disabled invocation semantic code', 'PLUGIN_DISABLED', '/fcp/capabilities/'+exported(PLUGINS[0])+'/invoke',
          dict(input={}, expectedRevision=disabled['revision']))
    for plugin in PLUGINS:
        check(plugin+' durable disabled intent', data('/fcp/plugins/'+plugin)['desired'] == 'DISABLED')
    return dict(status='PASS', contract='A35 shared HTTP semantics v1', authentication=adapter.auth_transport,
                checks=checks, packages={language: hashlib.sha256(archive).hexdigest() for language, archive in archives.items()})
