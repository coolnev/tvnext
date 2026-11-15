#!/usr/bin/env python3
"""Fetch a pinned x64 libmpv build and verify its published GitHub SHA-256."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile
import json

ROOT = Path(__file__).resolve().parents[1]
URL = ('https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261021/'
       'mpv-dev-x86_64-20261021-git-e471496c56.7z')
SHA256 = 'efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633'
PLAYER_URL = ('https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261021/'
              'mpv-x86_64-20261021-git-e471496c56.7z')
PLAYER_SHA256 = '4a355f3e89c83fabc730d33fb4e759dfec747806ed2287b61a90d1681b83e8d3'


def download_verified(url, expected, cache):
    archive = cache / url.rsplit('/', 1)[-1]
    if not archive.exists():
        temporary = archive.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    with archive.open('rb') as source:
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit(f'Checksum mismatch: {archive}; refusing to extract')
    return archive

JRE_URL=('https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/'
         'OpenJDK21U-jre_x64_windows_hotspot_21.0.9_10.zip')
JRE_SHA256='39c5e23f3ce4d420663afba8ffde28034b72e2b3e240943dc2321bc1f912eef9'
VULKAN_URL=('https://api.nuget.org/v3-flatcontainer/silk.net.vulkan.loader.native/2025.9.12/'
            'silk.net.vulkan.loader.native.2025.9.12.nupkg')
VULKAN_SHA256='33811c05ab0bcba632ad38abba459b0d72d6e58859342e0782d011863dde07d1'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sevenzip', default=shutil.which('7z') or shutil.which('7zz'))
    parser.add_argument('--java-output',type=Path,default=ROOT / '.local/windows-jre')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/windows-runtime')
    args = parser.parse_args()
    if not args.sevenzip:
        parser.error('Install 7-Zip or provide --sevenzip /path/to/7z')
    cache = ROOT / '.local/downloads'
    cache.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    for url, expected, dll in [(URL, SHA256, 'libmpv-2.dll'),
                               (PLAYER_URL, PLAYER_SHA256, 'd3dcompiler_43.dll')]:
        archive = download_verified(url, expected, cache)
        subprocess.run([args.sevenzip, 'e', '-y', '-o' + str(args.output), str(archive), dll], check=True)
        if not (args.output / dll).is_file():
            raise SystemExit(f'The verified archive did not contain {dll}')
    (args.output / 'MPV-SOURCE.txt').write_text(
        f'libmpv x64 build: {URL}\nArchive SHA-256: {SHA256}\n'
        f'Direct3D compiler source archive: {PLAYER_URL}\nArchive SHA-256: {PLAYER_SHA256}\n'
        'Build sources: https://github.com/shinchiro/mpv-winbuild-cmake/tree/20261021\n'
        'mpv source: https://github.com/mpv-player/mpv/tree/e471496c56\n'
        'License information: https://github.com/mpv-player/mpv/blob/e471496c56/Copyright\n', encoding='utf-8')

    archive=download_verified(VULKAN_URL,VULKAN_SHA256,cache)
    with zipfile.ZipFile(archive) as z:
        (args.output/'vulkan-1.dll').write_bytes(z.read('runtimes/win-x64/native/vulkan-1.dll'))
    archive=download_verified(JRE_URL,JRE_SHA256,cache)
    args.java_output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            relative=Path(*Path(name).parts[1:])
            if not str(relative) or name.endswith('/'):continue
            target=args.java_output/relative
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(z.read(name))
    manifests=[dict(url=URL,sha256=SHA256),dict(url=PLAYER_URL,sha256=PLAYER_SHA256),
        dict(url=JRE_URL,sha256=JRE_SHA256),dict(url=VULKAN_URL,sha256=VULKAN_SHA256)]
    (args.output/'RUNTIME-SOURCES.json').write_text(json.dumps(manifests,indent=2)+'\n',encoding='utf-8')
    print(f'Verified Windows playback and Java runtime prepared at {args.output} / {args.java_output}')



if __name__ == '__main__':
    main()
