package com.thunder.linespeaker

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Intent
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
import android.widget.Toast

/** Only LINE Official Account notifications; no other apps and no screen OCR. */
class MainActivity : Activity() {
    private lateinit var accessInfo: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var relayInfo: TextView

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
            text = "อ่านข้อความแจ้งเตือนเฉพาะ LINE Official Account\n" +
                "แจ้งยอดจากข้อความที่เห็นได้ หรืออ่านยอดในรูปสลิปถ้า Android แนบภาพมา\n" +
                "โหมดอัตโนมัติ: LINE OA รับรูป → Worker ส่งลิงก์ภาพ → มือถือ OCR และอ่านยอด\n" +
                "ต้องตั้งค่า Worker/Firebase และจับคู่อุปกรณ์ก่อนจึงจะใช้โหมดนี้ได้\n" +
                "ไม่เปิดดูแอปอื่น ไม่ตรวจสอบการโอน และไม่แก้ Thunder Webhook"
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

        addButton("ตั้งค่าเสียงพูด") { showSpeechSettings() }
        addSwitch("อ่านข้อความลูกค้า LINE OA", Preferences.customers(this)) {
            Preferences.setCustomers(this, it)
        }
        addSwitch("อ่านแจ้งเตือนรูป สลิป และยอดเงินใน LINE OA", Preferences.payments(this)) {
            Preferences.setPayments(this, it)
        }

