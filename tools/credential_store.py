#!/usr/bin/env python3
"""Manage protected CORE bearer credentials locally. Secrets never appear in arguments or output."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile
import time

MAX_REVISION=(1<<63)-1

class Rejected(ValueError):pass

def require(condition,code):
    if not condition:raise Rejected(code)

def unique(pairs):
    value={}
    for key,item in pairs:
        require(key not in value,'duplicate_field');value[key]=item
    return value

def parse(raw):
    def invalid(_):raise Rejected('invalid_number')
    return json.loads(raw,object_pairs_hook=unique,parse_constant=invalid)

def fields(value,required,optional=()):
    require(isinstance(value,dict) and set(required)<=set(value)<=set(required)|set(optional),'invalid_fields')

def token(value):
    require(isinstance(value,str) and 32<=len(value)<=256 and all(33<=ord(c)<=126 for c in value),'invalid_credential')

def directory(path):
    require(path.is_absolute() and path.parent.is_dir(),'absolute_private_directory_required')
    require(not path.parent.stat().st_mode&0o022,'writable_credential_directory')

def read_private(path):
    directory(path)
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as stream:
        info=os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) in (0o400,0o600),'private_regular_file_required')
        raw=stream.read(16385);require(0<len(raw)<=16384,'invalid_file_size');return raw

def validate(value):
    fields(value,{'format','revision','adminActor','admin'},{'memory'})
    require(type(value['format']) is int and value['format']==1,'invalid_format')
    require(type(value['revision']) is int and 1<=value['revision']<=MAX_REVISION,'invalid_revision')
    require(isinstance(value['adminActor'],str) and re.fullmatch('[0-9a-f]{64}',value['adminActor']),'invalid_admin_identity')
    ids=set();tokens=set()
    for name in ('admin','memory'):
        if name not in value:continue
        scope=value[name];fields(scope,{'current'},{'previous'})
        for slot in ('current','previous'):
            if slot not in scope:continue
            entry=scope[slot];fields(entry,{'id','token'}|({'expiresAt'} if slot=='previous' else set()))
            identifier=entry['id'];require(isinstance(identifier,str) and re.fullmatch('[a-zA-Z0-9_-]{1,40}',identifier),'invalid_credential_id')
            token(entry['token']);require(identifier not in ids and entry['token'] not in tokens,'credential_reuse')
            ids.add(identifier);tokens.add(entry['token'])
            if slot=='previous':require(type(entry['expiresAt']) is int and 1<=entry['expiresAt']<=int(time.time())+86400,'invalid_overlap_expiry')
    return value

def load(path):return validate(parse(read_private(path)))

def client(path):
    value=parse(read_private(path));fields(value,{'format','revision','id','token'})
    require(type(value['format']) is int and value['format']==1 and type(value['revision']) is int and 1<=value['revision']<=MAX_REVISION,'invalid_client_format')
    require(isinstance(value['id'],str) and re.fullmatch('[a-zA-Z0-9_-]{1,40}',value['id']),'invalid_credential_id')
    token(value['token']);return value

def atomic(path,value,replace):
    directory(path);raw=(json.dumps(value,sort_keys=True,indent=2)+'\n').encode()
    previous=path.lstat() if replace else None
    if previous:require(stat.S_ISREG(previous.st_mode),'symlink_rejected')
    fd,temporary=tempfile.mkstemp(prefix='.'+path.name+'-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as stream:
            # Root-operated rotation must not replace a service-readable file with root-only ownership.
            if previous and (previous.st_uid!=os.geteuid() or previous.st_gid!=os.getegid()):
                os.fchown(stream.fileno(),previous.st_uid,previous.st_gid)
            os.fchmod(stream.fileno(),0o600);stream.write(raw);stream.flush();os.fsync(stream.fileno())
        if replace:
            require(not path.is_symlink(),'symlink_rejected');os.replace(temporary,path)
        else:os.link(temporary,path,follow_symlinks=False)
        parent=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(parent)
        finally:os.close(parent)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)

@contextmanager
def locked(path):
    directory(path)
    fd=os.open(path.with_name(path.name+'.lock'),os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        info=os.fstat(fd);require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600,'invalid_lock_file')
        try:
            # Share POSIX record locks with Java FileChannel on macOS and Linux.
            fcntl.lockf(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise Rejected('credential_store_busy') from None
        yield
    finally:os.close(fd)

def entry(secret=None):return {'id':secrets.token_hex(16),'token':secret or secrets.token_urlsafe(48)}

def summary(value):
    now=int(time.time());scopes={}
    for name in ('admin','memory'):
        if name in value:scopes[name]=[{key:item for key,item in row.items() if key!='token'}|{'active':slot=='current' or row['expiresAt']>now}
            for slot,row in value[name].items()]
    return {'revision':value['revision'],'scopes':scopes}

def main():
    parser=argparse.ArgumentParser(description=__doc__);commands=parser.add_subparsers(dest='action',required=True)
    for name in ('init','status','rotate','revoke','export-memory'):
        sub=commands.add_parser(name);sub.add_argument('--file',type=Path,required=True)
        if name in ('rotate','revoke'):
            sub.add_argument('--scope',choices=('admin','memory'),required=True);sub.add_argument('--expected-revision',type=int,required=True)
        if name=='rotate':
            sub.add_argument('--grace-seconds',type=int,default=300);sub.add_argument('--revoke-previous',action='store_true')
        if name=='revoke':sub.add_argument('--id',required=True)
        if name=='export-memory':sub.add_argument('--output',type=Path,required=True);sub.add_argument('--replace',action='store_true')
    args=parser.parse_args();path=Path(os.path.abspath(args.file.expanduser()))
    try:
        if args.action=='init':
            with locked(path):
                require(not path.exists() and not path.is_symlink(),'credential_store_exists')
                admin=os.environ.get('NEXUS_ADMIN_TOKEN','');memory=os.environ.get('NEXUS_MEMORY_MCP_TOKEN','');token(admin)
                value={'format':1,'revision':1,'adminActor':hashlib.sha256(admin.encode()).hexdigest(),'admin':{'current':entry(admin)}}
                if memory:token(memory);value['memory']={'current':entry(memory)}
                validate(value);atomic(path,value,False)
        elif args.action in ('rotate','revoke'):
            with locked(path):
                value=load(path);require(value['revision']==args.expected_revision,'revision_conflict')
                require(value['revision']<MAX_REVISION and args.scope in value,'scope_or_revision_unavailable')
                scope=value[args.scope]
                if args.action=='rotate':
                    require(0<=args.grace_seconds<=86400,'invalid_grace_period')
                    require('previous' not in scope or scope['previous']['expiresAt']<=int(time.time()) or args.revoke_previous,'previous_credential_still_active')
                    current=scope['current'];scope={'current':entry()}
                    if args.grace_seconds:scope['previous']=current|{'expiresAt':int(time.time())+args.grace_seconds}
                    value[args.scope]=scope
                else:
                    require('previous' in scope and scope['previous']['id']==args.id,'only_previous_credential_can_be_revoked')
                    del scope['previous']
                value['revision']+=1;validate(value);atomic(path,value,True)
        else:
            value=load(path)
            if args.action=='export-memory':
                require('memory' in value,'memory_scope_unavailable');target=Path(os.path.abspath(args.output.expanduser()))
                require(target not in (path,path.with_name(path.name+'.lock')),'output_conflicts_with_store')
                if args.replace:client(target)
                atomic(target,{'format':1,'revision':value['revision'],**value['memory']['current']},args.replace)
        print(json.dumps({'ok':True,'action':args.action,**summary(value)},sort_keys=True));return 0
    except Rejected as error:reason=str(error)
    except (OSError,ValueError,TypeError,KeyError,UnicodeError,RecursionError):reason='invalid_input_or_io_failure'
    print(json.dumps({'ok':False,'action':args.action,'reason':reason},sort_keys=True));return 2

if __name__=='__main__':raise SystemExit(main())
