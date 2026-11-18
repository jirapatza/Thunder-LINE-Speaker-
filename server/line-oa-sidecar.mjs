/**
 * LINE OA -> Thunder verified-slip sidecar for Cloudflare Workers + Queues.
 * Does not change an existing webhook URL or access private LINE chats.
 * An existing handler must intentionally forward signed events to this Worker.
 */
const IMAGE_LIMIT = 4_194_304;
const WEBHOOK_LIMIT = 512_000;
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
  return Boolean(env.LINE_CHANNEL_SECRET && env.LINE_CHANNEL_ACCESS_TOKEN && env.THUNDER_API_KEY && env.SLIP_QUEUE);
}
function isImageMessage(e) {
  return e?.type === 'message' && e.message?.type === 'image' &&
    typeof e.message.id === 'string' && /^[0-9]{1,32}$/.test(e.message.id);
}
export async function enqueueImageEvents(payload, env) {
  const events = Array.isArray(payload?.events) ? payload.events : [];
  const images = events.filter(isImageMessage).slice(0, 50);
  if (images.length === 0) return 0;
  if (!env.SLIP_QUEUE?.sendBatch) throw new Error('queue_not_configured');
  await env.SLIP_QUEUE.sendBatch(images.map(e => ({ body: {
    messageId: e.message.id,
    eventId: String(e.webhookEventId || e.message.id).slice(0, 100),
  } })));
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
  const matched = Boolean(data.matchedAccount);
  const duplicate = data.isDuplicate === true;
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
export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && path === '/health') {
      return json({ service: 'line-oa-slip-speaker',
        status: requirements(env) && env.EVENT_SINK_URL && env.EVENT_SINK_SECRET ? 'configured' : 'needs_configuration' });
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
        const result = await verifySlipImage(job.body?.messageId, env);
        await dispatchResult(result, env);
        job.ack();
      } catch {
        // Retries are handled by Cloudflare Queues, never log private image content.
        job.retry();
      }
    }
  },
};
