import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync, createHmac} from 'node:crypto';
import worker from './line-oa-sidecar.mjs';
import {imageRelayConfigured,imageTicket,validateImageTicket,serveSlipImage,processImageRelayJob} from './image-relay.mjs';

const secret='image-ticket-secret-long-enough-1234567890123456789';
const code='device-pairing-secret-long-enough-1234567890';
const mid='123456789012345';
const device='a'.repeat(64);
const now=Date.now();

function memoryKV(initial={}) {
  const map=new Map(Object.entries(initial));
  return {
    get: async (key)=>{const item=map.get(key);return item===undefined?null:item;},
    put: async (key,value)=>{map.set(key,JSON.parse(value));},
    delete: async key=>{map.delete(key);},
    list: async ({prefix=''})=>({keys:[...map.keys()].filter(k=>k.startsWith(prefix)).map(name=>({name})),list_complete:true})
  };
}
function env(overrides={}) {
  return {
    SLIP_PROCESSING_MODE:'image_relay',
    LINE_CHANNEL_SECRET:'line-secret',
    LINE_CHANNEL_ACCESS_TOKEN:'line-access-token',
    DEVICE_PAIRING_SECRET:code,
    IMAGE_LINK_SECRET:secret,
    FIREBASE_SERVICE_ACCOUNT_JSON:'test-service',
    DEVICES:memoryKV({'device:'+device:{token:'valid-device-token-long-enough'}}),
    RESULT_CACHE:memoryKV(),
    SLIP_QUEUE:{sendBatch:async()=>{}},
    ...overrides,
  };
}
test('image relay mode refuses Thunder and other credentials without opt-in',()=>{
  const e=env();
  assert.equal(imageRelayConfigured(e),true);
  assert.equal(imageRelayConfigured({...e,SLIP_PROCESSING_MODE:undefined}),false);
  assert.equal(imageRelayConfigured({...e,IMAGE_LINK_SECRET:'short'}),false);
  assert.equal(imageRelayConfigured({...e,LINE_CHANNEL_ACCESS_TOKEN:undefined}),false);
});
test('signed per-device URL valid briefly and rejects alterations or revocation',async()=>{
  const e=env(),at=Date.now();
  const valid=await imageTicket(mid,at,device,e,at);
  assert.equal(await validateImageTicket(new URL(valid),e,at),true);
  const altered=new URL(valid);altered.searchParams.set('id','999');
  assert.equal(await validateImageTicket(altered,e,at),false);
  assert.equal(await validateImageTicket(new URL(valid),e,at+121_000),false);
  await e.DEVICES.delete('device:'+device);
  assert.equal(await validateImageTicket(new URL(valid),e,at),false);
});
test('download endpoint uses LINE content API and no Thunder request',async()=>{
  const at=Date.now(),e=env(),url=await imageTicket(mid,at,device,e,at);
  const requests=[];
  const response=await serveSlipImage(new Request(url),e,async(uri,options)=>{
    requests.push({uri,header:options.headers.Authorization});
    return new Response(new Uint8Array([0xff,0xd8,0xff]),{status:200,headers:{'content-type':'image/jpeg'}});
  },at);
  assert.equal(response.status,200);
  assert.equal(response.headers.get('cache-control'),'no-store, private');
  assert.deepEqual(requests,[{uri:'https://api-data.line.me/v2/bot/message/'+mid+'/content',header:'Bearer line-access-token'}]);
});
test('download rejects invalid, over-limit, or non-image content',async()=>{
  const at=Date.now(),e=env(),url=await imageTicket(mid,at,device,e,at);
  let r=await serveSlipImage(new Request(url),e,async()=>new Response('oops',{headers:{'content-type':'text/plain'}}),at);
  assert.equal(r.status,415);
  r=await serveSlipImage(new Request(url),e,async()=>new Response(new Uint8Array([1]),{headers:{'content-type':'image/jpeg','content-length':'5000000'}}),at);
  assert.equal(r.status,415);
  r=await serveSlipImage(new Request(url.replace('sig=','sig=bad')),e,async()=>{throw Error('must not fetch')},at);
  assert.equal(r.status,401);
});
test('unpaired images become pending but are never sent to Thunder',async()=>{
  const e=env({DEVICES:memoryKV()});
  let ok=false, retries=0;
  const job={body:{messageId:mid,receivedAt:Date.now()},ack(){ok=true;},retry(){retries++}};
  const result=await processImageRelayJob(job,e,async()=>{throw Error('no network when unpaired')});
  assert.equal(result,'pending');assert.equal(ok,true);assert.equal(retries,0);
  assert.equal((await e.RESULT_CACHE.get('image-relay:'+mid)).delivered,false);
});
test('webhook still accepts authenticated LINE image events in opt-in image relay mode',async()=>{
  const e=env();let count=0;
  e.SLIP_QUEUE={sendBatch:async(batch)=>{count+=batch.length;}};
  const body=JSON.stringify({events:[{type:'message',timestamp:Date.now(),message:{type:'image',id:mid}}]});
  const signature=createHmac('sha256',e.LINE_CHANNEL_SECRET).update(body).digest('base64');
  const r=await worker.fetch(new Request('https://example.com/line-webhook',{
    method:'POST',headers:{'x-line-signature':signature},body
  }),e,{});
  assert.equal(r.status,202);assert.equal(count,1);
});
test('queue handler is locked in relay mode and never reaches Thunder',async()=>{
  const e=env({DEVICES:memoryKV()});let acknowledged=0;
  await worker.queue({messages:[{
    body:{messageId:mid,receivedAt:Date.now()},
    ack:()=>{acknowledged++},retry:()=>{throw Error('unexpected retry')}
  }]},e);
  assert.equal(acknowledged,1);
});
