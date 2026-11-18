import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, generateKeyPairSync } from 'node:crypto';
import {
  isConfirmedPayment, registerDevice, sendPaymentToDevices, pushConfigured, isFreshPaymentEventTime,
} from './fcm-delivery.mjs';

function memoryKv() {
  const values = new Map();
  return {
    values,
    async list({prefix = ''} = {}) {
      return {keys: [...values.keys()].filter(k => k.startsWith(prefix)).map(name => ({name}))};
    },
    async get(key, type) {
      const value = values.get(key) ?? null;
      return type === 'json' && value !== null ? JSON.parse(value) : value;
    },
    async put(key, value) { values.set(key, value); },
    async delete(key) { values.delete(key); },
  };
}
const pairing = 'random-32-byte-server-secret-test-key';
const firebaseCredentials = () => {
  const {privateKey} = generateKeyPairSync('rsa', {modulusLength:2048});
  return JSON.stringify({
    type:'service_account', project_id:'speaker-project',
    client_email:'push@speaker-project.iam.gserviceaccount.com',
    private_key: privateKey.export({type:'pkcs8',format:'pem'}),
  });
};
const paid = {
  type:'payment_matched', matchedAccount:true, duplicate:false,
  amount:180, messageId:'123456789', receivedAt:Date.now(),
};
test('accept confirmed matched payment only; OCR, duplicates and mismatches never push', () => {
  assert.equal(isConfirmedPayment(paid), true);
  for (const change of [
    {type:'slip_unmatched'}, {matchedAccount:false}, {duplicate:true},
    {amount:0}, {amount:-1}, {amount:Infinity}, {messageId:'invalid-id'},
  ]) assert.equal(isConfirmedPayment({...paid,...change}),false);
});
test('registration requires configured secret, exact pairing and a valid token', async () => {
  const kv = memoryKv();
  const env = {DEVICES:kv, DEVICE_PAIRING_SECRET:pairing,
    FIREBASE_SERVICE_ACCOUNT_JSON:firebaseCredentials()};
  const request = (code,token) => new Request('https://test.invalid/register-device', {
    method:'POST', headers:{'x-pairing-code':code,'content-type':'application/json'},
    body:JSON.stringify({token}),
  });
  assert.equal(pushConfigured(env),true);
  assert.equal((await registerDevice(request('nope','a'.repeat(120)),env)).status,401);
  assert.equal((await registerDevice(request(pairing,'invalid token'),env)).status,400);
  const good = await registerDevice(request(pairing,'a'.repeat(120)),env);
  assert.equal(good.status,200);
  assert.equal((await good.json()).registered,true);
  assert.equal(kv.values.size,1);
  assert.equal((await registerDevice(request(pairing,'a'.repeat(120)),env)).status,200);
  assert.equal(kv.values.size,1);
  assert.equal((await registerDevice(request(pairing,'a'.repeat(120)),{})).status,503);
});
test('sends only amount, eventId and source time through Firebase OAuth and FCM HTTP v1',async () => {
  const kv = memoryKv();
  const env = {DEVICES:kv, DEVICE_PAIRING_SECRET:pairing,FIREBASE_SERVICE_ACCOUNT_JSON:firebaseCredentials()};
  const token = 'fcm_registration_token_'.repeat(5);
  await registerDevice(new Request('https://test.invalid/register-device',{
    method:'POST',headers:{'x-pairing-code':pairing},
    body:JSON.stringify({token}),
  }),env);
  const calls = [];
  const mocked = async (url,options) => {
    calls.push({url,options});
    if (url === 'https://oauth2.googleapis.com/token') {
      const payload = options.body.toString();
      assert.match(payload,/grant_type=/);
      assert.match(payload,/assertion=/);
      return Response.json({access_token:'oauth-valid-very-long-test-token',expires_in:3600});
    }
    if (url === 'https://fcm.googleapis.com/v1/projects/speaker-project/messages:send') {
      const parsed = JSON.parse(options.body);
      assert.deepEqual(parsed.message.data,{type:'payment_matched',amount:'180.00',eventId:'123456789',eventTime:String(paid.receivedAt)});
      assert.equal(parsed.message.token,token);
      assert.equal(options.headers.Authorization,'Bearer oauth-valid-very-long-test-token');
      return Response.json({name:'projects/speaker-project/messages/1'});
    }
    throw Error('unexpected URL '+url);
  };
  const sent=await sendPaymentToDevices(paid,env,mocked);
  assert.equal(sent.sent,1);
  assert.equal(calls.length,2);
  const before=calls.length;
  assert.equal((await sendPaymentToDevices({...paid,duplicate:true},env,mocked)).ignored,true);
  assert.equal(calls.length,before);
});
test('no paired devices avoids token exchange or network calls',async()=>{
  const env={DEVICES:memoryKv(),DEVICE_PAIRING_SECRET:pairing,FIREBASE_SERVICE_ACCOUNT_JSON:firebaseCredentials()};
  const out=await sendPaymentToDevices(paid,env,async()=>{throw Error('unexpected network')});
  assert.equal(out.noDevices,true);
});

