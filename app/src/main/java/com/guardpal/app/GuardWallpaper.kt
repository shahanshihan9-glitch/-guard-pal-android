package com.guardpal.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.sin

/**
 * Live wallpaper: Byte the guardian floating on your home screen.
 * Calm blue when safe, red when the last scan found a threat.
 * Purely visual — Android does not allow a wallpaper to take input or scan.
 */
class GuardWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = ByteEngine()

    inner class ByteEngine : WallpaperService.Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var t = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val draw = object : Runnable { override fun run() { frame() } }

        private fun status(): String {
            val p = GuardService.prefs(this@GuardWallpaper)
            return p.getString("lastResult", "none") ?: "none"
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) frame() else handler.removeCallbacks(draw)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false; handler.removeCallbacks(draw)
        }

        private fun frame() {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas()
                if (c != null) render(c)
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c) } catch (e: Exception) {}
            }
            handler.removeCallbacks(draw)
            if (visible) handler.postDelayed(draw, 40)
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat(); val h = c.height.toFloat()
            t += 0.04f
            val danger = status() == "danger"

            // background gradient
            val top = if (danger) Color.rgb(30, 12, 20) else Color.rgb(11, 18, 34)
            val bot = if (danger) Color.rgb(60, 16, 24) else Color.rgb(14, 30, 52)
            paint.shader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            val cx = w / 2f
            val cy = h * 0.42f + sin(t) * 14f
            val r = w * 0.20f

            // glow
            val glow = if (danger) Color.argb(90, 255, 90, 110) else Color.argb(80, 90, 150, 255)
            paint.shader = RadialGradient(cx, cy, r * 2.4f, glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 2.4f, paint)
            paint.shader = null

            // shield body
            val c1 = if (danger) Color.rgb(255, 120, 140) else Color.rgb(111, 182, 255)
            val c2 = if (danger) Color.rgb(200, 40, 70) else Color.rgb(47, 107, 255)
            paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, c1, c2, Shader.TileMode.CLAMP)
            val path = android.graphics.Path()
            path.moveTo(cx, cy - r)
            path.cubicTo(cx + r * 1.3f, cy - r, cx + r * 1.55f, cy - r * 0.1f, cx + r * 1.55f, cy + r * 0.35f)
            path.cubicTo(cx + r * 1.55f, cy + r * 1.35f, cx + r * 0.7f, cy + r * 1.9f, cx, cy + r * 2.2f)
            path.cubicTo(cx - r * 0.7f, cy + r * 1.9f, cx - r * 1.55f, cy + r * 1.35f, cx - r * 1.55f, cy + r * 0.35f)
            path.cubicTo(cx - r * 1.55f, cy - r * 0.1f, cx - r * 1.3f, cy - r, cx, cy - r)
            path.close()
            c.drawPath(path, paint)
            paint.shader = null

            // visor
            paint.color = Color.rgb(20, 30, 55)
            val vr = RectF(cx - r * 0.75f, cy - r * 0.05f, cx + r * 0.75f, cy + r * 0.65f)
            c.drawRoundRect(vr, r * 0.35f, r * 0.35f, paint)

            // eyes
            val blink = (t % 5f) < 0.18f
            val eyeCol = if (danger) Color.rgb(255, 210, 220) else Color.rgb(127, 246, 255)
            paint.color = eyeCol
            val ey = cy + r * 0.28f
            val look = sin(t * 0.7f) * r * 0.08f
            if (blink) {
                paint.strokeWidth = r * 0.06f
                c.drawLine(cx - r * 0.42f + look, ey, cx - r * 0.20f + look, ey, paint)
                c.drawLine(cx + r * 0.20f + look, ey, cx + r * 0.42f + look, ey, paint)
            } else {
                c.drawOval(RectF(cx - r * 0.42f + look, ey - r * 0.18f, cx - r * 0.20f + look, ey + r * 0.18f), paint)
                c.drawOval(RectF(cx + r * 0.20f + look, ey - r * 0.18f, cx + r * 0.42f + look, ey + r * 0.18f), paint)
            }

            // status text
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = w * 0.05f
            paint.isFakeBoldText = true
            val name = GuardService.prefs(this@GuardWallpaper).getString("name", "Byte") ?: "Byte"
            val label = if (danger) "$name found a threat" else "$name is watching over you"
            c.drawText(label, cx, cy + r * 2.9f, paint)

            paint.textSize = w * 0.035f
            paint.isFakeBoldText = false
            paint.color = if (danger) Color.rgb(255, 150, 165) else Color.rgb(150, 200, 255)
            val sub = if (danger) "Open Guard Pal to remove it" else "All clear"
            c.drawText(sub, cx, cy + r * 3.4f, paint)
        }
    }
}
