package com.thunder.linespeaker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 35, 30, 35)
        }
        layout.addView(TextView(this).apply {
            text = "Thunder LINE Speaker"
            textSize = 26f
        })
        layout.addView(TextView(this).apply {
            text = "ลูกค้าทัก LINE: พูดชื่อและชื่อหวย ไม่อ่านเลขที่สั่ง\nThunder ส่งยอดเป็นข้อความ: พูดยอดเงิน\nภาพสลิป: อ่านรูปที่เปิดหรือแชร์ได้เมื่อ Android เข้าถึงภาพ\n\nOCR ภายในเครื่องไม่ยืนยันการโอนเงินจริง\\nสลิป LINE OA ที่ตรวจจริงใช้ Cloudflare / Thunder / Firebase เมื่อเชื่อมต่อแล้ว"
            textSize = 16f
        })
        @Suppress("DEPRECATION")
        fun addSwitch(label: String, selected: Boolean, changed: (Boolean) -> Unit) {
            layout.addView(Switch(this).apply {
                text = label
                isChecked = selected
                textSize = 16f
                setOnCheckedChangeListener { _, value -> changed(value) }
            })
        }
        fun addButton(label: String, pressed: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { pressed() }
            })
        }
        addSwitch("พูดเมื่อลูกค้าทัก LINE", Preferences.customers(this)) { Preferences.setCustomers(this, it) }
        addSwitch("พูดยอดเงินเมื่อสลิปตรวจผ่าน Thunder จริง", Preferences.payments(this)) { Preferences.setPayments(this, it) }
        addSwitch("OCR ภาพที่เปิดอยู่ใน LINE", Preferences.screenOcr(this)) { Preferences.setScreenOcr(this, it) }
        val pairingField = EditText(this).apply {
            hint = "รหัสจับคู่ส่วนตัว (เมื่อเซิร์ฟเวอร์พร้อม)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        layout.addView(pairingField)
        addButton("จับคู่มือถือเพื่อรับผลตรวจสลิป LINE OA อัตโนมัติ") {
            val code = pairingField.text?.toString()?.trim().orEmpty()
            info.text = "กำลังตรวจสอบการจับคู่..."
            PushPairing.pair(this, code) { _, message ->
                runOnUiThread { info.text = message }
            }
        }
        addButton("อนุญาตแจ้งเตือนผลตรวจสลิป") {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
            } else {
                info.text = "เปิดสิทธิ์แจ้งเตือนแล้ว"
            }
        }
        addButton("ตรวจความพร้อม Cloudflare / LINE / Thunder / Firebase") {
            info.text = "กำลังตรวจสอบสถานะระบบ..."
            PushPairing.checkServerStatus { status ->
                runOnUiThread { info.text = status }
            }
        }
        addButton("อนุญาตอ่านการแจ้งเตือน") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton("อนุญาตอ่านภาพที่แสดงใน LINE") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("เลือกภาพสลิปจากเครื่อง") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 101)
        }
        addButton("ทดลองเสียงภาษาไทย") {
            SpeechEngine.speak(this, "รับเงินเข้า 180 บาทแล้วเด้อ", "test:${System.currentTimeMillis()}", 0)
        }
        info = TextView(this).apply {
            text = if (FirebaseBootstrap.isConfigured(this@MainActivity)) {
                "Firebase พร้อมให้จับคู่ (ต้องมีรหัสจับคู่ของเซิร์ฟเวอร์)"
            } else {
                "ยังไม่มีค่า Firebase จริง: โหมดรับผลตรวจจากเซิร์ฟเวอร์ยังไม่ทำงาน รุ่นนี้ยังเป็นโค้ดสำหรับทดสอบ ไม่ต้องติดตั้งซ้ำ"
            }
            textSize = 15f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(info)
        setContentView(ScrollView(this).apply { addView(layout) })
        handleIncomingImage(intent)
        // Renews FCM registration periodically even when the token itself did
        // not rotate. Requires no new pairing or new app installation.
        PushPairing.refreshWhenAppOpens(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingImage(intent)
    }

    private fun handleIncomingImage(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || !incoming.type.orEmpty().startsWith("image/")) return
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) readImage(uri)
    }

    @Deprecated("Android platform image picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            data?.data?.let { readImage(it) }
        }
    }

    private fun readImage(uri: Uri) {
        info.text = "กำลังอ่านภาพ..."
        ImageReader.fromUri(this, uri) { amount ->
            runOnUiThread {
                if (amount == null) {
                    info.text = "ไม่พบยอดเงินที่อ่านได้ชัดเจน (OCR รองรับตัวเลขและอักษรละตินเป็นหลัก)"
                } else {
                    info.text = "พบตัวเลขยอดเงิน $amount บาท (ไม่ใช่การยืนยันโอนเงินจากธนาคาร)"
                    SpeechEngine.speak(this, "พบยอดเงิน $amount บาทจากรูปภาพ", "shared:${System.currentTimeMillis()}", 0)
                }
            }
        }
    }
}
