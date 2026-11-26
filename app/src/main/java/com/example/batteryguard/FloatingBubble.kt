package com.example.batteryguard

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class FloatingBubble(private val ctx: Context, type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // The WindowManager surface is permanently fixed to screen origin.
    // Only its child background and text layout are adjusted by the sliders.
    private val windowRoot = FrameLayout(ctx)
    private val backgroundView = View(ctx)
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
    private val rows = LinkedHashMap<String, TextView>()
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private var shown = false
    private var textSizeSp = 14f
    private var rowHeightPx = px(30)
    private var configuredKeys: List<String> = emptyList()
    private var configuredStyle = ""
    private var configuredVoltageDetails = false
    private var insetLeft = 0
    private var contentWidthPx = px(180)
    private var contentHeightPx = px(80)

    init {
        // Background stays at the top-left; the text layer is positioned independently.
        windowRoot.addView(backgroundView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        windowRoot.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        windowRoot.setOnApplyWindowInsetsListener { _, insets ->
            val l = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetLeft ?: 0 else 0
            if (l != insetLeft) {
                insetLeft = l
                val keys = configuredKeys
                configuredKeys = emptyList()
                applySize(Prefs.size(ctx), keys)
            }
            insets
        }
        applySize(Prefs.size(ctx), Prefs.metrics(ctx).filter { it != "voltage" })
        applyAlpha(Prefs.alpha(ctx))
    }

    private fun px(dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()
    private fun label(key: String) = when (key) {
        "cpu_temp" -> "CPU"
        "battery_temp" -> "BATT"
        "ram" -> "RAM"
        "time" -> "TIME"
        "battery" -> "CHARGE"
        else -> key.uppercase()
    }
    private fun iconRes(key: String): Int = when (key) {
        "cpu_temp" -> R.drawable.ic_cpu_temp
        "battery_temp" -> R.drawable.ic_battery_temp
        "ram" -> R.drawable.ic_ram
        "time" -> R.drawable.ic_clock
        "battery" -> R.drawable.ic_charge
        else -> R.drawable.ic_thermo
    }
    private fun accent(key: String): Int = when (key) {
        "cpu_temp" -> 0xFFFFA23A.toInt()
        "battery_temp" -> 0xFFFFC15A.toInt()
        "ram" -> 0xFF31C8A0.toInt()
        "time" -> 0xFFFFD166.toInt()
        "battery" -> 0xFF83C98B.toInt()
        else -> Color.WHITE
    }

    private val iconSlotPx get() = px(28)
    private val iconPx get() = px(21)
    private fun isMono(style: String) = style == "rtss" || style == "terminal"
    private fun valueTypeface(style: String) = if (isMono(style)) Typeface.create("monospace", Typeface.BOLD) else Typeface.DEFAULT_BOLD
    private fun valueSizeSp(style: String) = when (style) { "rtss", "terminal" -> textSizeSp * 0.88f; "dashboard" -> textSizeSp * 1.05f; else -> textSizeSp }
    private fun rowH(style: String) = when (style) { "dashboard" -> (rowHeightPx * 1.55f).toInt(); else -> rowHeightPx }
    private fun rowGap(style: String) = if (style == "chips") px(4) else 0
    private fun filledIcon(key: String) = when (key) {
        "cpu_temp" -> R.drawable.ic_f_cpu_temp
        "battery_temp" -> R.drawable.ic_f_battery_temp
        "ram" -> R.drawable.ic_f_ram
        "time" -> R.drawable.ic_f_time
        "battery" -> R.drawable.ic_f_charge
        else -> R.drawable.ic_thermo
    }
    private fun emoji(key: String) = when (key) {
        "cpu_temp" -> "🖥"; "battery_temp" -> "🌡"; "ram" -> "💾"; "time" -> "🕒"; "battery" -> "🔋"; else -> "•"
    }

    private fun iconCell(res: Int, tint: Int, h: Int, slot: Int = iconSlotPx, size: Int = iconPx) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(slot, h)
        addView(ImageView(ctx).apply {
            setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
        })
    }
    private fun dotCell(color: Int, h: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(iconSlotPx, h)
        addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(px(10), px(10))
        })
    }
    private fun emojiCell(key: String, h: Int) = TextView(ctx).apply {
        text = emoji(key); gravity = Gravity.CENTER; includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 1.05f)
        layoutParams = LinearLayout.LayoutParams(iconSlotPx, h)
    }

    private fun valueText(style: String, key: String) = TextView(ctx).apply {
        setTextColor(when (style) { "neon" -> accent(key); "terminal" -> 0xFFB6FFB0.toInt(); else -> Color.WHITE })
        typeface = valueTypeface(style)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSizeSp(style)); maxLines = 1
        ellipsize = if (key == "ram") null else TextUtils.TruncateAt.END
        includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
        // RAM has a variable-length "used / total MB" string. Shrink it
        // slightly if needed instead of showing an ellipsis.
        if (key == "ram" && Build.VERSION.SDK_INT >= 26) {
            setAutoSizeTextTypeUniformWithConfiguration(
                (valueSizeSp(style) * 0.72f).toInt().coerceAtLeast(10),
                valueSizeSp(style).toInt().coerceAtLeast(12),
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
        when (style) {
            "minimal" -> setShadowLayer(px(3).toFloat(), 0f, px(1).toFloat(), Color.BLACK)
            "neon" -> setShadowLayer(px(6).toFloat(), 0f, 0f, accent(key))
            "terminal" -> setShadowLayer(px(4).toFloat(), 0f, 0f, 0x8800FF55.toInt())
        }
    }

    private fun hRow(h: Int, vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, h).apply { bottomMargin = 0 }
        children.forEach { addView(it) }
    }

    private fun createRow(style: String, key: String): Pair<View, TextView> {
        val h = rowH(style)
        val value = valueText(style, key)
        val valueLp = LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(5) }
        return when (style) {
            "rtss" -> {
                val name = TextView(ctx).apply {
                    text = label(key); setTextColor(accent(key)); typeface = Typeface.create("monospace", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.78f); includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(px(50), h)
                }
                hRow(h, name).also { it.addView(value, valueLp) } to value
            }
            "terminal" -> {
                val name = TextView(ctx).apply {
                    text = "> " + label(key).lowercase(); setTextColor(0xFF2BD36B.toInt()); typeface = Typeface.create("monospace", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.8f); includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(px(66), h)
                }
                hRow(h, name).also { it.addView(value, valueLp) } to value
            }
            "dashboard" -> {
                val strip = View(ctx).apply { setBackgroundColor(accent(key)); layoutParams = LinearLayout.LayoutParams(px(3), h - px(8)) }
                val caption = TextView(ctx).apply {
                    text = label(key); setTextColor(0xFF9AA4B5.toInt()); typeface = Typeface.DEFAULT
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp * 0.6f); includeFontPadding = false; maxLines = 1
                    letterSpacing = 0.1f
                }
                value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    addView(caption, LinearLayout.LayoutParams(-2, -2))
                    addView(value, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(1) })
                }
                hRow(h, strip).also { it.addView(col, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = px(8) }) } to value
            }
            "chips" -> {
                val row = hRow(h, iconCell(filledIcon(key), Color.WHITE, h, px(26), px(17))).also { it.addView(value, valueLp) }
                (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = rowGap(style)
                row.background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor((accent(key) and 0x00FFFFFF) or 0x99000000.toInt()) }
                row.setPadding(px(4), 0, px(8), 0)
                row to value
            }
            "compact" -> {
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-2, h).apply { marginEnd = px(10) }
                    addView(iconCell(filledIcon(key), accent(key), h, px(20), px(16)))
                    addView(value, LinearLayout.LayoutParams(-2, -1).apply { marginStart = px(2) })
                }
                cell to value
            }
            "minimal" -> hRow(h, iconCell(filledIcon(key), accent(key), h)).also { it.addView(value, valueLp) } to value
            "emoji" -> hRow(h, emojiCell(key, h)).also { it.addView(value, valueLp) } to value
            else -> hRow(h, iconCell(iconRes(key), accent(key), h)).also { it.addView(value, valueLp) } to value // classic, neon use outline icons
        }
    }

    private fun styleBackground(style: String, opacityPercent: Int = Prefs.alpha(ctx)): LayerDrawable {
        // Opacity slider controls the panel fill itself. At 100%, fill alpha
        // must be 255 (opaque), not the old hard-coded 218/235/238 values.
        val fillAlpha = (opacityPercent.coerceIn(0, 100) * 255 / 100)
        val r = when (style) { "rtss" -> 6; "minimal" -> 0; "neon" -> 10; "compact" -> 12; "terminal" -> 4; "dashboard" -> 14; "chips" -> 0; "emoji" -> 16; else -> 18 }.let { px(it) }.toFloat()
        val bg = GradientDrawable().apply {
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, 0f, 0f)
            when (style) {
                "rtss" -> { setColor(Color.argb(fillAlpha, 8, 10, 12)); setStroke(px(1), Color.argb(100, 150, 150, 150)) }
                "minimal", "chips" -> setColor(Color.argb(fillAlpha, 0, 0, 0))
                "neon" -> { setColor(Color.argb(fillAlpha, 6, 8, 14)); setStroke(px(2), Color.argb(230, 67, 200, 255)) }
                "compact" -> setColor(Color.argb(fillAlpha, 14, 18, 26))
                "terminal" -> { setColor(Color.argb(fillAlpha, 0, 10, 4)); setStroke(px(1), 0xFF1F9A50.toInt()) }
                "dashboard" -> setColor(Color.argb(fillAlpha, 34, 36, 42))
                "emoji" -> setColor(Color.argb(fillAlpha, 40, 30, 52))
                else -> setColor(Color.argb(fillAlpha, 25, 32, 46))
            }
        }
        return LayerDrawable(arrayOf(bg)).apply { setLayerInset(0, -px(3), -px(3), 0, 0) }
    }

    /** Window geometry changes only when text size, enabled metrics, or style changes. */
    fun applySize(dp: Int, metricKeys: List<String> = configuredKeys) {
        val keys = metricKeys.distinct()
        val style = Prefs.overlayStyle(ctx)
        val newTextSize = (dp * 0.30f).coerceIn(12f, 24f)
        val newRowHeight = px(maxOf(24, (newTextSize * 1.45f).toInt()))
        val keysChanged = configuredKeys != keys || configuredStyle != style
        val sizeChanged = textSizeSp != newTextSize || rowHeightPx != newRowHeight
        val voltageDetails = Prefs.voltageDetailsEnabled(ctx)
        val contentChanged = configuredVoltageDetails != voltageDetails
        if (!keysChanged && !sizeChanged && !contentChanged) {
            updateOverlayGeometry()
            return
        }
        textSizeSp = newTextSize; rowHeightPx = newRowHeight

        val samples = mapOf(
            "cpu_temp" to "99.9 °C", "battery_temp" to "99.9 °C", "ram" to "99999 / 99999 MB",
            "time" to "23:59", "battery" to if (voltageDetails) "100% · 4.50 В · −999 мА" else "100%"
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = valueTypeface(style)
            textSize = valueSizeSp(style) * ctx.resources.displayMetrics.scaledDensity
        }
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val leftPad = px(16)
        val padH = leftPad + px(8)
        val padV = when (style) { "rtss", "chips" -> px(4); else -> px(6) }
        val contentWidth: Float
        val contentHeight: Int
        if (style == "compact") {
            contentWidth = keys.sumOf { (paint.measureText((samples[it] ?: "н/д")) + px(20) + px(2) + px(10)).toDouble() }.toFloat() - px(10)
            contentHeight = rowHeightPx
            root.orientation = LinearLayout.HORIZONTAL
        } else {
            val extra = when (style) { "rtss" -> px(55); "terminal" -> px(71); "dashboard" -> px(11); "chips" -> px(46); else -> px(33) }
            contentWidth = keys.maxOfOrNull { paint.measureText((samples[it] ?: "н/д")) + extra } ?: px(70).toFloat()
            contentHeight = keys.size * (rowH(style) + rowGap(style))
            root.orientation = LinearLayout.VERTICAL
        }
        // Include actual row chrome (icon/name slot, value gap and right padding)
        // so the measured width is sufficient for the longest RAM value.
        // contentWidth already includes the row's icon/name slot and value gap.
        // Add only the left inset; adding right padding twice made the panel
        // visibly overhang past the last text/value on the right.
        val desired = (contentWidth + leftPad).toInt()
        // Width is content-measured in dp and does not depend on orientation.
        // Do not scale it by the current screen's short edge: usable height
        // changes with system bars/cutouts after rotation and caused stretching.
        contentWidthPx = maxOf(desired, px(180))
            .coerceAtLeast(px(92))
        contentHeightPx = contentHeight.coerceAtLeast(rowHeightPx) + padV * 2
        configuredKeys = keys; configuredStyle = style; configuredVoltageDetails = voltageDetails

        backgroundView.background = styleBackground(style)
        root.background = null
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(insetLeft + leftPad, padV, 0, padV)
        if (keysChanged || sizeChanged) rebuildRows(keys, style)
        updateOverlayGeometry()
    }

    /**
     * WindowManager is MATCH_PARENT at (0,0) and never resized or repositioned.
     * The background remains anchored at the top-left and grows right/down.
     * Only the text layer receives slider margins; negative margins are clipped
     * by the fixed screen-sized parent instead of moving the system window.
     */
    /**
     * Keep the same usable positioning range in portrait and landscape.
     * Use the shorter screen dimension as the common reference, then clamp
     * the child layer so its right/bottom edge cannot disappear off-screen.
     * The WindowManager surface itself remains MATCH_PARENT at (0,0).
     */
    private fun updateOverlayGeometry() {
        val metrics = ctx.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Slider values are dp offsets, so the same setting means the same
        // physical distance in portrait and landscape. Never scale by screen
        // dimensions or move the WindowManager surface.
        val offsetX = px(Prefs.posX(ctx)).coerceIn(
            -contentWidthPx + px(12),
            (screenWidth - contentWidthPx).coerceAtLeast(0)
        )
        val offsetY = px(Prefs.posY(ctx)).coerceIn(
            -contentHeightPx + rowHeightPx,
            (screenHeight - contentHeightPx).coerceAtLeast(0)
        )

        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT

        // Solid background extends from the fixed top-left origin to the
        // shifted text layer. The text itself is the only translated child.
        backgroundView.layoutParams = FrameLayout.LayoutParams(
            contentWidthPx + offsetX.coerceAtLeast(0),
            contentHeightPx + offsetY.coerceAtLeast(0),
            Gravity.TOP or Gravity.START
        )
        root.layoutParams = FrameLayout.LayoutParams(
            contentWidthPx,
            contentHeightPx,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = offsetX
            topMargin = offsetY
        }
        if (shown) try { wm.updateViewLayout(windowRoot, lp) } catch (_: Exception) {}
    }

    private fun rebuildRows(keys: List<String>, style: String) {
        val old = rows.mapValues { it.value.text }
        root.removeAllViews(); rows.clear()
        keys.forEach { key ->
            val (view, text) = createRow(style, key)
            old[key]?.let { text.text = it }
            root.addView(view); rows[key] = text
        }
    }

    fun applyAlpha(percent: Int) {
        val normalized = percent.coerceIn(0, 100)
        val a = normalized / 100f
        root.alpha = a
        // Apply opacity to the drawable's fill alpha, not only View.alpha:
        // previously semi-transparent ARGB fills stayed translucent even at 100%.
        backgroundView.alpha = 1f
        backgroundView.background = styleBackground(Prefs.overlayStyle(ctx), normalized)
    }

    fun show(values: Map<String, String>) {
        // Re-apply opacity on every refresh. SettingsActivity updates the
        // preference and restarts the service, but the existing overlay view
        // is reused; without this call its drawable kept the previous alpha.
        applyAlpha(Prefs.alpha(ctx))
        val keys = values.keys.toList(); applySize(Prefs.size(ctx), keys)
        var textChanged = false
        values.forEach { (key, value) ->
            val view = rows[key]
            if (view != null && view.text.toString() != value) { view.text = value; textChanged = true }
        }
        updateOverlayGeometry()
        if (!shown) try { wm.addView(windowRoot, lp); shown = true } catch (_: Exception) {}
        if (shown && textChanged) root.invalidate()
    }

    fun hide() { if (shown) try { wm.removeView(windowRoot) } catch (_: Exception) {}; shown = false }
}
