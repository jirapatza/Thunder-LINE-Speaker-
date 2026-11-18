/**
 * LINE OA -> Thunder verified-slip sidecar for Cloudflare Workers + Queues.
 * Does not change an existing webhook URL or access private LINE chats.
 * An existing handler must intentionally forward signed events to this Worker.
 */
import { registerDevice, sendPaymentToDevices, pushConfigured } from './fcm-delivery.mjs';

const IMAGE_LIMIT = 4_194_304;
const WEBHOOK_LIMIT = 512_000;
const MAX_NOTIFICATION_AGE_MS = 10 * 60 * 1000;
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/gif', 'image/webp']);
const encoder = new TextEncoder();

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}
async function hmacBase64(secret, input) {
  if (!secret) throw new Error('missing_hmac_secret');
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const bytes = new Uint8Array(await crypto.subtle.sign('HMAC', key, input));
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let mismatch = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i++) mismatch |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return mismatch === 0;
}
export async function verifySignature(secret, body, supplied) {
  if (!secret || !supplied || !(body instanceof ArrayBuffer)) return false;
  return constantTimeEquals(await hmacBase64(secret, body), supplied);
}
function requirements(env) {
  return Boolean(env.LINE_CHANNEL_SECRET && env.LINE_CHANNEL_ACCESS_TOKEN && env.THUNDER_API_KEY &&
    env.SLIP_QUEUE?.sendBatch && env.RESULT_CACHE?.get && env.RESULT_CACHE?.put && pushConfigured(env));
}
function isImageMessage(e) {
  return e?.type === 'message' && e.message?.type === 'image' &&
    typeof e.message.id === 'string' && /^[0-9]{1,32}$/.test(e.message.id);
}
export function isFreshPaymentTime(timestamp, now = Date.now()) {
  return Number.isFinite(timestamp) && timestamp <= now + 60_000 && timestamp >= now - MAX_NOTIFICATION_AGE_MS;
}

export async function enqueueImageEvents(payload, env) {
  const events = Array.isArray(payload?.events) ? payload.events : [];
  // LINE may redeliver the same event. Avoid duplicated queue items within
  // the current payload; the verification cache handles later redeliveries.
  // Do not silently discard events beyond an arbitrary 50-item limit.
  const unique = new Map();
  for (const e of events) {
    // LINE event timestamps are milliseconds from the source. Fail closed
    // if absent instead of fabricating a fresh arrival time for old slips.
    if (isImageMessage(e) && isFreshPaymentTime(e.timestamp) &&
        !unique.has(e.message.id)) unique.set(e.message.id, e);
  }
  if (!unique.size) return 0;
  if (!env.SLIP_QUEUE?.sendBatch) throw new Error('queue_not_configured');
  const images = [...unique.values()];
  for (let start = 0; start < images.length; start += 100) {
    const batch = images.slice(start, start + 100).map(e => ({body: {
      messageId: e.message.id,
      eventId: String(e.webhookEventId || e.message.id).slice(0, 100),
      receivedAt: e.timestamp,
    }}));
    await env.SLIP_QUEUE.sendBatch(batch);
  }
  return images.length;
}
async function lineImage(messageId, token, fetchFn) {
  const url = `https://api-data.line.me/v2/bot/message/${encodeURIComponent(messageId)}/content`;
  const response = await fetchFn(url, {
    headers: { Authorization: `Bearer ${token}` }, redirect: 'error',
  });
  if (!response.ok) throw new Error(`line_download_${response.status}`);
  const mime = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
  if (!IMAGE_TYPES.has(mime)) throw new Error('unsupported_image_type');
  const size = Number(response.headers.get('content-length'));
  if (Number.isFinite(size) && size > IMAGE_LIMIT) throw new Error('image_too_large');
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength < 1 || bytes.byteLength > IMAGE_LIMIT) throw new Error('image_size_invalid');
  return { bytes, mime };
}
export async function verifySlipImage(messageId, env, fetchFn = fetch) {
  if (!env.LINE_CHANNEL_ACCESS_TOKEN || !env.THUNDER_API_KEY) throw new Error('api_credentials_missing');
  if (!/^[0-9]{1,32}$/.test(messageId)) throw new Error('invalid_message_id');
  const image = await lineImage(messageId, env.LINE_CHANNEL_ACCESS_TOKEN, fetchFn);
  const form = new FormData();
  form.set('image', new Blob([image.bytes], { type: image.mime }), 'line-slip-image');
  form.set('checkDuplicate', 'true');
  form.set('matchAccount', 'true');
  const response = await fetchFn('https://api.thunder.in.th/v2/verify/bank', {
    method: 'POST', headers: { Authorization: `Bearer ${env.THUNDER_API_KEY}` },
    body: form, redirect: 'error',
  });
  if (!response.ok) throw new Error(`thunder_verify_${response.status}`);
  const result = await response.json();
  const data = result?.data;
  if (result?.success !== true || !data) throw new Error('thunder_invalid_response');
  const amount = Number(data.amountInSlip);
  if (!Number.isFinite(amount) || amount <= 0 || amount > 100_000_000) throw new Error('invalid_verified_amount');
  // Thunder declares matchedAccount as an object with bank/account details, or null.
  // Never treat arbitrary truthy JSON (including a string or an empty object) as a match.
  const account = data.matchedAccount;
  const matched = account !== null &&
    typeof account === 'object' && !Array.isArray(account) &&
    typeof account.bankNumber === 'string' && account.bankNumber.trim().length > 0 &&
    typeof account.bank?.code === 'string' && account.bank.code.trim().length > 0;
  // The API declares isDuplicate as a required boolean. Missing or malformed
  // duplicate status must never be announced as a new deposit.
  const duplicate = data.isDuplicate !== false;
  return {
    type: matched && !duplicate ? 'payment_matched' : (duplicate ? 'duplicate_slip' : 'slip_unmatched'),
    amount: Number(amount.toFixed(2)),
    matchedAccount: matched, duplicate, messageId,
  };
}
export async function dispatchResult(result, env, fetchFn = fetch) {
  if (!env.EVENT_SINK_URL || !env.EVENT_SINK_SECRET) throw new Error('event_sink_unconfigured');
  const url = new URL(env.EVENT_SINK_URL);
  if (url.protocol !== 'https:') throw new Error('event_sink_must_use_https');
  const payload = JSON.stringify({ ...result, source: 'line_oa_thunder' });
  const signature = await hmacBase64(env.EVENT_SINK_SECRET, encoder.encode(payload));
  const response = await fetchFn(url.toString(), {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-slip-signature': signature },
    body: payload, redirect: 'error',
  });
  if (!response.ok) throw new Error(`event_sink_${response.status}`);
}
/**
 * Complete unsent verified results after a device is paired. Each entry is
 * retained at most three days; FCM acceptance is not proof the phone spoke.
 * Android filters duplicates by message ID.
 */
