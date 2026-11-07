"""A11 synthetic fixture only. Every business read/write uses the host storage callback."""
import json
import os
import sys
import uuid

VERSION = '__PACKAGE_VERSION__'
PROCESS_TOKEN = str(uuid.uuid4())


def storage(operation, value):
    request = {'jsonrpc': '2.0', 'id': 'storage-' + str(uuid.uuid4()), 'method': 'fcp.call',
               'params': {'dependency': 'host.storage.' + operation, 'operation': 'invoke', 'input': value}}
    print(json.dumps(request), flush=True)
    response = json.loads(sys.stdin.readline())
    if response.get('id') != request['id'] or 'error' in response:
        raise ValueError('STORAGE_CALLBACK_REJECTED')
    return response['result']


def invoke(capability, value, context):
    base = {'collection': 'entries', 'dataScope': 'class:retention-a'}
    if capability == 'retention.read':
        result = {'active': storage('list', dict(base, equals={}, limit=100)),
                  'deletedLookup': storage('get', dict(base, id='deleted'))}
    elif capability == 'retention.write':
        action = value['action']
        if action not in ('put', 'delete') or value['id'] not in ('active', 'deleted'):
            raise ValueError('FIXTURE_ACTION_REJECTED')
        payload = dict(base, id=value['id'], expectedRevision=value['expectedRevision'], idempotencyKey=value['receiptKey'])
        if action == 'put':
            payload['body'] = {'value': value['value']}
        result = storage(action, payload)
    else:
        raise ValueError('FIXTURE_CAPABILITY_REJECTED')
    return {'result': result, 'pluginPid': os.getpid(), 'processToken': PROCESS_TOKEN,
            'packageVersion': VERSION, 'tenantId': context['tenantId'], 'principalId': context['principal']['id']}


for line in sys.stdin:
    request = json.loads(line)
    try:
        method, params = request['method'], request['params']
        if method == 'handshake':
            result = params
        elif method in ('ready', 'health', 'drain', 'cancel', 'shutdown'):
            result = {'ok': True}
        elif method == 'invoke':
            result = invoke(params['capability'], params['input'], params['context'])
        else:
            raise ValueError('FIXTURE_METHOD_REJECTED')
        response = {'jsonrpc': '2.0', 'id': request['id'], 'result': result}
    except Exception:
        response = {'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32000, 'message': 'FIXTURE_REQUEST_REJECTED'}}
    print(json.dumps(response), flush=True)
    if request['method'] == 'shutdown':
        break
