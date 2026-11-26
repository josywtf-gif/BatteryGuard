package com.example.batteryguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val metricsWorker = Executors.newSingleThreadExecutor()
    private val refreshPending = AtomicBoolean(false)
    private val cachedMetrics = LinkedHashMap<String, String>()
    private val lastMetricRead = HashMap<String, Long>()
    private var bubble: FloatingBubble? = null
    private val update = object : Runnable {
        override fun run() {
            if (!Prefs.isEnabled(this@MonitorService)) { stopSelf(); return }
            refreshOverlay()
            handler.postDelayed(this, 3000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        createChannel(); startForeground(NOTIFICATION_ID, notification())
        if (Prefs.overlay(this)) bubble = FloatingBubble(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        handler.post(update)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == ACTION_STOP) { Prefs.setEnabled(this, false); stopSelf(); return START_NOT_STICKY }; if (!Prefs.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }; if (bubble == null && Prefs.overlay(this)) bubble = FloatingBubble(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); refreshOverlay(); return START_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        metricsWorker.shutdownNow()
        bubble?.hide()
        bubble = null
        // Не сбрасываем пользовательский флаг при системном уничтожении процесса.
        // Явное выключение уже устанавливает false в ACTION_STOP/переключателе.
        if (Prefs.isEnabled(this)) scheduleRestart()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun refreshOverlay() {
        if (!Prefs.overlay(this)) {
            bubble?.hide()
            return
        }
        if (!refreshPending.compareAndSet(false, true)) return
        metricsWorker.execute {
            try {
                val now = SystemClock.elapsedRealtime()
                val enabled = Prefs.metrics(this).filter { it != "voltage" }
                val snapshot = synchronized(cachedMetrics) {
                    enabled.forEach { key ->
                        val interval = when (key) {
                            "cpu_temp" -> 3_000L
                            "battery_temp", "ram", "battery" -> 5_000L
                            "time" -> 30_000L
                            else -> 5_000L
                        }
                        if (!cachedMetrics.containsKey(key) || now - (lastMetricRead[key] ?: 0L) >= interval) {
                            metric(key)?.let { cachedMetrics[key] = it }
                            lastMetricRead[key] = now
                        }
                    }
                    LinkedHashMap<String, String>().also { result ->
                        enabled.forEach { key ->
                            when (key) {
                                else -> cachedMetrics[key]?.let { result[key] = it }
                            }
                        }
                    }
                }
                handler.post { showOverlay(snapshot) }
            } finally {
                refreshPending.set(false)
            }
        }
    }

    private fun renderCachedOverlay() {
        if (!Prefs.overlay(this)) return
        val values = LinkedHashMap<String, String>()
        synchronized(cachedMetrics) {
            Prefs.metrics(this).forEach { key ->
                when (key) {
                    "voltage" -> Unit
                    else -> cachedMetrics[key]?.let { values[key] = it }
                }
            }
        }
        showOverlay(values)
    }

    private fun showOverlay(values: Map<String, String>) {
        if (values.isEmpty()) { bubble?.hide(); return }
        if (bubble == null) bubble = FloatingBubble(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        bubble?.show(values)
    }

    private fun batteryIntent() = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun metric(key: String): String? = when (key) {
        "cpu_temp" -> CpuTemp.read()?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "н/д"
        "battery_temp" -> {
            val tenths = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths == Int.MIN_VALUE || tenths !in -200..1000) "н/д"
            else String.format(Locale.getDefault(), "%.1f °C", tenths / 10f)
        }
        "ram" -> {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "${(mi.totalMem - mi.availMem) / 1048576L} / ${mi.totalMem / 1048576L} MB"
        }
        "time" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        "battery" -> {
            val intent = batteryIntent()
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level < 0 || scale <= 0) "н/д" else {
                val percent = "${(level * 100 / scale).coerceIn(0, 100)}%"
                if (!Prefs.voltageDetailsEnabled(this)) percent else {
                    val millivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                    val voltage = if (millivolts > 0) String.format(Locale.getDefault(), "%.2f В", millivolts / 1000f) else "вольтаж н/д"
                    val currentMicroAmps = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    val current = if (currentMicroAmps != Int.MIN_VALUE) {
                        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val sign = when (status) {
                            BatteryManager.BATTERY_STATUS_CHARGING -> "+"
                            BatteryManager.BATTERY_STATUS_DISCHARGING -> "−"
                            else -> ""
                        }
                        "$sign${kotlin.math.abs(currentMicroAmps.toLong()) / 1000} мА"
                    } else "ток н/д"
                    "$percent · $voltage · $current"
                }
            }
        }
        else -> null
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) { val c=NotificationChannel(CHANNEL, "BatteryGuard", NotificationManager.IMPORTANCE_LOW).apply { description="Постоянная работа плавающего окна"; setShowBadge(false) }; getSystemService(NotificationManager::class.java).createNotificationChannel(c) } }
    private fun notification(): Notification {
        val stop=Intent(this, MonitorService::class.java).setAction(ACTION_STOP)
        val pi=PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_thermo)
            .setContentTitle("BatteryGuard работает")
            .setContentText("Плавающие системные показатели активны")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_thermo, "Выключить", pi).build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.isEnabled(this)) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleRestart() {
        if (!Prefs.isEnabled(this)) return
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val restart = Intent(this, ServiceRestartReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, 74, restart,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1800L,
            pending
        )
    }
    companion object { const val CHANNEL="batteryguard_monitor"; const val NOTIFICATION_ID=41; const val ACTION_STOP="batteryguard.STOP" }
}

