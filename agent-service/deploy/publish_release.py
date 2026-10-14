#!/usr/bin/env python3
"""Stage every asset in a draft, verify bytes, then publish once. Never clobber assets."""
import argparse, hashlib, json, os, subprocess, urllib.request, urllib.error
from pathlib import Path
from updater import SafeRedirect, UpdateError

class Releases:
    def __init__(self,repo,token):
        if repo!='Thekwspl/MagazynMobile': raise UpdateError('REPOSITORY_INVALID')
        self.repo=repo; self.token=token; self.opener=urllib.request.build_opener(SafeRedirect())
    def call(self,method,path,data=None):
        url='https://api.github.com/repos/'+self.repo+path
        payload=json.dumps(data).encode() if data is not None else None
        req=urllib.request.Request(url,payload,headers={'Authorization':'Bearer '+self.token,'Accept':'application/vnd.github+json','Content-Type':'application/json'},method=method)
        try:
            with self.opener.open(req,timeout=60) as response: return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code==404 and method=='GET': return None
            raise UpdateError('RELEASE_API_'+str(error.code)) from None
    def download(self,asset):
        req=urllib.request.Request(asset['url'],headers={'Authorization':'Bearer '+self.token,'Accept':'application/octet-stream'})
        with self.opener.open(req,timeout=60) as response: return response.read(250_000_000)
    def upload(self,release,path):
        url=release['upload_url'].split('{')[0]+'?name='+path.name
        req=urllib.request.Request(url,path.read_bytes(),headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/octet-stream'},method='POST')
        try:
            with self.opener.open(req,timeout=180) as response: return json.load(response)
        except urllib.error.HTTPError as error: raise UpdateError('UPLOAD_'+str(error.code)) from None

def publish(api,tag,commit,paths):
    release=api.call('GET','/releases/tags/'+tag)
    if release is None:
        release=api.call('POST','/releases',dict(tag_name=tag,target_commitish=commit,name='Magazyn Mobile '+tag,draft=True,prerelease=False,generate_release_notes=True))
    assets={a['name']:a for a in release['assets']}
    for path in paths:
        expected=hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name not in assets:
            if not release['draft']: raise UpdateError('PUBLISHED_RELEASE_INCOMPLETE')
            assets[path.name]=api.upload(release,path)
        actual=hashlib.sha256(api.download(assets[path.name])).hexdigest()
        if actual!=expected: raise UpdateError('ASSET_IMMUTABLE_'+path.name)
    if release['draft']: api.call('PATCH','/releases/'+str(release['id']),dict(draft=False,make_latest='true'))
    return 'PUBLISHED' if release['draft'] else 'UNCHANGED'

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--bundle',type=Path,required=True); parser.add_argument('--apk',type=Path,required=True); parser.add_argument('--commit',required=True)
    args=parser.parse_args()
    manifest=json.loads((args.bundle/'agent-service-manifest.json').read_text())
    if manifest['commit']!=args.commit: raise SystemExit('COMMIT_MISMATCH')
    actual=subprocess.check_output(['git','rev-parse',manifest['tag']+'^{commit}'],text=True).strip()
    if actual!=args.commit: raise SystemExit('TAG_COMMIT_MISMATCH')
    paths=[args.apk,args.bundle/'agent-service-manifest.json',args.bundle/manifest['asset'],args.bundle/(manifest['asset']+'.sha256')]
    try: print(publish(Releases(os.environ['GITHUB_REPOSITORY'],os.environ['GH_TOKEN']),manifest['tag'],args.commit,paths))
    except (UpdateError,OSError): raise SystemExit('RELEASE_NOT_PUBLISHED: check the failed stage; existing bytes were not overwritten')
