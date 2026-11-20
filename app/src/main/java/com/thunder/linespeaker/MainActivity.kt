package com.thunder.linespeaker

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Only LINE Official Account notifications; no other apps and no screen OCR. */
class MainActivity : Activity() {
    private lateinit var accessInfo: TextView
    private lateinit var voiceInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 35, 30, 35)
        }
        layout.addView(TextView(this).apply {
            text = "Thunder LINE OA Speaker"
            textSize = 25f
        })
        layout.addView(TextView(this).apply {
            text = "อ่านเฉพาะแจ้งเตือน LINE Official Account เท่านั้น\n" +
                "ไม่อ่าน LINE ส่วนตัว ธนาคาร SMS หรือแอปอื่น\n\n" +
                "ข้อความลูกค้า: พูดชื่อและชื่อหวย ไม่อ่านเลขที่สั่ง\n" +
                "ข้อความแจ้งรูปหรือสลิป: พูดว่ามีรูป/สลิปเข้ามา\n" +
                "ข้อความมียอดเงิน: อ่านยอดที่ปรากฏในแจ้งเตือน\n\n" +
                "ไม่ตรวจสลิปซ้ำ ไม่ใช้ Thunder API ไม่แก้ Webhook เดิม"
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

        addSwitch("อ่านข้อความลูกค้า LINE OA", Preferences.customers(this)) {
            Preferences.setCustomers(this, it)
        }
        addSwitch("อ่านแจ้งเตือนรูป สลิป และยอดเงินใน LINE OA", Preferences.payments(this)) {
            Preferences.setPayments(this, it)
        }

        accessInfo = TextView(this).apply {
            textSize = 17f
            setPadding(0, 20, 0, 8)
        }
        layout.addView(accessInfo)
        addButton("ตรวจสิทธิ์อ่านแจ้งเตือน") {
            updateAccessStatus()
        }
        addButton("อนุญาตอ่านแจ้งเตือน LINE OA") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton("ทดลองเสียงข้อความลูกค้า") {
            SpeechEngine.speak(
                this, "สมหญิง ซื้อหวยฮานอย VIP",
                "test-customer:" + System.currentTimeMillis(), 0
            )
        }
        addButton("ทดลองเสียงแจ้งสลิป") {
            SpeechEngine.speak(
                this, "มีสลิปส่งเข้ามาทาง LINE แล้วเด้อ",
                "test-slip:" + System.currentTimeMillis(), 0
            )
        }
        addButton("ทดลองเสียงแจ้งยอดเงิน") {
            SpeechEngine.speak(
                this, "มีแจ้งเตือนยอด 180 บาทแล้วเด้อ",
                "test-amount:" + System.currentTimeMillis(), 0
            )
        }
        voiceInfo = TextView(this).apply {
            text = VoiceHealth.summary(this@MainActivity)
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }
        layout.addView(voiceInfo)
        addButton("ตรวจสถานะแจ้งเตือนและเสียงล่าสุด") {
            voiceInfo.text = VoiceHealth.summary(this)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        if (::accessInfo.isInitialized) updateAccessStatus()
        if (::voiceInfo.isInitialized) voiceInfo.text = VoiceHealth.summary(this)
    }

    private fun updateAccessStatus() {
        val component = ComponentName(this, LineNotificationService::class.java)
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component) == true
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.split(":")?.any { ComponentName.unflattenFromString(it) == component } == true
        }
        accessInfo.text = if (granted) {
            "สิทธิ์อ่านแจ้งเตือน: อนุญาตแล้ว (แอปกรองเฉพาะ LINE OA)"
        } else {
            "สิทธิ์อ่านแจ้งเตือน: ยังไม่ได้อนุญาต"
        }
    }
}
