#!/usr/bin/env python3
"""Verify the actual archive and execute its HTTP runtime in an isolated directory."""
import argparse, json, shutil, subprocess, tempfile
from pathlib import Path
from updater import compatible, verify_archive

def check(directory):
    manifest=json.loads((directory/'agent-service-manifest.json').read_text())
    node=shutil.which('node')
    major=int(subprocess.check_output([node,'--version'],text=True).strip().removeprefix('v').split('.')[0])
    compatible(manifest,major)
    with tempfile.TemporaryDirectory() as tmp:
        code=Path(tmp); verify_archive(directory/manifest['asset'],code,manifest)
        if (code/'node_modules/typescript').exists(): raise AssertionError('DEV_DEPENDENCY_IN_PACKAGE')
        script='''import {pathToFileURL} from 'node:url'; import {randomBytes} from 'node:crypto';
const {createAgentService}=await import(pathToFileURL(process.argv[2]).href);
const token=randomBytes(48).toString('hex'); const service=createAgentService({mode:'local',clientToken:token});
await new Promise(resolve=>service.server.listen(0,'127.0.0.1',resolve));
try {
 const base='http://127.0.0.1:'+service.server.address().port;
 const health=await (await fetch(base+'/health')).json();
 const unauthorized=await fetch(base+'/v1/runtime'); if(unauthorized.status!==401)throw Error('AUTH_NOT_ENFORCED');
 const runtime=await (await fetch(base+'/v1/runtime',{headers:{Authorization:'Bearer '+token}})).json();
 if(health.status!=='ok'||runtime.schemaVersion!==2||runtime.release.commit!==process.argv[3]||runtime.release.tag!==process.argv[4])throw Error('RUNTIME_METADATA_INVALID');
 console.log('BUNDLE_RUNTIME_PASS');
} finally {service.close();service.server.closeAllConnections();}'''
        subprocess.run([node,'--input-type=module','--eval',script,'bundle-check',str(code/'dist/src/server.js'),manifest['commit'],manifest['tag']],check=True,timeout=30)
    print('Archive hash, file inventory, metadata, runtime dependencies and actual HTTP runtime: PASS')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);a=p.parse_args();check(a.directory)
