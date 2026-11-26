package com.example.batteryguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the user-enabled foreground monitor after Android removes the app task. */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.isEnabled(context) || !Prefs.overlay(context)) return
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }
}
