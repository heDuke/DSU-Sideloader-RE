#!/usr/bin/env python3
"""Render production overview/library components in an isolated copy; no emulator or KVM."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path)
parser.add_argument('--workdir', required=True, type=Path, help='New, non-existing directory')
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
source = args.source.resolve()
work = args.workdir.resolve()
if work.exists():
    parser.error('--workdir must not already exist')
if source == work or source in work.parents:
    parser.error('--workdir must be outside the source checkout')
origin = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
dirty = bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip())
fingerprint = hashlib.sha256()
for path in sorted((source / 'app/src/main').rglob('*')):
    if path.is_file():
        fingerprint.update(str(path.relative_to(source)).encode())
        fingerprint.update(hashlib.sha256(path.read_bytes()).digest())
shutil.copytree(source, work, ignore=shutil.ignore_patterns('.git', '.gradle', 'build'))
build = work / 'app/build.gradle.kts'
build.write_text(build.read_text().replace('plugins {', 'plugins {\n    id("app.cash.paparazzi") version "1.3.5"', 1))
test = work / 'app/src/test/java/preview/PreviewTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(Path(__file__).with_name('PreviewTest.kt'), test)
subprocess.run(['bash', './gradlew', '--no-daemon', ':app:recordPaparazziDebug', '--tests', 'preview.PreviewTest'], cwd=work, check=True)
args.output.mkdir(parents=True, exist_ok=True)
for image in (work / 'app/src/test/snapshots/images').glob('*.png'):
    shutil.copyfile(image, args.output / image.name)
(args.output / 'origin.json').write_text(json.dumps({
    'head': origin, 'dirty': dirty, 'app_main_sha256': fingerprint.hexdigest(),
    'renderer': 'Paparazzi 1.3.5 / layoutlib, not an Android device',
    'cases': 'empty/selected install, library, active task, native settings/about components; phone light/dark, 200% text, wide',
}, indent=2) + '\n')
print('Rendered images:', args.output)
