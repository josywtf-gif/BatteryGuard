package com.example.batteryguard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var button: MaterialButton
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        button = findViewById(R.id.btn)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        findViewById<android.view.View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        button.setOnClickListener { toggle() }
        updateUi()
    }
    override fun onResume() { super.onResume(); updateUi() }
    private fun toggle() {
        if (Prefs.isEnabled(this)) {
            Prefs.setEnabled(this, false)
            stopService(Intent(this, MonitorService::class.java))
            updateUi()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            Toast.makeText(this, "Разрешите уведомления и нажмите «Включить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        if (!Prefs.overlay(this)) {
            Toast.makeText(this, "Для плавающего окна разрешите отображение поверх других приложений.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        Prefs.setEnabled(this, true)
        val i = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, i)
        updateUi()
    }
    private fun updateUi() {
        val on = Prefs.isEnabled(this)
        button.text = if (on) "Выключить" else "Включить"
        button.setBackgroundColor(getColor(if (on) R.color.green_on else R.color.brand))
    }
}
