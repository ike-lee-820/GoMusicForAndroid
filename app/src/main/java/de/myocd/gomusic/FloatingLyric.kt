package de.myocd.gomusic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** 字体选项：与设置里的顺序一致 */
val FONT_NAMES = listOf("默认", "无衬线", "衬线", "等宽", "粗体")

fun floatTypeface(idx: Int): Typeface = when (idx) {
    1 -> Typeface.SANS_SERIF
    2 -> Typeface.SERIF
    3 -> Typeface.MONOSPACE
    4 -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    else -> Typeface.DEFAULT
}

/** 是否拥有悬浮窗权限 */
fun canOverlay(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

/** 悬浮歌词窗：纯 View 绘制，不依赖 Activity，退出界面后仍可显示 */
@SuppressLint("ViewConstructor")
private class LyricView(ctx: Context, private val onMove: (Float, Float) -> Unit) : View(ctx) {
    var text: String = ""
        set(v) { field = v; invalidate() }
    var sub: String = ""
        set(v) { field = v; invalidate() }
    var textColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var strokeW: Float = 3f
        set(v) { field = v; invalidate() }
    var strokeColor: Int = Color.BLACK
        set(v) { field = v; invalidate() }
    var sizePx: Float = 16 * resources.displayMetrics.scaledDensity
        set(v) { field = v; invalidate() }
    var face: Typeface = Typeface.DEFAULT
        set(v) { field = v; invalidate() }

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (text.isBlank() && sub.isBlank()) return
        val pad = (sizePx * 0.4f).roundToInt().coerceAtLeast(4)
        val w = width - pad * 2
        if (w <= 0) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = sizePx
            typeface = face
        }
        var top = pad.toFloat()
        if (text.isNotBlank()) {
            val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            c.save()
            c.translate(pad.toFloat(), top)
            l.draw(c)
            c.restore()
            if (strokeW > 0) {
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = strokeColor
                    style = Paint.Style.STROKE
                    strokeWidth = strokeW * resources.displayMetrics.density
                    strokeJoin = Paint.Join.ROUND
                    textSize = sizePx
                    typeface = face
                }
                val ls = StaticLayout.Builder.obtain(text, 0, text.length, sp, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                c.save()
                c.translate(pad.toFloat(), top)
                ls.draw(c)
                c.restore()
            }
            top += paint.fontSpacing
        }
        if (sub.isNotBlank()) {
            val p2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                alpha = (Color.alpha(textColor) * 0.6f).roundToInt()
                textSize = sizePx * 0.8f
                typeface = face
            }
            val l2 = StaticLayout.Builder.obtain(sub, 0, sub.length, p2, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            c.save()
            c.translate(pad.toFloat(), top)
            l2.draw(c)
            c.restore()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.rawX
                lastY = e.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastX
                val dy = e.rawY - lastY
                if (!moved && kotlin.math.abs(dx) + kotlin.math.abs(dy) < 8) return true
                moved = true
                val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                lp.x += dx.roundToInt()
                lp.y += dy.roundToInt()
                runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(this, lp) }
                lastX = e.rawX
                lastY = e.rawY
                val dm = resources.displayMetrics
                onMove(lp.x.toFloat() / dm.widthPixels, lp.y.toFloat() / dm.heightPixels)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

object FloatingLyric {
    private var wm: WindowManager? = null
    private var view: LyricView? = null
    @Volatile private var shown = false

    fun isShown() = shown

    /** 应用样式（不改开关状态） */
    private fun style(v: LyricView, p: Prefs) {
        v.textColor = p.floatColor
        v.strokeW = p.floatStroke
        v.strokeColor = p.floatStrokeColor
        v.sizePx = p.floatSize * v.resources.displayMetrics.scaledDensity
        v.face = floatTypeface(p.floatFont)
    }

    fun show(ctx: Context) {
        if (shown) return
        if (!canOverlay(ctx)) return
        val p = Prefs(ctx)
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = LyricView(ctx.applicationContext) { fx, fy ->
            p.floatX = fx.coerceIn(0f, 1f)
            p.floatY = fy.coerceIn(0f, 1f)
        }
        style(v, p)
        val dm = ctx.resources.displayMetrics
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (p.floatX * dm.widthPixels).roundToInt()
            y = (p.floatY * dm.heightPixels).roundToInt()
        }
        try {
            w.addView(v, lp)
        } catch (e: Exception) {
            return
        }
        wm = w
        view = v
        shown = true
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
        shown = false
    }

    /** 更新歌词文本：cur 当前行，next 下一行 */
    fun update(cur: String, next: String = "") {
        val v = view ?: return
        if (v.text == cur && v.sub == next) return
        v.text = cur
        v.sub = next
    }

    /** 设置里改了样式后立刻生效 */
    fun restyle(ctx: Context) {
        val v = view ?: return
        style(v, Prefs(ctx))
    }
}
