// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-image crop view with a fixed-aspect selection rectangle.
 *
 * The source bitmap is drawn fit-centered. The selection lives in *bitmap*
 * coordinate space: one finger drags it, a two-finger pinch resizes it around
 * its center while preserving [aspect]. Output pixels come from
 * [croppedBitmap] at the source bitmap's native resolution.
 */
class KeyboardBackgroundCropView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Gesture {
        NONE,
        DRAG,
        SCALE,
    }

    private var source: Bitmap? = null
    private var crop = RectF()
    private var aspect = 1f
    private var gesture = Gesture.NONE
    private var downCrop = RectF()
    private var downPoint = ViewPoint()
    private var activePointer = -1
    private var startSpan = 0f
    private var startWidth = 0f

    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            color = Color.WHITE
        }
    private val gridPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
            color = Color.argb(120, 255, 255, 255)
        }
    private val scrimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Show [bitmap] and reset the selection to a centered rectangle of [aspect]. */
    fun setImageBitmap(
        bitmap: Bitmap,
        aspect: Float,
    ) {
        require(aspect > 0f) { "aspect must be positive" }
        this.aspect = aspect
        source = bitmap
        resetCrop()
        invalidate()
    }

    fun setAspectRatio(aspect: Float) {
        require(aspect > 0f) { "aspect must be positive" }
        this.aspect = aspect
        resetCrop()
        invalidate()
    }

    /** The selected region as a new bitmap at native resolution, or null. */
    fun croppedBitmap(): Bitmap? {
        val bitmap = source ?: return null
        val rect = bitmapCropRect() ?: return null
        return if (rect.width() <= 0 || rect.height() <= 0) {
            null
        } else {
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        }
    }

    /** Recenter the selection at its default size (keyboard aspect, largest fit). */
    fun resetSelection() {
        resetCrop()
        invalidate()
    }

    private fun resetCrop() {
        val bitmap = source ?: return
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        var width = w
        var height = width / aspect
        if (height > h) {
            height = h
            width = height * aspect
        }
        crop.set((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    private fun bitmapCropRect(): Rect? {
        val bitmap = source ?: return null
        val left = crop.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top = crop.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right = crop.right.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = crop.bottom.coerceIn(0f, bitmap.height.toFloat()).toInt()
        return Rect(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = source ?: return
        canvas.drawColor(Color.BLACK)
        val (scale, offsetX, offsetY) = displayTransform(bitmap)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()

        val viewCrop = mapBitmapRectToView(crop)
        // Scrim around the selection.
        canvas.drawRect(0f, 0f, width.toFloat(), viewCrop.top, scrimPaint)
        canvas.drawRect(0f, viewCrop.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, viewCrop.top, viewCrop.left, viewCrop.bottom, scrimPaint)
        canvas.drawRect(viewCrop.right, viewCrop.top, width.toFloat(), viewCrop.bottom, scrimPaint)

        canvas.drawRect(viewCrop, borderPaint)
        drawGrid(canvas, viewCrop)
    }

    private fun drawGrid(
        canvas: Canvas,
        r: RectF,
    ) {
        val thirdW = r.width() / 3f
        val thirdH = r.height() / 3f
        for (i in 1..2) {
            val x = r.left + thirdW * i
            canvas.drawLine(x, r.top, x, r.bottom, gridPaint)
        }
        for (i in 1..2) {
            val y = r.top + thirdH * i
            canvas.drawLine(r.left, y, r.right, y, gridPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = source ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.DRAG
                activePointer = event.getPointerId(0)
                downCrop.set(crop)
                downPoint.set(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    gesture = Gesture.SCALE
                    startSpan = span(event)
                    startWidth = crop.width()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.DRAG -> {
                        // A pan only translates the rectangle: size and aspect
                        // stay exactly as they were when the gesture started.
                        if (event.pointerCount > 1) {
                            // Missed POINTER_DOWN edge: a second finger means
                            // resize intent, never a dragging rect.
                            gesture = Gesture.SCALE
                            startSpan = span(event)
                            startWidth = crop.width()
                        } else {
                            drag(event)
                        }
                    }
                    Gesture.SCALE -> scale(event)
                    Gesture.NONE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    gesture = Gesture.DRAG
                    val remaining = if (event.getPointerId(0) == event.getPointerId(event.actionIndex)) 1 else 0
                    activePointer = event.getPointerId(remaining)
                    val idx = event.findPointerIndex(activePointer)
                    if (idx >= 0) {
                        downCrop.set(crop)
                        downPoint.set(event.getX(idx), event.getY(idx))
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesture = Gesture.NONE
                activePointer = -1
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drag(event: MotionEvent) {
        if (activePointer < 0) return
        val idx = event.findPointerIndex(activePointer)
        if (idx < 0) return
        val (scale, _, _) = displayTransform(source ?: return)
        val dx = (event.getX(idx) - downPoint.x) / scale
        val dy = (event.getY(idx) - downPoint.y) / scale
        crop.set(downCrop)
        crop.offset(dx, dy)
        clampCrop()
    }

    private fun scale(event: MotionEvent) {
        if (event.pointerCount < 2 || startSpan <= 0f) return
        val current = span(event)
        val factor = current / startSpan
        var newWidth = startWidth * factor
        val bitmap = source ?: return
        val maxWidth = bitmap.width.toFloat()
        newWidth = newWidth.coerceIn(minCropSize(bitmap), maxWidth)
        var newHeight = newWidth / aspect
        if (newHeight > bitmap.height.toFloat()) {
            newHeight = bitmap.height.toFloat()
            newWidth = newHeight * aspect
        }
        val centerX = crop.centerX()
        val centerY = crop.centerY()
        crop.set(
            centerX - newWidth / 2f,
            centerY - newHeight / 2f,
            centerX + newWidth / 2f,
            centerY + newHeight / 2f,
        )
        clampCrop()
    }

    private fun clampCrop() {
        val bitmap = source ?: return
        val maxLeft = bitmap.width.toFloat() - crop.width()
        val maxTop = bitmap.height.toFloat() - crop.height()
        crop.left = crop.left.coerceIn(0f, maxLeft.coerceAtLeast(0f))
        crop.top = crop.top.coerceIn(0f, maxTop.coerceAtLeast(0f))
        crop.right = crop.left + crop.width()
        crop.bottom = crop.top + crop.height()
    }

    private fun minCropSize(bitmap: Bitmap): Float {
        // A reasonable floor: at least 10% of the shorter edge, 48 px minimum.
        val shortest = min(bitmap.width, bitmap.height).toFloat()
        return max(shortest * 0.1f, 48f)
    }

    private fun displayTransform(bitmap: Bitmap): DisplayTransform {
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val offsetX = (width - bitmap.width * scale) / 2f
        val offsetY = (height - bitmap.height * scale) / 2f
        return DisplayTransform(scale, offsetX, offsetY)
    }

    private fun mapBitmapRectToView(r: RectF): RectF {
        val (scale, offsetX, offsetY) = displayTransform(source ?: return r)
        return RectF(
            r.left * scale + offsetX,
            r.top * scale + offsetY,
            r.right * scale + offsetX,
            r.bottom * scale + offsetY,
        )
    }

    private fun span(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private class ViewPoint {
        var x = 0f
        var y = 0f

        fun set(
            x: Float,
            y: Float,
        ) {
            this.x = x
            this.y = y
        }
    }

    private data class DisplayTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )
}
