#!/usr/bin/env python3
"""Build a deterministic, credential-free Linux/amd64 runtime bundle in CI."""
import argparse, gzip, hashlib, json, os, re, shutil, subprocess, tarfile, tempfile
from pathlib import Path

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def build(repo, output, commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit): raise ValueError('COMMIT_INVALID')
    version = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', (repo/'app/build.gradle.kts').read_text()).group(1)
    protocol = int(re.search(r'PROTOCOL_VERSION\s*=\s*(\d+)', (repo/'agent-service/src/contracts.ts').read_text()).group(1))
    source = repo/'agent-service'
    package = json.loads((source/'package.json').read_text())
    output.mkdir(parents=True, exist_ok=True)
    metadata = dict(formatVersion=1, tag='v'+version, appVersion=version, packageVersion=package['version'], commit=commit,
                    protocolVersion=protocol, supportedClientProtocols=[protocol], runtime=dict(nodeMin=18, pythonMin='3.11', os='linux', arch='x64'))
    with tempfile.TemporaryDirectory() as work:
        stage=Path(work)/'bundle'; stage.mkdir()
        shutil.copytree(source/'dist/src', stage/'dist/src')
        for p in (stage/'dist/src').glob('smokeCodex.*'): p.unlink()
        for name in ['package.json', 'package-lock.json']: shutil.copy2(source/name, stage/name)
        subprocess.run(['npm','ci','--omit=dev','--ignore-scripts','--no-audit','--no-fund','--prefix',str(stage)], check=True)
        # No developer tests, bin symlinks, private keys or environment files in the release.
        for p in sorted(stage.rglob('*'), key=lambda p: len(p.parts), reverse=True):
            if p.is_symlink() or p.name=='.package-lock.json' or '.bin' in p.parts or p.name == '.env' or p.suffix in {'.pem','.key','.pfx','.jks','.keystore','.node'}:
                if p.is_dir() and not p.is_symlink(): shutil.rmtree(p)
                else: p.unlink()
        (stage/'release.json').write_text(json.dumps(metadata, sort_keys=True)+'\n')
        files={p.relative_to(stage).as_posix(): digest(p) for p in sorted(stage.rglob('*')) if p.is_file()}
        (stage/'files.sha256.json').write_text(json.dumps(files, sort_keys=True)+'\n')
        archive=output/f'agent-service-v{version}.tar.gz'
        with archive.open('wb') as target, gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as tar:
                for p in sorted(stage.rglob('*')):
                    info=tar.gettarinfo(str(p), arcname=p.relative_to(stage).as_posix())
                    info.uid=info.gid=0; info.uname=info.gname=''; info.mtime=0
                    info.mode=0o755 if p.is_dir() else 0o644
                    with p.open('rb') if p.is_file() else open(os.devnull,'rb') as data: tar.addfile(info, data if p.is_file() else None)
        manifest={**metadata, 'asset':archive.name, 'sha256':digest(archive), 'size':archive.stat().st_size,
                  'expandedBytes':sum(p.stat().st_size for p in stage.rglob('*') if p.is_file()),
                  'requiredFiles':['dist/src/server.js','dist/src/contracts.js','node_modules/zod/package.json','package.json','release.json','files.sha256.json']}
        (output/'agent-service-manifest.json').write_text(json.dumps(manifest, sort_keys=True)+'\n')
        (output/f'{archive.name}.sha256').write_text(f'{manifest["sha256"]}  {archive.name}\n')
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2]); parser.add_argument('--output',type=Path,required=True); parser.add_argument('--commit',required=True)
    args=parser.parse_args(); print(json.dumps(build(args.repo,args.output,args.commit),sort_keys=True))
