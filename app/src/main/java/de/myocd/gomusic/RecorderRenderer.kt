package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 一帧需要的全部素材。预览与正式编码共用，保证「所见即所得」。 */
data class RecFrameState(
    val song: Song,
    val cover: Bitmap?,
    val rows: List<LyricRow>,
    val positionMs: Long,
    val durationMs: Long,
)

/** 按全体偏移算出当前高亮行 */
fun recActiveRow(st: RecFrameState, offsetMs: Long): Int {
    val t = st.positionMs - offsetMs
    if (st.rows.isEmpty() || t < 0) return -1
    var idx = -1
    for (i in st.rows.indices) {
        if (st.rows[i].timeMs >= 0 && st.rows[i].timeMs <= t) idx = i else break
    }
    return idx
}

/**
 * 一帧的总入口。背景、歌词、进度条由两套样式共用，只有主体布局不同：
 * 经典 = 横屏播放器的「左信息 + 右歌词」；圆盘 = 黑胶唱片 + 歌词。
 *
 * 所有可选项（样式 / 字号 / 字体 / 译文 / 偏移 / 信息区 / 比例 / 分辨率）
 * 都从同一份 [RecorderConfig] 读，预览与编码调的是同一个函数，不会各画一套。
 *
 * 只使用硬件 Canvas 也支持的绘制调用（不 clipPath / 不 BlurMaskFilter），
 * 因此同一份代码既能画进 Compose 预览，也能画进 MediaCodec 的 input Surface。
 */
fun renderRecFrame(cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?) {
    val w = cv.width.toFloat()
    val h = cv.height.toFloat()
    if (w <= 0f || h <= 0f) return
    val scale = h / 1080f
    val pad = 28f * scale

    drawRecBackground(cv, st.cover, w, h, scale)

    when (RecStyle.of(c.style)) {
        RecStyle.CLASSIC -> drawClassic(cv, st, c, tf, w, h, pad, scale)
        RecStyle.DISC -> drawDisc(cv, st, c, tf, w, h, pad, scale)
    }
    drawRecProgress(cv, st, w, h, pad, scale)
}

/** 共用的模糊封面底 + 上下渐变遮罩 */
private fun drawRecBackground(cv: Canvas, cover: Bitmap?, w: Float, h: Float, scale: Float) {
    cv.drawColor(Color.rgb(12, 11, 16))
    cover?.let { bmp ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 70 }
        val k = max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val dw = bmp.width * k
        val dh = bmp.height * k
        cv.drawBitmap(
            bmp, Rect(0, 0, bmp.width, bmp.height),
            RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p,
        )
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    fill.shader = LinearGradient(
        0f, 0f, 0f, h,
        Color.argb(110, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP,
    )
    cv.drawRect(0f, 0f, w, h, fill)
}

/** 经典：左侧封面 + 歌曲信息，右侧居中歌词 */
private fun drawClassic(
    cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
    w: Float, h: Float, pad: Float, scale: Float,
) {
    val infoW = w * c.infoRatio.coerceIn(0.18f, 0.60f)
    val coverSize = min(infoW - pad * 2f, h * 0.42f).coerceAtLeast(40f)
    val cx = (infoW - coverSize) / 2f
    val cy = h * 0.18f
    val cp = Paint(Paint.ANTI_ALIAS_FLAG)
    if (st.cover != null) {
        cv.drawBitmap(st.cover, null, RectF(cx, cy, cx + coverSize, cy + coverSize), cp)
    } else {
        cp.color = Color.rgb(40, 38, 48)
        cv.drawRoundRect(RectF(cx, cy, cx + coverSize, cy + coverSize), 16f * scale, 16f * scale, cp)
    }

    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = tf ?: Typeface.DEFAULT
        isFakeBoldText = true
    }
    tp.textSize = 30f * scale
    cv.drawText(ellipsizeRec(st.song.title, tp, infoW - pad * 2f), pad, cy + coverSize + 44f * scale, tp)
    tp.isFakeBoldText = false
    tp.textSize = 21f * scale
    tp.color = Color.argb(205, 255, 255, 255)
    cv.drawText(ellipsizeRec(st.song.artist, tp, infoW - pad * 2f), pad, cy + coverSize + 78f * scale, tp)
    cv.drawText(ellipsizeRec(st.song.album, tp, infoW - pad * 2f), pad, cy + coverSize + 108f * scale, tp)

    drawRecLyrics(cv, st, c, tf, infoW + pad, (w - infoW - pad * 2f).coerceAtLeast(60f), h * 0.5f, scale)
}

