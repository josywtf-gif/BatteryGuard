package com.example.batteryguard

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

object Prefs {
    const val FILE = "batteryguard"
    private const val ORDER = "metrics_order_all_v2"
    private const val ENABLED = "metrics_enabled_v2"
    private const val MIGRATED = "sensor_metrics_migrated_v2"
    private const val LEGACY_ORDER = "metrics_order_all"
    private const val LEGACY_ENABLED = "metrics_enabled"
    private const val LEGACY_METRICS = "metrics_order"

    const val DEFAULT_METRICS = "cpu_temp,battery_temp,ram,time,battery"
    val ALL_METRICS = listOf("cpu_temp", "battery_temp", "ram", "time", "battery", "voltage")

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    private fun migrateKey(key: String): String? = when (key) {
        "temp", "cpu_temp" -> "cpu_temp"       // старый «temp» был температурой CPU
        "battery_temp" -> "battery_temp"
        "ram", "time", "battery", "voltage" -> key
        // «cpu» (загрузка CPU) и «gpu» удалены: это не температуры и GPU не поддерживается стабильно
        else -> null
    }

    private fun migrate(c: Context) {
        val prefs = sp(c)
        if (prefs.getBoolean(MIGRATED, false)) return

        val oldOrder = prefs.getString(LEGACY_ORDER, null)
            ?: prefs.getString(LEGACY_METRICS, DEFAULT_METRICS)
            ?: DEFAULT_METRICS
        val order = oldOrder.split(',').mapNotNull(::migrateKey).distinct().toMutableList()
        if ("cpu_temp" !in order) order.add(0, "cpu_temp")
        if ("battery_temp" !in order) order.add((order.indexOf("cpu_temp") + 1).coerceAtLeast(0), "battery_temp")
        ALL_METRICS.filter { it !in order }.forEach { order.add(it) }

        val oldEnabled = prefs.getString(LEGACY_ENABLED, null)
            ?: prefs.getString(LEGACY_METRICS, DEFAULT_METRICS)
            ?: DEFAULT_METRICS
        val enabled = oldEnabled.split(',').mapNotNull(::migrateKey).distinct().toMutableList()
        // Добавляем новую температуру аккумулятора при обновлении; пользователь сможет отключить её.
        if ("battery_temp" !in enabled) enabled.add("battery_temp")
        prefs.edit()
            .putString(ORDER, order.joinToString(","))
            .putString(ENABLED, enabled.joinToString(","))
            .putBoolean(MIGRATED, true)
            .apply()
    }

    fun order(c: Context): MutableList<String> {
        migrate(c)
        val saved = sp(c).getString(ORDER, DEFAULT_METRICS).orEmpty()
        val base = saved.split(',').filter { it in ALL_METRICS }.distinct().toMutableList()
        ALL_METRICS.filter { it !in base }.forEach { base.add(it) }
        return base
    }

    fun setOrder(c: Context, value: List<String>) {
        val complete = (value.filter { it in ALL_METRICS }.distinct() + ALL_METRICS.filter { it !in value }).distinct()
        sp(c).edit().putString(ORDER, complete.joinToString(",")).apply()
    }

    fun metrics(c: Context): MutableList<String> {
        migrate(c)
        val enabled = sp(c).getString(ENABLED, DEFAULT_METRICS).orEmpty()
            .split(',').filter { it in ALL_METRICS }.toSet()
        return order(c).filter { it in enabled }.toMutableList()
    }

    fun setMetrics(c: Context, v: List<String>) {
        migrate(c)
        sp(c).edit().putString(ENABLED, v.filter { it in ALL_METRICS }.distinct().joinToString(",")).apply()
    }

    fun voltageDetailsEnabled(c: Context) = "voltage" in metrics(c)
    val STYLE_LABELS = linkedMapOf(
        "classic" to "Стандартный — контурные значки на тёмной карточке",
        "rtss" to "Игровой OSD — подписи и моно-шрифт, как в RTSS",
        "minimal" to "Минимализм — без фона, заливные цветные значки, тень",
        "neon" to "Неон — светящиеся цветные значения, рамка",
        "compact" to "Компактный — одна строка с заливными значками",
        "chips" to "Плашки — цветные капсулы с заливными значками",
        "terminal" to "Терминал — зелёный моно-текст «> cpu»",
        "dashboard" to "Панель — мелкая подпись над значением, цветная полоса",
        "emoji" to "Эмодзи — значки-смайлы"
    )
    val STYLES = STYLE_LABELS.keys
    fun overlayStyle(c: Context) = sp(c).getString("overlay_style", "classic")?.takeIf { it in STYLES } ?: "classic"
    fun setOverlayStyle(c: Context, value: String) = sp(c).edit().putString("overlay_style", if (value in STYLES) value else "classic").apply()
    fun size(c: Context) = sp(c).getInt("size", 48)
    fun setSize(c: Context, v: Int) = sp(c).edit().putInt("size", v).apply()
    fun alpha(c: Context) = sp(c).getInt("alpha", 90)
    fun setAlpha(c: Context, v: Int) = sp(c).edit().putInt("alpha", v).apply()
    // Смещения окна хранятся в dp, чтобы положение сохранялось при смене плотности экрана.
    fun posX(c: Context) = sp(c).getInt("x", -24)
    fun posY(c: Context) = sp(c).getInt("y", -24)
    fun setPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()
    fun overlay(c: Context) = Settings.canDrawOverlays(c)
}
