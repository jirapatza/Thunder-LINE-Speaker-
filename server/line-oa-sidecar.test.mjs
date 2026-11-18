import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { verifySignature, verifySlipImage, dispatchResult, deliverPendingResults, enqueueImageEvents, isFreshPaymentTime } from './line-oa-sidecar.mjs';
import { createHmac } from 'node:crypto';
const enc = new TextEncoder();
const signature = (secret, raw) => createHmac('sha256', secret).update(raw).digest('base64');
const event = JSON.stringify({ events: [
  { type: 'message', webhookEventId: 'e1', timestamp: Date.now(), message: { id: '123456789', type: 'image' } },
  { type: 'message', webhookEventId: 'e2', message: { id: '555', type: 'text', text: 'hello' } },
] });

test('validates HMAC before accepting an event', async () => {
  assert.equal(await verifySignature('s', enc.encode(event).buffer, signature('s', event)), true);
  assert.equal(await verifySignature('s', enc.encode(event).buffer, signature('wrong', event)), false);
  assert.equal(await verifySignature('s', enc.encode(event).buffer, null), false);
});

test('accepts signed image events and rejects unknown or unsigned requests', async () => {
  const batch = [];
  const env = { LINE_CHANNEL_SECRET:'s', LINE_CHANNEL_ACCESS_TOKEN:'t',
    THUNDER_API_KEY:'k', SLIP_QUEUE: {sendBatch: async items => batch.push(...items)},
    RESULT_CACHE:{get:async()=>null,put:async()=>{}},
    DEVICES:{list:async()=>({keys:[]})}, DEVICE_PAIRING_SECRET:'x'.repeat(32), FIREBASE_SERVICE_ACCOUNT_JSON:'configured' };
  const url = 'https://example.invalid/line-webhook';
  const good = await worker.fetch(new Request(url, {method:'POST',body:event,headers:{'x-line-signature':signature('s',event)}}),env);
  assert.equal(good.status,202);
  assert.equal((await good.json()).queued,1);
  assert.equal(batch.length,1);
  assert.equal(batch[0].body.messageId,'123456789');
  assert.equal(batch[0].body.eventId,'e1');
  assert.ok(isFreshPaymentTime(batch[0].body.receivedAt));
  const bad = await worker.fetch(new Request(url,{method:'POST',body:event,headers:{'x-line-signature':'invalid'}}),env);
  assert.equal(bad.status,401);
  const unconfigured = await worker.fetch(new Request(url,{method:'POST',body:event}),{});
  assert.equal(unconfigured.status,503);
});

test('downloads an image from LINE and passes it to Thunder by multipart upload', async () => {
  const urls = [];
  const mock = async (url,opts) => {
    urls.push([url,opts]);
    if (url.startsWith('https://api-data.line.me/')) return new Response(new Uint8Array([255,216,255]),{headers:{'content-type':'image/jpeg'}});
    if (url==='https://api.thunder.in.th/v2/verify/bank') return Response.json({success:true,data:{amountInSlip:180,matchedAccount:{bank:{code:'004'},bankNumber:'123-4-56789-0'},isDuplicate:false}});
    throw Error('Unexpected request');
  };
  const result = await verifySlipImage('123',{LINE_CHANNEL_ACCESS_TOKEN:'line-token',THUNDER_API_KEY:'thunder-token'},mock);
  assert.equal(result.type,'payment_matched');
  assert.equal(result.amount,180);
  assert.equal(urls.length,2);
  assert.equal(urls[0][1].headers.Authorization,'Bearer line-token');
  assert.equal(urls[1][1].headers.Authorization,'Bearer thunder-token');
  assert.equal(urls[1][1].body.get('image').size,3);
  assert.equal(urls[1][1].body.get('checkDuplicate'),'true');
  assert.equal(urls[1][1].body.get('matchAccount'),'true');
});

