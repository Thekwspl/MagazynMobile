import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {parseAgentResponse,PROTOCOL_VERSION} from '../src/contracts.js';
test('the same v2 proposal fixture is accepted by Android and agent-service with strict validation',()=>{
 const raw=readFileSync(new URL('../../../app/src/test/resources/agent-contract-v2.json',import.meta.url),'utf8');
 const reply=parseAgentResponse(raw); assert.equal(reply.schemaVersion,PROTOCOL_VERSION); assert.equal(reply.items[0].quantity,3);
 assert.throws(()=>parseAgentResponse(JSON.stringify({...JSON.parse(raw),schemaVersion:1})),/PROTOCOL/);
 assert.throws(()=>parseAgentResponse(JSON.stringify({...JSON.parse(raw),unexpected:'field'})),/PROTOCOL/);
});
