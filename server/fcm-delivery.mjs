/**
 * FCM data-push relay for verified LINE OA slips.
 * Device registration requires a high-entropy, server-side pairing secret.
 * No slip images, account IDs, or customer names are sent to FCM.
 */
const scope = 'https://www.googleapis.com/auth/firebase.messaging';
const maxDevices = 5;
const maxPaymentAgeMs = 10 * 60 * 1000;
const maxFutureSkewMs = 60 * 1000;
const encoded = new TextEncoder();
let cachedAccess = null;

function b64url(bytes) {
  let ascii = '';
  for (const byte of bytes) ascii += String.fromCharCode(byte);
  return btoa(ascii).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function secureEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}
function configuredServiceAccount(env) {
  if (!env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('firebase_unconfigured');
  let config;
  try { config = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON); }
  catch { throw new Error('firebase_invalid_json'); }
  if (config.type !== 'service_account' || !/^[a-z0-9-]{4,128}$/.test(config.project_id || '') ||
      !config.client_email?.includes('@') || !config.private_key?.includes('BEGIN PRIVATE KEY')) {
    throw new Error('firebase_credentials_invalid');
  }
  return config;
}
async function oauthToken(env, fetchFn, at) {
  const account = configuredServiceAccount(env);
  const cacheId = account.project_id + ':' + account.client_email;
  if (cachedAccess?.id === cacheId && cachedAccess?.expiresAt > at + 60) {
    return { accessToken: cachedAccess.token, projectId: account.project_id };
  }
  const der = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '')),
    c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, {
    name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256',
  }, false, ['sign']);
  const header = b64url(encoded.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64url(encoded.encode(JSON.stringify({
    iss: account.client_email, scope, aud: 'https://oauth2.googleapis.com/token',
    iat: at, exp: at + 3500,
  })));
  const unsigned = `${header}.${claims}`;
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoded.encode(unsigned));
  const assertion = `${unsigned}.${b64url(new Uint8Array(sig))}`;
  const response = await fetchFn('https://oauth2.googleapis.com/token', {
    method: 'POST', redirect: 'error',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
  });
  if (!response.ok) throw new Error(`google_token_${response.status}`);
  const token = await response.json();
  if (typeof token.access_token !== 'string' || token.access_token.length < 10) {
    throw new Error('google_token_malformed');
  }
  cachedAccess = {
    token: token.access_token, id: cacheId,
    expiresAt: at + Math.min(Number(token.expires_in) || 3600, 3600),
  };
  return { accessToken: cachedAccess.token, projectId: account.project_id };
}
async function deviceKey(token) {
  const hash = await crypto.subtle.digest('SHA-256', encoded.encode(token));
  return 'device:' + Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2, '0')).join('');
}
export function isConfirmedPayment(result) {
  return result?.type === 'payment_matched' &&
    result?.matchedAccount === true && result?.duplicate === false &&
    Number.isFinite(result?.amount) && result.amount > 0 &&
    result.amount <= 100_000_000 &&
    /^[0-9]{1,32}$/.test(result?.messageId || '');
}
export function isFreshPaymentEventTime(receivedAt, nowMs = Date.now()) {
  return Number.isSafeInteger(receivedAt) &&
    receivedAt >= nowMs - maxPaymentAgeMs &&
    receivedAt <= nowMs + maxFutureSkewMs;
}
export function pushConfigured(env) {
  return Boolean(env.DEVICES?.list && env.DEVICE_PAIRING_SECRET && env.FIREBASE_SERVICE_ACCOUNT_JSON);
}
export async function registerDevice(request, env) {
  if (!pushConfigured(env)) return Response.json({error:'not_configured'}, {status:503});
  const expected = env.DEVICE_PAIRING_SECRET;
  if (expected.length < 24) return Response.json({error:'invalid_server_configuration'}, {status:503});
  const provided = request.headers.get('x-pairing-code');
  if (!secureEqual(provided, expected)) return Response.json({error:'unauthorized'}, {status:401});
  if (Number(request.headers.get('content-length')) > 4096) return Response.json({error:'payload_too_large'}, {status:413});
  let body;
  try { body = await request.json(); } catch { return Response.json({error:'invalid_json'}, {status:400}); }
  const token = body?.token;
  if (typeof token !== 'string' || token.length < 20 || token.length > 4096 ||
      !/^[A-Za-z0-9_:\-]+$/.test(token)) return Response.json({error:'invalid_fcm_token'}, {status:400});
  const key = await deviceKey(token);
  const devices = await env.DEVICES.list({prefix:'device:',limit:20});
  if (devices.keys.length >= maxDevices && !devices.keys.some(k => k.name === key)) {
    return Response.json({error:'device_limit_reached'}, {status:409});
  }
  await env.DEVICES.put(key, JSON.stringify({token, pairedAt: Date.now()}), {expirationTtl:90 * 86400});
  return Response.json({registered:true,deviceCount:devices.keys.length + (devices.keys.some(k => k.name === key) ? 0 : 1)});
}
async function pushOne(accessToken, projectId, token, message, fetchFn) {
  const response = await fetchFn(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: 'POST', redirect: 'error',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'content-type':'application/json; charset=utf-8',
    },
    body: JSON.stringify({
      message: { token, data: message,
        android: { priority: 'HIGH', ttl: '120s' },
      },
    }),
  });
  if (response.ok) return 'sent';
  // An HTTP 404 can also mean the Firebase PROJECT does not exist.
  // Only erase a device token for an explicit UNREGISTERED token error.
  let error;
  try { error = await response.json(); } catch { /* Keep the token. */ }
  const details = Array.isArray(error?.error?.details) ? error.error.details : [];
  if (details.some(d => d['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError' &&
      d.errorCode === 'UNREGISTERED')) return 'unregistered';
  throw new Error(`fcm_send_${response.status}`);
}
export async function sendPaymentToDevices(result, env, fetchFn = fetch, at = Math.floor(Date.now() / 1000)) {
  if (!isConfirmedPayment(result)) return {sent:0, ignored:true};
  // FCM TTL limits storage, but not delayed callback processing on Android.
  // Carry the original LINE event time so the handset can reject stale alerts.
  if (!isFreshPaymentEventTime(result.receivedAt, at * 1000)) {
    return {sent:0, ignored:true, expired:true};
  }
  if (!pushConfigured(env)) throw new Error('push_not_configured');
  const listing = await env.DEVICES.list({prefix:'device:', limit:100});
  if (!listing.keys?.length) return {sent:0, noDevices:true};
  const {accessToken,projectId} = await oauthToken(env,fetchFn,at);
  const payload = {
    type:'payment_matched',
    amount: result.amount.toFixed(2),
    eventId: result.messageId,
    eventTime: String(result.receivedAt),
  };
  let sent = 0;
  for (const key of listing.keys) {
    const stored = await env.DEVICES.get(key.name, 'json');
    if (!stored?.token) continue;
    const state = await pushOne(accessToken,projectId,stored.token,payload,fetchFn);
    if (state === 'sent') sent++;
    else if (state === 'unregistered') await env.DEVICES.delete(key.name);
  }
  return {sent};
}


/**
 * Send individually signed image-retrieval tickets to paired phones.
 * Unlike payment_matched, this carries NO amount and no claim of verification.
 */
export async function sendDataToDevices(env, payloadForDevice, fetchFn=fetch, at=Math.floor(Date.now()/1000)) {
  if (!pushConfigured(env)) throw new Error('push_not_configured');
  const listing=await env.DEVICES.list({prefix:'device:',limit:100});
  if (!listing.keys?.length) return {sent:0,noDevices:true};
  const {accessToken,projectId}=await oauthToken(env,fetchFn,at);
  let sent=0;
  for(const key of listing.keys){
    const stored=await env.DEVICES.get(key.name,'json');
    if (!stored?.token || !/^device:[a-f0-9]{64}$/.test(key.name)) continue;
    const data=await payloadForDevice(key.name.slice(7));
    if (!data || Object.values(data).some(v=>typeof v!=='string')) throw new Error('invalid_fcm_data');
    if (JSON.stringify(data).length>3500) throw new Error('fcm_data_too_large');
    const result=await pushOne(accessToken,projectId,stored.token,data,fetchFn);
    if(result==='sent') sent++;
    if(result==='unregistered') await env.DEVICES.delete(key.name);
  }
  return {sent};
}
