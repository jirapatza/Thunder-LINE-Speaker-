# Thunder LINE OA Speaker — Android v0.11 (รุ่นทดสอบ)

เป้าหมาย: **LINE Official Account ได้รูปสลิป → vivo อ่านยอดเงินที่ปรากฏในรูปเป็นเสียงอัตโนมัติ** แม้ไม่ได้เปิดรูปเอง โดยไม่เปลี่ยน Webhook URL ของ LINE OA และไม่ส่งรูปไปตรวจสอบ Thunder ซ้ำ

## ความพร้อมจริง
- โค้ดโหมดภาพอัตโนมัติเพิ่มใน Cloudflare Worker และแอป Android แล้ว; ต้องผ่าน CI และทดสอบบนมือถือจริงต่อ
- **ยังไม่เปิดใช้งานกับ LINE OA จริง** เพราะ Cloudflare Worker ที่ตรวจมี Queue/KV แต่ยังไม่มี LINE/Firebase/Pairing secrets และยังไม่พบโค้ด Webhook เดิมที่ส่งต่อเหตุการณ์
- Debug APK ที่ไม่มี Firebase CI variables ยังใช้โหมดอ่าน Notification / แชร์รูปอ่าน OCR เดิมได้ แต่รับภาพอัตโนมัติจาก FCM ไม่ได้
- ไม่ได้แก้ระบบตรวจเงินเข้า, Thunder API หรือ URL Webhook เดิม

## วิธีทำงาน
1. LINE OA รับรูปจากลูกค้า และ LINE Messaging API แจ้ง messageId ไปยัง Webhook ที่ตั้งอยู่เดิม
2. ผู้ดูแล Webhook เดิมต้องต่อการส่งสำเนาเหตุการณ์ที่มี HMAC signature ไปยัง Worker path /forwarded-webhook (หรือ LINE ส่งตรงมา /line-webhook หากเป็นเส้นทางที่เจ้าของกำหนด)
3. Worker ใช้ Queue, KV และ FCM data message เพื่อแจ้งแอปมือถือว่ามีรูปใหม่ พร้อมลิงก์ HTTPS มีลายเซ็น HMAC รายอุปกรณ์ที่หมดอายุภายใน 2 นาที
4. WorkManager บน Android ดาวน์โหลดภาพโดยผ่าน Worker ของโปรเจกต์เท่านั้น Worker ดึงรูปจาก LINE Content API โดยใช้ Channel Access Token ที่เก็บบนเซิร์ฟเวอร์
5. OCR ในมือถือด้วย ML Kit และพูด "พบยอดในรูปสลิป 180 บาทแล้วเด้อ" (ขึ้นอยู่กับจำนวนเงินที่อ่านได้) ไม่อ้างว่าโอนเงินเข้าจริง
6. ลบไฟล์ภาพชั่วคราวหลัง OCR; ไม่จัดเก็บภาพใน Worker KV และไม่เรียก Thunder เพื่อ re-verify

## การตั้งค่าที่จำเป็นก่อนเปิดจริง
Cloudflare Worker เดิม line-oa-slip-speaker:
- Worker Secrets: LINE_CHANNEL_SECRET, LINE_CHANNEL_ACCESS_TOKEN, FIREBASE_SERVICE_ACCOUNT_JSON, DEVICE_PAIRING_SECRET (อย่างน้อย 24 อักขระ), IMAGE_LINK_SECRET (อย่างน้อย 32 อักขระ), FORWARD_SECRET (ถ้ารับสำเนาจาก Webhook เดิม)
- Worker variable: SLIP_PROCESSING_MODE=image_relay (ต้องเปิดอย่างตั้งใจ; ค่าเริ่มต้นคงโหมดตรวจ Thunder เดิม), optional PUBLIC_WORKER_URL เมื่อใช้โดเมนอื่น
- Bindings เดิม: SLIP_QUEUE, DEVICES, RESULT_CACHE
- GitHub Actions vars ที่ต้องใช้ตอน Build APK: FIREBASE_ANDROID_APP_ID, FIREBASE_API_KEY, FIREBASE_PROJECT_ID, FIREBASE_SENDER_ID
- ต้องจับคู่มือถือหนึ่งครั้งผ่านหน้าจอแอป แล้วทดสอบว่า FCM ไปถึงเครื่องจริง
- ไม่ใส่ Secret หรือ Firebase service account key ใน GitHub source code, APK หรือแชต

