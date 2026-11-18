# Thunder LINE Speaker — LINE OA / Thunder / FCM

## สถานะจริง ณ 19 พฤศจิกายน 2569

**ระบบยังไม่ครบวงจรและยังไม่ควรติดตั้ง APK รอบใหม่**

ส่วนที่ทำเสร็จและตรวจสอบได้:

- Android: โค้ดรุ่น `0.5-dev` รับการแจ้งเตือน LINE, ทดลอง OCR, รับผลตรวจจาก FCM, ป้องกันพูดซ้ำด้วย event ID, ตั้งค่าการจับคู่ด้วยรหัสส่วนตัว, เพิ่มปุ่มตรวจความพร้อม Worker และต่ออายุการลงทะเบียน FCM อัตโนมัติเมื่อเปิดแอป (อย่างมากวันละครั้งหลังจับคู่); Android Build ผ่าน GitHub Actions ใน PR #7
- Server: โค้ดอ่านรูป LINE OA, ตรวจสลิปผ่าน Thunder v2, เช็ก `matchedAccount` และ `isDuplicate`, ส่ง FCM เฉพาะ `payment_matched`; Node tests ผ่าน GitHub Actions
- GitHub: PR #1, #2, #3, #5, #6, #7, #8, #9, #10, #11, #12 และ #13 merged เข้า `main`; PR #6 แก้การตัดภาพเกิน 50 รายการ/สถานะ readiness; PR #7 เพิ่มการตรวจความพร้อมจากมือถือและต่ออายุ FCM หลังเปิดแอป; GitHub Actions ผ่าน
- Cloudflare: อัปโหลด Worker `line-oa-slip-speaker` จาก `server/line-oa-sidecar.mjs` และ `server/fcm-delivery.mjs` สำเร็จ; อัปโหลดโค้ดล่าสุดจาก PR #13 และตรวจอ่านกลับยืนยัน timestamp ต้นทางสำหรับ FCM พร้อม Queue/KV ครบ
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

### ตรวจสถานะโดยไม่อ้างว่าใช้งานจริงผ่านแล้ว

`GET /health` จะส่ง `needs_configuration` หากยังไม่ได้เชื่อมค่าจริงหรือยังไม่ได้จับคู่มือถือ และใช้ `ready_for_live_test` เฉพาะเมื่อพบบริการและมือถือพร้อมสำหรับการ **เริ่มทดสอบ** เท่านั้น ไม่ใช่การรับรองว่าสลิปจริงหรือเสียงตอนล็อกหน้าจอผ่านแล้ว โดยส่ง `endToEndVerified:false` เสมอจนกว่าจะมีหลักฐานการทดสอบจริง

การรับ webhook กรองภาพที่ซ้ำใน payload เดียวกันด้วย `messageId` และแบ่งส่ง Cloudflare Queue เป็นชุดละไม่เกิน 100 เหตุการณ์ แทนการตัดทิ้งเกิน 50 รูปอย่างเงียบ ๆ ส่วนการส่งซ้ำข้าม webhook หลายรอบให้ใช้ cache การตรวจสลิปแยกตาม `messageId` โดย Queue ปัจจุบันตั้ง `max_concurrency: 1`; ยังไม่ควรอ้างว่าได้การันตี exactly-once เพราะ Cloudflare Queue และ KV มีข้อจำกัดของระบบกระจายงาน

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


## ตรวจสอบล่าสุด — 18 พฤศจิกายน 2569 (PR #7)

