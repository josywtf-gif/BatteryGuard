package com.example.batteryguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class ToggleTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick()
        if (!Prefs.isEnabled(this) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 3, i, android.app.PendingIntent.FLAG_IMMUTABLE)) else startActivityAndCollapse(i)
            return
        }
        if (!Prefs.isEnabled(this) && !Prefs.overlay(this)) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 2, i, android.app.PendingIntent.FLAG_IMMUTABLE)) else startActivityAndCollapse(i)
            return
        }
        val enable = !Prefs.isEnabled(this)
        Prefs.setEnabled(this, enable)
        if (enable) ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        else stopService(Intent(this, MonitorService::class.java))
        refresh()
    }
    private fun refresh() { qsTile?.let { it.state = if (Prefs.isEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() } }
}
