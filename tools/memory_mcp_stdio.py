#!/usr/bin/env python3
"""Private stdio-to-CORE adapter; credentials come from the environment or a protected reloadable file."""
import hashlib
import ipaddress
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

VERSION='2025-06-18'
MAX_REQUEST=32768
MAX_RESPONSE=65536

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('duplicate_field')
        result[key]=value
    return result

def parse(data):
    def invalid_number(_):raise ValueError('invalid_number')
    return json.loads(data,object_pairs_hook=unique_object,parse_constant=invalid_number)

def emit_error(identifier,code,message):
    print(json.dumps({'jsonrpc':'2.0','id':identifier,'error':{'code':code,'message':message}},ensure_ascii=True),flush=True)

def valid_token(token):return isinstance(token,str) and 32<=len(token)<=256 and all(33<=ord(c)<=126 for c in token)

class TokenSource:
    def __init__(self):
        filename=os.environ.get('NEXUS_MEMORY_MCP_TOKEN_FILE','')
        self.file=Path(filename) if filename else None
        self.token=os.environ.get('NEXUS_MEMORY_MCP_TOKEN','') if not self.file else None
        self.revision=0;self.fingerprint=None
        self.get()
    def get(self):
        if self.file is None:
            if not valid_token(self.token):raise ValueError('invalid_config')
            return self.token
        if not self.file.is_absolute() or self.file.parent.stat().st_mode&0o022:raise ValueError('invalid_config')
        fd=os.open(self.file,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) not in (0o400,0o600):raise ValueError('invalid_config')
            raw=stream.read(2049)
        if not 0<len(raw)<=2048:raise ValueError('invalid_config')
        value=parse(raw)
        if not isinstance(value,dict) or set(value)!={'format','revision','id','token'} or type(value['format']) is not int or value['format']!=1:
            raise ValueError('invalid_config')
        if type(value['revision']) is not int or not 1<=value['revision']<1<<63 or not isinstance(value['id'],str) or not re.fullmatch('[a-zA-Z0-9_-]{1,40}',value['id']) or not valid_token(value['token']):
            raise ValueError('invalid_config')
        fingerprint=hashlib.sha256(raw).digest()
        if value['revision']<self.revision or value['revision']==self.revision and fingerprint!=self.fingerprint:raise ValueError('invalid_config')
        self.revision=value['revision'];self.fingerprint=fingerprint;return value['token']

def configuration():
    url=os.environ.get('NEXUS_MEMORY_MCP_URL','http://127.0.0.1:8088/mcp/memory')
    endpoint=urllib.parse.urlsplit(url)
    if any(c.isspace() or ord(c)<33 for c in url):raise ValueError('invalid_config')
    try:
        loopback=endpoint.hostname=='localhost' or ipaddress.ip_address(endpoint.hostname or '').is_loopback
    except ValueError:loopback=False
    if not endpoint.hostname or endpoint.username is not None or endpoint.password is not None or endpoint.query or endpoint.fragment or endpoint.path!='/mcp/memory':raise ValueError('invalid_config')
    if endpoint.scheme!='https' and not(endpoint.scheme=='http' and loopback):raise ValueError('invalid_config')
    endpoint.port  # Reject an invalid port before processing any input.
    return url,TokenSource()

def main():
    try:url,tokens=configuration()
    except (OSError,ValueError,TypeError,RecursionError):
        print('Invalid voice memory endpoint configuration',file=sys.stderr);return 2
    # No environment proxies or redirects: the credential stays on the configured endpoint.
    http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    negotiated=None
    while True:
        line=sys.stdin.buffer.readline(MAX_REQUEST+2)
        if not line:return 0
        if len(line.rstrip(b'\r\n'))>MAX_REQUEST:
            print('Voice memory request exceeds limit',file=sys.stderr);return 2
        try:message=parse(line)
        except (ValueError,UnicodeError,RecursionError):
            emit_error(None,-32700,'Parse error');continue
        if not isinstance(message,dict) or message.get('jsonrpc')!='2.0' or not isinstance(message.get('method'),str):
            emit_error(None,-32600,'Invalid Request');continue
        identifier=message.get('id');request='id' in message
        if request and (isinstance(identifier,bool) or not isinstance(identifier,(str,int))):
            emit_error(None,-32600,'Invalid Request');continue
        if negotiated is None and message['method']!='initialize':
            if request:emit_error(identifier,-32002,'Initialize first')
            continue
        try:data=json.dumps(message,ensure_ascii=False,allow_nan=False).encode('utf-8')
        except (ValueError,UnicodeError):
            if request:emit_error(identifier,-32600,'Invalid Request')
            continue
        if len(data)>MAX_REQUEST:
            if request:emit_error(identifier,-32600,'Request exceeds limit')
            continue
        try:
            headers={'Authorization':'Bearer '+tokens.get(),'Content-Type':'application/json','Accept':'application/json, text/event-stream'}
            if negotiated:headers['MCP-Protocol-Version']=negotiated
            call=urllib.request.Request(url,data=data,headers=headers,method='POST')
            with http.open(call,timeout=20) as response:
                if not request:
                    if response.status!=202:raise ValueError('unexpected_response')
                    continue
                if response.status!=200 or response.headers.get_content_type()!='application/json':raise ValueError('unexpected_response')
                lengths=response.headers.get_all('Content-Length',[])
                if len(lengths)>1 or lengths and (not lengths[0].isdigit() or int(lengths[0])>MAX_RESPONSE):raise ValueError('response_limit')
                body=response.read(MAX_RESPONSE+1)
                if len(body)>MAX_RESPONSE or lengths and len(body)!=int(lengths[0]):raise ValueError('incomplete_response')
                result=parse(body)
                if not isinstance(result,dict) or result.get('jsonrpc')!='2.0' or type(result.get('id')) is not type(identifier) or result.get('id')!=identifier or ('result' in result)==('error' in result):raise ValueError('invalid_response')
                if message['method']=='initialize' and 'result' in result:
                    if not isinstance(result['result'],dict) or result['result'].get('protocolVersion')!=VERSION:raise ValueError('unsupported_version')
                    negotiated=VERSION
                print(json.dumps(result,ensure_ascii=True,allow_nan=False),flush=True)
        except (OSError,ValueError,TypeError,RecursionError,HTTPException) as failure:
            if isinstance(failure,urllib.error.HTTPError):failure.close()
            if request:emit_error(identifier,-32000,'memory_endpoint_unavailable')
            # Never echo request text, endpoint URLs, upstream errors or credentials.

if __name__=='__main__':
    try:sys.exit(main())
    except (BrokenPipeError,KeyboardInterrupt):sys.exit(0)