- GitHub PR #7 merged (commit `23046ad6b62d58426ae444c540d9d7e48d435a49`), Android Build/Unit tests สำเร็จ
- ตัวแอปมีปุ่มตรวจสถานะ Cloudflare/LINE/Thunder/Firebase โดยไม่แสดงคีย์ และมีการต่ออายุการจับคู่อุปกรณ์หลังเปิดแอปเพื่อลดปัญหาหมดอายุ KV ที่ 90 วัน
- ค้นหาไฟล์/โค้ด Webhook เดิมใน GitHub ที่เชื่อมอยู่ ไม่พบ endpoint; Gmail/Drive ที่เข้าถึงได้ไม่พบข้อมูลโปรเจกต์สำหรับเติม Credentials
- Cloudflare Worker ยังไม่มี `LINE_CHANNEL_SECRET`, `LINE_CHANNEL_ACCESS_TOKEN`, `THUNDER_API_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`, `DEVICE_PAIRING_SECRET` จึงยังไม่สามารถรับภาพและส่งเสียงจริง
- ต้องใช้สิทธิ์เจ้าของ LINE OA/Thunder/Firebase ในการตั้งค่าคีย์จริงและต้องทดสอบบน vivo V30 Pro ก่อนประกาศส่งมอบ
- ไม่สร้างคีย์ปลอม ไม่ฝังคีย์ส่วนตัวใน GitHub ไม่เปลี่ยน Webhook เดิม และยังไม่ออก APK ที่อ้างว่าพร้อมใช้งาน


## ปรับระบบอ่านเสียงภาษาไทย (PR #8 · 18 พ.ย. 2569)

- Android `SpeechEngine` เปลี่ยนจากตัวแปรรอพูดข้อความเดียวเป็นคิว FIFO สูงสุด 200 รายการ ไม่ทับข้อความลูกค้าและยอดเงินเมื่อแจ้งเตือนเข้าติดกันช่วงเริ่ม Thai TTS
- กรองเหตุการณ์เสียงซ้ำทั้งระหว่างรอพูด และหลังคำสั่งพูดถูกเครื่อง TTS รับงานแล้ว; หาก TTS ปฏิเสธคำสั่ง จะเก็บข้อความไว้เพื่อพยายามเมื่อมีเหตุการณ์ใหม่
- เพิ่ม JVM Unit tests สำหรับคิวเสียง: ลำดับ FIFO, ป้องกันซ้ำ, รักษาข้อความเมื่อคำสั่งเสียงไม่สำเร็จ, และขอบเขตคิว
- GitHub PR #8 merged: `3943095d63466e4ead648032201476e67b5a6aa7` และ Android Build / tests ผ่าน
- **ยังไม่มีการทดสอบเสียงผ่านลำโพงบน vivo V30 Pro จริง** โดยเฉพาะเมื่อแอปถูกปิด/เครื่องล็อกหน้าจอ; การรับคำสั่งของ TTS ไม่ใช่หลักฐานว่าได้ยินเสียง
- Cloudflare Worker ตรวจล่าสุดยังมี bindings DEVICES, RESULT_CACHE, SLIP_QUEUE แต่ยังไม่มี Secrets สำหรับ LINE/Thunder/Firebase จึงยังไม่เปิดการตรวจสลิปอัตโนมัติจริง


## ป้องกันแจ้งยอดเงินเก่า (PR #9 · 18 พ.ย. 2569)
- PR #9 merged commit `2dfb31b714f6d88b642c4d5af504f85752aa2502`; GitHub Actions ทดสอบฝั่ง Server และ Android ผ่าน
- เหตุการณ์ LINE OA ที่ระบุ timestamp เก่ากว่า 10 นาที หรือใหม่เกิน 60 วินาทีจะไม่ส่งตรวจซ้ำ
- Cloudflare Queue เก็บ `receivedAt` จาก timestamp ของ LINE; หากคิวสะสมจนเกิน 10 นาที จะไม่เรียกยอดนั้นเป็นเงินเข้าใหม่
- ผลตรวจที่ค้างรอจับคู่มือถือจะถูกตรวจ `verifiedAt` ก่อนส่งเสียง หากเกิน 10 นาทีหรือเป็นข้อมูล cache เก่าที่ไม่มี timestamp จะทำเครื่องหมายหมดอายุ ไม่พูดเป็นยอดใหม่
- Cloudflare Worker อัปโหลดโค้ด PR #9 สำเร็จและอ่านกลับตรวจพบตัวกรองความใหม่ พร้อม binding `DEVICES`, `RESULT_CACHE`, `SLIP_QUEUE` ครบ
- Cloudflare Secrets ยังว่าง จึงยังไม่ผ่านการรับสลิปจริงจาก LINE OA ไป Thunder/Firebase และไม่เปลี่ยน Webhook เดิม