export async function deliverPendingResults(env, pageLimit = 100, maxPages = 5) {
  if (!env.RESULT_CACHE?.list || !pushConfigured(env)) return { scanned: 0, delivered: 0 };
  let cursor;
  let scanned = 0;
  let delivered = 0;
  for (let page = 0; page < maxPages; page++) {
    const listing = await env.RESULT_CACHE.list({
      prefix: 'verification:', limit: pageLimit, ...(cursor ? {cursor} : {}),
    });
    for (const item of listing.keys || []) {
      const state = await env.RESULT_CACHE.get(item.name, 'json');
      if (!state || state.delivered || !state.result) continue;
      scanned++;
      // A phone paired days later must never announce old transfers as new.
      // Legacy cache items without a timestamp are treated as expired.
      if (!isFreshPaymentTime(state.receivedAt)) {
        await env.RESULT_CACHE.put(item.name,
          JSON.stringify({...state, delivered:true, expired:true}),
          {expirationTtl:3 * 86400});
        continue;
      }
      const outcome = await sendPaymentToDevices({...state.result, receivedAt: state.receivedAt}, env);
      if (outcome.noDevices) return {scanned, delivered};
      if (outcome.ignored || outcome.sent > 0) {
        if (env.EVENT_SINK_URL && env.EVENT_SINK_SECRET) {
          await dispatchResult(state.result, env);
        }
        await env.RESULT_CACHE.put(item.name,
          JSON.stringify({result:state.result,delivered:true}),
          {expirationTtl:3 * 86400});
        delivered++;
      }
    }
    if (listing.list_complete || !listing.cursor) break;
    cursor = listing.cursor;
  }
  return {scanned, delivered};
}

