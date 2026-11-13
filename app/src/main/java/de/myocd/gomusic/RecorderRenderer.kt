package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * 横屏播放器样式的一帧：左侧歌曲信息区（封面 / 标题 / 歌手 / 专辑），
 * 右侧居中歌词（当前句高亮、上下渐隐、可带译文），底部进度条。
 *
 * 只使用硬件 Canvas 也支持的绘制调用（不 clipPath / 不 BlurMaskFilter），
 * 因此同一份代码既能画进 Compose 预览，也能画进 MediaCodec 的 input Surface。
 */
fun renderRecFrame(cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?) {
    val w = cv.width.toFloat()
    val h = cv.height.toFloat()
    if (w <= 0f || h <= 0f) return
    val scale = h / 1080f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    cv.drawColor(Color.rgb(12, 11, 16))
    st.cover?.let { bmp ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 70 }
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val k = max(w / bw, h / bh)
        val dw = bw * k
        val dh = bh * k
        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        cv.drawBitmap(bmp, src, dst, p)
    }
    fill.shader = LinearGradient(
        0f, 0f, 0f, h,
        Color.argb(110, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP,
    )
    cv.drawRect(0f, 0f, w, h, fill)
    fill.shader = null

    val infoW = w * c.infoRatio.coerceIn(0.18f, 0.60f)
    val pad = 28f * scale
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

    // 右：歌词
    val lx = infoW + pad
    val lw = (w - lx - pad).coerceAtLeast(60f)
    val act = recActiveRow(st, c.lyricOffsetMs)
    val base = c.lyricSizeSp.toFloat().coerceIn(10f, 48f) * scale
    val lineH = base * 1.95f
    val centerY = h * 0.5f
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

    // 底部进度条与时间
    val barY = h - 26f * scale
    val bp = Paint(Paint.ANTI_ALIAS_FLAG)
    bp.color = Color.argb(90, 255, 255, 255)
    cv.drawRect(lx, barY, w - pad, barY + 3f * scale, bp)
    bp.color = Color.WHITE
    val prog = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    cv.drawRect(lx, barY, lx + (w - pad - lx) * prog, barY + 3f * scale, bp)
    val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 255, 255)
        textSize = 16f * scale
    }
    val a = fmtRecTime(st.positionMs)
    val b = fmtRecTime(st.durationMs)
    cv.drawText(a, lx, barY - 10f * scale, clock)
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
