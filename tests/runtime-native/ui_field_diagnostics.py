"""Failure-only diagnostics for the synthetic plugin Message field, never full page/state."""
import json
import re
from pathlib import Path


def field_accessible_names(snapshot):
    # Keep only role/name pairs. Never retain editable values or continuation text.
    result = []
    for line in snapshot.splitlines():
        match = re.match(r'\s*-\s*(textbox|spinbutton|combobox)(?: "([^"]*)")?(?=[: \[]|$)',line)
        if match:
            result.append({'role':match.group(1),'name':match.group(2) or ''})
    return result


def capture_field_diagnostics(page, output):
    field = page.get_by_test_id('plugin-page').locator('.fcp-plugin-field').first
    # No values, cookies, storage, headers, surrounding page text or raw outerHTML.
    structure = field.evaluate('''root => Array.from(root.querySelectorAll('*')).map(element => ({
      tag: element.tagName.toLowerCase(),
      attributes: Object.fromEntries(['role','type','aria-label','aria-labelledby','id'].filter(name => element.hasAttribute(name)).map(name => [name,element.getAttribute(name)]))
    }))''')
    snapshot = field.aria_snapshot(timeout=3000)
    # This fixture field has fixed public label "Message". Suppress editable values.
    names = field_accessible_names(snapshot)
    Path(output).write_text(json.dumps({'scope':'Synthetic plugin Message field only; editable values omitted', 'elements':structure, 'ariaRoleNames':names},ensure_ascii=False,indent=2)+'\n')
