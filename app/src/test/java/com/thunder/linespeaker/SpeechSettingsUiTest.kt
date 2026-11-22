package com.thunder.linespeaker

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechSettingsUiTest {
    private fun all(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else emptyList()

    @Test fun menuShowsThreeEditableIndependentSections() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val menu = all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "ตั้งค่าเสียงพูด" }
        menu.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        val views = all(dialog.window!!.decorView)
        assertEquals(3, views.filterIsInstance<EditText>().size)
        assertEquals(3, views.filterIsInstance<Switch>().size)
        val buttons = views.filterIsInstance<Button>().map { it.text.toString() }
        assertEquals(3, buttons.count { it.startsWith("ทดสอบเสียง:") })
        assertEquals(3, buttons.count { it.startsWith("บันทึก:") })
    }
}
