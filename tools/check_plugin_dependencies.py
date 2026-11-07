#!/usr/bin/env python3
"""Run the exact installer dependency policy before packaging; never executes plugin code."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def check_plugin_directory(directory):
    directory = Path(directory).resolve()
    if not directory.is_dir():
        raise ValueError('An unpacked plugin directory is required')
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('A JDK 17+ on PATH is required for the shared build/install dependency gate')
    sources = ['core/src/main/java/fcp/FcpException.java', 'core/src/main/java/fcp/Json.java',
               'core/src/main/java/fcp/plugin/PluginDependencyPolicy.java']
    with tempfile.TemporaryDirectory(prefix='fcp-dependency-gate-') as build:
        subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', build,
                        *[str(ROOT/source) for source in sources]], check=True)
        subprocess.run(['java', '-cp', build, 'fcp.plugin.PluginDependencyPolicy', str(directory)], check=True, stdout=sys.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    check_plugin_directory(args.directory)
