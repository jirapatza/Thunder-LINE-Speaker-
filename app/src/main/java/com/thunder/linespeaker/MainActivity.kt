package com.thunder.linespeaker
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,32) }
  layout.addView(TextView(this).apply { text = "Thunder LINE Speaker\nอ่านข้อความแจ้งเตือน LINE ที่มีคำว่า Thunder และยอดเงิน แล้วพูดเป็นภาษาไทย\n\nแอปไม่ส่งข้อมูลไปยังเซิร์ฟเวอร์"; textSize = 19f })
  layout.addView(Button(this).apply { text = "อนุญาตให้อ่านการแจ้งเตือน"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } })
  setContentView(layout)
 }
}