test('duplicate or unmatched slip never becomes a confirmed received payment', async () => {
  for (const [matchedAccount,isDuplicate,want] of [[null,false,'slip_unmatched'],[{bank:{code:'004'},bankNumber:'12345'},true,'duplicate_slip']]) {
    const mock = async url => url.startsWith('https://api-data.line.me/')
      ? new Response(new Uint8Array([255,216,255]),{headers:{'content-type':'image/jpeg'}})
      : Response.json({success:true,data:{amountInSlip:250,matchedAccount,isDuplicate}});
    assert.equal((await verifySlipImage('123',{LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k'},mock)).type,want);
  }
});

test('signs downstream notification and refuses insecure endpoint', async () => {
  const value = {type:'payment_matched',amount:180,messageId:'123'};
  await assert.rejects(dispatchResult(value,{EVENT_SINK_URL:'http://example.com',EVENT_SINK_SECRET:'abc'}),/https/);
  let seen;
  await dispatchResult(value,{EVENT_SINK_URL:'https://receiver.example/notify',EVENT_SINK_SECRET:'abc'},async (url,opts) => {
    seen = {url,opts}; return new Response('ok');
  });
  assert.equal(seen.url,'https://receiver.example/notify');
  assert.equal(seen.opts.headers['x-slip-signature'],signature('abc',seen.opts.body));
});

test('rejects invalid and oversized image formats',async () => {
  const env={LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k'};
  await assert.rejects(verifySlipImage('123',env,async()=>new Response('not image',{headers:{'content-type':'text/plain'}})),/unsupported/);
  await assert.rejects(verifySlipImage('123',env,async()=>new Response('x',{headers:{'content-type':'image/jpeg','content-length':'5000000'}})),/too_large/);
});

test('does not allow a LINE signature to authorize the separate forward endpoint',async () => {
  const env={FORWARD_SECRET:'r',LINE_CHANNEL_SECRET:'l',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}},RESULT_CACHE:{get:async()=>null,put:async()=>{}},
    DEVICES:{list:async()=>({keys:[]})},DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured'};
  const url='https://example.invalid/forwarded-webhook';
  assert.equal((await worker.fetch(new Request(url,{method:'POST',body:event,headers:{'x-line-signature':signature('l',event)}}),env)).status,401);
  assert.equal((await worker.fetch(new Request(url,{method:'POST',body:event,headers:{'x-relay-signature':signature('r',event)}}),env)).status,202);
});

test('queue resumes cached bank verification without charging Thunder again',async()=>{
  const cached=new Map([['verification:123',{result:{
    type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123',
  },delivered:false}]]);
  let acks=0, retries=0;
  const env={
    LINE_CHANNEL_SECRET:'s',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}},
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      get:async k=>cached.get(k)||null,
      put:async (k,v)=>{cached.set(k,JSON.parse(v));},
    },
  };
  await worker.queue({messages:[{
    body:{messageId:'123'},
    ack(){acks++}, retry(){retries++},
  }]},env);
  assert.equal(acks,1);
  assert.equal(retries,0);
  assert.equal(cached.get('verification:123').delivered,true);
  assert.equal(cached.get('verification:123').expired,true);
});

test('truthy but incomplete matchedAccount does not create a confirmed payment', async () => {
  for (const malformed of [{}, {bank:{}}, {bank:'bank'}, 'matched', true]) {
    const mock = async url => url.startsWith('https://api-data.line.me/')
      ? new Response(new Uint8Array([255,216,255]), {headers:{'content-type':'image/jpeg'}})
      : Response.json({success:true,data:{amountInSlip:150,matchedAccount:malformed,isDuplicate:false}});
    const result = await verifySlipImage('123', {LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k'}, mock);
    assert.equal(result.type,'slip_unmatched');
    assert.equal(result.matchedAccount,false);
  }
});

test('when no phone is paired, verified slip is held as pending and queue acknowledges safely', async () => {
  const state = new Map([['verification:123', {
    result:{type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123'},
    delivered:false,
  }]]);
  const env = {
    LINE_CHANNEL_SECRET:'s',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}}, DEVICE_PAIRING_SECRET:'x'.repeat(32),
    FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      get:async key => state.get(key),
      put:async (key,value) => state.set(key,JSON.parse(value)),
      list:async()=>({keys:[{name:'verification:123'}],list_complete:true}),
    },
  };
  let acks=0,retries=0;
  await worker.queue({messages:[{body:{messageId:'123'},ack(){acks++},retry(){retries++}}]},env);
  assert.equal(acks,1);
  assert.equal(retries,0);
  assert.equal(state.get('verification:123').delivered,true);
  assert.equal(state.get('verification:123').expired,true);
  const pending=await deliverPendingResults(env);
  assert.equal(pending.delivered,0);
  assert.equal(state.get('verification:123').delivered,true);
});

test('invalid queue message is acknowledged instead of calling Thunder',async()=>{
  let acks=0;
  await worker.queue({messages:[{body:{messageId:'not a valid ID'},ack(){acks++},retry(){throw Error('unexpected retry')}}]},{
    LINE_CHANNEL_SECRET:'s',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}},RESULT_CACHE:{get:async()=>{throw Error('must not read')},put:async()=>{}},
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
  });
  assert.equal(acks,1);
});