## ตัดการแจ้งยอดสลิปเก่าจากเวลาต้นทางจริง (PR #10 · 18 พ.ย. 2569)

- PR #10 merged (commit `417cc4a1e32620c0161f414a68fe15d5481203f9`); GitHub Actions ทั้งการทดสอบ Server และ Android Build ผ่าน
- เมื่อ webhook LINE OA ส่งรูปที่ไม่มีฟิลด์ `timestamp` ตามมาตรฐาน LINE จะไม่ส่งเข้า Queue และไม่ประดิษฐ์เวลาใหม่แทน
- Queue ที่ไม่มี `receivedAt` หรือหมดอายุ 10 นาที จะไม่ส่งสลิปตรวจ Thunder; ผลค้างที่ยังไม่ส่งจะถูกทำเครื่องหมายหมดอายุ
- อายุยอดค้างนับจาก `receivedAt` ของเหตุการณ์ LINE ต้นฉบับ ไม่ใช่ `verifiedAt` ของการตรวจ Thunder ในเวลาถัดมา
- อัปโหลดโค้ดล่าสุดขึ้น Cloudflare Worker `line-oa-slip-speaker` สำเร็จและอ่านโค้ดบน Worker กลับพบเงื่อนไข timestamp ทั้งหมด พร้อมคง Binding DEVICES, RESULT_CACHE, SLIP_QUEUE ครบ
- Cloudflare Secrets ยังว่าง จึงยังไม่พร้อมตรวจสลิปจาก LINE OA จริง, ส่ง FCM และยืนยันเสียงบน vivo V30 Pro; ไม่เปลี่ยน webhook เดิม และไม่ให้ติดตั้ง APK ใหม่ระหว่างทดสอบ

## ทดสอบเส้นทางรับสลิปครบชุดแบบจำลอง (PR #11 · 18 พ.ย. 2569)

- PR #11 merged (commit `b6419d133eaf0a29f73f8143f329ae7b6d8dd59a`); GitHub Actions ชุดทดสอบ Server และ Android Build ผ่าน
- เพิ่ม `server/live-payment-flow.integration.test.mjs` เพื่อรันโค้ด Worker จริงตั้งแต่ signed forwarded LINE OA event → Queue → LINE image API → Thunder verification → Google OAuth → FCM HTTP v1 (จำลองเฉพาะ HTTP ผู้ให้บริการและฐานข้อมูล)
- พิสูจน์ในชุดทดสอบว่า LINE ส่งเหตุการณ์เดิมซ้ำไม่ทำให้เรียก Thunder ซ้ำและไม่ส่ง FCM ซ้ำ, สลิปซ้ำ/ผู้รับไม่ตรงไม่สร้างการแจ้งรับเงิน, ลายเซ็น relay ผิดถูกปฏิเสธ
- ทดสอบเมื่อยังไม่มีมือถือจับคู่: ผลตรวจที่ผ่านเก็บรอไว้ แล้วเมื่อมือถือจับคู่จึงส่ง FCM จากข้อมูลแคช **โดยไม่เรียก Thunder ตรวจซ้ำ**
- **เป็นการทดสอบจำลอง ไม่ใช่การยืนยันว่าธนาคารได้รับเงินหรือเสียงดังจริงบน vivo V30 Pro**; ต้องเติมคีย์ LINE/Thunder/Firebase และต่อเหตุการณ์จากเจ้าของ Webhook เดิมก่อนทดสอบอุปกรณ์
- ไม่มีการเปลี่ยน URL Webhook เดิม ไม่มีการติดตั้ง APK และไม่มีการอัปโหลด Worker โค้ดใหม่ เพราะ PR นี้เพิ่มเฉพาะชุดทดสอบ

## ป้องกันอ่านยอดเงินผิดจากชื่อผู้ส่ง LINE (PR #12 · 19 พ.ย. 2569)

