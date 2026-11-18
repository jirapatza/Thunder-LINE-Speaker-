import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { verifySignature, verifySlipImage, dispatchResult } from './line-oa-sidecar.mjs';
import { createHmac } from 'node:crypto';
const enc = new TextEncoder();
const signature = (secret, raw) => createHmac('sha256', secret).update(raw).digest('base64');
const event = JSON.stringify({ events: [
  { type: 'message', webhookEventId: 'e1', message: { id: '123456789', type: 'image' } },
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
    THUNDER_API_KEY:'k', SLIP_QUEUE: {sendBatch: async items => batch.push(...items)} };
  const url = 'https://example.invalid/line-webhook';
  const good = await worker.fetch(new Request(url, {method:'POST',body:event,headers:{'x-line-signature':signature('s',event)}}),env);
  assert.equal(good.status,202);
  assert.equal((await good.json()).queued,1);
  assert.deepEqual(batch,[{body:{messageId:'123456789',eventId:'e1'}}]);
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
    if (url==='https://api.thunder.in.th/v2/verify/bank') return Response.json({success:true,data:{amountInSlip:180,matchedAccount:{bank:'bank'},isDuplicate:false}});
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
  for (const [matchedAccount,isDuplicate,want] of [[null,false,'slip_unmatched'],[{bank:'yes'},true,'duplicate_slip']]) {
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
    SLIP_QUEUE:{sendBatch:async()=>{}}};
  const url='https://example.invalid/forwarded-webhook';
  assert.equal((await worker.fetch(new Request(url,{method:'POST',body:event,headers:{'x-line-signature':signature('l',event)}}),env)).status,401);
  assert.equal((await worker.fetch(new Request(url,{method:'POST',body:event,headers:{'x-relay-signature':signature('r',event)}}),env)).status,202);
});
