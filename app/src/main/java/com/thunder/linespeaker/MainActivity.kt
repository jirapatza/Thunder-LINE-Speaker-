package com.thunder.linespeaker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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
            text = "ลูกค้าทัก LINE: พูดชื่อและชื่อหวย ไม่อ่านเลขที่สั่ง\nThunder ส่งยอดเป็นข้อความ: พูดยอดเงิน\nภาพสลิป: อ่านรูปที่เปิดหรือแชร์ได้เมื่อ Android เข้าถึงภาพ\n\nทุกอย่างประมวลผลในเครื่อง"
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
        addSwitch("พูดยอดเงินจาก Thunder", Preferences.payments(this)) { Preferences.setPayments(this, it) }
        addSwitch("OCR ภาพที่เปิดอยู่ใน LINE", Preferences.screenOcr(this)) { Preferences.setScreenOcr(this, it) }
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
            text = "รูปภาพอัตโนมัติ: อ่านได้เมื่อ LINE แนบรูปกับแจ้งเตือน หรือเปิดรูปบนหน้าจอ LINE และเปิดสิทธิ์ช่วยการเข้าถึง ไม่สามารถดึงรูปทุกห้องแชตได้เมื่อปิด LINE"
            textSize = 15f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(info)
        setContentView(ScrollView(this).apply { addView(layout) })
        handleIncomingImage(intent)
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
