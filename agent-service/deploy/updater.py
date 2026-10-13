#!/usr/bin/env python3
"""Pull approved GitHub releases. No GCP API calls, VM schedules or code builds."""
import sys
import argparse, contextlib, fcntl, hashlib, json, os, platform, re, shlex, shutil, subprocess, tarfile, tempfile, time
import urllib.request, urllib.error, urllib.parse
from pathlib import Path, PurePosixPath

class UpdateError(Exception): pass
class Deferred(UpdateError): pass

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        next_req=super().redirect_request(req,fp,code,msg,headers,newurl)
        if next_req is not None and urllib.parse.urlparse(req.full_url).netloc != urllib.parse.urlparse(newurl).netloc:
            next_req.remove_header('Authorization'); next_req.remove_header('X-agent-update-token')
        if not newurl.startswith('https://'): raise UpdateError('REDIRECT_INSECURE')
        return next_req

def atomic_json(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.state-')
    try:
        with os.fdopen(fd,'w') as file: json.dump(data,file,sort_keys=True); file.flush(); os.fsync(file.fileno())
        os.replace(tmp,path)
        directory=os.open(path.parent,os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def version(tag):
    if not re.fullmatch(r'v\d+\.\d+\.\d+',tag): raise UpdateError('VERSION_INVALID')
    return tuple(map(int,tag[1:].split('.')))

def load_config(path):
    if path.stat().st_mode & 0o077: raise UpdateError('CONFIG_PERMISSIONS')
    config=json.loads(path.read_text())
    if any('WYMAGA_WERYFIKACJI' in str(v) for v in config.values()): raise UpdateError('CONFIG_PLACEHOLDER')
    for key in ['root','current','service','node','environment_file','update_token_file','local_url','baseline']:
        if not isinstance(config.get(key),str) or not config[key] or any(c in config[key] for c in ['\n','\r','\x00']): raise UpdateError('CONFIG_'+key.upper())
    if not re.fullmatch(r'[A-Za-z0-9_.@-]+\.service',config['service']): raise UpdateError('SERVICE_INVALID')
    for key in ['root','current','node','environment_file','update_token_file','baseline']:
        if not Path(config[key]).is_absolute() or any(c.isspace() or c=='%' for c in config[key]): raise UpdateError('PATH_'+key.upper())
    parsed=urllib.parse.urlparse(config['local_url'])
    if parsed.scheme!='http' or parsed.hostname!='127.0.0.1' or parsed.path not in ['', '/'] or parsed.username: raise UpdateError('LOCAL_URL_INVALID')
    root=Path(config['root'])
    if root.exists() and (root.stat().st_mode & 0o022 or root.stat().st_uid!=os.geteuid()): raise UpdateError('ROOT_PERMISSIONS')
    if not Path(config['current']).is_symlink(): raise UpdateError('CURRENT_NOT_SYMLINK')
    if config.get('repository','Thekwspl/MagazynMobile')!='Thekwspl/MagazynMobile': raise UpdateError('REPOSITORY_INVALID')
    if Path(config['current']).parent.resolve()!=Path(config['root']).resolve(): raise UpdateError('CURRENT_OUTSIDE_ROOT')
    if Path(config['root']).resolve()==Path(config['baseline']).resolve() or Path(config['root']).resolve().is_relative_to(Path(config['baseline']).resolve()): raise UpdateError('ROOT_OVERLAPS_BASELINE')
    return config

def read_secret(path, name=None):
    p=Path(path)
    if p.stat().st_mode & 0o007: raise UpdateError('SECRET_PERMISSIONS')
    if name is None: value=p.read_text().strip()
    else:
        value=None
        for line in p.read_text().splitlines():
            cleaned=line.removeprefix('export ').strip()
            if cleaned.startswith(name+'='):
                words=shlex.split(cleaned.split('=',1)[1], comments=True)
                if len(words)==1: value=words[0]
        if value is None: raise UpdateError('TOKEN_NOT_IN_ENVIRONMENT_FILE')
    if len(value)<43 or any(c.isspace() for c in value): raise UpdateError('TOKEN_INVALID')
    return value

class GitHub:
    def __init__(self): self.opener=urllib.request.build_opener(SafeRedirect())
    def get(self,url,limit=2_000_000):
        if not url.startswith('https://api.github.com/repos/Thekwspl/MagazynMobile/') and not url.startswith('https://github.com/Thekwspl/MagazynMobile/releases/download/'):
            raise UpdateError('DOWNLOAD_ORIGIN_INVALID')
        try:
            with self.opener.open(urllib.request.Request(url,headers={'User-Agent':'MagazynMobile-Updater','Accept':'application/vnd.github+json'}),timeout=30) as response:
                data=response.read(limit+1)
                if len(data)>limit: raise UpdateError('DOWNLOAD_TOO_LARGE')
                return data
        except (OSError,urllib.error.URLError): raise UpdateError('NETWORK_UNAVAILABLE') from None
    def latest(self):
        release=json.loads(self.get('https://api.github.com/repos/Thekwspl/MagazynMobile/releases/latest'))
        if release.get('draft') or release.get('prerelease') or not release.get('published_at'): raise UpdateError('RELEASE_NOT_APPROVED')
        tag=release['tag_name']; version(tag)
        assets={a['name']:a for a in release.get('assets',[]) if a.get('state')=='uploaded'}
        if 'agent-service-manifest.json' not in assets: raise Deferred('NO_COMPATIBLE_RELEASE')
        manifest=json.loads(self.get(assets['agent-service-manifest.json']['browser_download_url']))
        if manifest.get('tag')!=tag: raise UpdateError('TAG_MISMATCH')
        ref=json.loads(self.get('https://api.github.com/repos/Thekwspl/MagazynMobile/git/ref/tags/'+tag))['object']
        if ref['type']=='tag': ref=json.loads(self.get(ref['url']))['object']
        if ref['type']!='commit' or ref['sha']!=manifest.get('commit'): raise UpdateError('COMMIT_MISMATCH')
        asset=assets.get(manifest.get('asset'))
        if not asset or asset['size']!=manifest.get('size'): raise UpdateError('ASSET_MISSING')
        return manifest,asset['browser_download_url']
    def download(self,url,path,size):
        # A bounded download fails closed on truncation or connection loss before activation.
        data=self.get(url,limit=min(size,200_000_000))
        if len(data)!=size: raise UpdateError('DOWNLOAD_TRUNCATED')
        path.write_bytes(data)

REQUIRED={'dist/src/server.js','dist/src/contracts.js','node_modules/zod/package.json','package.json','release.json','files.sha256.json'}

def compatible(manifest,node_major,os_name='linux',arch='x64'):
    if manifest.get('formatVersion')!=1 or manifest.get('protocolVersion')!=2 or manifest.get('supportedClientProtocols')!=[2]: raise UpdateError('PROTOCOL_INCOMPATIBLE')
    version(manifest.get('tag',''))
    if manifest.get('appVersion')!=manifest['tag'][1:] or not isinstance(manifest.get('packageVersion'),str): raise UpdateError('APP_VERSION_MISMATCH')
    if not re.fullmatch('[0-9a-f]{40}',manifest.get('commit','')) or not re.fullmatch('[0-9a-f]{64}',manifest.get('sha256','')): raise UpdateError('MANIFEST_INVALID')
    runtime=manifest.get('runtime',{})
    python_min=runtime.get('pythonMin','')
    if not re.fullmatch(r'\d+\.\d+',python_min) or sys.version_info[:2]<tuple(map(int,python_min.split('.'))): raise UpdateError('PYTHON_INCOMPATIBLE')
    if runtime.get('os')!=os_name or runtime.get('arch')!=arch or not isinstance(runtime.get('nodeMin'),int) or node_major<runtime['nodeMin']: raise UpdateError('RUNTIME_INCOMPATIBLE')
    if manifest.get('asset')!=f'agent-service-{manifest["tag"]}.tar.gz' or not REQUIRED.issubset(set(manifest.get('requiredFiles',[]))): raise UpdateError('MANIFEST_FILES_INVALID')
    if not isinstance(manifest.get('size'),int) or not 0<manifest['size']<=200_000_000 or not isinstance(manifest.get('expandedBytes'),int) or not 0<manifest['expandedBytes']<=512_000_000: raise UpdateError('MANIFEST_SIZE_INVALID')

def verify_archive(archive,destination,manifest):
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=manifest['sha256']: raise UpdateError('HASH_MISMATCH')
    seen=set(); expanded=0
    try:
        with tarfile.open(archive,'r:gz') as tar:
            members=tar.getmembers()
            if len(members)>30000: raise UpdateError('ARCHIVE_TOO_MANY_FILES')
            for member in members:
                p=PurePosixPath(member.name)
                if p.is_absolute() or '..' in p.parts or '\\' in member.name or member.name in seen or not (member.isfile() or member.isdir()): raise UpdateError('ARCHIVE_PATH_INVALID')
                if not p.parts or p.parts[0] not in {'dist','node_modules','package.json','package-lock.json','release.json','files.sha256.json'}: raise UpdateError('ARCHIVE_FILE_FORBIDDEN')
                if p.name=='.env' or p.suffix in {'.pem','.key','.pfx','.jks','.keystore','.node'}: raise UpdateError('ARCHIVE_SECRET_OR_NATIVE_FILE')
                seen.add(member.name); expanded+=member.size
                if expanded>manifest['expandedBytes']: raise UpdateError('ARCHIVE_EXPANSION_LIMIT')
                target=destination.joinpath(*p.parts)
                if member.isdir(): target.mkdir(parents=True,exist_ok=True)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True)
                    with tar.extractfile(member) as source, target.open('xb') as out: shutil.copyfileobj(source,out)
                    target.chmod(0o644)
        if not REQUIRED.issubset(seen): raise UpdateError('REQUIRED_FILE_MISSING')
        meta=json.loads((destination/'release.json').read_text())
        for key in ['formatVersion','tag','commit','appVersion','packageVersion','protocolVersion','supportedClientProtocols','runtime']:
            if meta.get(key)!=manifest.get(key): raise UpdateError('PACKAGE_METADATA_MISMATCH')
        files=json.loads((destination/'files.sha256.json').read_text())
        actual={p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()}-{'files.sha256.json'}
        if set(files)!=actual: raise UpdateError('FILE_INVENTORY_MISMATCH')
        for name,expected in files.items():
            if hashlib.sha256((destination/name).read_bytes()).hexdigest()!=expected: raise UpdateError('FILE_HASH_MISMATCH')
    except (tarfile.TarError,OSError,ValueError,KeyError): raise UpdateError('PACKAGE_CORRUPT') from None

class SystemService:
    def __init__(self,config):
        self.config=config
        self.client=read_secret(config['environment_file'],'AGENT_CLIENT_TOKEN')
        self.update=read_secret(config['update_token_file'])
    def request(self,path,method='GET'):
        try:
            req=urllib.request.Request(self.config['local_url'].rstrip('/')+path,
                headers={'Authorization':'Bearer '+self.client,'X-Agent-Update-Token':self.update},method=method)
            # Loopback has no redirects; credentials never leave this socket.
            opener=urllib.request.build_opener(NoRedirect())
            with opener.open(req,timeout=10) as response: return json.load(response)
        except (OSError,urllib.error.URLError,ValueError): raise UpdateError('LOCAL_API_UNAVAILABLE') from None
    def idle(self,allow_loss=False,bootstrap=False):
        try:
            self.request('/v1/update/drain','POST')
            status=self.request('/v1/runtime')
        except UpdateError:
            if bootstrap and allow_loss: return
            raise Deferred('UPDATE_CONTROL_UNAVAILABLE') from None
        if not allow_loss and (status.get('activeRequests')!=0 or status.get('pendingSessions')!=0):
            self.resume(); raise Deferred('ACTIVE_SESSIONS')
    def resume(self):
        self.request('/v1/update/resume','POST')
    def restart(self):
        try: subprocess.run(['systemctl','restart',self.config['service']],check=True,timeout=60,capture_output=True)
        except (subprocess.SubprocessError,OSError): raise UpdateError('SERVICE_RESTART_FAILED') from None
    def health(self,manifest):
        for attempt in range(12):
            try:
                active=subprocess.run(['systemctl','is-active','--quiet',self.config['service']],timeout=10,capture_output=True).returncode==0
                health=self.request('/health')
                if manifest is None:
                    if active and health.get('status')=='ok': return # Legacy baseline lacks runtime metadata.
                else:
                    status=self.request('/v1/runtime')
                    if active and health.get('status')=='ok' and status.get('schemaVersion')==manifest['protocolVersion'] and status.get('supportedClientProtocols')==manifest['supportedClientProtocols'] and status.get('mode')=='codex' and status.get('release',{}).get('commit')==manifest['commit'] and status.get('release',{}).get('tag')==manifest['tag']: return
            except UpdateError: pass
            time.sleep(1) # bounded startup readiness polling, never a repair for application tests
        raise UpdateError('HEALTH_CHECK_FAILED')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): raise UpdateError('LOCAL_REDIRECT_FORBIDDEN')

