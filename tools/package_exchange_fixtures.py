#!/usr/bin/env python3
"""Build deterministic trusted development fixture archives; does not install or grant them."""
import argparse, hashlib, json, pathlib, zipfile
from check_plugin_dependencies import check_plugin_directory
parser=argparse.ArgumentParser();parser.add_argument('--output',type=pathlib.Path,required=True);args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];args.output.mkdir(parents=True,exist_ok=True)
for name in ('roster','mock-exam'):
    check_plugin_directory(root/'runtime/runtime-plugins'/name)
    output=args.output/(name+'-1.0.0.zip')
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for file in ('plugin.json','worker.py','fcp_trace.py'):
            info=zipfile.ZipInfo(file,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            archive.writestr(info,(root/'runtime/observability'/file if file=='fcp_trace.py' else root/'runtime/runtime-plugins'/name/file).read_bytes())
    print(json.dumps(dict(plugin=name,path=str(output),sha256=hashlib.sha256(output.read_bytes()).hexdigest())))