test('Firebase project 404 must not erase a valid device token',async()=>{
  const kv=memoryKv();
  const creds=JSON.parse(firebaseCredentials());
  creds.project_id='speaker-project-404';
  const env={
    DEVICES:kv,DEVICE_PAIRING_SECRET:pairing,
    FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify(creds),
  };
  const token='fcm_project404_'.repeat(6);
  const registration=await registerDevice(new Request('https://test.invalid/register-device',{
    method:'POST',headers:{'x-pairing-code':pairing},
    body:JSON.stringify({token}),
  }),env);
  assert.equal(registration.status,200);
  const mock=async url => url==='https://oauth2.googleapis.com/token'
    ? Response.json({access_token:'oauth-valid-very-long-test-token',expires_in:3600})
    : Response.json({error:{status:'NOT_FOUND',message:'Project does not exist'}},{status:404});
  await assert.rejects(sendPaymentToDevices(paid,env,mock),/fcm_send_404/);
  assert.equal(kv.values.size,1,'device token must not be deleted for project failure');
});

test('FCM explicit UNREGISTERED token is pruned without treating project errors as invalid',async()=>{
  const kv=memoryKv();
  const creds=JSON.parse(firebaseCredentials());
  creds.project_id='speaker-project-unregistered';
  const env={
    DEVICES:kv,DEVICE_PAIRING_SECRET:pairing,
    FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify(creds),
  };
  const token='fcm_unregistered_'.repeat(6);
  await registerDevice(new Request('https://test.invalid/register-device',{
    method:'POST',headers:{'x-pairing-code':pairing},
    body:JSON.stringify({token}),
  }),env);
  const mock=async url => url==='https://oauth2.googleapis.com/token'
    ? Response.json({access_token:'oauth-valid-very-long-test-token',expires_in:3600})
    : Response.json({error:{status:'NOT_FOUND',details:[
      {'@type':'type.googleapis.com/google.firebase.fcm.v1.FcmError',errorCode:'UNREGISTERED'},
    ]}},{status:404});
  const result=await sendPaymentToDevices(paid,env,mock);
  assert.equal(result.sent,0);
  assert.equal(kv.values.size,0);
});

test('rejects FCM delivery without original timestamp, and never sends stale money', async()=>{
  const now=1_800_000_000_000;
  assert.equal(isFreshPaymentEventTime(now-600_000,now),true);
  assert.equal(isFreshPaymentEventTime(now-600_001,now),false);
  assert.equal(isFreshPaymentEventTime(now+60_000,now),true);
  assert.equal(isFreshPaymentEventTime(now+60_001,now),false);
  assert.equal(isFreshPaymentEventTime(undefined,now),false);
  assert.equal(isFreshPaymentEventTime('1800000000000',now),false);
  const kv=memoryKv();
  const env={DEVICES:kv,DEVICE_PAIRING_SECRET:pairing,FIREBASE_SERVICE_ACCOUNT_JSON:'configured'};
  let count=0;
  const never=async()=>{count++;throw Error('no request allowed')};
  for(const result of [
    {...paid,receivedAt:undefined},
    {...paid,receivedAt:now-600_001},
    {...paid,receivedAt:now+60_001},
  ]) {
    const out=await sendPaymentToDevices(result,env,never,now/1000);
    assert.equal(out.sent,0);
    assert.equal(out.expired,true);
  }
  assert.equal(count,0,'outdated or untimestamped slips cannot call Firebase OAuth or FCM');
});
