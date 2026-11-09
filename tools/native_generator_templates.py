"""Repair only the pinned native standard Vue generator template, before packaging.

The ZIP download and safe-apply path share this resource. Never rewrite generated
output or user workspace files. Tree/Soybean/custom templates are not patched.
"""
from pathlib import Path
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PIN = '8136a0191a2258c0e1b36a8146a1c5ebc070c139'
TARGET = 'ruoyi-modules/ruoyi-generator/src/main/resources/vm/vue/index.vue.vm'
PREIMAGE = '5d970ef3c45c6ca20b5105b49f23021b60daeb08dd3f475fcb14900300cf65b3'

EDIT_FORM = '''// Checkbox controls keep arrays; the API contract remains comma-separated strings.
type ${BusinessName}EditForm = Omit<${BusinessName}Form, never
#foreach ($column in $columns)
#if($column.htmlType == "checkbox" && ($column.insert || $column.edit))
  | '${column.javaField}'
#end
#end
> & {
#foreach ($column in $columns)
#if($column.htmlType == "checkbox" && ($column.insert || $column.edit))
  ${column.javaField}: string[];
#end
#end
};

const initFormData: ${BusinessName}EditForm = {'''
PAYLOAD = '''      const payload: ${BusinessName}Form = {
        ...form.value,
#foreach ($column in $columns)
#if($column.htmlType == "checkbox" && ($column.insert || $column.edit))
        ${column.javaField}: form.value.${column.javaField}.join(','),
#end
#end
      };'''
OLD_JOIN = '''      #foreach ($column in $columns)
        #if($column.htmlType == "checkbox")
        form.value.$column.javaField = form.value.${column.javaField}.join(",");
        #end
      #end'''
OLD_SPLIT = '''#foreach ($column in $columns)
  #if($column.htmlType == "checkbox")
  form.value.$column.javaField = form.value.${column.javaField}.split(",");
  #end
#end'''
NEW_SPLIT = '''#foreach ($column in $columns)
#if($column.htmlType == "checkbox" && ($column.insert || $column.edit))
  form.value.${column.javaField} = record.${column.javaField} ? String(record.${column.javaField}).split(',') : [];
#end
#end'''
# Standard CRUD renders this as type="daterange", not a time-of-day range.
# Element Plus keyboard parsing does not apply default-time; normalize at the
# query boundary for both keyboard and calendar paths without mutating controls.
DAY_RANGE_QUERY = """  const range${AttrName} = dateRange${AttrName}.value;
  proxy?.addDateRange(queryParams.value,
    range${AttrName}?.length === 2 && range${AttrName}.every(Boolean)
      ? [range${AttrName}[0].slice(0, 10) + ' 00:00:00', range${AttrName}[1].slice(0, 10) + ' 23:59:59']
      : [], '${AttrName}');"""

REPLACEMENTS = (
    ("  proxy?.addDateRange(queryParams.value, dateRange${AttrName}.value, '${AttrName}');", DAY_RANGE_QUERY),
    ("const dateRange${AttrName} = ref<[DateModelType, DateModelType]>(['', '']);",
     "const dateRange${AttrName} = ref<[string, string]>(['', '']);"),
    ('const initFormData: ${BusinessName}Form = {', EDIT_FORM),
    ('reactive<PageData<${BusinessName}Form, ${BusinessName}Query>>',
     'reactive<PageData<${BusinessName}EditForm, ${BusinessName}Query>>'),
    ('  Object.assign(form.value, res.data);',
     '  const record: Partial<${BusinessName}Form> = res.data;\n  Object.assign(form.value, record);'),
    (OLD_SPLIT, NEW_SPLIT),
    (OLD_JOIN, PAYLOAD),
    ('await update${BusinessName}(form.value)', 'await update${BusinessName}(payload)'),
    ('await add${BusinessName}(form.value)', 'await add${BusinessName}(payload)'),
)


def apply_generator_templates(project: Path) -> None:
    project = Path(project).resolve()
    expected = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams']['backend']['commit']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
    if expected != PIN or head != PIN:
        raise RuntimeError('Generator template upstream commit drift')
    target = project / TARGET
    if target.resolve() != target:
        raise RuntimeError('Generator template symlink refused')
    original = target.read_bytes()
    if hashlib.sha256(original).hexdigest() != PREIMAGE:
        raise RuntimeError('Generator template preimage drift')
    source = original.decode('utf-8')
    for anchor, replacement in REPLACEMENTS:
        if source.count(anchor) != 1:
            raise RuntimeError('Generator template single-anchor drift')
        source = source.replace(anchor, replacement, 1)
    target.write_text(source, encoding='utf-8')
