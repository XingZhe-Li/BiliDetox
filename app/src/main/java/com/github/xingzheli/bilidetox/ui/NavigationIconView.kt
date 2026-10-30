package com.github.xingzheli.bilidetox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 统一的线条图标，不依赖宿主可能变更的资源名或外部图片。 */
class NavigationIconView(context: Context, private val kind: Kind, color: Int) : View(context) {
    enum class Kind { SEARCH, FOLLOWING, DOWNLOADS, HISTORY, FAVORITES, WATCH_LATER }

    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 32f
        canvas.save()
        canvas.translate((width - 32 * scale) / 2, (height - 32 * scale) / 2)
        canvas.scale(scale, scale)
        fun path(draw: Path.() -> Unit) = canvas.drawPath(Path().apply(draw), pen)
        when (kind) {
            Kind.SEARCH -> {
                canvas.drawCircle(13f, 13f, 8f, pen)
                canvas.drawLine(19f, 19f, 27f, 27f, pen)
            }
            Kind.FOLLOWING -> {
                canvas.drawCircle(12f, 10f, 4f, pen)
                canvas.drawCircle(23f, 11f, 3f, pen)
                path { moveTo(4f, 27f); lineTo(4f, 24f); cubicTo(4f, 16f, 20f, 16f, 20f, 24f); lineTo(20f, 27f) }
                path { moveTo(23f, 19f); cubicTo(28f, 19f, 29f, 22f, 29f, 26f) }
            }
            Kind.DOWNLOADS -> {
                canvas.drawLine(16f, 4f, 16f, 20f, pen)
                path { moveTo(10f, 14f); lineTo(16f, 20f); lineTo(22f, 14f) }
                path { moveTo(5f, 21f); lineTo(5f, 27f); lineTo(27f, 27f); lineTo(27f, 21f) }
            }
            Kind.HISTORY -> {
                canvas.drawCircle(16f, 16f, 11f, pen)
                path { moveTo(16f, 9f); lineTo(16f, 16f); lineTo(21f, 19f) }
            }
            Kind.FAVORITES -> path {
                moveTo(7f, 5f); lineTo(25f, 5f); lineTo(25f, 28f)
                lineTo(16f, 22f); lineTo(7f, 28f); close()
            }
            Kind.WATCH_LATER -> {
                canvas.drawRoundRect(RectF(4f, 7f, 28f, 28f), 4f, 4f, pen)
                canvas.drawLine(10f, 3f, 22f, 3f, pen)
                path { moveTo(13f, 13f); lineTo(21f, 17.5f); lineTo(13f, 22f); close() }
            }
        }
        canvas.restore()
    }
}
