import assert from 'node:assert/strict';
import test from 'node:test';
import {once} from 'node:events';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createAgentService} from '../src/server.js';
import type {AgentResponse} from '../src/contracts.js';

test('runtime version is authenticated; drain requires separate local credential and preserves continuations', async () => {
 const root=mkdtempSync(join(tmpdir(),'mm-update-'));
 const token='fixture-'+ 'x'.repeat(64), update='fixture-'+ 'y'.repeat(64);
 const previous=process.env.AGENT_UPDATE_TOKEN_FILE;
 const file=join(root,'admin-token'); writeFileSync(file,update,{mode:0o600}); process.env.AGENT_UPDATE_TOKEN_FILE=file;
 const service=createAgentService({mode:'local',clientToken:token});
 try {
  service.server.listen(0,'127.0.0.1'); await once(service.server,'listening');
  const address=service.server.address(); assert.ok(address&&typeof address!=='string');
  const base='http://127.0.0.1:'+address.port;
  const call=(path:string,method='GET',value?:unknown,admin=false)=>fetch(base+path,{method,headers:{Authorization:'Bearer '+token,...(admin?{'x-agent-update-token':update}:{})},body:value?JSON.stringify(value):undefined});
  assert.equal((await fetch(base+'/v1/runtime')).status,401);
  const runtime=await (await call('/v1/runtime')).json() as {schemaVersion:number,supportedClientProtocols:number[]};
  assert.equal(runtime.schemaVersion,2); assert.deepEqual(runtime.supportedClientProtocols,[2]);
  assert.equal((await call('/v1/update/drain','POST')).status,403);
  await call('/v1/catalog/full-sync','PUT',{revision:1,people:[{id:'p',firstName:'Jan',lastName:'Kowalski'}],products:[{id:'g',name:'Rękawice',unit:'para'}],shipyards:[],taskPlaces:[]});
  const reply=await (await call('/v1/sessions/message','POST',{schemaVersion:2,mode:'ORDER',message:'Kowalski 2 rękawice'})).json() as AgentResponse;
  assert.equal(reply.status,'needs_data');
  assert.equal((await (await call('/v1/runtime')).json() as {pendingSessions:number}).pendingSessions,1);
  assert.equal((await call('/v1/update/drain','POST',undefined,true)).status,200);
  assert.equal((await call('/v1/sessions/message','POST',{schemaVersion:2,mode:'ORDER',message:'Kowalski rękawice'})).status,503);
  const proposal=await (await call('/v1/sessions/'+reply.sessionId+'/tool-results','POST',{results:[{requestId:reply.needsData[0].id,tool:'get_current_stock',data:{stocks:[{productId:'g',available:8}]}}]})).json() as AgentResponse;
  assert.equal(proposal.status,'proposal');
  assert.equal((await (await call('/v1/runtime')).json() as {pendingSessions:number}).pendingSessions,0);
  await call('/v1/update/resume','POST',undefined,true);
  assert.equal((await (await call('/v1/runtime')).json() as {draining:boolean}).draining,false);
 } finally { service.close(); service.server.closeAllConnections(); if(previous===undefined)delete process.env.AGENT_UPDATE_TOKEN_FILE; else process.env.AGENT_UPDATE_TOKEN_FILE=previous; rmSync(root,{recursive:true}); }
});
