#!/usr/bin/env python3
"""Keep BatteryGuard's overlay WindowManager surface fixed at screen (0, 0).

Position controls resize the transparent surface to the right/bottom and move
only the content child inside it. They must never change WindowManager x/y.
"""
from pathlib import Path

candidates = [
    Path("app/src/main/java/com/example/batteryguard/FloatingBubble.kt"),
    *Path(".").rglob("FloatingBubble.kt"),
]
path = next((p for p in candidates if p.is_file()), None)
if path is None:
    raise SystemExit("FloatingBubble.kt not found; project extraction failed")

source = path.read_text(encoding="utf-8")
start = source.index("    private fun updateOverlayGeometry() {")
layout = source.index("        if (shown) try { wm.updateViewLayout(windowRoot, lp) }", start)

geometry = """    private fun updateOverlayGeometry() {
        // WindowManager surface never moves: top-left is always screen (0, 0).
        // Positive slider values add transparent space on the right/bottom.
        // Negative values move only the content beyond the fixed top/left edge.
        val offsetX = px(Prefs.posX(ctx))
        val offsetY = px(Prefs.posY(ctx))
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.width = (contentWidthPx + offsetX.coerceAtLeast(0)).coerceAtLeast(contentWidthPx)
        lp.height = (contentHeightPx + offsetY.coerceAtLeast(0)).coerceAtLeast(contentHeightPx)
        root.layoutParams = FrameLayout.LayoutParams(
            contentWidthPx,
            contentHeightPx,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = offsetX
            topMargin = offsetY
        }
"""
source = source[:start] + geometry + source[layout:]
path.write_text(source, encoding="utf-8")
print(f"Fixed overlay content geometry: {path}")
