package com.thunder.linespeaker

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
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
    private lateinit var accessInfo: TextView

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
            text = "LINE แจ้งเตือนมา → มือถือพูดให้รู้ทันที\nแจ้งเตือนรูปภาพหรือสลิป: บอกว่ามีรูปหรือสลิปเข้ามา\nถ้าข้อความแจ้งยอดเงิน: อ่านเฉพาะยอด ไม่ตรวจสอบการโอน\nข้อความลูกค้า: พูดชื่อและชื่อหวย ไม่อ่านเลขที่สั่ง\n\nทำงานจากการแจ้งเตือน LINE ในเครื่อง ไม่ต้องเปลี่ยน Webhook หรือเชื่อม Thunder API\nหมายเหตุ: หาก LINE ไม่แสดงยอดในแจ้งเตือน แอปจะอ่านยอดจากรูปที่ซ่อนอยู่ไม่ได้"
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
        addSwitch("อ่านเสียงเมื่อลูกค้าทัก LINE", Preferences.customers(this)) {
            Preferences.setCustomers(this, it)
        }
        addSwitch("อ่านเสียงแจ้งเตือนรูปภาพ/สลิป/ยอดจาก LINE", Preferences.payments(this)) {
            Preferences.setPayments(this, it)
        }
        addSwitch("OCR ภาพจาก LINE เพิ่มเติม (ทางเลือก)", Preferences.screenOcr(this)) {
            Preferences.setScreenOcr(this, it)
        }
        accessInfo = TextView(this).apply {
            textSize = 17f
            setPadding(0, 20, 0, 8)
        }
        layout.addView(accessInfo)
        addButton("ตรวจสถานะสิทธิ์อ่านแจ้งเตือน") {
            updateAccessStatus()
        }
        addButton("อนุญาตอ่านการแจ้งเตือน LINE") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton("ทดสอบเสียงแจ้งเตือนรูปภาพ") {
            SpeechEngine.speak(
                this, "มีรูปภาพส่งเข้ามาทาง LINE แล้วเด้อ",
                "test-photo:" + System.currentTimeMillis(), 0
            )
        }
        addButton("ทดสอบเสียงแจ้งยอดเงิน") {
            SpeechEngine.speak(
                this, "มีแจ้งเตือนยอด 180 บาทแล้วเด้อ",
                "test-amount:" + System.currentTimeMillis(), 0
            )
        }
        addButton("อนุญาต OCR ภาพที่เปิดอยู่ใน LINE (ทางเลือก)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("เลือกภาพสลิปจากเครื่อง (ทางเลือก)") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 101)
        }
        info = TextView(this).apply {
            text = "เปิดสิทธิ์อ่านการแจ้งเตือนให้แอปก่อนใช้จริง หากเป็นรูปอย่างเดียวจะบอกว่ามีรูปส่งมา ไม่เดายอดเงิน"
            textSize = 15f
            setPadding(0, 24, 0, 0)
        }
        layout.addView(info)
        setContentView(ScrollView(this).apply { addView(layout) })
        handleIncomingImage(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::accessInfo.isInitialized) updateAccessStatus()
    }

    private fun updateAccessStatus() {
        val listener = ComponentName(this, LineNotificationService::class.java)
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(listener) == true
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.split(":")
                ?.any { ComponentName.unflattenFromString(it) == listener } == true
        }
        accessInfo.text = if (granted) {
            "สิทธิ์อ่านแจ้งเตือน: อนุญาตแล้ว ✓"
        } else {
            "สิทธิ์อ่านแจ้งเตือน: ยังไม่ได้อนุญาต — กดปุ่มอนุญาตด้านล่าง"
        }
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