class Updater:
    def __init__(self,config,github,service,free_space=None):
        self.config=config; self.root=Path(config['root']); self.current=Path(config['current'])
        self.github=github; self.service=service; self.free_space=free_space or (lambda: shutil.disk_usage(self.root).free)
        self.journal=self.root/'transaction.json'; self.last=self.root/'last-result.json'
    def record(self,status,**fields):
        # No exception messages, URLs, environment values or HTTP bodies are logged.
        try: atomic_json(self.last,dict(status=status,time=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),**fields))
        except OSError: pass # journald still receives the sanitized CLI status when storage is full
    def point(self,target):
        temp=self.root/'.current-next'
        if temp.is_symlink(): temp.unlink()
        os.symlink(str(target),temp); os.replace(temp,self.current)
        fd=os.open(self.root,os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
    def recover(self,allow_loss=False):
        transaction=json.loads(self.journal.read_text())
        old=Path(transaction['previous'])
        if self.current.resolve()==old:
            with contextlib.suppress(UpdateError): self.service.resume()
            self.journal.unlink(); self.record('ROLLED_BACK',stage='pre-switch-recovery',current=str(old))
            return 'ROLLED_BACK'
        if not old.is_dir() or (old!=Path(self.config['baseline']) and old.parent!=self.root/'releases'): raise UpdateError('ROLLBACK_PATH_INVALID')
        self.service.idle(allow_loss=allow_loss)
        self.point(old); self.service.restart()
        meta=json.loads((old/'release.json').read_text()) if (old/'release.json').is_file() else None
        self.service.health(meta)
        self.journal.unlink(); self.record('ROLLED_BACK',previous=transaction['next'],current=str(old),stage='recovery')
        with contextlib.suppress(UpdateError): self.service.resume()
        return 'ROLLED_BACK'
    def run(self,apply=False,allow_loss=False,bootstrap=False,retry_failed=False):
        self.root.mkdir(parents=True,exist_ok=True)
        with (self.root/'update.lock').open('a') as lock:
            try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError: return 'DEFERRED_LOCKED'
            try:
                if self.journal.exists():
                    if not apply: return 'RECOVERY_REQUIRED'
                    return self.recover(allow_loss)
                manifest,url=self.github.latest()
                node=subprocess.run([self.config['node'],'--version'],check=True,capture_output=True,text=True,timeout=10).stdout.strip()
                compatible(manifest,int(node.removeprefix('v').split('.')[0]),platform.system().lower(),{'x86_64':'x64','aarch64':'arm64'}.get(platform.machine(),platform.machine()))
                old=self.current.resolve(strict=True)
                installed=json.loads((old/'release.json').read_text()) if (old/'release.json').is_file() else None
                known_manifest=self.root/'manifests'/(manifest['tag']+'.json')
                if known_manifest.exists() and json.loads(known_manifest.read_text())!=manifest: raise UpdateError('RELEASE_MANIFEST_IMMUTABLE')
                if installed and version(installed['tag'])>=version(manifest['tag']):
                    if installed['tag']==manifest['tag'] and installed['commit']!=manifest['commit']: raise UpdateError('INSTALLED_TAG_IMMUTABLE')
                    self.record('UP_TO_DATE',current=installed['tag']); return 'UP_TO_DATE'
                failed=self.root/'failed-activation.json'
                if apply and not retry_failed and failed.exists() and json.loads(failed.read_text()).get('sha256')==manifest['sha256']: raise Deferred('PREVIOUS_ACTIVATION_FAILED')
                if self.free_space()<manifest['expandedBytes']*2+manifest['size']+64_000_000: raise UpdateError('DISK_SPACE_INSUFFICIENT')
                self.record('DOWNLOADING',next=manifest['tag'],stage='download')
                (self.root/'releases').mkdir(exist_ok=True)
                with tempfile.TemporaryDirectory(dir=self.root,prefix='.prepare-') as work:
                    archive=Path(work)/'package.tar.gz'; stage=Path(work)/'code'; stage.mkdir()
                    self.github.download(url,archive,manifest['size']); verify_archive(archive,stage,manifest)
                    if not apply:
                        self.record('UPDATE_AVAILABLE',next=manifest['tag'],commit=manifest['commit'],integrity='PASS',stage='verified-plan'); return 'UPDATE_AVAILABLE'
                    if not self.config.get('service_adapter_confirmed'): raise UpdateError('SERVICE_ADAPTER_NOT_CONFIRMED')
                    atomic_json(known_manifest,manifest)
                    destination=self.root/'releases'/manifest['tag']
                    if destination.exists():
                        if not (destination/'release.json').is_file() or json.loads((destination/'release.json').read_text())!=json.loads((stage/'release.json').read_text()): raise UpdateError('RELEASE_DIRECTORY_IMMUTABLE')
                        # Never reuse potentially modified bytes from an earlier failed preparation.
                        expected=json.loads((stage/'files.sha256.json').read_text())
                        actual={p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()}-{'files.sha256.json'}
                        if actual!=set(expected) or any(p.is_symlink() for p in destination.rglob('*')): raise UpdateError('RELEASE_DIRECTORY_MODIFIED')
                        for name,hashed in expected.items():
                            if not (destination/name).is_file() or hashlib.sha256((destination/name).read_bytes()).hexdigest()!=hashed: raise UpdateError('RELEASE_DIRECTORY_MODIFIED')
                    else: os.replace(stage,destination)
                atomic_json(self.journal,dict(previous=str(old),next=str(destination),tag=manifest['tag']))
                try: self.service.idle(allow_loss=allow_loss,bootstrap=bootstrap)
                except UpdateError:
                    with contextlib.suppress(UpdateError): self.service.resume()
                    self.journal.unlink()
                    raise
                try:
                    self.record('INSTALLING',previous=installed['tag'] if installed else 'baseline',next=manifest['tag'],integrity='PASS')
                    self.point(destination); self.service.restart(); self.service.health(manifest)
                    atomic_json(self.root/'previous.json',dict(path=str(old)))
                    if failed.exists(): failed.unlink()
                    self.journal.unlink(); self.record('SUCCESS',previous=installed['tag'] if installed else 'baseline',current=manifest['tag'],commit=manifest['commit'],integrity='PASS',health='PASS')
                    return 'SUCCESS'
                except UpdateError as activation_error:
                    self.point(old); self.service.restart()
                    self.service.health(installed)
                    atomic_json(failed,dict(sha256=manifest['sha256'],commit=manifest['commit'],tag=manifest['tag'],cause=str(activation_error)))
                    self.journal.unlink(); self.record('ROLLED_BACK',current=installed['tag'] if installed else 'baseline',attempted=manifest['tag'],stage='activation',cause=str(activation_error),integrity='PASS',health='FAILED',rollbackHealth='PASS')
                    return 'ROLLED_BACK'
                finally:
                    with contextlib.suppress(UpdateError): self.service.resume()
            except Deferred as error:
                self.record('DEFERRED',stage=str(error)); return 'DEFERRED'
            except (UpdateError,OSError,ValueError,KeyError,subprocess.SubprocessError) as error:
                code=str(error) if isinstance(error,UpdateError) else type(error).__name__
                self.record('FAILED',stage=code); return 'FAILED'

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--config',type=Path,required=True); parser.add_argument('--apply',action='store_true'); parser.add_argument('--allow-session-loss',action='store_true'); parser.add_argument('--bootstrap',action='store_true'); parser.add_argument('--retry-failed',action='store_true'); parser.add_argument('--status',action='store_true')
    args=parser.parse_args()
    try:
        config=load_config(args.config)
        if args.status:
            state=Path(config['root'])/'last-result.json'
            active=Path(config['current']).resolve()
            metadata=active/'release.json'
            installed=json.loads(metadata.read_text()) if metadata.exists() else {'tag':'baseline'}
            try:
                runtime=SystemService(config).request('/v1/runtime')
                running=dict(schemaVersion=runtime.get('schemaVersion'),mode=runtime.get('mode'),
                    tag=runtime.get('release',{}).get('tag'),commit=runtime.get('release',{}).get('commit'),
                    activeRequests=runtime.get('activeRequests'),pendingSessions=runtime.get('pendingSessions'),draining=runtime.get('draining'))
            except UpdateError: running={'status':'RUNTIME_DIAGNOSTICS_UNAVAILABLE'}
            print(json.dumps({'running':running,'installed':{key:installed.get(key) for key in ['tag','commit','protocolVersion']},
                'lastResult':json.loads(state.read_text()) if state.exists() else {'status':'NOT_CONFIGURED'}}))
        else:
            result=Updater(config,GitHub(),SystemService(config)).run(args.apply,args.allow_session_loss,args.bootstrap,args.retry_failed)
            print(json.dumps({'status':result})); raise SystemExit(1 if result in ['FAILED','RECOVERY_REQUIRED'] else 0)
    except (UpdateError,OSError,ValueError):
        print('{"status":"FAILED","stage":"configuration"}'); raise SystemExit(1)