        addSwitch(
            "อ่านยอดจากรูปที่เปิดใน LINE OA (ต้องอนุญาต Accessibility)",
            Preferences.screenSlipOcr(this)
        ) { Preferences.setScreenSlipOcr(this, it) }
        addButton("อนุญาตอ่านภาพที่เปิดอยู่ใน LINE OA (ทางเลือก)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("เลือกภาพสลิปจากเครื่องเพื่ออ่านยอด") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 201)
        }
        layout.addView(TextView(this).apply {
            text = "อ่านยอดสลิปอัตโนมัติ (FCM + OCR ในเครื่อง)"
            textSize = 19f
            setPadding(0, 18, 0, 4)
        })
        relayInfo = TextView(this).apply {
            textSize = 15f
            text = if (FirebaseBootstrap.isConfigured(this@MainActivity)) {
                "Firebase ใน APK: ตั้งค่าแล้ว — ตรวจ Worker และจับคู่อุปกรณ์"
            } else {
                "Firebase ใน APK: ยังไม่ได้ตั้งค่า — ระบบอัตโนมัติยังไม่พร้อม"
            }
        }
        layout.addView(relayInfo)
        val pairCode = EditText(this).apply {
            hint = "รหัสจับคู่จาก Worker (ไม่ต้องส่งลงแชต)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        layout.addView(pairCode)
        addButton("จับคู่มือถือเพื่อรับรูปสลิปอัตโนมัติ") {
            val value = pairCode.text.toString().trim()
            relayInfo.text = "กำลังจับคู่กับ Worker..."
            PushPairing.pair(this, value) { success, message ->
                runOnUiThread {
                    relayInfo.text = message
                    if (success) pairCode.text.clear()
                }
            }
        }
        addButton("ตรวจสถานะ Worker สำหรับอ่านสลิป") {
            relayInfo.text = "กำลังตรวจ Worker..."
            PushPairing.status { message ->
                runOnUiThread { relayInfo.text = message }
            }
        }
        sourceInfo = TextView(this).apply {
            textSize = 16f
            setPadding(0, 18, 0, 4)
        }
        layout.addView(sourceInfo)
        addButton("เปิดการตั้งค่าแจ้งเตือน LINE OA") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, LineSourcePolicy.OA_PACKAGE)
                })
            } catch (_: ActivityNotFoundException) {
                sourceInfo.text = "เปิดการตั้งค่าการแจ้งเตือน LINE OA โดยตรงไม่ได้บนเครื่องนี้"
            }
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
        handleSharedImage(intent)
    }

    @Suppress("DEPRECATION")
    private fun showSpeechSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val labels = mapOf(
            SpeechType.CUSTOMER to "ลูกค้าทักมา",
            SpeechType.PICTURE to "ลูกค้าส่งรูป",
            SpeechType.VERIFIED to "Thunder ตรวจสลิปผ่าน"
        )
        for (type in SpeechType.entries) {
            val toggle = Switch(this).apply {
                text = labels.getValue(type)
                isChecked = Preferences.speechEnabled(this@MainActivity, type)
            }
            val input = EditText(this).apply {
                setText(Preferences.speechText(this@MainActivity, type))
                setSingleLine(false)
                contentDescription = "คำพูด " + labels.getValue(type)
            }
            container.addView(toggle)
            container.addView(input)
            if (type == SpeechType.PICTURE) container.addView(TextView(this).apply {
                text = "รูปภาพไม่ใช่หลักฐานยืนยันว่าเงินเข้าจริง"
            })
            if (type == SpeechType.VERIFIED) container.addView(TextView(this).apply {
                text = "ใส่ {ยอดเงิน} ในข้อความ ห้ามใส่ตัวเลขอื่น ระบบจะพูดเฉพาะผลตรวจผ่านที่รับได้จริง"
            })
            container.addView(Button(this).apply {
                text = "ทดสอบเสียง: " + labels.getValue(type)
                setOnClickListener {
                    val sample = SpeechSettings.render(type, true, input.text.toString(),
                        if (type == SpeechType.VERIFIED) java.math.BigDecimal("260") else null)
                    if (sample == null) Toast.makeText(this@MainActivity, "กรุณาตรวจข้อความและ {ยอดเงิน}", Toast.LENGTH_LONG).show()
                    else SpeechEngine.speak(this@MainActivity, sample, "settings-test:" + System.currentTimeMillis(), 0)
                }
            })
            container.addView(Button(this).apply {
                text = "บันทึก: " + labels.getValue(type)
                setOnClickListener {
                    val saved = Preferences.saveSpeech(this@MainActivity, type, toggle.isChecked, input.text.toString())
                    Toast.makeText(this@MainActivity, if (saved) "บันทึกแล้ว" else "ข้อความว่าง ยาวเกินไป หรือรูปแบบยอดเงินไม่ถูกต้อง", Toast.LENGTH_LONG).show()
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this).setTitle("ตั้งค่าเสียงพูด").setView(scroll)
            .setPositiveButton("ปิด", null).setOnDismissListener { recreate() }.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedImage(intent)
    }

    @Deprecated("Android system file chooser result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 && resultCode == RESULT_OK) {
            data?.data?.let { readSlipImage(it) }
        }
    }

    private fun handleSharedImage(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND ||
            !incoming.type.orEmpty().startsWith("image/")) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            incoming.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        uri?.let { readSlipImage(it) }
    }

    private fun readSlipImage(uri: Uri) {
        VoiceHealth.slipOcrStatus(this, "กำลังอ่านรูปที่เลือกเอง")
        SlipImageReader.fromUri(this, uri, allowUnlabeled = true) { amount ->
            runOnUiThread {
                if (amount == null) {
                    VoiceHealth.slipOcrStatus(this, "ไม่พบยอดที่อ่านได้ในรูปที่เลือก")
                } else {
                    VoiceHealth.slipOcrStatus(this, "อ่านยอดจากรูปที่เลือกได้")
                    SpeechEngine.speak(
                        this, "พบยอดในรูปสลิป $amount บาทแล้วเด้อ",
                        "selected-slip:" + System.currentTimeMillis(), 0
                    )
                }
                voiceInfo.text = VoiceHealth.summary(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::sourceInfo.isInitialized) updateLineOaStatus()
        if (::accessInfo.isInitialized) updateAccessStatus()
        if (::voiceInfo.isInitialized) voiceInfo.text = VoiceHealth.summary(this)
        PushPairing.refreshWhenAppOpens(this)
    }

    private fun updateLineOaStatus() {
        val present = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    LineSourcePolicy.OA_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(LineSourcePolicy.OA_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        sourceInfo.text = if (present) {
            "LINE OA: พบแอป Official Account บนเครื่องแล้ว"
        } else {
            "LINE OA: ไม่พบแอป Official Account บนเครื่อง — จะไม่มีแจ้งเตือนให้อ่าน"
        }
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
