#!/usr/bin/env python3
"""Public credential CLI with disposable files; never loads a real deployment env."""
import hashlib
import fcntl
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

CLI=Path(__file__).with_name('credential_store.py')
ADMIN='administrator-fixture-credential-at-least-32-characters'
MEMORY='memory-fixture-credential-at-least-32-characters'

class CredentialCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='nexus-credential-cli-');self.addCleanup(self.temporary.cleanup)
        self.directory=Path(self.temporary.name);self.store=self.directory/'tokens.json'
        self.env={'PATH':os.environ.get('PATH',os.defpath),'NEXUS_ADMIN_TOKEN':ADMIN,'NEXUS_MEMORY_MCP_TOKEN':MEMORY}
    def invoke(self,action,*arguments,ok=True):
        result=subprocess.run([sys.executable,str(CLI),action,'--file',str(self.store),*map(str,arguments)],
            env=self.env,capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0 if ok else 2,result.stdout+result.stderr)
        self.assertNotIn(ADMIN,result.stdout+result.stderr);self.assertNotIn(MEMORY,result.stdout+result.stderr)
        return json.loads(result.stdout)
    def test_initialize_rotate_export_and_revoke_without_exposing_secrets(self):
        self.assertEqual(self.invoke('init')['revision'],1)
        initial=json.loads(self.store.read_text());self.assertEqual(stat.S_IMODE(self.store.stat().st_mode),0o600)
        self.assertEqual(initial['adminActor'],hashlib.sha256(ADMIN.encode()).hexdigest())
        self.assertEqual(self.invoke('rotate','--scope','memory','--grace-seconds',120,'--expected-revision',1)['revision'],2)
        rotated=json.loads(self.store.read_text());self.assertNotEqual(rotated['memory']['current']['token'],MEMORY)
        self.assertEqual(rotated['memory']['previous']['token'],MEMORY)
        self.assertEqual(rotated['admin'],initial['admin']);self.assertEqual(rotated['adminActor'],initial['adminActor'])
        client=self.directory/'memory-client.json';self.invoke('export-memory','--output',client)
        exported=json.loads(client.read_text());self.assertEqual(set(exported),{'format','revision','id','token'})
        self.assertEqual(exported['token'],rotated['memory']['current']['token']);self.assertNotIn(ADMIN,client.read_text())
        self.assertEqual(stat.S_IMODE(client.stat().st_mode),0o600)
        self.invoke('revoke','--scope','memory','--id',rotated['memory']['previous']['id'],'--expected-revision',2)
        self.assertNotIn('previous',json.loads(self.store.read_text())['memory'])
        status=self.invoke('status');self.assertEqual(status['revision'],3)
        self.assertNotIn(exported['token'],json.dumps(status));self.assertNotIn('adminActor',status)
    def test_conflicts_and_live_overlap_preserve_the_existing_store(self):
        self.invoke('init');initial=self.store.read_bytes();self.invoke('init',ok=False);self.assertEqual(self.store.read_bytes(),initial)
        rejected=self.invoke('rotate','--scope','admin','--expected-revision',2,ok=False)
        self.assertEqual(rejected['reason'],'revision_conflict');self.assertEqual(self.store.read_bytes(),initial)
        self.invoke('rotate','--scope','admin','--expected-revision',1);current=self.store.read_bytes()
        rejected=self.invoke('rotate','--scope','admin','--expected-revision',2,ok=False)
        self.assertEqual(rejected['reason'],'previous_credential_still_active');self.assertEqual(self.store.read_bytes(),current)
        self.invoke('rotate','--scope','admin','--expected-revision',2,'--grace-seconds',0,'--revoke-previous')
        value=json.loads(self.store.read_text());self.assertNotIn('previous',value['admin'])
        self.invoke('revoke','--scope','admin','--id',value['admin']['current']['id'],'--expected-revision',3,ok=False)
    def test_unsafe_files_and_concurrent_writer_are_rejected_without_repair(self):
        self.invoke('init');original=self.store.read_bytes();self.store.chmod(0o644)
        self.invoke('status',ok=False);self.assertEqual(self.store.read_bytes(),original);self.store.chmod(0o600)
        real=self.directory/'original.json';self.store.rename(real);self.store.symlink_to(real)
        self.invoke('rotate','--scope','memory','--expected-revision',1,ok=False)
        self.assertEqual(real.read_bytes(),original);self.store.unlink();real.rename(self.store)
        with self.store.with_suffix('.json.lock').open('r+') as lock:
            fcntl.flock(lock.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(self.invoke('rotate','--scope','memory','--expected-revision',1,ok=False)['reason'],'credential_store_busy')
        self.assertEqual(self.store.read_bytes(),original)
    def test_export_refuses_overwrite_unrelated_files_and_keeps_admin_out(self):
        self.invoke('init');target=self.directory/'client.json';target.write_text('user owned file');target.chmod(0o600)
        self.invoke('export-memory','--output',target,ok=False);self.invoke('export-memory','--output',target,'--replace',ok=False)
        self.assertEqual(target.read_text(),'user owned file');target.unlink()
        self.invoke('export-memory','--output',target)
        self.invoke('rotate','--scope','memory','--expected-revision',1,'--grace-seconds',0)
        self.invoke('export-memory','--output',target,'--replace')
        exported=json.loads(target.read_text());self.assertEqual(exported['revision'],2);self.assertNotIn(ADMIN,target.read_text())

if __name__=='__main__':unittest.main()
