"""Fail-closed integration of safe generator UI into the pinned plus-ui page."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TARGET = 'src/views/tool/gen/index.vue'
PIN = 'd0d451967676707021b9857df529c395b27e90a7'
PREIMAGE = 'b87ce2cc797679224d8f714cad52082a321a04e012df10020e31b964a77c23a9'


def apply_generator_frontend(project: Path) -> None:
    project = Path(project).resolve()
    expected = json.loads((ROOT / 'upstreams.lock.json').read_text())['upstreams']['admin']['commit']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
    if expected != PIN or head != PIN:
        raise RuntimeError('Generator frontend upstream commit drift')
    target = project / TARGET
    if target.resolve() != target or hashlib.sha256(target.read_bytes()).hexdigest() != PREIMAGE:
        raise RuntimeError('Generator frontend preimage drift')
    source = target.read_text()
    replacements = [
        ('@click="handleGenTable()">生成</el-button>', '@click="handleGenTable()">下载</el-button>\n            <el-button v-hasPermi="[\'tool:gen:code\']" data-testid="generator-open" type="success" plain :disabled="multiple" @click="generatorApplyRef?.show(ids)">生成并应用</el-button>'),
        ('<el-tooltip content="生成代码" placement="top">', '<el-tooltip content="下载代码" placement="top">'),
        ('@click="handleGenTable(scope.row)"></el-button>\n            </el-tooltip>', '@click="handleGenTable(scope.row)"></el-button>\n            </el-tooltip>\n            <el-button v-hasPermi="[\'tool:gen:code\']" :data-testid="`generator-open-${scope.row.tableId}`" link type="success" @click="generatorApplyRef?.show([scope.row.tableId])">生成并应用</el-button>'),
        ('    <import-table ref="importRef" @ok="handleQuery" />', '    <import-table ref="importRef" @ok="handleQuery" />\n    <GeneratorApply ref="generatorApplyRef" />'),
        ("import ImportTable from './importTable.vue';", "import ImportTable from './importTable.vue';\nimport GeneratorApply from '@/fcp/generator/GeneratorApply.vue';\nconst generatorApplyRef = ref<InstanceType<typeof GeneratorApply>>();"),
    ]
    for anchor, replacement in replacements:
        if source.count(anchor) != 1:
            raise RuntimeError('Generator frontend single-anchor drift')
        source = source.replace(anchor, replacement, 1)
    # Stage all inputs and validate destinations before making any write.
    staged = []
    for name in ('GeneratorApply.vue', 'api.ts', 'types.ts'):
        overlay = ROOT / 'integration/plus-ui/src/fcp/generator' / name
        destination = project / 'src/fcp/generator' / name
        if overlay.is_symlink() or destination.resolve() != destination:
            raise RuntimeError('Generator frontend symlink refused')
        data = overlay.read_bytes()
        if destination.exists() and destination.read_bytes() != data:
            raise RuntimeError('Generator frontend overlay would overwrite existing changes')
        staged.append((destination, data))
    for destination, data in staged:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    target.write_text(source)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path, help='Pinned plus-ui checkout')
    apply_generator_frontend(parser.parse_args().project)
