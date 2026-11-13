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
import kotlin.math.PI
import kotlin.math.sin

/** 录制文字 Paint：统一注入字体来源与可变字体 wght 轴 */
private fun recTextPaint(c: RecorderConfig, tf: Typeface?): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf ?: Typeface.DEFAULT
        if (c.fontMode != "系统默认") {
            val w = c.fontWeight.coerceIn(WGHT_MIN, WGHT_MAX)
            runCatching { setFontVariationSettings("'wght' $w") }
        }
    }

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
 * 一帧的总入口。背景与底部进度条由两套样式共用，歌词绘制各自一套：
 * 经典 = 录制默认的「左封面信息 + 右居中歌词」；
 * 圆盘 = 横屏播放器版本：左封面信息区 + 右侧圆弧旋转歌词（ArcLyrics 几何）。
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
    drawRecProgress(cv, st, w, h, pad, scale, c, tf)
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

    val tp = recTextPaint(c, tf).apply {
        color = Color.WHITE
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

/**
 * 圆盘 = 横屏播放器版本：复刻 WidePlayerPane 的「左封面 / 右圆弧歌词」。
 *
 * 几何照 Screens.kt 的 ArcLyrics：行距随字号联动、可见行数按高度算、
 * 圆心半径由最外侧倾角 20° 反推并乘 0.7、每行沿圆弧的横纵位移、
 * 绕左端旋转、smoothstep 边缘淡出。dp 换算成输出像素后由 Canvas 绘制，
 * 预览与编码调用同一份函数，不再画黑胶唱片 / 唱针。
 */
private fun drawDisc(
    cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
    w: Float, h: Float, pad: Float, scale: Float,
) {
    val infoW = (w * c.infoRatio.coerceIn(0.18f, 0.60f)).coerceAtMost(w * 0.60f)
    val gap = 16f * scale
    val lyricX = infoW + gap
    val lyricW = (w - lyricX - pad).coerceAtLeast(60f)

    // 左：封面 → 歌手 → 歌名 → 专辑名，居中排布（顺序与横屏播放器一致）
    val coverSize = min(infoW * 0.66f, h * 0.56f).coerceAtLeast(40f)
    val cx = (infoW - coverSize) / 2f
    val cy = (h - coverSize) / 2f - h * 0.08f
    val cp = Paint(Paint.ANTI_ALIAS_FLAG)
    if (st.cover != null) {
        cv.drawBitmap(st.cover, null, RectF(cx, cy, cx + coverSize, cy + coverSize), cp)
    } else {
        cp.color = Color.rgb(40, 38, 48)
        cv.drawRoundRect(RectF(cx, cy, cx + coverSize, cy + coverSize), 16f * scale, 16f * scale, cp)
    }
    val tp = recTextPaint(c, tf).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    var ty = cy + coverSize + 26f * scale
    tp.textSize = 19f * scale
    tp.color = Color.argb(185, 255, 255, 255)
    cv.drawText(ellipsizeRec(st.song.artist, tp, infoW - pad), infoW / 2f, ty, tp)
    ty += 34f * scale
    tp.isFakeBoldText = true
    tp.textSize = 28f * scale
    tp.color = Color.WHITE
    cv.drawText(ellipsizeRec(st.song.title, tp, infoW - pad), infoW / 2f, ty, tp)
    ty += 30f * scale
    tp.isFakeBoldText = false
    tp.textSize = 17f * scale
    tp.color = Color.argb(165, 255, 255, 255)
    cv.drawText(ellipsizeRec(st.song.album.ifBlank { "未知专辑" }, tp, infoW - pad), infoW / 2f, ty, tp)

    drawArcLyrics(cv, st, c, tf, lyricX, lyricW, h * 0.5f, h, scale)
}

/**
 * ArcLyrics 的 Canvas 版：歌词沿半径 r 的圆弧排列，当前句在弧的中点，
 * 其余行按角度分布、绕左端旋转、越远越小越淡；译文贴在同一行下方一起转。
 */
private fun drawArcLyrics(
    cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
    lx: Float, lw: Float, centerY: Float, h: Float, scale: Float,
) {
    val rows = st.rows
    if (rows.isEmpty()) return
    val act = recActiveRow(st, c.lyricOffsetMs).coerceAtLeast(0).coerceAtMost(rows.lastIndex)
    val base = c.lyricSizeSp.toFloat().coerceIn(10f, 48f) * scale
    val hasTrans = c.showTrans && rows.any { !it.trans.isNullOrBlank() }
    // 78dp / 23sp ≈ 3.4：行距随字号联动；显示译文时按原代码乘 1.6
    val rowSpan = base * 3.4f * (if (hasTrans) 1.6f else 1f)
    val regionH = h * 0.86f
    val visibleRows = (regionH / rowSpan).toInt().coerceIn(9, 25)
    val span = (visibleRows / 2).coerceAtLeast(4)          // ARC_MIN_SPAN_WIDE
    val tiltRad = 20f * PI.toFloat() / 180f                // ARC_MAX_TILT_DEG
    val rPx = (span * rowSpan / tiltRad * 0.7f)            // ARC_RADIUS_BOOST_WIDE
        .coerceAtLeast(regionH * 0.5f)
    val stepRad = rowSpan / rPx
    val lineWPx = lw * 0.78f
    val maxShift = ((lw - lineWPx) / 2f).coerceAtLeast(0f)
    val shiftPx = (rPx * (1f - cos(span * stepRad)) / 2f).coerceAtMost(maxShift)
    val edge = span + 1
    val lp = recTextPaint(c, tf)
    for (i in (act - edge).coerceAtLeast(0)..(act + edge).coerceAtMost(rows.lastIndex)) {
        val d = (i - act).toFloat()
        val th = d * stepRad
        val x = -rPx * (1f - cos(th)) + shiftPx
        val y = rPx * sin(th)
        val t = (abs(d) / span.toFloat()).coerceIn(0f, 1f)
        val fade = 1f - t * t * (3f - 2f * t)
        val isCur = i == act
        val alpha = (255f * (0.18f + 0.82f * fade) * (if (isCur) 1f else 0.55f))
            .toInt().coerceIn(0, 255)
        val row = rows[i]
        cv.save()
        cv.translate(lx + x, centerY + y)
        cv.rotate(th * 180f / PI.toFloat(), 0f, rowSpan * 0.5f)
        lp.textSize = if (isCur) base * 1.12f else base * 0.86f
        lp.isFakeBoldText = isCur
        lp.color = Color.argb(alpha, 255, 255, 255)
        cv.drawText(ellipsizeRec(row.text, lp, lineWPx), 0f, rowSpan * 0.5f, lp)
        if (c.showTrans && !row.trans.isNullOrBlank()) {
            lp.textSize = base * 0.56f
            lp.isFakeBoldText = false
            lp.color = Color.argb((alpha * 0.75f).toInt(), 210, 210, 225)
            cv.drawText(ellipsizeRec(row.trans!!, lp, lineWPx), 0f, rowSpan * 0.5f + base * 0.8f, lp)
        }
        cv.restore()
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
    val lp = recTextPaint(c, tf)
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
    c: RecorderConfig, tf: Typeface?,
) {
    val barY = h - 26f * scale
    val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    bp.color = Color.argb(90, 255, 255, 255)
    cv.drawRect(pad, barY, w - pad, barY + 3f * scale, bp)
    bp.color = Color.WHITE
    val prog = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    cv.drawRect(pad, barY, pad + (w - pad * 2f) * prog, barY + 3f * scale, bp)
    val clock = recTextPaint(c, tf).apply {
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
