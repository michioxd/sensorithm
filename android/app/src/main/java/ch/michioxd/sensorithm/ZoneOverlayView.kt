package ch.michioxd.sensorithm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class ZoneOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val normalBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCDDDDDD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val normalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CEEEEEE")
        style = Paint.Style.FILL
    }
    
    private val activeRedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.8 * 255).toInt(), 255, 100, 100)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val activeRedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.3 * 255).toInt(), 255, 100, 100)
        style = Paint.Style.FILL
    }
    
    private val activeBlueBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.8 * 255).toInt(), 100, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val activeBlueBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.3 * 255).toInt(), 100, 200, 255)
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((0.8 * 255).toInt(), 255, 255, 255)
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.parseColor("#80000000"))
    }

    var previewWidth: Int = 0
    var previewHeight: Int = 0
    
    var offsetX: Float = 0.5f
    var offsetY: Float = 0.5f

    var sensorSizeX: Int = 21
    var sensorSizeY: Int = 21
    var distance: Int = 40
    var angle: Int = 180
    
    var pixelOffsetX: Int = 0
    var pixelOffsetY: Int = 0
    
    var activeMask: Byte = 0

    var onOffsetChanged: (() -> Unit)? = null
    var onCameraBoundsChanged: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null
    
    private var lastLeft = -1
    private var lastTop = -1
    private var lastRight = -1
    private var lastBottom = -1

    fun updateParams(
        previewWidth: Int, previewHeight: Int,
        pixelOffsetX: Int, pixelOffsetY: Int,
        sensorSizeX: Int, sensorSizeY: Int, distance: Int, angle: Int
    ) {
        this.previewWidth = previewWidth
        this.previewHeight = previewHeight
        this.pixelOffsetX = pixelOffsetX
        this.pixelOffsetY = pixelOffsetY
        this.sensorSizeX = sensorSizeX
        this.sensorSizeY = sensorSizeY
        this.distance = distance
        this.angle = angle
        invalidate()
    }
    
    fun updateActiveMask(mask: Byte) {
        this.activeMask = mask
        invalidate()
    }

    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    
                    offsetX += dx / width
                    offsetY += dy / height
                    
                    if (previewWidth > 0 && previewHeight > 0) {
                        val halfSizeX = sensorSizeX / 2f
                        val halfSizeY = sensorSizeY / 2f
                        
                        val rad = Math.toRadians((angle - 180).toDouble())
                        val dx = Math.sin(rad).toFloat()
                        val dy = Math.cos(rad).toFloat()
                        
                        val spanX = Math.abs(2.5f * distance * dx) + halfSizeX
                        val minOffsetX = spanX / previewWidth
                        val maxOffsetX = 1.0f - spanX / previewWidth
                        if (minOffsetX <= maxOffsetX) {
                            offsetX = offsetX.coerceIn(minOffsetX, maxOffsetX)
                        } else {
                            offsetX = 0.5f
                        }
                        
                        val spanY = Math.abs(2.5f * distance * dy) + halfSizeY
                        val minOffsetY = spanY / previewHeight
                        val maxOffsetY = 1.0f - spanY / previewHeight
                        if (minOffsetY <= maxOffsetY) {
                            offsetY = offsetY.coerceIn(minOffsetY, maxOffsetY)
                        } else {
                            offsetY = 0.5f
                        }
                    }

                    lastTouchX = event.x
                    lastTouchY = event.y
                    
                    onOffsetChanged?.invoke()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (previewWidth == 0 || previewHeight == 0) return

        val scale = min(width.toFloat() / previewWidth, height.toFloat() / previewHeight)
        val scaledW = previewWidth * scale
        val scaledH = previewHeight * scale

        val leftOffset = (width - scaledW) / 2f
        val topOffset = (height - scaledH) / 2f
        
        val leftBounds = leftOffset.toInt()
        val topBounds = topOffset.toInt()
        val rightBounds = (leftOffset + scaledW).toInt()
        val bottomBounds = (topOffset + scaledH).toInt()
        
        if (leftBounds != lastLeft || topBounds != lastTop || rightBounds != lastRight || bottomBounds != lastBottom) {
            lastLeft = leftBounds
            lastTop = topBounds
            lastRight = rightBounds
            lastBottom = bottomBounds
            post {
                onCameraBoundsChanged?.invoke(leftBounds, topBounds, rightBounds, bottomBounds)
            }
        }

        canvas.save()
        
        val rad = Math.toRadians((angle - 180).toDouble())
        val dx = Math.sin(rad).toFloat()
        val dy = Math.cos(rad).toFloat()

        val boxWidth = sensorSizeX * scale
        val boxHeight = sensorSizeY * scale
        val minDim = min(boxWidth, boxHeight)
        textPaint.textSize = minDim * 0.7f
        val textOffset = (textPaint.descent() + textPaint.ascent()) / 2f

        for (i in 0 until 6) {
            val rawX = (previewWidth / 2f + pixelOffsetX + (i - 2.5f) * distance * dx).toInt()
            val rawY = (previewHeight / 2f + pixelOffsetY + (i - 2.5f) * distance * dy).toInt()
            
            val cx = leftOffset + rawX * scale
            val cy = topOffset + rawY * scale
            
            val halfSizeX = boxWidth / 2f
            val halfSizeY = boxHeight / 2f
            
            val left = cx - halfSizeX
            val top = cy - halfSizeY
            val right = cx + halfSizeX
            val bottom = cy + halfSizeY
            
            val bitMaskIndex = i
            val isActive = (activeMask.toInt() and (1 shl bitMaskIndex)) != 0
            
            val bgPaint = if (isActive) {
                if (i == 5) activeBlueBgPaint else activeRedBgPaint
            } else normalBgPaint
            
            val borderPaint = if (isActive) {
                if (i == 5) activeBlueBorderPaint else activeRedBorderPaint
            } else normalBorderPaint
            
            canvas.drawRect(left, top, right, bottom, bgPaint)
            canvas.drawRect(left, top, right, bottom, borderPaint)
            
            canvas.drawText((6 - i).toString(), cx, cy - textOffset, textPaint)
        }
        
        canvas.restore()
    }
}