## ข้อจำกัด
- โหมดอัตโนมัติใช้เฉพาะภาพที่ส่งเข้า LINE OA Messaging API และมี Webhook ที่ได้รับอนุญาต; ใช้กับ LINE ส่วนตัวไม่ได้
- แอปยังกรอง Android NotificationListener เฉพาะ com.linecorp.lineoa และไม่อ่านข้อความจากแอปอื่น; สิทธิ์ Accessibility OCR หน้าจอเป็นทางเลือกที่ปิดโดยค่าเริ่มต้น
- โหมดใหม่นี้ใช้ INTERNET เพื่อรับ FCM และดาวน์โหลดภาพผ่าน Worker จึงไม่ควรกล่าวว่า APK ไม่มีสิทธิ์อินเทอร์เน็ต
- OCR อาจตีความจำนวนเงินผิด โดยเฉพาะภาพที่มีหลายยอด; "พบยอดในภาพ" **ไม่ใช่การยืนยันว่ารับเงินแล้ว**
- หาก LINE OA/Firebase/Worker ไม่มีคีย์จริง หรือ Webhook ไม่ส่งต่อเหตุการณ์ ระบบอัตโนมัติจะยังไม่ทำงาน
- Android อาจจำกัด FCM / WorkManager / TTS ตอนล็อกจอหรือพักแอปบน vivo; ต้องทดสอบด้วยเครื่องจริง
- ภาพถูกส่งจาก LINE API ผ่าน Worker ไปมือถือแบบชั่วคราว; FCM ส่งเฉพาะลิงก์มีลายเซ็นที่มีอายุ 2 นาที ไม่แนบรูปทั้งไฟล์ ไม่เก็บชื่อ/บัญชีผู้โอนใน payload
- รูปต้นฉบับถูกดาวน์โหลดจาก LINE อีกครั้งเมื่อมือถือขอภายในช่วงที่ลิงก์ยังใช้ได้

## ทดสอบ
Server: node --test server/*.test.mjs
Android: gradle :app:testDebugUnitTest :app:assembleDebug
E2E: ส่งรูปจากบัญชีลูกค้าเข้า LINE OA → Webhook เดิมส่งต่อ → Queue → FCM → vivo ดึงรูป → OCR → พูดออกลำโพงแม้ล็อกหน้าจอ

## ตั้งค่าเสียงพูด (รุ่นกำลังพัฒนา)

ในเมนู **ตั้งค่าเสียงพูด** เลือกเปิด–ปิด แก้ข้อความ ทดลอง และกดบันทึกแยกสำหรับข้อความลูกค้า รูปภาพ และผลตรวจ Thunder ที่ผ่านแล้ว การทดสอบผลตรวจใช้ตัวอย่าง 260 บาท ข้อความผลตรวจต้องมี `{ยอดเงิน}` และไม่มีตัวเลขอื่น เพื่อป้องกันยอดคงที่ปะปนกับยอดที่ยืนยัน

การแจ้งรูปภาพใช้ข้อความที่ผู้ใช้ตั้ง แม้ข้อความเริ่มต้นจะเป็น “ลูกค้าโอนเงินแล้ว” แต่การส่งรูป **ไม่ยืนยันว่าเงินเข้าจริง** ส่วนการพูดยอดยืนยันต้องเป็นเหตุการณ์ Firebase `payment_matched` ที่ผ่านการตรวจรูปแบบและเวลา โดยอาศัย Worker/Firebase ที่กำหนดค่าอย่างถูกต้อง แอปไม่สามารถพิสูจน์การทำงานครบวงจรจากข้อความแจ้งเตือน LINE หรือชื่อผู้ส่งได้ หากไม่พบผลตรวจที่เชื่อถือได้จะไม่พูดยอดยืนยัน

การอ่านภาพที่ผู้ใช้เลือกเองยังมีอยู่ ส่วนการจับภาพหน้าจอและการรับรูปเบื้องหลังยังตรวจภาพได้แต่ไม่พูดยอด OCR โดยอัตโนมัติ เพื่อไม่ให้สับสนกับยอดที่ตรวจผ่าน ข้อจำกัดของ LINE OA, Firebase, การจำแนกผู้ส่ง และเสียงขณะล็อกจอต้องทดสอบบน vivo V30 Pro จริง
