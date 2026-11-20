/**
 * Opt-in LINE OA image relay: deliver a short-lived authorized download URL
 * to the paired Android app. The phone OCRs the real image locally. No Thunder
 * API call is made in this mode, and a slip is NEVER called bank verified.
 */
import { pushConfigured, sendDataToDevices } from './fcm-delivery.mjs';

const TICKET_MS = 120_000;
const MAX_IMAGE = 4_194_304;
const MAX_AGE = 10 * 60_000;
const MIME = new Set(['image/jpeg', 'image/png', 'image/webp']);
const BASE_DEFAULT = 'https://line-oa-slip-speaker.jirapatcip2557.workers.dev';
const enc = new TextEncoder();

const validMessageId = id => typeof id === 'string' && /^[0-9]{1,32}$/.test(id);
const freshTime = (timestamp, now = Date.now()) =>
  Number.isSafeInteger(timestamp) && timestamp <= now + 60_000 && timestamp >= now - MAX_AGE;
const enabled = env => env.SLIP_PROCESSING_MODE === 'image_relay';

export function imageRelayConfigured(env) {
  return enabled(env) && Boolean(
    env.LINE_CHANNEL_SECRET && env.LINE_CHANNEL_ACCESS_TOKEN &&
    typeof env.IMAGE_LINK_SECRET === 'string' && env.IMAGE_LINK_SECRET.length >= 32 &&
    env.SLIP_QUEUE?.sendBatch && env.DEVICES?.get && env.RESULT_CACHE?.get &&
    env.RESULT_CACHE?.put && pushConfigured(env)
  );
}

function baseUrl(env) {
  const value = env.PUBLIC_WORKER_URL || BASE_DEFAULT;
  const parsed = new URL(value);
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password ||
      parsed.search || parsed.hash || parsed.pathname !== '/' ||
      !/^[a-z0-9.-]+$/i.test(parsed.hostname)) throw new Error('bad_public_url');
  return parsed.origin;
}

async function signature(secret, id, at, exp, device) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret),
    {name:'HMAC', hash:'SHA-256'}, false, ['sign']);
  const signed = await crypto.subtle.sign('HMAC', key,
    enc.encode([id, String(at), String(exp), device].join('\n')));
  return Array.from(new Uint8Array(signed), b => b.toString(16).padStart(2,'0')).join('');
}

function secureMatch(a,b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length,b.length); i++)
    diff |= (a.charCodeAt(i)||0) ^ (b.charCodeAt(i)||0);
  return diff === 0;
}

export async function imageTicket(id, at, device, env, now = Date.now()) {
  if (!imageRelayConfigured(env) || !validMessageId(id) || !freshTime(at,now) ||
      !/^[a-f0-9]{64}$/.test(device)) throw new Error('ticket_invalid');
  const exp = now + TICKET_MS;
  const sig = await signature(env.IMAGE_LINK_SECRET, id, at, exp, device);
  const u = new URL('/slip-image', baseUrl(env));
  u.searchParams.set('id',id); u.searchParams.set('at',String(at));
  u.searchParams.set('exp',String(exp)); u.searchParams.set('device',device);
  u.searchParams.set('sig',sig);
  return u.toString();
}

export async function validateImageTicket(url, env, now = Date.now()) {
  if (!imageRelayConfigured(env)) return false;
  const id=url.searchParams.get('id'), device=url.searchParams.get('device');
  const atS=url.searchParams.get('at'), expS=url.searchParams.get('exp'), sig=url.searchParams.get('sig');
  if (!validMessageId(id) || !/^[a-f0-9]{64}$/.test(device||'') ||
      !/^[0-9]{13}$/.test(atS||'') || !/^[0-9]{13}$/.test(expS||'') ||
      !/^[a-f0-9]{64}$/.test(sig||'')) return false;
  const at=Number(atS), exp=Number(expS);
  if (!freshTime(at,now) || exp < now || exp > now+TICKET_MS ||
      exp-at > MAX_AGE + TICKET_MS) return false;
  const valid = await signature(env.IMAGE_LINK_SECRET, id, at, exp, device);
  if (!secureMatch(sig,valid)) return false;
  // Revoked/expired devices cannot use old signed URLs.
  const registered = await env.DEVICES?.get?.('device:'+device,'json');
  return typeof registered?.token === 'string';
}

