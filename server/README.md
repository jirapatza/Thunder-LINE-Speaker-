# Thunder LINE Speaker — LINE OA / Thunder / FCM

## สถานะจริง ณ 18 พฤศจิกายน 2569

**ระบบยังไม่ครบวงจรและยังไม่ควรติดตั้ง APK รอบใหม่**

ส่วนที่ทำเสร็จและตรวจสอบได้:

- Android: โค้ดรุ่น `0.5-dev` รับการแจ้งเตือน LINE, ทดลอง OCR, รับผลตรวจจาก FCM, ป้องกันพูดซ้ำด้วย event ID, ตั้งค่าการจับคู่ด้วยรหัสส่วนตัว; Android Build ผ่าน GitHub Actions ใน PR #3
- Server: โค้ดอ่านรูป LINE OA, ตรวจสลิปผ่าน Thunder v2, เช็ก `matchedAccount` และ `isDuplicate`, ส่ง FCM เฉพาะ `payment_matched`; Node tests ผ่าน GitHub Actions
- GitHub: PR #1, #2, #3 และ #5 merged เข้า `main`; PR #5 เป็นการแก้ความปลอดภัยการตรวจยอดและระบบส่ง FCM
- Cloudflare: อัปโหลด Worker `line-oa-slip-speaker` จาก `server/line-oa-sidecar.mjs` และ `server/fcm-delivery.mjs` สำเร็จ; อัปโหลดโค้ดแก้ไขล่าสุดจาก PR #5 แล้วและอ่านกลับตรวจพบการเก็บยอดรอส่งกับการตรวจบัญชีที่เข้มงวดขึ้น
- Cloudflare Queue: `line-oa-slip-verification` สร้างแล้ว และเชื่อมเป็น consumer ของ Worker แล้ว (batch 1, retry 3)
- Cloudflare KV: `line-oa-speaker-devices` และ `line-oa-speaker-results` ผูกกับ Worker แล้ว
- Worker ไม่มีคีย์ LINE / Thunder / Firebase อยู่ใน Secrets จึงอยู่ในโหมดปิดการรับงานจริง (`needs_configuration`); **ไม่ได้ส่งสลิปจริง**
- หากสลิปผ่าน Thunder แต่ยังไม่มีมือถือจับคู่ ระบบจะเก็บผลใน RESULT_CACHE (TTL 3 วัน) โดย **ยังไม่ทำเครื่องหมายว่าส่งสำเร็จ** และจะพยายามส่งเมื่อมีการจับคู่อุปกรณ์สำเร็จ; FCM ตอบรับไม่ได้ยืนยันว่าเสียงดังบนมือถือจริง
- ไม่ได้เปลี่ยน Webhook LINE OA เดิม และไม่ได้แก้โปรเจกต์ Bombim00/Project-1

ส่วนที่ต้องมีเพื่อเปิดใช้งานจริง:

1. ยืนยันว่าเป็น **LINE Official Account** ที่รับรูปสลิป ไม่ใช่แชต LINE ส่วนตัว; รูปจากแชตส่วนตัวไม่สามารถโหลดด้วย LINE Messaging API
2. เจ้าของ LINE OA ต้องให้ `LINE_CHANNEL_SECRET` และ `LINE_CHANNEL_ACCESS_TOKEN` ผ่าน Cloudflare Worker Secrets (ไม่ใส่ลง GitHub หรือแชตสาธารณะ)
3. ต้องมี `THUNDER_API_KEY` ที่ใช้งานจริงและมีโควตาสำหรับตรวจสลิปผ่าน `POST https://api.thunder.in.th/v2/verify/bank`
4. ต้องมี Firebase project ที่เปิด FCM HTTP v1, service account ให้ Worker ผ่าน `FIREBASE_SERVICE_ACCOUNT_JSON` และค่า `FIREBASE_ANDROID_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID` ใน GitHub Actions repository variables
5. ต้องตั้ง `DEVICE_PAIRING_SECRET` อย่างน้อย 24 อักขระใน Worker Secret แล้วจับคู่แอปบน vivo V30 Pro หนึ่งครั้ง (ค่า secret ไม่ควรฝังใน APK หรือ repo)
6. ต้องเข้าถึงเจ้าของ **Webhook เดิม** เพื่อเพิ่มการส่งสำเนาเหตุการณ์อย่างได้รับอนุญาตไป `/forwarded-webhook` พร้อม `x-relay-signature` HMAC และ `FORWARD_SECRET`; **ไม่ต้องเปลี่ยน URL Webhook เดิม**
7. ทดสอบปลายทางจริง: LINE OA ส่งภาพ → Worker ได้ event → Thunder ตรวจว่าตรงบัญชี → FCM ส่งเข้าเครื่อง → มีเสียงเมื่อ LINE ปิดและขณะล็อกหน้าจอ → ไม่พูดซ้ำและไม่พูดผิดเมื่อสลิปซ้ำ/ผิดบัญชี

