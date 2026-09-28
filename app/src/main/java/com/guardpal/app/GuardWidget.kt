package com.guardpal.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews

/** A small home-screen widget: Byte + your safety status. Tapping it opens Guard Pal. */
class GuardWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(ctx, mgr, id)
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GuardWidget::class.java))
            for (id in ids) render(ctx, mgr, id)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val p = GuardService.prefs(ctx)
            val result = p.getString("lastResult", "none")
            val name = p.getString("name", "Byte") ?: "Byte"
            val danger = result == "danger"
            val warn = result == "warn"

            val views = RemoteViews(ctx.packageName, R.layout.widget_guard)
            views.setImageViewBitmap(R.id.widgetIcon, byteBitmap(danger))
            views.setTextViewText(R.id.widgetTitle, name)
            views.setTextViewText(
                R.id.widgetStatus,
                when { danger -> "⚠ Threat found — tap"; warn -> "Be careful — tap"; result == "safe" -> "All clear ✓"; else -> "Tap to scan" }
            )
            views.setInt(R.id.widgetRoot, "setBackgroundColor",
                if (danger) Color.rgb(40, 14, 22) else Color.rgb(15, 22, 40))

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("widget", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            mgr.updateAppWidget(id, views)
        }

        private fun byteBitmap(danger: Boolean): Bitmap {
            val s = 160
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = s / 2f; val cy = s * 0.45f; val r = s * 0.32f
            val c1 = if (danger) Color.rgb(255, 120, 140) else Color.rgb(111, 182, 255)
            val c2 = if (danger) Color.rgb(200, 40, 70) else Color.rgb(47, 107, 255)
            paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, c1, c2, Shader.TileMode.CLAMP)
            val path = Path()
            path.moveTo(cx, cy - r)
            path.cubicTo(cx + r * 1.3f, cy - r, cx + r * 1.5f, cy, cx + r * 1.5f, cy + r * 0.4f)
            path.cubicTo(cx + r * 1.5f, cy + r * 1.3f, cx + r * 0.7f, cy + r * 1.8f, cx, cy + r * 2.1f)
            path.cubicTo(cx - r * 0.7f, cy + r * 1.8f, cx - r * 1.5f, cy + r * 1.3f, cx - r * 1.5f, cy + r * 0.4f)
            path.cubicTo(cx - r * 1.5f, cy, cx - r * 1.3f, cy - r, cx, cy - r)
            path.close()
            c.drawPath(path, paint); paint.shader = null
            paint.color = Color.rgb(20, 30, 55)
            c.drawRoundRect(RectF(cx - r * 0.7f, cy - r * 0.05f, cx + r * 0.7f, cy + r * 0.6f), r * 0.3f, r * 0.3f, paint)
            paint.color = if (danger) Color.rgb(255, 210, 220) else Color.rgb(127, 246, 255)
            c.drawOval(RectF(cx - r * 0.4f, cy + r * 0.1f, cx - r * 0.18f, cy + r * 0.45f), paint)
            c.drawOval(RectF(cx + r * 0.18f, cy + r * 0.1f, cx + r * 0.4f, cy + r * 0.45f), paint)
            return bmp
        }
    }
}