test('missing Thunder duplicate flag must never announce money received', async () => {
  const mock = async url => url.startsWith('https://api-data.line.me/')
    ? new Response(new Uint8Array([255,216,255]), {headers:{'content-type':'image/jpeg'}})
    : Response.json({success:true,data:{
      amountInSlip:180,
      matchedAccount:{bank:{code:'004'},bankNumber:'123-4-56789-0'},
    }});
  const result=await verifySlipImage('123',{LINE_CHANNEL_ACCESS_TOKEN:'line',THUNDER_API_KEY:'t'},mock);
  assert.equal(result.duplicate,true);
  assert.equal(result.type,'duplicate_slip');
});

test('image events are deduplicated within webhook payload and never silently cut at 50', async () => {
  const batches=[];
  const env={SLIP_QUEUE:{sendBatch:async batch=>batches.push(batch)}};
  const events=Array.from({length:103},(_,i)=>({
    type:'message',webhookEventId:'e'+i,timestamp:Date.now(),
    message:{type:'image',id:String(1000+i)},
  }));
  events.push({...events[0],webhookEventId:'redelivery'});
  events.push({type:'message',message:{type:'text',id:'9999'}});
  assert.equal(await enqueueImageEvents({events},env),103);
  assert.deepEqual(batches.map(x=>x.length),[100,3]);
  const sent=batches.flatMap(x=>x.map(y=>y.body.messageId));
  assert.equal(sent.length,103);
  assert.equal(new Set(sent).size,103);
  assert.equal(sent[0],'1000');
  assert.equal(sent[102],'1102');
});

test('health never claims end-to-end live delivery without a real-device test', async () => {
  const url='https://example.invalid/health';
  const blank=await (await worker.fetch(new Request(url),{})).json();
  assert.equal(blank.status,'needs_configuration');
  assert.equal(blank.endToEndVerified,false);
  const readyEnv={
    LINE_CHANNEL_SECRET:'secret',LINE_CHANNEL_ACCESS_TOKEN:'token',
    THUNDER_API_KEY:'key',SLIP_QUEUE:{sendBatch:async()=>{}},
    RESULT_CACHE:{get:async()=>null,put:async()=>{}},
    DEVICES:{list:async()=>({keys:[{name:'device:1'}]})},
    DEVICE_PAIRING_SECRET:'secret'.repeat(6),
    FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
  };
  const ready=await (await worker.fetch(new Request(url),readyEnv)).json();
  assert.equal(ready.status,'ready_for_live_test');
  assert.equal(ready.endToEndVerified,false);
  assert.equal(ready.originalWebhookForwarding,'not_verified');
  assert.equal(ready.pairedDevices,1);
});

