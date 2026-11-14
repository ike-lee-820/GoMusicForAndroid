package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
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
    /** 兼容旧调用；雪天改用下面三个频段 */
    val energy: Float = 0.35f,
    /** 对应 sn.html 的 energy.bass / mid / high，每 100ms 一组，0–1 */
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
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

/** 切行滚动时长：整列歌词在这段时间内滑到新位置，而不是硬切。 */
private const val REC_LYRIC_SCROLL_MS = 420f

/**
 * 连续的「当前行位置」（浮点行索引）。
 * 纯函数，只由时间轴决定：预览跳到任意时刻、帧重排、整帧缓存都安全。
 * 行切换后 REC_LYRIC_SCROLL_MS 内用 easeOutCubic 从上一行滑到本行，之后稳定停在整数行。
 */
fun recLyricPos(st: RecFrameState, offsetMs: Long): Float {
    val rows = st.rows
    if (rows.isEmpty()) return 0f
    val t = st.positionMs - offsetMs
    if (t < 0) return -1f
    var idx = -1
    for (i in rows.indices) {
        if (rows[i].timeMs >= 0 && rows[i].timeMs <= t) idx = i else break
    }
    if (idx < 0) return -1f
    val since = (t - rows[idx].timeMs.coerceAtLeast(0L)).toFloat()
    val a = (since / REC_LYRIC_SCROLL_MS).coerceIn(0f, 1f)
    val eased = 1f - (1f - a) * (1f - a) * (1f - a)
    return (idx - 1f + eased).coerceAtLeast(-1f)
}