/** 圆盘：黑胶唱片 + 中心圆形封面 + 唱针；歌词在右侧（横屏）或下方（竖屏） */
private fun drawDisc(
    cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
    w: Float, h: Float, pad: Float, scale: Float,
) {
    val horizontal = w >= h
    val r = min(w, h) * (0.16f + c.infoRatio.coerceIn(0.18f, 0.60f) * 0.28f)
    val cx = if (horizontal) w * 0.30f else w * 0.5f
    val cy = if (horizontal) h * 0.50f else h * 0.34f

    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.rgb(22, 20, 26)
    cv.drawCircle(cx, cy, r, p)

    // 唱片纹路
    p.style = Paint.Style.STROKE
    p.strokeWidth = 1.2f * scale
    p.color = Color.argb(38, 255, 255, 255)
    var ring = r * 0.44f
    while (ring < r * 0.97f) {
        cv.drawCircle(cx, cy, ring, p)
        ring += 5f * scale
    }
    p.style = Paint.Style.FILL

    // 中心封面（BitmapShader 画圆，避开硬件 Canvas 不支持的 clipPath）
    if (st.cover != null) {
        val b = st.cover
        val k = (r * 1.44f) / min(b.width, b.height).toFloat()
        val m = Matrix()
        m.setScale(k, k)
        m.postTranslate(cx - b.width * k / 2f, cy - b.height * k / 2f)
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        sh.setLocalMatrix(m)
        p.shader = sh
        cv.drawCircle(cx, cy, r * 0.72f, p)
        p.shader = null
    } else {
        p.color = Color.rgb(48, 44, 56)
        cv.drawCircle(cx, cy, r * 0.72f, p)
    }
    p.color = Color.rgb(12, 11, 16)
    cv.drawCircle(cx, cy, r * 0.065f, p)
    p.color = Color.argb(120, 255, 255, 255)
    cv.drawCircle(cx, cy, r * 0.025f, p)

    // 旋转高光点：角度由歌曲位置驱动，预览与编码完全一致
    val ang = (st.positionMs % 6000L).toDouble() / 6000.0 * Math.PI * 2.0
    p.color = Color.argb(170, 255, 255, 255)
    cv.drawCircle(
        (cx + sin(ang) * r * 0.86f).toFloat(),
        (cy - cos(ang) * r * 0.86f).toFloat(),
        3.5f * scale, p,
    )

    // 唱针
    p.color = Color.argb(210, 220, 216, 232)
    p.strokeWidth = 5f * scale
    cv.drawLine(cx + r * 1.18f, cy - r * 1.02f, cx + r * 0.70f, cy - r * 0.60f, p)
    p.strokeWidth = 1f
    cv.drawCircle(cx + r * 1.18f, cy - r * 1.02f, 9f * scale, p)

    // 歌名 / 歌手（圆盘下方或左区上方）
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = tf ?: Typeface.DEFAULT
        isFakeBoldText = true
        textSize = 26f * scale
    }
    val infoY = cy + r + 40f * scale
    cv.drawText(ellipsizeRec(st.song.title, tp, w * 0.52f), pad, infoY, tp)
    tp.isFakeBoldText = false
    tp.textSize = 19f * scale
    tp.color = Color.argb(190, 255, 255, 255)
    cv.drawText(ellipsizeRec("${st.song.artist} · ${st.song.album}", tp, w * 0.52f), pad, infoY + 32f * scale, tp)

    if (horizontal) {
        drawRecLyrics(cv, st, c, tf, w * 0.56f, (w * 0.94f - w * 0.56f).coerceAtLeast(60f), h * 0.48f, scale)
    } else {
        drawRecLyrics(cv, st, c, tf, w * 0.08f, w * 0.84f, h * 0.76f, scale)
    }
}

/** 两套样式共用的歌词绘制：字号 / 字体 / 译文 / 全体偏移全部生效 */
private fun drawRecLyrics(
    cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
    lx: Float, lw: Float, centerY: Float, scale: Float,
) {
    val act = recActiveRow(st, c.lyricOffsetMs)
    val base = c.lyricSizeSp.toFloat().coerceIn(10f, 48f) * scale
    val lineH = base * 1.95f
    val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf ?: Typeface.DEFAULT }
    for (d in -3..3) {
        val i = act + d
        if (i < 0 || i >= st.rows.size) continue
        val row = st.rows[i]
        val y = centerY + d * lineH
        val active = d == 0
        lp.color = if (active) Color.WHITE
        else Color.argb((130 - abs(d) * 28).coerceAtLeast(35), 255, 255, 255)
        lp.textSize = if (active) base * 1.12f else base * 0.86f
        lp.isFakeBoldText = active
        cv.drawText(ellipsizeRec(row.text, lp, lw), lx, y, lp)
        if (c.showTrans && !row.trans.isNullOrBlank()) {
            lp.textSize = base * 0.56f
            lp.isFakeBoldText = false
            lp.color = Color.argb(if (active) 190 else 90, 210, 210, 225)
            cv.drawText(ellipsizeRec(row.trans!!, lp, lw), lx, y + base * 0.72f, lp)
        }
    }
}

/** 两套样式共用的底部进度条与时间 */
private fun drawRecProgress(
    cv: Canvas, st: RecFrameState, w: Float, h: Float, pad: Float, scale: Float,
) {
    val barY = h - 26f * scale
    val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    bp.color = Color.argb(90, 255, 255, 255)
    cv.drawRect(pad, barY, w - pad, barY + 3f * scale, bp)
    bp.color = Color.WHITE
    val prog = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    cv.drawRect(pad, barY, pad + (w - pad * 2f) * prog, barY + 3f * scale, bp)
    val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 255, 255)
        textSize = 16f * scale
    }
    val a = fmtRecTime(st.positionMs)
    val b = fmtRecTime(st.durationMs)
    cv.drawText(a, pad, barY - 10f * scale, clock)
    cv.drawText(b, w - pad - clock.measureText(b), barY - 10f * scale, clock)
}

private fun ellipsizeRec(text: String, p: Paint, maxW: Float): String {
    if (p.measureText(text) <= maxW) return text
    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (p.measureText(text.substring(0, mid) + "…") <= maxW) lo = mid else hi = mid - 1
    }
    return text.substring(0, lo) + "…"
}

private fun fmtRecTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}