test('rejects old and far-future LINE images before queueing for verification', async()=>{
  const now=Date.now();
  assert.equal(isFreshPaymentTime(now),true);
  assert.equal(isFreshPaymentTime(now-9*60_000),true);
  assert.equal(isFreshPaymentTime(now-11*60_000),false);
  assert.equal(isFreshPaymentTime(now+2*60_000),false);
  assert.equal(isFreshPaymentTime(undefined),false);
  const sent=[];
  const env={SLIP_QUEUE:{sendBatch:async items=>sent.push(...items)}};
  const count=await enqueueImageEvents({events:[
    {type:'message',timestamp:now-11*60_000,message:{type:'image',id:'101'}},
    {type:'message',timestamp:now,message:{type:'image',id:'102'}},
    {type:'message',timestamp:now+5*60_000,message:{type:'image',id:'103'}},
    {type:'message',message:{type:'image',id:'104'}},
  ]},env);
  assert.equal(count,1);
  assert.equal(sent[0].body.messageId,'102');
  assert.equal(sent[0].body.receivedAt,now);
});
test('pending payment notifies zero phones after being older than ten minutes',async()=>{
  const state=new Map([['verification:123',{result:{
    type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123',
  },verifiedAt:Date.now()-11*60_000,delivered:false}]]);
  const env={
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      list:async()=>({keys:[{name:'verification:123'}],list_complete:true}),
      get:async k=>state.get(k),
      put:async(k,v)=>state.set(k,JSON.parse(v)),
    },
  };
  const out=await deliverPendingResults(env);
  assert.equal(out.delivered,0);
  assert.equal(state.get('verification:123').expired,true);
  assert.equal(state.get('verification:123').delivered,true);
});

test('old source timestamps remain expired even if Thunder verification just happened', async()=>{
  const now=Date.now();
  const state=new Map([['verification:123',{result:{
    type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123',
  },receivedAt:now-11*60_000,verifiedAt:now,delivered:false}]]);
  const env={
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      list:async()=>({keys:[{name:'verification:123'}],list_complete:true}),
      get:async k=>state.get(k),
      put:async(k,v)=>state.set(k,JSON.parse(v)),
    },
  };
  const out=await deliverPendingResults(env);
  assert.equal(out.delivered,0);
  assert.equal(state.get('verification:123').expired,true);
  assert.equal(state.get('verification:123').delivered,true);
});

test('legacy missing-timestamp queue job is discarded without calling Thunder',async()=>{
  const stored=new Map([['verification:123',{result:{
    type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123',
  },verifiedAt:Date.now(),delivered:false}]]);
  let acks=0,retries=0;
  const env={
    LINE_CHANNEL_SECRET:'s',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}},
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      get:async k=>stored.get(k),
      put:async(k,v)=>stored.set(k,JSON.parse(v)),
    },
  };
  await worker.queue({messages:[{body:{messageId:'123'},
    ack(){acks++},retry(){retries++}}]},env);
  assert.equal(acks,1);
  assert.equal(retries,0);
  assert.equal(stored.get('verification:123').expired,true);
});

test('fresh source timestamp retains verified result awaiting phone pairing',async()=>{
  const now=Date.now();
  const stored=new Map([['verification:123',{result:{
    type:'payment_matched',matchedAccount:true,duplicate:false,amount:180,messageId:'123',
  },receivedAt:now,verifiedAt:now,delivered:false}]]);
  let acks=0,retries=0;
  const env={
    LINE_CHANNEL_SECRET:'s',LINE_CHANNEL_ACCESS_TOKEN:'t',THUNDER_API_KEY:'k',
    SLIP_QUEUE:{sendBatch:async()=>{}},
    DEVICE_PAIRING_SECRET:'x'.repeat(32),FIREBASE_SERVICE_ACCOUNT_JSON:'configured',
    DEVICES:{list:async()=>({keys:[]})},
    RESULT_CACHE:{
      get:async k=>stored.get(k),
      put:async(k,v)=>stored.set(k,JSON.parse(v)),
    },
  };
  await worker.queue({messages:[{body:{messageId:'123',receivedAt:now},
    ack(){acks++},retry(){retries++}}]},env);
  assert.equal(acks,1);
  assert.equal(retries,0);
  assert.equal(stored.get('verification:123').delivered,false);
});
