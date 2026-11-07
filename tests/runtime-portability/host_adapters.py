"""Only authentication transport/grant persistence differs between the two test hosts."""
import json
import urllib.error
import urllib.request


class NativeAdapter:
    auth_transport = 'genuine RuoYi browser login + Sa-Token bearer + clientid; MySQL grants'

    def __init__(self, client, set_grant, tenant_id, principal_id):
        self.client, self._set_grant = client, set_grant
        self.tenant_id, self.principal_id = tenant_id, principal_id

    def request(self, path, body=None, token=True):
        return self.client.request(path, body, token=token)

    def set_grant(self, plugin, allow):
        self._set_grant(plugin, allow)

    def upload_request(self, package, digest, key, revision):
        headers = {'Content-Type': 'application/octet-stream', 'clientid': self.client.client_id,
                   'Authorization': 'Bearer '+self.client.token, 'X-Fcp-Digest': digest,
                   'Idempotency-Key': key, 'X-Fcp-Revision': str(revision)}
        request = urllib.request.Request(self.client.base+'/fcp/packages', data=package, headers=headers, method='POST')
        try:
            with self.client.opener.open(request, timeout=30) as response:
                return response.status, json.loads(response.read(2*1024*1024))
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read(2*1024*1024))


class DevelopmentAdapter(NativeAdapter):
    auth_transport = 'explicit loopback development identity file; never native authentication evidence'