/** 0–1 聚焦度：所在行 1、相邻行平滑衰减到 0，用来插值字号 / 透明度 / 粗细。 */
private fun recFocus(d: Float): Float {
    val x = (1f - abs(d)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 帧渲染统计：静态层预渲染 + 相同帧复用到底省了多少帧。 */
object RecFrameStats {
    @Volatile var frames = 0
    @Volatile var reused = 0
    fun reset() { frames = 0; reused = 0 }
    fun report(): String {
        val f = frames
        val r = reused
        return "帧渲染 $f 帧，复用 $r 帧（${if (f > 0) r * 100 / f else 0}%）"
    }
}

/**
 * 一帧的总入口。
 *
 * 26.11.24 提速核心：把「模糊背景 + 渐变 + 封面 + 歌曲信息」这些整首歌都不变的
 * 内容预渲染成一张静态层 Bitmap；再把整帧缓存起来，只要高亮行与进度条都没变化
 * 就直接贴上一帧。逐帧只剩一次 drawBitmap，不再每帧缩放封面、重建渐变、重画文字。
 * 雨季 / 雪天的粒子是唯一需要逐帧画的东西，且仍然复用静态层。
 */
fun renderRecFrame(cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?) =
    RecFrameCache.render(cv, st, c, tf)

/** 按可用宽度把过长歌词折成多行（中文按字、英文优先在空格断），放不下才省略。 */
private fun wrapRecLines(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
    if (text.isBlank()) return emptyList()
    if (p.measureText(text) <= maxW) return listOf(text)
    val out = ArrayList<String>(maxLines)
    var rest = text
    while (rest.isNotEmpty()) {
        if (out.size == maxLines - 1 || p.measureText(rest) <= maxW) {
            out.add(if (p.measureText(rest) <= maxW) rest else ellipsizeRec(rest, p, maxW))
            break
        }
        var lo = 0
        var hi = rest.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(rest.substring(0, mid)) <= maxW) lo = mid else hi = mid - 1
        }
        var cut = lo.coerceAtLeast(1)
        val head = rest.substring(0, cut)
        val sp = head.lastIndexOf(' ')
        if (sp > cut / 2 && sp > 0) cut = sp
        out.add(rest.substring(0, cut).trimEnd())
        rest = rest.substring(cut).trimStart()
    }
    return out
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

private data class StaticKey(
    val coverId: Int,
    val title: String,
    val artist: String,
    val album: String,
    val style: String,
    val infoRatio: Float,
    val infoSizeSp: Int,
    val bgMode: String,
    val fontMode: String,
    val fontWeight: Int,
    val fontPath: String,
    val tfId: Int,
    val w: Int,
    val h: Int,
)

private object RecFrameCache {

    private var staticKey: StaticKey? = null
    private var staticBmp: Bitmap? = null
    private var fullBmp: Bitmap? = null
    private var fullSig: String? = null

    private fun bitmap(old: Bitmap?, w: Int, h: Int): Bitmap {
        if (old != null && !old.isRecycled && old.width == w && old.height == h) return old
        if (old != null && !old.isRecycled) old.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** 画面签名：高亮行、进度条像素桶、全部影响排版的配置；动态雨/雪再带上时间。 */
    private fun frameSig(
        st: RecFrameState, c: RecorderConfig, w: Int, h: Int, tf: Typeface?,
    ): String {
        val posQ = (recLyricPos(st, c.lyricOffsetMs) * 24f).toInt()
        val prog = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
        val bucket = (prog * w / 2f).toInt()
        // 雨季 / 雪天都是逐帧动画，禁用整帧复用；左侧信息静态层仍然复用。
        val dynamic = when (c.bgMode) {
            "雨季", "雪天" -> st.positionMs.toString()
            else -> ""
        }
        return listOf(
            posQ, bucket, c.style, c.bgMode, c.lyricSizeSp, c.infoSizeSp, c.showTrans,
            c.lyricOffsetMs, c.infoRatio, c.fontMode, c.fontWeight, c.fontPath,
            System.identityHashCode(tf), st.rows.size, st.song.title, w, h, dynamic,
        ).joinToString("|")
    }

    @Synchronized
    fun render(cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?) {
        val w = cv.width
        val h = cv.height
        if (w <= 0 || h <= 0) return
        RecFrameStats.frames++
        val sig = frameSig(st, c, w, h, tf)
        val cached = fullBmp
        if (cached != null && !cached.isRecycled &&
            cached.width == w && cached.height == h && sig == fullSig
        ) {
            cv.drawBitmap(cached, 0f, 0f, null)
            RecFrameStats.reused++
            return
        }
        val target = bitmap(cached, w, h)
        drawFull(Canvas(target), st, c, tf, w.toFloat(), h.toFloat())
        cv.drawBitmap(target, 0f, 0f, null)
        fullBmp = target
        fullSig = sig
    }

    /** 静态层：只画左侧封面与歌曲信息，背景透明，整首歌只生成一次。 */
    private fun infoBitmap(
        st: RecFrameState, c: RecorderConfig, tf: Typeface?, w: Int, h: Int,
    ): Bitmap {
        val key = StaticKey(
            coverId = System.identityHashCode(st.cover),
            title = st.song.title,
            artist = st.song.artist,
            album = st.song.album,
            style = c.style,
            infoRatio = c.infoRatio,
            infoSizeSp = c.infoSizeSp,
            bgMode = c.bgMode,
            fontMode = c.fontMode,
            fontWeight = c.fontWeight,
            fontPath = c.fontPath,
            tfId = System.identityHashCode(tf),
            w = w,
            h = h,
        )
        val old = staticBmp
        if (old != null && !old.isRecycled && staticKey == key) return old
        val b = bitmap(old, w, h)
        b.eraseColor(Color.TRANSPARENT)
        val cv = Canvas(b)
        val scale = h / 1080f
        val pad = 28f * scale
        when (RecStyle.of(c.style)) {
            RecStyle.CLASSIC -> drawClassicInfo(cv, st, c, tf, w.toFloat(), h.toFloat(), pad, scale)
            RecStyle.DISC -> drawDiscInfo(cv, st, c, tf, w.toFloat(), h.toFloat(), pad, scale)
        }
        staticBmp = b
        staticKey = key
        return b
    }

    /**
     * 只判断 GL 是否交回空帧。专辑封面本身可能大面积同色（米色底插画），
     * 旧版按「5x5 采样亮度差 < 4」判纯色会把正常雨景误丢，只剩一块背景色。
     */
    private fun isBlankGl(b: Bitmap): Boolean {
        if (b.isRecycled || b.width < 2 || b.height < 2) return true
        val w = b.width
        val h = b.height
        var visible = 0
        for (j in 0 until 5) {
            for (i in 0 until 5) {
                val c = b.getPixel(i * (w - 1) / 4, j * (h - 1) / 4)
                if (((c ushr 24) and 0xFF) > 8) visible++
            }
        }
        return visible < 7
    }

    private fun drawFull(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?, w: Float, h: Float,
    ) {
        val scale = h / 1080f
        val pad = 28f * scale
        val base = makeBackdrop(st.cover, w.toInt(), h.toInt(), c.bgMode)
        if (base == null) {
            // 没有封面时也不留一整块死背景色
            val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w, h,
                    Color.rgb(26, 24, 32), Color.rgb(10, 9, 13), Shader.TileMode.CLAMP,
                )
            }
            cv.drawRect(0f, 0f, w, h, gp)
        } else if (c.bgMode == "雨季") {
            // 参考实现的 fragment 输出的就是「被雨滴折射后的背景图」本身：
            // 把已经按比例做好的封面底作为 dispImage 上传，GL 输出即该模式背景。
            // 读回空帧时保留封面底，绝不在上面整屏盖一块纯色。
            val gl = RainGlsl.render(base, w.toInt(), h.toInt())
            val useGl = gl != null && !gl.isRecycled && !isBlankGl(gl)
            val bg: Bitmap = if (useGl) gl!! else base
            cv.drawBitmap(bg, 0f, 0f, null)
        } else {
            cv.drawBitmap(base, 0f, 0f, null)
        }
        cv.drawBitmap(infoBitmap(st, c, tf, w.toInt(), h.toInt()), 0f, 0f, null)
        if (c.bgMode == "雪天") drawSnowLayer(cv, st, w, h)
        val infoW = (w * c.infoRatio.coerceIn(0.18f, 0.60f)).coerceAtMost(w * 0.60f)
        when (RecStyle.of(c.style)) {
            RecStyle.CLASSIC -> drawClassicLyrics(
                cv, st, c, tf, infoW + pad,
                (w - infoW - pad * 2f).coerceAtLeast(60f), h * 0.5f, h, scale,
            )
            RecStyle.DISC -> {
                val gap = 16f * scale
                drawArcLyrics(
                    cv, st, c, tf, infoW + gap,
                    (w - infoW - gap - pad).coerceAtLeast(60f), h * 0.5f, h, scale,
                )
            }
        }
        drawProgress(cv, st, w, h, pad, scale, c, tf)
    }

    private var backCoverId = 0
    private var backW = 0
    private var backH = 0
    private var backMode = ""
    private var backBmp: Bitmap? = null

    /**
     * 26.11.31：背景只生成一张，直接就是输出尺寸，不再缩半再放大拉伸。
     * ① 四周用同一封面等比放大铺满（比例不变，只裁切延伸区）并模糊补齐；
     * ② 中央完整封面 contain 居中，原始宽高比不变、不拉伸不裁切；
     * ③ 高斯模式只叠一层轻模糊，不再整屏盖 1/10 缩略图；
     * ④ 一层很淡的暗渐变保证文字可读。
     * 三种模式共用这一张底，雨季交给着色器折射，雪天在之上叠透明雪层。
     */
    private fun makeBackdrop(cover: Bitmap?, w: Int, h: Int, mode: String): Bitmap? {
        if (cover == null || cover.isRecycled || w <= 0 || h <= 0) return null
        val id = System.identityHashCode(cover)
        if (backCoverId == id && backW == w && backH == h && backMode == mode &&
            backBmp != null && !backBmp!!.isRecycled
        ) return backBmp
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val cw = cover.width.toFloat()
        val ch = cover.height.toFloat()
        // ① 等比放大（cover 语义，只裁切不变形）→ 模糊，作为四周延伸
        val edge = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sf = max(w / cw, h / ch)
        val fw = cw * sf
        val fh = ch * sf
        Canvas(edge).drawBitmap(
            cover, null,
            RectF((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f), p,
        )
        val edgeBlur = blurSmall(edge, 6)
        cv.drawBitmap(edgeBlur, 0f, 0f, null)
        edge.recycle()
        edgeBlur.recycle()
        // ② 主体：contain，完整、等比、不拉伸
        val s = min(w / cw, h / ch)
        val dw = cw * s
        val dh = ch * s
        cv.drawBitmap(
            cover, null,
            RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p,
        )
        // ③ 高斯模糊：只叠一层轻模糊，封面轮廓仍看得见
        if (mode == "高斯模糊") {
            val soft = blurSmall(out, 2)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }
            cv.drawBitmap(soft, 0f, 0f, bp)
            soft.recycle()
        }
        // ④ 很淡的暗渐变
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(6, 0, 0, 0), Color.argb(60, 0, 0, 0), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        backBmp?.let { if (!it.isRecycled) it.recycle() }
        backBmp = out
        backCoverId = id
        backW = w
        backH = h
        backMode = mode
        return out
    }

    /** 小图上做水平 + 垂直盒式模糊；radius=1 即「略微模糊」。 */
    private fun blurSmall(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val r = radius.coerceIn(1, 8)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (k in -r..r) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val c = px[y * w + xx]
                    sa += ((c ushr 24) and 0xFF)
                    sr += ((c ushr 16) and 0xFF)
                    sg += ((c ushr 8) and 0xFF)
                    sb += (c and 0xFF)
                    n++
                }
                tmp[y * w + x] = ((sa / n).toInt() shl 24) + ((sr / n).toInt() shl 16) +
                    ((sg / n).toInt() shl 8) + (sb / n).toInt()
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val c = tmp[yy * w + x]
                    sa += ((c ushr 24) and 0xFF)
                    sr += ((c ushr 16) and 0xFF)
                    sg += ((c ushr 8) and 0xFF)
                    sb += (c and 0xFF)
                    n++
                }
                px[y * w + x] = ((sa / n).toInt() shl 24) + ((sr / n).toInt() shl 16) +
                    ((sg / n).toInt() shl 8) + (sb / n).toInt()
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private var snow: SnowEffect? = null
    private var snowW = 0
    private var snowH = 0
    private var snowLastMs = -1L

    /** 雪天：把 sn.html 的 canvas 透明层贴到封面底之上，绝不整屏替换背景。 */
    private fun drawSnowLayer(cv: Canvas, st: RecFrameState, w: Float, h: Float) {
        var e = snow
        if (e == null || snowW != w.toInt() || snowH != h.toInt()) {
            e = SnowEffect(w, h)
            snow = e
            snowW = w.toInt()
            snowH = h.toInt()
            snowLastMs = -1L
        }
        val dt = if (snowLastMs < 0) 1f / 60f else (st.positionMs - snowLastMs) / 1000f
        if (snowLastMs < 0 || dt < 0f || dt > 1.5f) {
            // 预览固定显示时长一半、或帧回退：等价于在该时刻重新打开页面
            e.seek(st.positionMs / 1000f)
            e.step(1f / 60f, st.bass, st.mid, st.high)
        } else {
            e.step(dt, st.bass, st.mid, st.high)
        }
        snowLastMs = st.positionMs
        e.renderTo(cv)
    }

    /** 经典：封面与三行歌曲信息都从同一个 x 起排，字号由 infoSizeSp 单独控制。 */
    private fun drawClassicInfo(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
        w: Float, h: Float, pad: Float, scale: Float,
    ) {
        val infoW = (w * c.infoRatio.coerceIn(0.18f, 0.60f)).coerceAtMost(w * 0.60f)
        val coverSize = min(infoW - pad * 2f, h * 0.42f).coerceAtLeast(40f)
        val cx = pad
        val cy = h * 0.18f
        val cp = Paint(Paint.ANTI_ALIAS_FLAG)
        val coverBmp = st.cover
        if (coverBmp != null && !coverBmp.isRecycled) {
            cv.drawBitmap(coverBmp, null, RectF(cx, cy, cx + coverSize, cy + coverSize), cp)
        } else {
            cp.color = Color.rgb(40, 38, 48)
            cv.drawRoundRect(RectF(cx, cy, cx + coverSize, cy + coverSize), 16f * scale, 16f * scale, cp)
        }
        val base = c.infoSizeSp.toFloat().coerceIn(REC_INFO_MIN_SP.toFloat(), REC_INFO_MAX_SP.toFloat()) * scale
        val maxTextW = infoW - pad * 2f
        val tp = recTextPaint(c, tf).apply { textAlign = Paint.Align.LEFT }
        tp.color = Color.WHITE
        tp.isFakeBoldText = true
        tp.textSize = base * 1.35f
        cv.drawText(ellipsizeRec(st.song.title, tp, maxTextW), pad, cy + coverSize + base * 1.9f, tp)
        tp.isFakeBoldText = false
        tp.textSize = base * 0.95f
        tp.color = Color.argb(205, 255, 255, 255)
        cv.drawText(ellipsizeRec(st.song.artist, tp, maxTextW), pad, cy + coverSize + base * 3.1f, tp)
        tp.textSize = base * 0.88f
        cv.drawText(ellipsizeRec(st.song.album, tp, maxTextW), pad, cy + coverSize + base * 4.1f, tp)
    }

    /**
     * 圆盘 = 横屏播放器歌词模式：左侧信息区顺序仍是封面 → 歌手 → 歌名 → 专辑名，
     * 26.11.24 起同样左对齐并用独立信息字号；右侧圆弧歌词几何保持不变。
     */
    private fun drawDiscInfo(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
        w: Float, h: Float, pad: Float, scale: Float,
    ) {
        val infoW = (w * c.infoRatio.coerceIn(0.18f, 0.60f)).coerceAtMost(w * 0.60f)
        val coverSize = min(infoW * 0.66f, h * 0.56f).coerceAtLeast(40f)
        val cx = pad
        val cy = (h - coverSize) / 2f - h * 0.08f
        val cp = Paint(Paint.ANTI_ALIAS_FLAG)
        val coverBmp = st.cover
        if (coverBmp != null && !coverBmp.isRecycled) {
            cv.drawBitmap(coverBmp, null, RectF(cx, cy, cx + coverSize, cy + coverSize), cp)
        } else {
            cp.color = Color.rgb(40, 38, 48)
            cv.drawRoundRect(RectF(cx, cy, cx + coverSize, cy + coverSize), 16f * scale, 16f * scale, cp)
        }
        val base = c.infoSizeSp.toFloat().coerceIn(REC_INFO_MIN_SP.toFloat(), REC_INFO_MAX_SP.toFloat()) * scale
        val maxTextW = infoW - pad * 2f
        val tp = recTextPaint(c, tf).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
        var ty = cy + coverSize + base * 1.35f
        tp.textSize = base * 0.86f
        tp.color = Color.argb(185, 255, 255, 255)
        cv.drawText(ellipsizeRec(st.song.artist, tp, maxTextW), pad, ty, tp)
        ty += base * 1.5f
        tp.isFakeBoldText = true
        tp.textSize = base * 1.25f
        tp.color = Color.WHITE
        cv.drawText(ellipsizeRec(st.song.title, tp, maxTextW), pad, ty, tp)
        ty += base * 1.45f
        tp.isFakeBoldText = false
        tp.textSize = base * 0.8f
        tp.color = Color.argb(165, 255, 255, 255)
        cv.drawText(
            ellipsizeRec(st.song.album.ifBlank { "未知专辑" }, tp, maxTextW), pad, ty, tp,
        )
    }

    /** 经典歌词：过长歌词最多 3 行自动折行，译文最多 2 行；行距按字号联动。 */
    private fun drawClassicLyrics(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
        lx: Float, lw: Float, centerY: Float, h: Float, scale: Float,
    ) {
        val pos = recLyricPos(st, c.lyricOffsetMs)
        val base = c.lyricSizeSp.toFloat()
            .coerceIn(REC_LYRIC_MIN_SP.toFloat(), REC_LYRIC_MAX_SP.toFloat()) * scale
        val hasTrans = c.showTrans && st.rows.any { !it.trans.isNullOrBlank() }
        val lineH = base * (1.12f * 3f + (if (hasTrans) 1.05f else 0.35f))
        val visible = (h * 0.78f / lineH).toInt().coerceIn(3, 7)
        val half = visible / 2
        val lp = recTextPaint(c, tf).apply { textAlign = Paint.Align.LEFT }
        val lo = kotlin.math.floor(pos).toInt() - half - 1
        val hi = kotlin.math.ceil(pos).toInt() + half + 1
        for (i in lo.coerceAtLeast(0)..hi.coerceAtMost(st.rows.lastIndex)) {
            val d = i - pos
            val focus = recFocus(d)
            val row = st.rows[i]
            val y = centerY + d * lineH
            lp.color = Color.argb((72 + 183 * focus).toInt().coerceIn(0, 255), 255, 255, 255)
            lp.textSize = base * (0.84f + 0.30f * focus)
            lp.isFakeBoldText = focus > 0.6f
            val lines = wrapRecLines(row.text, lp, lw, 3)
            var ly = y - (lines.size - 1) * base * 0.55f
            for (l in lines) {
                cv.drawText(l, lx, ly, lp)
                ly += base * 1.06f
            }
            if (c.showTrans && !row.trans.isNullOrBlank()) {
                lp.textSize = base * 0.56f
                lp.isFakeBoldText = false
                lp.color = Color.argb((64 + 126 * focus).toInt().coerceIn(0, 255), 210, 210, 225)
                val tl = wrapRecLines(row.trans!!, lp, lw, 2)
                var ty = ly + base * 0.12f
                for (t in tl) {
                    cv.drawText(t, lx, ty, lp)
                    ty += base * 0.72f
                }
            }
        }
    }

    /** ArcLyrics 的 Canvas 版：几何照横屏播放器，歌词与译文都允许折行。 */
    private fun drawArcLyrics(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?,
        lx: Float, lw: Float, centerY: Float, h: Float, scale: Float,
    ) {
        val rows = st.rows
        if (rows.isEmpty()) return
        val pos = recLyricPos(st, c.lyricOffsetMs)
        val base = c.lyricSizeSp.toFloat()
            .coerceIn(REC_LYRIC_MIN_SP.toFloat(), REC_LYRIC_MAX_SP.toFloat()) * scale
        val hasTrans = c.showTrans && rows.any { !it.trans.isNullOrBlank() }
        val rowSpan = base * 3.4f * (if (hasTrans) 1.6f else 1f)
        val regionH = h * 0.86f
        val visibleRows = (regionH / rowSpan).toInt().coerceIn(3, 25)
        val span = (visibleRows / 2).coerceAtLeast(2)
        val tiltRad = 20f * PI.toFloat() / 180f
        val rPx = (span * rowSpan / tiltRad * 0.7f).coerceAtLeast(regionH * 0.35f)
        val stepRad = rowSpan / rPx
        val lineWPx = lw * 0.78f
        val maxShift = ((lw - lineWPx) / 2f).coerceAtLeast(0f)
        val shiftPx = (rPx * (1f - cos(span * stepRad)) / 2f).coerceAtMost(maxShift)
        val edge = span + 1
        val lp = recTextPaint(c, tf)
        val lo = kotlin.math.floor(pos).toInt() - edge - 1
        val hi = kotlin.math.ceil(pos).toInt() + edge + 1
        for (i in lo.coerceAtLeast(0)..hi.coerceAtMost(rows.lastIndex)) {
            val d = i - pos
            val th = d * stepRad
            val x = -rPx * (1f - cos(th)) + shiftPx
            val y = rPx * sin(th)
            val t = (abs(d) / span.toFloat()).coerceIn(0f, 1f)
            val fade = 1f - t * t * (3f - 2f * t)
            val focus = recFocus(d)
            val isCur = focus > 0.75f
            val alpha = (255f * (0.18f + 0.82f * fade) * (0.55f + 0.45f * focus))
                .toInt().coerceIn(0, 255)
            val row = rows[i]
            cv.save()
            cv.translate(lx + x, centerY + y)
            cv.rotate(th * 180f / PI.toFloat(), 0f, rowSpan * 0.5f)
            lp.textSize = base * (0.86f + 0.26f * focus)
            lp.isFakeBoldText = isCur
            lp.color = Color.argb(alpha, 255, 255, 255)
            val lines = wrapRecLines(row.text, lp, lineWPx, 2)
            var ly = rowSpan * 0.5f - (lines.size - 1) * base * 0.5f
            for (l in lines) {
                cv.drawText(l, 0f, ly, lp)
                ly += base * 1.0f
            }
            if (c.showTrans && !row.trans.isNullOrBlank()) {
                lp.textSize = base * 0.56f
                lp.isFakeBoldText = false
                lp.color = Color.argb((alpha * 0.75f).toInt(), 210, 210, 225)
                val tl = wrapRecLines(row.trans!!, lp, lineWPx, 2)
                var ty = ly + base * 0.1f
                for (t2 in tl) {
                    cv.drawText(t2, 0f, ty, lp)
                    ty += base * 0.7f
                }
            }
            cv.restore()
        }
    }

    /** 两套样式共用的底部进度条与时间 */
    private fun drawProgress(
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
}
