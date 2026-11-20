import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, generateKeyPairSync } from 'node:crypto';
import worker from './line-oa-sidecar.mjs';

function memoryKV() {
  const data = new Map();
  return {
    async get(key, type) { const value = data.get(key); return value === undefined ? null : type === 'json' ? JSON.parse(value) : value; },
    async put(key, value) { data.set(key, value); },
    async delete(key) { data.delete(key); },
    async list({prefix = ''} = {}) { return {keys: [...data.keys()].filter(k => k.startsWith(prefix)).map(name => ({name})), list_complete: true}; },
  };
}

test('signed forwarded image -> queue -> FCM ticket -> LINE image, without Thunder', async () => {
  const {privateKey} = generateKeyPairSync('rsa', {modulusLength: 2048});
  const jobs = [], calls = [], background = [];
  const token = 'integration_fcm_device_token_123456789';
  const env = {
    SLIP_PROCESSING_MODE: 'image_relay', FORWARD_SECRET: 'test-forward-secret',
    LINE_CHANNEL_SECRET: 'test-line-secret', LINE_CHANNEL_ACCESS_TOKEN: 'test-line-token',
    DEVICE_PAIRING_SECRET: 'test-pairing-secret-at-least-24-characters',
    IMAGE_LINK_SECRET: 'test-image-link-secret-at-least-32-characters',
    FIREBASE_SERVICE_ACCOUNT_JSON: JSON.stringify({type:'service_account',project_id:'relay-integration-test',client_email:'test@relay-integration-test.iam.gserviceaccount.com',private_key:privateKey.export({type:'pkcs8',format:'pem'})}),
    PUBLIC_WORKER_URL:'https://worker.example', DEVICES:memoryKV(), RESULT_CACHE:memoryKV(),
    SLIP_QUEUE:{async sendBatch(batch) { jobs.push(...batch); }},
  };
  const ctx = {waitUntil(p) {background.push(p);}};
  const registration = await worker.fetch(new Request('https://worker.example/register-device', {
    method:'POST', headers:{'x-pairing-code':env.DEVICE_PAIRING_SECRET}, body:JSON.stringify({token}),
  }),env,ctx);
  assert.equal(registration.status,200);
  await Promise.all(background);
  let imageUrl;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    calls.push(String(url));
    if (url === 'https://oauth2.googleapis.com/token') return Response.json({access_token:'test-oauth-access-token',expires_in:3600});
    if (url === 'https://fcm.googleapis.com/v1/projects/relay-integration-test/messages:send') {
      const message = JSON.parse(options.body).message;
      assert.equal(message.token, token);
      assert.equal(message.data.type,'slip_image_ready');
      assert.equal(message.data.eventId,'123456789');
      assert.equal(message.data.amount,undefined);
      imageUrl = message.data.imageUrl;
      return Response.json({name:'mock-fcm-message'});
    }
    if (url === 'https://api-data.line.me/v2/bot/message/123456789/content') {
      assert.equal(options.headers.Authorization,'Bearer test-line-token');
      return new Response(Uint8Array.from([255,216,255]),{headers:{'content-type':'image/jpeg'}});
    }
    throw new Error('unexpected external request');
  };
  try {
    const body = JSON.stringify({events:[{type:'message',timestamp:Date.now(),message:{type:'image',id:'123456789'}}]});
    const signature = createHmac('sha256',env.FORWARD_SECRET).update(body).digest('base64');
    const req = () => new Request('https://worker.example/forwarded-webhook',{method:'POST',headers:{'x-relay-signature':signature},body});
    assert.equal((await worker.fetch(req(),env,ctx)).status,202);
    assert.equal(jobs.length,1);
    let ack = 0, retry = 0;
    const consume = async () => worker.queue({messages:[{body:jobs.shift().body,ack(){ack++;},retry(){retry++;}}]},env);
    await consume();
    assert.equal(ack,1); assert.equal(retry,0); assert.ok(imageUrl);
    const image = await worker.fetch(new Request(imageUrl),env,ctx);
    assert.equal(image.status,200);
    assert.deepEqual([...new Uint8Array(await image.arrayBuffer())],[255,216,255]);
    assert.equal(image.headers.get('cache-control'),'no-store, private');
    assert.equal((await worker.fetch(req(),env,ctx)).status,202);
    await consume();
    assert.equal(ack,2);
    assert.equal(calls.filter(url => url.includes('messages:send')).length,1);
    assert.equal(calls.some(url => url.includes('thunder.in.th')),false);
  } finally { globalThis.fetch = originalFetch; }
});