export async function serveSlipImage(request, env, fetchFn = fetch, now = Date.now()) {
  const u = new URL(request.url);
  if (request.method !== 'GET' || u.pathname !== '/slip-image')
    return new Response('Not found',{status:404});
  if (!(await validateImageTicket(u, env, now)))
    return new Response('Unauthorized',{status:401,headers:{'cache-control':'no-store'}});
  const id = u.searchParams.get('id');
  const result = await fetchFn(
    'https://api-data.line.me/v2/bot/message/'+encodeURIComponent(id)+'/content',
    {headers:{Authorization:'Bearer '+env.LINE_CHANNEL_ACCESS_TOKEN},redirect:'error'}
  );
  if (!result.ok) return new Response('Image unavailable',{status:502});
  const mime=(result.headers.get('content-type')||'').split(';')[0].trim().toLowerCase();
  const len=Number(result.headers.get('content-length')||0);
  if (!MIME.has(mime) || len > MAX_IMAGE)
    return new Response('Unsupported image',{status:415});
  const bytes=await result.arrayBuffer();
  if (bytes.byteLength < 1 || bytes.byteLength > MAX_IMAGE)
    return new Response('Invalid image size',{status:413});
  return new Response(bytes,{status:200,headers:{
    'content-type':mime, 'content-length':String(bytes.byteLength),
    'cache-control':'no-store, private', 'x-content-type-options':'nosniff',
    'referrer-policy':'no-referrer'
  }});
}

export async function sendImageRelay(messageId, receivedAt, env, fetchFn = fetch, now = Date.now()) {
  if (!imageRelayConfigured(env)) throw new Error('relay_not_configured');
  if (!validMessageId(messageId) || !freshTime(receivedAt, now))
    return {sent:0, ignored:true, expired:true};
  return sendDataToDevices(env, async deviceHash => ({
    type:'slip_image_ready', eventId:messageId, eventTime:String(receivedAt),
    imageUrl:await imageTicket(messageId, receivedAt, deviceHash, env, now),
  }), fetchFn);
}

export async function processImageRelayJob(job, env, fetchFn = fetch, now = Date.now()) {
  if (!imageRelayConfigured(env)) throw new Error('relay_not_configured');
  const id=job.body?.messageId, receivedAt=job.body?.receivedAt;
  if (!validMessageId(id) || !freshTime(receivedAt,now)) {
    job.ack(); return 'expired_or_invalid';
  }
  const cacheKey='image-relay:'+id;
  const cached=await env.RESULT_CACHE.get(cacheKey,'json');
  if (cached?.delivered) { job.ack(); return 'duplicate'; }
  if (!cached) await env.RESULT_CACHE.put(cacheKey, JSON.stringify({
    receivedAt, delivered:false
  }),{expirationTtl:600});
  const outcome=await sendImageRelay(id,receivedAt,env,fetchFn,now);
  if (outcome.sent>0) await env.RESULT_CACHE.put(cacheKey,JSON.stringify({
    receivedAt,delivered:true
  }),{expirationTtl:600});
  // If no device is paired, the short-lived image cannot be read yet.
  // Do not claim the event was delivered; pairing may drain pending items.
  job.ack();
  return outcome.sent>0?'sent':'pending';
}

export async function deliverPendingImages(env, fetchFn = fetch, now = Date.now()) {
  if (!imageRelayConfigured(env) || !env.RESULT_CACHE?.list)
    return {scanned:0,sent:0};
  const results=await env.RESULT_CACHE.list({prefix:'image-relay:',limit:100});
  let scanned=0,sent=0;
  for(const item of results.keys||[]){
    const entry=await env.RESULT_CACHE.get(item.name,'json');
    const id=item.name.slice('image-relay:'.length);
    if (!entry || entry.delivered || !freshTime(entry.receivedAt,now)) continue;
    scanned++;
    const result=await sendImageRelay(id,entry.receivedAt,env,fetchFn,now);
    if (result.sent>0){
      sent+=result.sent;
      await env.RESULT_CACHE.put(item.name,JSON.stringify({
        receivedAt:entry.receivedAt,delivered:true
      }),{expirationTtl:600});
    }
  }
  return {scanned,sent};
}