export default {
  async fetch(request, env, ctx) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && path === '/') return new Response('LINE OA Slip Speaker พร้อมทำงาน (รอกำหนดค่าระบบเชื่อมต่อ)', {headers:{'content-type':'text/plain; charset=utf-8'}});
    if (request.method === 'GET' && path === '/health') {
      let pairedDevices = 0;
      if (env.DEVICES?.list) {
        try { pairedDevices = (await env.DEVICES.list({prefix:'device:',limit:20})).keys?.length || 0; }
        catch { /* No secrets exposed by health. */ }
      }
      return json({
        service: 'line-oa-slip-speaker',
        // Configured credentials do NOT mean the original LINE webhook is
        // forwarding events, or that a vivo handset has successfully spoken.
        status: requirements(env) && pairedDevices > 0 ? 'ready_for_live_test' : 'needs_configuration',
        endToEndVerified: false,
        originalWebhookForwarding: 'not_verified',
        pairedDevices,
        lineConfigured: Boolean(env.LINE_CHANNEL_SECRET && env.LINE_CHANNEL_ACCESS_TOKEN),
        thunderConfigured: Boolean(env.THUNDER_API_KEY),
        fcmConfigured: Boolean(env.FIREBASE_SERVICE_ACCOUNT_JSON),
      });
    }
    if (request.method === 'POST' && path === '/register-device') {
      const response = await registerDevice(request, env);
      // A user may pair after a slip was verified. Drain pending verifications
      // in the background rather than dropping the money announcement.
      if (response.ok && ctx?.waitUntil) {
        ctx.waitUntil(deliverPendingResults(env).catch(() => {
          // A later registration / queue retry can resume unsent results.
        }));
      }
      return response;
    }
    if (request.method !== 'POST' || !['/line-webhook', '/forwarded-webhook'].includes(path)) return json({ error: 'not_found' }, 404);
    if (!requirements(env)) return json({ error: 'not_configured' }, 503);
    if (path === '/forwarded-webhook' && !env.FORWARD_SECRET) return json({ error: 'forward_not_configured' }, 503);
    if (Number(request.headers.get('content-length')) > WEBHOOK_LIMIT) return json({ error: 'payload_too_large' }, 413);
    const raw = await request.arrayBuffer();
    if (!raw.byteLength || raw.byteLength > WEBHOOK_LIMIT) return json({ error: 'invalid_payload_size' }, 413);
    const secret = path === '/line-webhook' ? env.LINE_CHANNEL_SECRET : env.FORWARD_SECRET;
    const signature = path === '/line-webhook' ? request.headers.get('x-line-signature') : request.headers.get('x-relay-signature');
    if (!(await verifySignature(secret, raw, signature))) return json({ error: 'invalid_signature' }, 401);
    let parsed;
    try { parsed = JSON.parse(new TextDecoder().decode(raw)); }
    catch { return json({ error: 'invalid_json' }, 400); }
    try {
      const queued = await enqueueImageEvents(parsed, env);
      return json({ accepted: true, queued }, 202);
    } catch {
      return json({ error: 'queue_failed' }, 503);
    }
  },
  async queue(batch, env) {
    for (const job of batch.messages) {
      try {
        if (!requirements(env)) throw new Error('worker_not_configured');
        const messageId = job.body?.messageId;
        if (typeof messageId !== 'string' || !/^[0-9]{1,32}$/.test(messageId)) {
          job.ack();
          continue;
        }
        const key = 'verification:' + messageId;
        if (!isFreshPaymentTime(job.body?.receivedAt)) {
          // Old queue envelopes lacked a source timestamp. Do not verify or
          // announce them: their images may belong to arbitrarily old transfers.
          const cached = await env.RESULT_CACHE.get(key, 'json');
          if (cached && !cached.delivered) {
            await env.RESULT_CACHE.put(key,
              JSON.stringify({...cached, delivered:true, expired:true}),
              {expirationTtl:3 * 86400});
          }
          job.ack();
          continue;
        }
        let state = await env.RESULT_CACHE.get(key, 'json');
        if (!state) {
          const result = await verifySlipImage(messageId, env);
          state = {result, delivered:false, receivedAt:job.body.receivedAt, verifiedAt:Date.now()};
          // Cache the result BEFORE delivery, so a retry cannot re-verify the
          // same slip (and incorrectly classify the second attempt as duplicate).
          await env.RESULT_CACHE.put(key, JSON.stringify(state), {expirationTtl: 3 * 86400});
        }
        if (!state.delivered) {
          if (!isFreshPaymentTime(state.receivedAt)) {
            await env.RESULT_CACHE.put(key, JSON.stringify({...state, delivered:true,expired:true}),
              {expirationTtl:3 * 86400});
            job.ack();
            continue;
          }
          const outcome = await sendPaymentToDevices({...state.result, receivedAt: state.receivedAt}, env);
          // No paired devices or a token rejected by FCM: persist as pending.
          // Never mark a verified payment delivered without an accepted send.
          if (outcome.noDevices || (!outcome.ignored && outcome.sent === 0)) {
            job.ack();
            continue;
          }
          if (env.EVENT_SINK_URL && env.EVENT_SINK_SECRET) {
            await dispatchResult(state.result, env);
          }
          await env.RESULT_CACHE.put(key,
            JSON.stringify({result:state.result,delivered:true}),
            {expirationTtl: 3 * 86400});
        }
        job.ack();
      } catch {
        // At-least-once queue delivery; Android deduplicates by messageId.
        job.retry();
      }
    }
  },
};