- PR #12 รวมเข้า `main` (commit `10f8cdb54b79fb7bde6b795dfb97b8fe96212632`); Android Unit Tests และ Build ผ่าน
- ปิดช่องโหว่ที่ LINE NotificationListener ยอมให้แชตที่ตั้งชื่อผู้ส่งว่า `Thunder` และมีตัวเลขในข้อความเรียกเสียง `รับเงินเข้า ... บาทแล้วเด้อ` โดยไม่ผ่าน Thunder API
- คำประกาศ `รับเงินเข้า ... บาทแล้วเด้อ` จะมาจากเส้นทาง **Firebase verified-payment event** เท่านั้น และยังต้องใช้ค่า Firebase จริงของโปรเจกต์ที่กำหนด
- อ่านตัวเลขจาก OCR ยังระบุว่าเป็นเพียง `พบยอดเงิน ... จากรูปภาพ` ไม่ใช่หลักฐานการโอนเงิน
- เพิ่ม `PaymentAnnouncementPolicy` และ JUnit regression tests สำหรับข้อความ LINE/OCR ที่ปลอมแปลงเป็นยอดรับเงินจริง, ฟิลด์เหตุการณ์ที่ผิด และจำนวนเงินไม่สมเหตุสมผล
- เพิ่ม Android manifest `queries` สำหรับ TTS_SERVICE เพื่อให้ Android รุ่นใหม่เข้าถึงบริการอ่านออกเสียงที่ติดตั้งได้ตามข้อกำหนดการมองเห็นแพ็กเกจ
- แก้ข้อความหน้าแอปที่เคยอ้างว่าทุกอย่างทำบนเครื่อง ให้แสดงตามจริงว่าการตรวจสลิปอัตโนมัติต้องใช้ Cloudflare/Thunder/Firebase
- **ยังไม่เปิดใช้กับการโอนเงินจริง**: Cloudflare Worker ยังไม่มี LINE/Thunder/Firebase Secrets และยังไม่พบสิทธิ์แก้ handler Webhook เดิม; ไม่มีการเปลี่ยน webhook, ไม่ต้องติดตั้ง APK รอบใหม่, ต้องทดสอบกับ vivo V30 Pro ก่อนส่งมอบ

## ป้องกัน FCM แจ้งยอดเก่าที่มือถือ (PR #13 · 19 พ.ย. 2569)

- PR #13 merged เป็น commit `e429418c38a4e6ea79f6443d92e96be100caaa89`; GitHub Actions ผ่านทั้ง Node server regression/integration tests และ Android Unit Tests/Build
- ผลตรวจจาก Thunder ส่ง FCM พร้อม `eventTime` ซึ่งเป็นเวลา `timestamp` ของข้อความ LINE OA ต้นฉบับในหน่วยมิลลิวินาที (ส่งเป็น string ตามข้อกำหนด FCM data)
- Cloudflare ป้องกันการส่ง FCM หากไม่พบเวลาเดิม เวลาเกิน 10 นาที หรือเวลาจากอนาคตเกิน 1 นาที; ใช้หลักเดียวกันกับการส่งผลค้างหลังจับคู่
- Android `VerifiedPaymentMessagingService` ตรวจ `eventTime` ซ้ำก่อนแสดงการแจ้งเตือนและอ่านว่าเงินเข้า; ข้อความไม่มีเวลาหรือเกินเวลาจะไม่ประกาศยอดเงิน
- การใช้ Firebase `ttl:120s` ยังมีอยู่ แต่ไม่ถือว่าเพียงพอจะรับรองเวลาอ่านเสียงบนมือถือโดยลำพัง
- อัปโหลด Worker สำเร็จและอ่านโค้ดกลับตรวจครบ พร้อมคง DEVICES, RESULT_CACHE, SLIP_QUEUE และยืนยันว่า Worker มี Secrets **0 รายการ**
- **ยังไม่ใช้งานจริงครบวงจร:** ต้องเชื่อม LINE Channel credentials, Thunder API key, Firebase service account, รหัสจับคู่ และส่งต่อข้อความจากเจ้าของ Webhook LINE OA เดิม; ต้องทดสอบเสียงบน vivo V30 Pro ก่อนส่งมอบ APK ใช้งานจริง
- ไม่เปลี่ยน URL ของ Webhook เดิม ไม่ฝังคีย์ลงแหล่งโค้ด ไม่แก้ Bombim00/Project-1
