# LINE OA → Thunder sidecar (server-side; **not live yet**)

## Purpose

The existing Android app cannot obtain image bytes from an ordinary background LINE notification. LINE's **official Messaging API** can retrieve an image **sent to your LINE Official Account** if the original signed webhook event contains the image's message ID.

This separate Worker module:

1. Authenticates a LINE webhook's raw body with X-Line-Signature, or an explicitly forwarded copy using a different shared secret.
2. Places image message IDs in Cloudflare Queues (no image stored in queue).
3. Downloads the image from the official LINE content API using LINE_CHANNEL_ACCESS_TOKEN.
4. Uploads the image as multipart/form-data directly to Thunder v2 POST /verify/bank with checkDuplicate and matchAccount enabled.
5. Emits a signed, minimal result to EVENT_SINK_URL. Only payment_matched (not duplicate, matchedAccount present) may be announced as received funds. Other statuses are slip_unmatched or duplicate_slip.

## What this does NOT do

- It does **not** read images in personal LINE chats.
- It does **not** alter the existing LINE webhook URL. For this to process real events without changing that URL, the **existing webhook owner** must later authorize a small forwarding call from the existing backend to POST /forwarded-webhook. Until then no real LINE event arrives.
- It does **not** deliver events to the Android phone yet. A secure push receiver (preferably FCM) and device setup are still needed. Do not say the end-to-end goal is complete.
- It does **not** include API keys in GitHub, simulate a successful bank verification, or silently deploy configuration.
- It does **not** imply the Thunder API is free; check the account's actual quota and terms.

## Required Cloudflare bindings and secrets

Queue producer binding: SLIP_QUEUE; consumer: same Worker. No persistent raw images.
Secrets: LINE_CHANNEL_SECRET, LINE_CHANNEL_ACCESS_TOKEN, THUNDER_API_KEY, EVENT_SINK_SECRET, FORWARD_SECRET (only needed for forwarded events).
Environment variable: EVENT_SINK_URL (must be HTTPS).

Use a separate receiving endpoint for the signed result, not the existing LINE webhook; verify x-slip-signature over the **exact raw request body** before processing. EVENT_SINK_URL and EVENT_SINK_SECRET are required before enabling the queue consumer.

**Source route selection**:
- POST /line-webhook: direct LINE-signed events; do not change the currently configured webhook to this URL.
- POST /forwarded-webhook: existing handler may forward the original body, signed with its own FORWARD_SECRET, **only after approved**. Nothing automatically forwards events today.
- GET /health: readiness summary without exposing secrets.

## Current development checklist (18 Nov 2026)

- [x] Inspected existing Cloudflare Worker line-oa-slip-speaker: **placeholder only**, no secrets/bindings.
- [x] Inspected GitHub Thunder-LINE-Speaker- v0.4; built Android app, but no verified background image acquisition.
- [x] Designed and unit-tested LINE webhook HMAC, image download, Thunder image upload, matchAccount/duplicate guard, signed result delivery.
- [ ] Configure queue/Worker bindings and secrets in the owner's Cloudflare/LINE/Thunder accounts.
- [ ] Verify that the existing LINE Official Account webhook actually receives customer images; current endpoint/source is not present in the accessible repos.
- [ ] Arrange authorized forwarding from existing webhook **without changing its URL**.
- [ ] Build and connect a secure Android notification receiver (FCM) with the actual device registration.
- [ ] Run real end-to-end tests using a real LINE OA image, a non-sensitive test transfer, and vivo V30 Pro, including locked-screen behavior.
- [ ] Only then produce one final APK for installation.

## Official documentation

- LINE webhook and Get Content: https://developers.line.biz/en/docs/messaging-api/receiving-messages/
- Thunder image upload: https://document.thunder.in.th/th/v2/verify/bank/image
- Cloudflare Queues: https://developers.cloudflare.com/queues/get-started/

Run Node unit tests: `node --test server/line-oa-sidecar.test.mjs`.
