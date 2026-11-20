package com.thunder.linespeaker

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Firebase must be explicitly configured in CI. No built-in API keys. */
object FirebaseBootstrap {
    fun isConfigured(context: Context): Boolean =
        context.getString(R.string.firebase_android_app_id).isNotBlank() &&
        context.getString(R.string.firebase_api_key).isNotBlank() &&
        context.getString(R.string.firebase_project_id).isNotBlank() &&
        context.getString(R.string.firebase_sender_id).isNotBlank()

    fun initialize(context: Context): Boolean {
        if (!isConfigured(context)) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(context.getString(R.string.firebase_android_app_id))
                .setApiKey(context.getString(R.string.firebase_api_key))
                .setProjectId(context.getString(R.string.firebase_project_id))
                .setGcmSenderId(context.getString(R.string.firebase_sender_id))
                .build()
            FirebaseApp.initializeApp(context.applicationContext, options)
            true
        } catch (_: Exception) { false }
    }
}

class SpeakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseBootstrap.initialize(this)
    }
}
