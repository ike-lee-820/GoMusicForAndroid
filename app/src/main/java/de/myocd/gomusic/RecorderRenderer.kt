package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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
    /** 雪天用：当前时刻的音频能量 0–1；预览与无包络时用 0.35 */
    val energy: Float = 0.35f,
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
        val act = recActiveRow(st, c.lyricOffsetMs)
        val prog = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
        val bucket = (prog * w / 2f).toInt()
        // 雨 / 雪是循环动画：按 50ms 相位桶 + 8 级能量桶取签名，
        // 同一桶内直接复用整帧，避免逐帧全量重画把编码拖回实时速度。
        val dynamic = if (c.bgMode == "雨季" || c.bgMode == "雪天") {
            val phase = ((st.positionMs % 1800L) * 36L / 1800L).toInt()
            "$phase/${(st.energy * 8f).toInt().coerceIn(0, 8)}"
        } else ""
        return listOf(
            act, bucket, c.style, c.bgMode, c.lyricSizeSp, c.infoSizeSp, c.showTrans,
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

    /** 静态层：背景效果 + 左侧封面与歌曲信息。整首歌只生成一次。 */
    private fun staticBitmap(
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
        val cv = Canvas(b)
        val scale = h / 1080f
        drawBackground(cv, st.cover, w.toFloat(), h.toFloat(), scale, c.bgMode)
        val pad = 28f * scale
        when (RecStyle.of(c.style)) {
            RecStyle.CLASSIC -> drawClassicInfo(cv, st, c, tf, w.toFloat(), h.toFloat(), pad, scale)
            RecStyle.DISC -> drawDiscInfo(cv, st, c, tf, w.toFloat(), h.toFloat(), pad, scale)
        }
        staticBmp = b
        staticKey = key
        return b
    }

    private fun drawFull(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?, w: Float, h: Float,
    ) {
        val scale = h / 1080f
        val pad = 28f * scale
        cv.drawBitmap(staticBitmap(st, c, tf, w.toInt(), h.toInt()), 0f, 0f, null)
        drawParticles(cv, c.bgMode, st.positionMs, st.energy, w, h, scale)
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

    /**
     * 26.11.26：背景一律以「专辑封面」为底。
     * center-crop 后降采样到约 1/6，做半径 1 的盒式模糊（略微模糊）再铺满整屏，
     * 只压一层很淡的暗渐变保证文字可读。四种效果都只在这张封面底上叠加：
     * - 高斯模糊：封面 + 极淡雾面
     * - 液态玻璃：整屏一层磨砂玻璃 + 一条贯穿全屏的光泽，不再切成上下两片卡片
     * - 雨季：玻璃上的水滴，水滴内取清晰封面并局部放大折射（curtains.js 雨特效思路）
     * - 雪天：柔边雪片随音频能量改变下落速度 / 风 / 亮度（sn.html 的 AUDIO REACTIVE）
     */
    private var backCoverId = 0
    private var backW = 0
    private var backH = 0
    private var backClear: Bitmap? = null
    private var backBlur: Bitmap? = null
    private var backShader: BitmapShader? = null
    private var flakeBmp: Bitmap? = null

    /** 封面底图只按「封面对象 + 输出尺寸」生成一次，两张小图合计不到 1MB。 */
    private fun backdrop(cover: Bitmap?, w: Int, h: Int): Boolean {
        if (cover == null || cover.isRecycled || w <= 0 || h <= 0) return false
        val id = System.identityHashCode(cover)
        if (backCoverId == id && backW == w && backH == h &&
            backClear != null && !backClear!!.isRecycled
        ) return true
        val sw = (w / 6).coerceIn(96, 480)
        val sh = (h / 6).coerceIn(96, 480)
        val clear = cropSmall(cover, sw, sh)
        val blur = blurSmall(clear, 1)
        backClear?.let { if (!it.isRecycled) it.recycle() }
        backBlur?.let { if (!it.isRecycled) it.recycle() }
        backClear = clear
        backBlur = blur
        backShader = BitmapShader(clear, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        backCoverId = id
        backW = w
        backH = h
        return true
    }

    private fun cropSmall(cover: Bitmap, sw: Int, sh: Int): Bitmap {
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val cw = cover.width.toFloat()
        val ch = cover.height.toFloat()
        val s = max(sw / cw, sh / ch)
        val dw = cw * s
        val dh = ch * s
        val dx = (sw - dw) / 2f
        val dy = (sh - dh) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(out).drawBitmap(cover, null, RectF(dx, dy, dx + dw, dy + dh), p)
        return out
    }

    private fun drawBackground(
        cv: Canvas, cover: Bitmap?, w: Float, h: Float, scale: Float, mode: String,
    ) {
        cv.drawColor(Color.rgb(12, 11, 16))
        if (!backdrop(cover, w.toInt(), h.toInt())) return
        val base = backBlur ?: return
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cv.drawBitmap(base, null, RectF(0f, 0f, w, h), p)
        // 旧版是 110→215 的黑渐变，封面几乎看不见；这里只留很淡的一层。
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(38, 0, 0, 0), Color.argb(135, 0, 0, 0), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w, h, shade)
        when (mode) {
            "高斯模糊" -> {
                val mist = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(16, 255, 255, 255) }
                cv.drawRect(0f, 0f, w, h, mist)
            }
            "液态玻璃" -> drawGlass(cv, w, h, scale)
            "雨季" -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 30, 62, 112) }
                cv.drawRect(0f, 0f, w, h, t)
            }
            "雪天" -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(14, 170, 205, 255) }
                cv.drawRect(0f, 0f, w, h, t)
            }
        }
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

    /**
     * 液态玻璃：整屏一层磨砂玻璃 + 一条贯穿全屏的斜向光泽。
     * 26.11.26 起删掉上下两片圆角玻璃卡片和中间色带矩形 —— 不分割。
     */
    private fun drawGlass(cv: Canvas, w: Float, h: Float, scale: Float) {
        val soft = backBlur ?: return
        // 1) 整层再柔化一次并轻微放大，模拟玻璃折射
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 160 }
        val d = 0.025f
        cv.drawBitmap(soft, null, RectF(-w * d, -h * d, w * (1f + d), h * (1f + d)), p)
        // 2) 整屏雾面
        val mist = Paint(Paint.ANTI_ALIAS_FLAG)
        mist.shader = LinearGradient(
            0f, 0f, w, h,
            Color.argb(24, 255, 255, 255), Color.argb(50, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w, h, mist)
        // 3) 一条贯穿全屏的斜向光泽（不是独立玻璃块）
        val g = Paint(Paint.ANTI_ALIAS_FLAG)
        g.shader = LinearGradient(
            0f, h * 0.16f, w, h * 0.64f,
            Color.argb(0, 255, 255, 255), Color.argb(32, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w, h, g)
    }

    private fun frac(x: Float): Float = x - kotlin.math.floor(x)
    private fun hash11(n: Float): Float = frac(kotlin.math.sin(n * 127.1f) * 43758.5453f)

    private fun drawParticles(
        cv: Canvas, mode: String, tMs: Long, energy: Float, w: Float, h: Float, scale: Float,
    ) {
        when (mode) {
            "雨季" -> drawRain(cv, tMs, w, h, scale)
            "雪天" -> drawSnow(cv, tMs, energy, w, h, scale)
        }
    }

    /**
     * 雨季：参考 Lavender-z/demo 雨特效的 WebGL 思路 —— 背景是一张图，
     * 水滴位置扰动取样 UV、水滴内清晰、水滴外模糊。Canvas 版用
     * BitmapShader(清晰封面) + 每滴一个局部放大 Matrix 实现折射，
     * 共享同一个 shader 对象，逐帧零分配。
     */
    private fun drawRain(cv: Canvas, tMs: Long, w: Float, h: Float, scale: Float) {
        val shader = backShader ?: return
        val clear = backClear ?: return
        val sx = w / clear.width.toFloat()
        val sy = h / clear.height.toFloat()
        val m = Matrix()
        val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * scale
            color = Color.argb(105, 255, 255, 255)
        }
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 220, 238, 255)
            strokeWidth = 1.3f * scale
        }
        for (i in 0 until 42) {
            val a = hash11(i * 1.13f + 3.7f)
            val b = hash11(i * 2.71f + 11.2f)
            val c = hash11(i * 4.37f + 27.9f)
            val d = hash11(i * 7.19f + 5.4f)
            val depth = 0.55f + c * 1.15f
            val r = (3.4f + b * 7.6f) * scale * depth
            val x = a * w
            val speed = 0.45f + d * 0.85f
            val y = ((b * h + tMs * 0.001f * speed * 330f * scale) % (h + r * 4f)) - r * 2f
            val lens = 1.18f + c * 0.28f
            val dx = (a - 0.5f) * r * 1.6f
            val dy = -r * 0.35f
            m.setScale(sx * lens, sy * lens)
            m.postTranslate(x * (1f - lens) + dx, y * (1f - lens) + dy)
            shader.setLocalMatrix(m)
            cv.drawLine(x, y - r, x - r * 0.12f, y - r * (2.2f + speed * 2.6f), trail)
            lensPaint.alpha = (190 + (c * 55).toInt()).coerceAtMost(255)
            cv.drawCircle(x, y, r, lensPaint)
            cv.drawCircle(x, y, r, edge)
            cv.drawCircle(x - r * 0.3f, y - r * 0.32f, r * 0.16f, spec)
        }
        val fog = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(10, 220, 235, 255) }
        cv.drawRect(0f, 0f, w, h, fog)
    }

    /** 柔边雪片贴图：中心白、边缘透明，避免旧版「实心白点」的塑料感。 */
    private fun snowFlake(): Bitmap {
        flakeBmp?.let { if (!it.isRecycled) return it }
        val s = 64
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            s / 2f, s / 2f, s / 2f,
            Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        flakeBmp = b
        return b
    }

    /** 雪天 = AUDIO REACTIVE：能量驱动下落速度、风幅与整体亮度。 */
    private fun drawSnow(
        cv: Canvas, tMs: Long, energy: Float, w: Float, h: Float, scale: Float,
    ) {
        val flake = snowFlake()
        val e = energy.coerceIn(0f, 1f)
        val wind = (7f + 30f * e) * scale
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val crystal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * scale
            color = Color.argb(150, 255, 255, 255)
        }
        for (i in 0 until 78) {
            val a = hash11(i * 1.7f + 1.1f)
            val b = hash11(i * 3.3f + 9.4f)
            val c = hash11(i * 5.9f + 21.7f)
            val d = hash11(i * 8.1f + 4.2f)
            val depth = 0.35f + c * 1.35f
            val r = (1.8f + d * 4.6f) * scale * depth
            val speed = (0.4f + b * 0.8f) * (0.55f + 1.25f * e)
            val y = ((a * h + tMs * 0.001f * speed * 105f * scale) % (h + r * 4f)) - r * 2f
            val x = b * w + kotlin.math.sin(tMs * 0.00072f + i * 0.9f) * wind * depth +
                kotlin.math.sin(tMs * 0.00019f + i * 0.37f) * 7f * scale
            p.alpha = (105 + (depth * 70).toInt() + (e * 35).toInt()).coerceAtMost(245)
            cv.drawBitmap(flake, null, RectF(x - r, y - r, x + r, y + r), p)
            if (depth > 1.25f && i % 7 == 0) {
                val rr = r * 1.5f
                cv.save()
                cv.translate(x, y)
                cv.rotate((tMs * 0.02f + i * 17f) % 360f)
                for (k in 0 until 3) {
                    val th = k * 60f * PI.toFloat() / 180f
                    cv.drawLine(
                        -kotlin.math.cos(th) * rr, -kotlin.math.sin(th) * rr,
                        kotlin.math.cos(th) * rr, kotlin.math.sin(th) * rr, crystal,
                    )
                }
                cv.restore()
            }
        }
        if (e > 0.55f) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(((e - 0.55f) * 26f).toInt().coerceAtMost(14), 215, 235, 255)
            }
            cv.drawRect(0f, 0f, w, h, glow)
        }
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
        val act = recActiveRow(st, c.lyricOffsetMs)
        val base = c.lyricSizeSp.toFloat()
            .coerceIn(REC_LYRIC_MIN_SP.toFloat(), REC_LYRIC_MAX_SP.toFloat()) * scale
        val hasTrans = c.showTrans && st.rows.any { !it.trans.isNullOrBlank() }
        val lineH = base * (1.12f * 3f + (if (hasTrans) 1.05f else 0.35f))
        val visible = (h * 0.78f / lineH).toInt().coerceIn(3, 7)
        val half = visible / 2
        val lp = recTextPaint(c, tf).apply { textAlign = Paint.Align.LEFT }
        for (d in -half..half) {
            val i = act + d
            if (i < 0 || i >= st.rows.size) continue
            val row = st.rows[i]
            val y = centerY + d * lineH
            val active = d == 0
            lp.color = if (active) Color.WHITE
            else Color.argb((130 - abs(d) * 28).coerceAtLeast(35), 255, 255, 255)
            lp.textSize = if (active) base * 1.12f else base * 0.86f
            lp.isFakeBoldText = active
            val lines = wrapRecLines(row.text, lp, lw, 3)
            var ly = y - (lines.size - 1) * base * 0.55f
            for (l in lines) {
                cv.drawText(l, lx, ly, lp)
                ly += base * 1.06f
            }
            if (c.showTrans && !row.trans.isNullOrBlank()) {
                lp.textSize = base * 0.56f
                lp.isFakeBoldText = false
                lp.color = Color.argb(if (active) 190 else 90, 210, 210, 225)
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
        val act = recActiveRow(st, c.lyricOffsetMs).coerceAtLeast(0).coerceAtMost(rows.lastIndex)
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