**ยังไม่พบที่อยู่หรือโค้ดของ Webhook LINE OA เดิมใน 4 repos ที่เชื่อมไว้** ดังนั้นยังไม่แก้หรือส่งต่อเหตุการณ์แทนเจ้าของระบบโดยไม่ทราบเส้นทางจริง

## Architecture

```text
ลูกค้าส่งรูป -> LINE OA เดิม -> Webhook เดิม (ต้องต่อสำเนาแบบ HMAC)
    -> Worker /forwarded-webhook -> Cloudflare Queue
    -> LINE Content API (messageId) -> Thunder verify/bank multipart image
    -> Result Cache KV (กันตรวจซ้ำ/ค่าบริการซ้ำ)
    -> เฉพาะ matched && !duplicate -> FCM HTTP v1
    -> Android FirebaseMessagingService -> notification + Thai TTS
```

Webhook ส่วนตัวของ LINE ไม่อยู่ในขอบเขต และ Android NotificationListenerService ไม่สามารถดึงรูปจากทุกแชต LINE ที่ปิดอยู่ได้

### Cloudflare Worker configuration

Bindings ที่สร้างและตรวจสอบแล้ว:

- `SLIP_QUEUE`: queue `line-oa-slip-verification`
- `DEVICES`: KV `line-oa-speaker-devices`
- `RESULT_CACHE`: KV `line-oa-speaker-results`

Secrets ที่ยังขาด: `LINE_CHANNEL_SECRET`, `LINE_CHANNEL_ACCESS_TOKEN`, `THUNDER_API_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `DEVICE_PAIRING_SECRET`, `FORWARD_SECRET` (เฉพาะกรณีใช้งาน forwarding)

Optional: `EVENT_SINK_URL` และ `EVENT_SINK_SECRET` สำหรับระบบปลายทางอีกตัวหนึ่งที่ต้องการรับผลที่มีลายเซ็น; ไม่บังคับสำหรับเส้นทาง FCM

Endpoints: `GET /` ข้อความสถานะ, `GET /health` readiness, `POST /line-webhook` สำหรับ LINE โดยตรง, `POST /forwarded-webhook` สำหรับผู้ดูแล Webhook เดิมส่งต่ออย่างลงลายเซ็น, `POST /register-device` สำหรับมือถือที่มี pairing code

ระบบปิดการรับภาพจริงถ้าขาดคีย์/การตั้งค่า โดยตอบ HTTP 503 แทนการอ้างว่าสำเร็จ; `/health` ไม่แสดงคีย์

### ข้อมูลและความปลอดภัย

- เก็บเฉพาะ FCM device token ใน KV (หมดอายุ 90 วัน) และผลตรวจสลิปแบบย่อไว้ 3 วัน; ไม่เก็บไฟล์รูปถาวรใน Queue หรือ KV
- รูปสลิปถูกดาวน์โหลดจาก LINE OA แล้วส่งต่อไป **Thunder API** เพื่อการตรวจสอบจริง จึงมีการประมวลผลรูปโดยผู้ให้บริการภายนอก ไม่ใช่ OCR บนเครื่องอย่างเดียว
- ข้อความ FCM มีเฉพาะสถานะยอดรับที่ผ่านเงื่อนไข จำนวนเงิน และรหัสเหตุการณ์; ไม่มีเลขบัญชีหรือรูปภาพ
- การรับรองยอดโดย Thunder ต้องตรวจสอบบัญชีผู้รับและสลิปซ้ำสำเร็จ ไม่รับรองจาก OCR อย่างเดียว
- FCM และระบบเสียงใน background มีข้อจำกัดของ Android/การประหยัดแบตเตอรี่ ต้องทดสอบบน vivo V30 Pro จริงก่อนสรุป
- GitHub Actions debug APK แต่ละรอบอาจใช้กุญแจลงลายเซ็นต่างกัน จึง **ต้องเตรียมการเซ็นรุ่นส่งมอบด้วยคีย์คงที่** ก่อนให้ติดตั้งทับ โดยยังไม่มีการออก APK รุ่นสุดท้าย

## ทดสอบ

```bash
node --test server/*.test.mjs
```

หลักฐาน: GitHub PR #3 (FCM + Android CI), PR #5 (fail-closed slip verification, pending FCM delivery; server CI และ Android CI ผ่าน), Cloudflare API upload/deployment ล่าสุดสำเร็จ 18 พ.ย. 2569; ยังไม่ยืนยัน production HTTP GET จากเครือข่ายภายนอก และยังไม่มี end-to-end transfer จริง

เอกสารอ้างอิง:
- https://developers.line.biz/en/docs/messaging-api/receiving-messages/
- https://document.thunder.in.th/th/v2/verify/bank/image
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://developers.cloudflare.com/queues/

**ข้อจำกัด Coda:** ตัวเชื่อมต่อ Coda ส่งข้อผิดพลาดภายใน ณ วันที่ตรวจ จึงเก็บสถานะนี้ใน GitHub แทน ไม่ได้อ้างว่าบันทึกเข้า Coda แล้ว
