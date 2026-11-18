import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac, generateKeyPairSync} from 'node:crypto';
import worker from './line-oa-sidecar.mjs';
import {registerDevice} from './fcm-delivery.mjs';

function kv() {
  const records = new Map();
  return {
    records,
    async get(key, mode) {
      const stored = records.get(key);
      return stored === undefined ? null : mode === 'json' ? JSON.parse(stored) : stored;
    },
    async put(key, value) { records.set(key, value); },
    async delete(key) { records.delete(key); },
    async list({prefix = ''} = {}) {
      return {keys:[...records.keys()].filter(k=>k.startsWith(prefix)).map(name=>({name})),
        list_complete:true};
    },
  };
}

const hmac = (secret, value) =>
  createHmac('sha256', secret).update(value).digest('base64');

test('signed LINE forward -> Queue -> Thunder -> exactly one FCM send for same messageId', async () => {
  const {privateKey} = generateKeyPairSync('rsa', {modulusLength:2048});
  const devices = kv();
  const cache = kv();
  const jobs = [];
  const calls = {line:0, thunder:0, oauth:0, fcm:0};
  const token = 'fcm_test_registration_token_'.repeat(5);
  const env = {
    FORWARD_SECRET:'integration-relay-key',
    LINE_CHANNEL_SECRET:'integration-line-secret',
    LINE_CHANNEL_ACCESS_TOKEN:'integration-line-token',
    THUNDER_API_KEY:'integration-thunder-key',
    DEVICE_PAIRING_SECRET:'integration-high-entropy-device-code-123456',
    FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify({
      type:'service_account',
      project_id:'speaker-integration-test',
      client_email:'speaker@speaker-integration-test.iam.gserviceaccount.com',
      private_key:privateKey.export({type:'pkcs8',format:'pem'}),
    }),
    DEVICES:devices,
    RESULT_CACHE:cache,
    SLIP_QUEUE:{async sendBatch(entries) {jobs.push(...entries);}},
  };

  const pairing = await registerDevice(new Request('https://example.invalid/register-device', {
    method:'POST', headers:{'x-pairing-code':env.DEVICE_PAIRING_SECRET},
    body:JSON.stringify({token}),
  }),env);
  assert.equal(pairing.status,200);

  async function forward(id, signed=true) {
    const raw = JSON.stringify({events:[{
      type:'message', timestamp:Date.now(),
      webhookEventId:'webhook-'+id, message:{type:'image',id},
    }]});
    const response=await worker.fetch(new Request('https://example.invalid/forwarded-webhook',{
      method:'POST', body:raw,
      headers:{'x-relay-signature': signed ? hmac(env.FORWARD_SECRET,raw) : 'bad'},
    }),env);
    return response;
  }

  const priorFetch = globalThis.fetch;
  let currentId = '';
  globalThis.fetch = async (url, options={}) => {
    if (url.startsWith('https://api-data.line.me/v2/bot/message/')) {
      assert.equal(options.headers.Authorization,'Bearer integration-line-token');
      currentId = url.split('/').at(-2);
      calls.line++;
      return new Response(Uint8Array.from([0xff,0xd8,0xff]),{
        headers:{'content-type':'image/jpeg'},
      });
    }
    if (url==='https://api.thunder.in.th/v2/verify/bank') {
      calls.thunder++;
      assert.equal(options.headers.Authorization,'Bearer integration-thunder-key');
      assert.equal(options.body.get('matchAccount'),'true');
      assert.equal(options.body.get('checkDuplicate'),'true');
      return Response.json({success:true,data:{
        amountInSlip:182,
        matchedAccount:currentId==='222' ? null : {
          bank:{code:'004'}, bankNumber:'1234567890',
        },
        isDuplicate:currentId==='333',
      }});
    }
    if (url==='https://oauth2.googleapis.com/token') {
      calls.oauth++;
      return Response.json({access_token:'oauth-integration-token-valid',expires_in:3600});
    }
    if (url==='https://fcm.googleapis.com/v1/projects/speaker-integration-test/messages:send') {
      calls.fcm++;
      assert.equal(options.headers.Authorization,'Bearer oauth-integration-token-valid');
      const payload=JSON.parse(options.body);
      assert.equal(payload.message.token,token);
      assert.equal(payload.message.data.type,'payment_matched');
      assert.equal(payload.message.data.amount,'182.00');
      assert.ok(Math.abs(Date.now() - Number(payload.message.data.eventTime)) < 10 * 60_000,
        'FCM must carry original LINE event time, not a fabricated delivery time');
      assert.ok(['111','555'].includes(payload.message.data.eventId),
        'only a confirmed matching slip may reach FCM');
      assert.equal(payload.message.android.priority,'HIGH');
      return Response.json({name:'projects/speaker-integration-test/messages/sent'});
    }
    throw new Error('unexpected_external_request:'+url);
  };

  async function consume() {
    const item = jobs.shift();
    assert.ok(item,'signed webhook must enqueue an image');
    let acks=0, retries=0;
    await worker.queue({messages:[{
      body:item.body,
      ack(){acks++;},
      retry(){retries++;},
    }]},env);
    assert.equal(acks,1,'queue must acknowledge the job');
    assert.equal(retries,0,'queue must not retry successfully handled job');
  }

  try {
    assert.equal((await forward('111')).status,202);
    assert.equal(jobs.length,1);
    await consume();
    assert.deepEqual({line:calls.line,thunder:calls.thunder,fcm:calls.fcm},
      {line:1,thunder:1,fcm:1});
    assert.equal((await cache.get('verification:111','json')).delivered,true);

    // An authentic LINE redelivery is enqueued, but neither the Thunder API
    // nor the user phone is called twice for the same cached messageId.
    assert.equal((await forward('111')).status,202);
    await consume();
    assert.deepEqual({line:calls.line,thunder:calls.thunder,fcm:calls.fcm},
      {line:1,thunder:1,fcm:1});

    assert.equal((await forward('222')).status,202);
    await consume();
    assert.equal((await cache.get('verification:222','json')).result.type,'slip_unmatched');
    assert.equal(calls.fcm,1,'wrong recipient must not trigger a money announcement');

    assert.equal((await forward('333')).status,202);
    await consume();
    assert.equal((await cache.get('verification:333','json')).result.type,'duplicate_slip');
    assert.equal(calls.fcm,1,'duplicate slip must not trigger a money announcement');

    const before=jobs.length;
    assert.equal((await forward('444',false)).status,401);
    assert.equal(jobs.length,before,'unsigned LINE events are not queued');

    assert.equal(calls.thunder,3);
    assert.equal(calls.fcm,1);

    // The server verifies a new slip without any paired phones. Keep the
    // confirmed result without re-verifying it when a phone pairs later.
    devices.records.clear();
    assert.equal((await forward('555')).status,202);
    await consume();
    assert.equal(calls.thunder,4);
    assert.equal(calls.fcm,1);
    assert.equal((await cache.get('verification:555','json')).delivered,false);

    const background=[];
    const enrolling=await worker.fetch(new Request('https://example.invalid/register-device',{
      method:'POST', headers:{'x-pairing-code':env.DEVICE_PAIRING_SECRET},
      body:JSON.stringify({token}),
    }),env,{waitUntil(task){background.push(task);}});
    assert.equal(enrolling.status,200);
    assert.equal(background.length,1);
    await Promise.all(background);
    assert.equal(calls.thunder,4,'pairing must use cached verification');
    assert.equal(calls.fcm,2,'pairing should deliver pending confirmed payment once');
    assert.equal((await cache.get('verification:555','json')).delivered,true);
  } finally {
    globalThis.fetch=priorFetch;
  }
});
