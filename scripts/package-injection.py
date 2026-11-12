"""Flatten only the mod's explicitly nested libraries for the native payload.
The Fabric mod jar remains unchanged for normal installation.
"""
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import argparse
import hashlib
import json


def flatten(source: Path, destination: Path, thin=False):
    entries = {}
    dependencies = []
    def visit(data, label):
        with ZipFile(BytesIO(data)) as archive:
            names = set(archive.namelist())
            metadata = json.loads(archive.read('fabric.mod.json')) if 'fabric.mod.json' in names else {}
            for entry in archive.infolist():
                name = entry.filename
                if entry.is_dir() or name.endswith('.jar') or name == 'module-info.class':
                    continue
                if name.startswith('META-INF/') or name == 'fabric.mod.json' or name.endswith('.mixins.json'):
                    continue
                content = archive.read(entry)
                if name in entries and entries[name] != content:
                    if name.endswith('.class'):
                        raise ValueError(f'Conflicting payload class: {name} in {label}')
                    continue
                entries[name] = content
            for nested in metadata.get('jars', []):
                dependencies.append(nested['file'])
                visit(archive.read(nested['file']), nested['file'])
    visit(source.read_bytes(), source.name)
    required = ['dev/technix/logic/inject/Bootstrap.class', 'dev/technix/mica/api/InjectedMica.class',
                'imgui/ImGui.class', 'imgui/gl3/ImGuiImplGl3.class', 'com/sun/jna/Native.class']
    for name in required:
        if name not in entries:
            raise ValueError(f'Missing required payload dependency: {name}')
    if not any(name.endswith('.dll') and 'imgui' in name.lower() for name in entries):
        raise ValueError('Missing Windows imgui native library')
    destination.parent.mkdir(parents=True, exist_ok=True)
    def is_payload(name):
        return name.startswith(('dev/technix/logic/', 'dev/technix/mica/', 'assets/logic/', 'assets/mica/')) \
            and not name.startswith('dev/technix/logic/host/')

    with ZipFile(destination, 'w', ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            if not thin or is_payload(name):
                output.writestr(ZipInfo(name), data, ZIP_DEFLATED)
    runtime = destination.with_name('logic-runtime.jar')
    with ZipFile(runtime, 'w', ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            if not is_payload(name):
                output.writestr(ZipInfo(name), data, ZIP_DEFLATED)
    print(f'Shared runtime: {runtime}')
    print(f'Payload: {destination}, {len(entries)} entries, {len(dependencies)} nested dependencies')
    print('SHA256:', hashlib.sha256(destination.read_bytes()).hexdigest())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mod', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--thin', action='store_true', help='Keep dependencies only in the shared runtime JAR')
    args = parser.parse_args()
    flatten(args.mod, args.output, args.thin)
