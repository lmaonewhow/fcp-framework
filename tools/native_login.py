"""Native acceptance login completion; no credential generation or response substitution.

Storage remains in memory. A context snapshot replaces evaluation in a document
which can be destroyed by the real admin-to-teacher post-login redirect.
"""
from __future__ import annotations
import re
from native_client import require_loopback


def native_token(state, base):
    require_loopback(base)
    origin = base.rstrip('/')
    tokens = [entry.get('value') for item in state.get('origins', [])
              if item.get('origin') == origin for entry in item.get('localStorage', [])
              if entry.get('name') == 'FCP-Host-Token']
    if len(tokens) != 1 or not isinstance(tokens[0], str) or not tokens[0]:
        raise RuntimeError('Expected exactly one genuine native token in browser-context state')
    return tokens[0]


def authenticated_storage_state(page, context, base, workspace, timeout_ms=30000):
    """Wait for native routed UI, then return one in-memory context state/token pair.

    No page.evaluate, token injection, arbitrary sleep, retry or cookie/token log.
    A wrong destination or failed native shell bootstrap remains a test failure.
    """
    require_loopback(base)
    markers = {'ingestion':'fcp-ingestion', 'plugins':'fcp-plugin-workspace'}
    if workspace not in markers:
        raise ValueError('Only explicit native acceptance workspaces are allowed')
    destination = re.compile(re.escape(base.rstrip('/'))+r'/(?:admin|teacher)/fcp/'+workspace+r'/?(?:[?#].*)?$')
    page.wait_for_url(destination, wait_until='domcontentloaded', timeout=timeout_ms)
    page.get_by_test_id(markers[workspace]).wait_for(state='visible',timeout=timeout_ms)
    state = context.storage_state()
    return state, native_token(state, base)
