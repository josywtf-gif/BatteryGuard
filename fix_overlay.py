#!/usr/bin/env python3
"""Repair BatteryGuard overlay geometry in the archived Android project."""
from pathlib import Path
import re

candidates = list(Path(".").rglob("FloatingBubble.kt"))
if not candidates:
    raise SystemExit("FloatingBubble.kt not found")
path = next((p for p in candidates if "com/example/batteryguard" in str(p)), candidates[0])
s = path.read_text(encoding="utf-8")

# Keep the WindowManager surface permanently attached to the screen's top-left.
start = s.index("    private val lp = WindowManager.LayoutParams(")
end = s.index("    private var shown = false", start)
lp = """    private val lp = WindowManager.LayoutParams(
        px(180), px(80), type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
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
"""
s = s[:start] + lp + s[end:]

if "private var contentWidthPx" not in s:
    s = s.replace("    private var insetLeft = 0\n",
                  "    private var insetLeft = 0\n    private var contentWidthPx = px(180)\n    private var contentHeightPx = px(80)\n")

# Remove the previous oversized full-screen window sizing.
s = s.replace("""        val oldWidth = lp.width; val oldHeight = lp.height
        lp.width = ctx.resources.displayMetrics.widthPixels + px(600)
        lp.height = ctx.resources.displayMetrics.heightPixels + px(600)
""", "")

s = s.replace("""        if (!keysChanged && !sizeChanged && !contentChanged) return
""", """        if (!keysChanged && !sizeChanged && !contentChanged) {
            updateOverlayGeometry()
            return
        }
""")

# Replace the previous child/window positioning block with fixed-corner sizing.
start = s.index("        val contentWidthPx = desired.coerceIn", s.index("fun applySize"))
end = s.index("    private fun rebuildRows", start)
geometry = """        contentWidthPx = desired.coerceIn(px(92), screenWidth.coerceAtLeast(px(92)))
        contentHeightPx = contentHeight.coerceAtLeast(rowHeightPx) + padV * 2
        configuredKeys = keys; configuredStyle = style; configuredVoltageDetails = voltageDetails

        root.background = styleBackground(style)
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(insetLeft + leftPad, padV, px(8), padV)
        if (keysChanged || sizeChanged) rebuildRows(keys, style)
        updateOverlayGeometry()
    }

    /** Window remains at (0,0). Sliders resize its right/bottom edges;
     * content is anchored to those edges and clipped at the fixed top/left. */
    private fun updateOverlayGeometry() {
        val offsetX = px(Prefs.posX(ctx))
        val offsetY = px(Prefs.posY(ctx))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.width = (contentWidthPx + offsetX).coerceAtLeast(1)
        lp.height = (contentHeightPx + offsetY).coerceAtLeast(1)
        root.layoutParams = FrameLayout.LayoutParams(
            contentWidthPx,
            contentHeightPx,
            Gravity.RIGHT or Gravity.BOTTOM
        )
        if (shown) try { wm.updateViewLayout(windowRoot, lp) } catch (_: Exception) {}
    }

"""
s = s[:start] + geometry + s[end:]

# Refresh changes only geometry; never assign WindowManager x/y from the sliders.
s = re.sub(
    r"""    fun show\(values: Map<String, String>\) \{.*?\n    fun hide\(\).*?\n\}""",
    """    fun show(values: Map<String, String>) {
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
}""",
    s,
    flags=re.S
)

if "lp.x = -px(Prefs.posX(ctx))" in s or "widthPixels + px(600)" in s:
    raise SystemExit("Old moving/oversized window geometry remains")
if "private fun updateOverlayGeometry()" not in s:
    raise SystemExit("Geometry fix was not applied")

path.write_text(s, encoding="utf-8")
print(f"Fixed overlay geometry in {path}")
