import shutil
import contextlib, fcntl, hashlib, io, json, os, tarfile, tempfile, threading, unittest
from pathlib import Path
from unittest.mock import patch, Mock
from updater import Updater, UpdateError, Deferred, compatible, verify_archive, atomic_json, read_secret, load_config, SystemService
from publish_release import publish
from render_systemd import render

class FakeService:
    def __init__(self): self.restarts=0; self.busy=False; self.fail_start=False; self.fail_health=False; self.draining=False; self.crash=False
    def idle(self,**kwargs):
        self.draining=True
        if self.busy and not kwargs.get('allow_loss'): self.resume(); raise Deferred('ACTIVE_SESSIONS')
    def restart(self):
        self.restarts+=1
        if self.crash: self.crash=False; raise SystemExit('simulated interruption')
        if self.fail_start: self.fail_start=False; raise UpdateError('SERVICE_RESTART_FAILED')
    def health(self,manifest):
        if self.fail_health: self.fail_health=False; raise UpdateError('HEALTH_CHECK_FAILED')
    def resume(self): self.draining=False

class FakeGitHub:
    def __init__(self,manifest,archive): self.manifest=manifest; self.archive=archive; self.error=False; self.truncated=False
    def latest(self):
        if self.error: raise UpdateError('NETWORK_UNAVAILABLE')
        return self.manifest,'fixture://package'
    def download(self,url,path,size):
        if self.truncated: path.write_bytes(self.archive[:20]); raise UpdateError('DOWNLOAD_TRUNCATED')
        path.write_bytes(self.archive)

def fixture_archive(tag='v1.2.2',extra=None,omit=None):
    metadata=dict(formatVersion=1,tag=tag,appVersion=tag[1:],packageVersion='0.1.0',commit='a'*40,
        protocolVersion=2,supportedClientProtocols=[2],runtime=dict(nodeMin=18,pythonMin='3.11',os='linux',arch='x64'))
    files={'dist/src/server.js':b'code','dist/src/contracts.js':b'protocol','node_modules/zod/package.json':b'{}',
        'package.json':b'{}','release.json':json.dumps(metadata).encode()}
    if omit: files.pop(omit)
    if extra: files.update(extra)
    files['files.sha256.json']=json.dumps({p:hashlib.sha256(data).hexdigest() for p,data in files.items()}).encode()
    out=io.BytesIO()
    with tarfile.open(fileobj=out,mode='w:gz') as tar:
        for p,data in files.items():
            info=tarfile.TarInfo(p); info.size=len(data); tar.addfile(info,io.BytesIO(data))
    archive=out.getvalue()
    manifest={**metadata,'asset':f'agent-service-{tag}.tar.gz','sha256':hashlib.sha256(archive).hexdigest(),'size':len(archive),
        'expandedBytes':sum(map(len,files.values())),'requiredFiles':['dist/src/server.js','dist/src/contracts.js','node_modules/zod/package.json','package.json','release.json','files.sha256.json']}
    return manifest,archive

class UpdaterTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root=Path(self.tmp.name)/'managed'; self.root.mkdir()
        self.old=self.root/'releases/v1.2.1'; self.old.mkdir(parents=True)
        self.oldmeta=fixture_archive('v1.2.1')[0]; atomic_json(self.old/'release.json',self.oldmeta)
        self.current=self.root/'current'; self.current.symlink_to(self.old)
        self.config=dict(root=str(self.root),current=str(self.current),service='fixture.service',node=shutil.which('node'),baseline=str(self.old),service_adapter_confirmed=True)
        self.manifest,self.archive=fixture_archive(); self.github=FakeGitHub(self.manifest,self.archive); self.service=FakeService()
        self.updater=Updater(self.config,self.github,self.service)
    def assert_old(self): self.assertEqual(self.old,self.current.resolve())
    def test_no_new_version_does_not_restart(self):
        self.github.manifest=fixture_archive('v1.2.1')[0]; self.assertEqual('UP_TO_DATE',self.updater.run(True)); self.assertEqual(0,self.service.restarts)
    def test_dry_run_verifies_bytes_without_switch(self):
        self.assertEqual('UPDATE_AVAILABLE',self.updater.run()); self.assert_old(); self.assertEqual(0,self.service.restarts)
    def test_success_then_repeat_is_idempotent_and_keeps_old_version(self):
        self.assertEqual('SUCCESS',self.updater.run(True)); self.assertTrue(self.old.exists()); self.assertEqual('UP_TO_DATE',self.updater.run(True)); self.assertEqual(1,self.service.restarts)
    def test_hash_mismatch(self):
        self.github.archive=b'corrupt'; self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_corrupt_tar_correct_hash(self):
        self.github.archive=b'not-a-tar'; self.github.manifest['sha256']=hashlib.sha256(self.github.archive).hexdigest()
        self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_required_file_missing(self):
        self.github.manifest,self.github.archive=fixture_archive(omit='dist/src/server.js'); self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_path_traversal_and_secret_rejected(self):
        for extra in [{'../escape':b'x'},{'.env':b'TOKEN=secret'},{'node_modules/key.pem':b'secret'}]:
            self.github.manifest,self.github.archive=fixture_archive(extra=extra)
            self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_no_disk_space(self):
        self.updater.free_space=lambda:0; self.assertEqual('FAILED',self.updater.run(True)); self.assert_old(); self.assertEqual(0,self.service.restarts)
    def test_github_unavailable(self):
        self.github.error=True; self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_network_interrupted(self):
        self.github.truncated=True; self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
    def test_start_failure_rolls_back(self):
        self.service.fail_start=True; self.assertEqual('ROLLED_BACK',self.updater.run(True)); self.assert_old(); self.assertEqual(2,self.service.restarts)
    def test_health_failure_rolls_back(self):
        self.service.fail_health=True; self.assertEqual('ROLLED_BACK',self.updater.run(True)); self.assert_old()
    def test_failed_version_is_not_restarted_on_each_timer_tick(self):
        self.service.fail_health=True
        self.assertEqual('ROLLED_BACK',self.updater.run(True))
        self.assertEqual('DEFERRED',self.updater.run(True)); self.assertEqual(2,self.service.restarts)
        self.assertEqual('SUCCESS',self.updater.run(True,retry_failed=True))
    def test_failed_rollback_restart_is_recovered_even_when_pointer_is_already_old(self):
        self.service.fail_health=True
        self.service.restart=Mock(side_effect=[None,UpdateError('SERVICE_RESTART_FAILED'),None])
        self.assertEqual('FAILED',self.updater.run(True)); self.assert_old()
        self.assertTrue(self.updater.journal.exists())
        self.assertEqual('ROLLED_BACK',self.updater.run(True)); self.assert_old()
        self.assertEqual(3,self.service.restart.call_count)
    def test_crash_journal_recovers_previous_version(self):
        self.service.crash=True
        with self.assertRaises(SystemExit): self.updater.run(True)
        self.assertTrue(self.updater.journal.exists()); self.assertEqual('ROLLED_BACK',self.updater.run(True)); self.assert_old()
    def test_crash_before_switch_resumes_without_interrupting_sessions(self):
        atomic_json(self.updater.journal,dict(previous=str(self.old),next=str(self.root/'releases/v1.2.2')))
        self.service.busy=True; self.service.draining=True
        self.assertEqual('ROLLED_BACK',self.updater.run(True)); self.assertEqual(0,self.service.restarts); self.assertFalse(self.service.draining)
    def test_explicit_manual_session_loss_override_is_not_automatic(self):
        self.service.busy=True
        self.assertEqual('DEFERRED',self.updater.run(True))
        self.assertEqual('SUCCESS',self.updater.run(True,allow_loss=True)); self.assertEqual(1,self.service.restarts)
    def test_active_sessions_defer(self):
        self.service.busy=True; self.assertEqual('DEFERRED',self.updater.run(True)); self.assert_old(); self.assertFalse(self.service.draining)
    def test_parallel_attempt_uses_kernel_lock(self):
        with (self.root/'update.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual('DEFERRED_LOCKED',self.updater.run(True)); self.assertEqual(0,self.service.restarts)
    def test_protocol_and_runtime_incompatibility(self):
        for change in [dict(protocolVersion=3),dict(supportedClientProtocols=[3]),dict(runtime=dict(nodeMin=999,pythonMin='3.11',os='linux',arch='x64')),dict(runtime=dict(nodeMin=18,pythonMin='999.0',os='linux',arch='x64'))]:
            with self.assertRaises(UpdateError): compatible({**self.manifest,**change},24)
    def test_changed_bytes_under_installed_tag_rejected(self):
        self.assertEqual('SUCCESS',self.updater.run(True)); self.github.manifest['commit']='b'*40
        self.assertEqual('FAILED',self.updater.run(True))
    def test_changed_archive_under_same_commit_and_tag_rejected(self):
        self.assertEqual('SUCCESS',self.updater.run(True))
        self.github.manifest['sha256']='b'*64
        self.assertEqual('FAILED',self.updater.run(True)); self.assertEqual(1,self.service.restarts)
    def test_configuration_is_outside_code_and_secrets_not_in_logs(self):
        secret='SECRET'+('x'*50); env=self.root.parent/'existing.env'; env.write_text('AGENT_CLIENT_TOKEN="'+secret+'"\nAGENT_MODE=codex\n'); env.chmod(0o600)
        self.assertEqual(secret,read_secret(env,'AGENT_CLIENT_TOKEN')); before=env.read_bytes()
        self.updater.run(True); self.assertEqual(before,env.read_bytes()); self.assertNotIn(secret,self.updater.last.read_text()); self.assertFalse((self.current/'.env').exists())
    def test_timer_does_not_depend_on_boot_hour(self):
        for hour in ['06:00','09:00','14:00','00:00']:
            out=self.root/'units'; config={**self.config,'update_token_file':'/config/token'}
            render(config,out,'/opt/updater.py','/etc/config.json',17)
            timer=(out/'magazyn-agent-update.timer').read_text()
            self.assertIn('OnBootSec=2min',timer); self.assertIn('OnUnitInactiveSec=17min',timer); self.assertNotIn('OnCalendar',timer); self.assertNotIn(hour,timer)

class StoppedServiceRecoveryTest(unittest.TestCase):
    def test_unavailable_api_only_allows_automatic_recovery_when_systemd_proves_no_process(self):
        service=object.__new__(SystemService); service.config={'service':'fixture.service'}
        service.request=Mock(side_effect=UpdateError('LOCAL_API_UNAVAILABLE'))
        cases=[('failed','0',True),('inactive','0',True),('active','24',False),('activating','0',False),('failed','24',False)]
        for state,pid,safe in cases:
            with self.subTest(state=state,pid=pid), patch('updater.subprocess.run',return_value=Mock(stdout=f'ActiveState={state}\nMainPID={pid}\n',returncode=0)):
                if safe: service.idle()
                else:
                    with self.assertRaises(Deferred): service.idle()
    def test_missing_control_and_systemctl_failure_do_not_claim_idleness(self):
        service=object.__new__(SystemService); service.config={'service':'fixture.service'}
        service.request=Mock(side_effect=UpdateError('LOCAL_API_UNAVAILABLE'))
        with patch('updater.subprocess.run',side_effect=OSError('fixture')):
            with self.assertRaises(Deferred): service.idle()

class FakeRelease:
    def __init__(self): self.release=None; self.files={}; self.published=False; self.fail_upload=False
    def call(self,method,path,data=None):
        if method=='GET': return self.release
        if method=='POST': self.release=dict(id=1,assets=[],draft=True); return self.release
        if method=='PATCH': self.published=True; self.release['draft']=False; return self.release
    def upload(self,release,path):
        if self.fail_upload: raise UpdateError('UPLOAD_FAILED')
        item=dict(name=path.name,url=path.name); release['assets'].append(item); self.files[path.name]=path.read_bytes(); return item
    def download(self,asset): return self.files[asset['name']]

class PublicationTest(unittest.TestCase):
    def test_atomic_draft_and_same_bytes_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            files=[Path(tmp)/n for n in ['apk','package','manifest']]
            for p in files: p.write_bytes(p.name.encode())
            api=FakeRelease(); publish(api,'v1.2.2','a'*40,files); self.assertTrue(api.published)
            publish(api,'v1.2.2','a'*40,files)
            files[1].write_bytes(b'different')
            with self.assertRaises(UpdateError): publish(api,'v1.2.2','a'*40,files)
            self.assertEqual(b'package',api.files['package'])
    def test_failure_does_not_publish_partial_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'package'; p.write_bytes(b'code'); api=FakeRelease(); api.fail_upload=True
            with self.assertRaises(UpdateError): publish(api,'v1.2.2','a'*40,[p])
            self.assertTrue(api.release['draft']); self.assertFalse(api.published)

if __name__=='__main__': unittest.main()
