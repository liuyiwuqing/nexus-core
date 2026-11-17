export async function watchConsoleEvents(token:string,signal:AbortSignal,notify:(event:string)=>void):Promise<void>{
  const response=await fetch('/api/v1/events',{headers:{...(token?{Authorization:'Bearer '+token}:{}),Accept:'text/event-stream'},credentials:'same-origin',cache:'no-store',signal});
  if(response.status===401){notify('unauthorized');return;}
  if(!response.ok||!response.headers.get('Content-Type')?.startsWith('text/event-stream')||!response.body)throw new Error('event_stream_unavailable');
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',lastRevision='';
  try{while(!signal.aborted){const {done,value}=await reader.read();if(done)break;buffer=(buffer+decoder.decode(value,{stream:true})).replace(/\r\n/g,'\n');if(buffer.length>65536)throw new Error('invalid_event_stream');
    let split;while((split=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,split);buffer=buffer.slice(split+2);let event='',data='';for(const line of frame.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('data:'))data+=line.slice(5).trim();}
      if(!data)continue;const payload=JSON.parse(data) as {schemaVersion:number;revision?:string};if(payload.schemaVersion!==1)throw new Error('unsupported_event_schema');
      if(event==='unauthorized'){notify(event);return;}
      if(event==='ready'||event==='heartbeat')notify('connected');
      if(event==='ready'||event==='invalidate'&&payload.revision!==lastRevision){lastRevision=payload.revision||'';notify('invalidate');}
    }
  }}finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
