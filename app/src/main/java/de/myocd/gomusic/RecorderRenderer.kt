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
        // 雨季严格按参考 WebGL：uTime 按 60fps 帧计数逐帧递增，不复用整帧；
        // 雪天仍按 50ms 相位桶 + 8 级能量桶复用，避免拖慢编码。
        val dynamic = when (c.bgMode) {
            "雨季" -> (st.positionMs * 60L / 1000L).toString()
            "雪天" -> {
                val phase = ((st.positionMs % 1800L) * 36L / 1800L).toInt()
                "$phase/${(st.energy * 8f).toInt().coerceIn(0, 8)}"
            }
            // 玻璃反光缓慢流动，8 个相位一档：有动态又不会把整帧复用率打到 0
            "液态玻璃" -> "g${((st.positionMs % 1600L) / 200L).toInt()}"
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

    /** 非雨季的背景（封面底 + 略微模糊）只生成一次；雨季由 RainGlsl 逐帧输出。 */
    private var backBmp: Bitmap? = null
    private var backKey: String? = null

    private fun staticBackground(
        st: RecFrameState, c: RecorderConfig, w: Int, h: Int, scale: Float,
    ): Bitmap {
        val key = listOf(System.identityHashCode(st.cover), c.bgMode, w, h).joinToString("|")
        val old = backBmp
        if (old != null && !old.isRecycled && backKey == key) return old
        val b = bitmap(old, w, h)
        drawBackground(Canvas(b), st.cover, w.toFloat(), h.toFloat(), scale, c.bgMode)
        backBmp = b
        backKey = key
        return b
    }

    private fun drawFull(
        cv: Canvas, st: RecFrameState, c: RecorderConfig, tf: Typeface?, w: Float, h: Float,
    ) {
        val scale = h / 1080f
        val pad = 28f * scale
        // 雨季严格走参考 WebGL 着色器：每渲染一帧 uTime +1，每帧单独渲染。
        // 着色器不可用时退回静态封面底，不会整屏空白。
        val rain = if (c.bgMode == "雨季") {
            RainGlsl.render(st.cover, w.toInt(), h.toInt())
        } else null
        if (rain != null && !rain.isRecycled) {
            cv.drawBitmap(rain, 0f, 0f, null)
        } else {
            cv.drawBitmap(staticBackground(st, c, w.toInt(), h.toInt(), scale), 0f, 0f, null)
        }
        cv.drawBitmap(infoBitmap(st, c, tf, w.toInt(), h.toInt()), 0f, 0f, null)
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
     * - 高斯模糊：清晰封面 + 一层轻模糊
     * - 液态玻璃：整屏一层磨砂玻璃 + 一条贯穿全屏的光泽，不再切成上下两片卡片
     * - 雨季：整屏由 Lavender-z/demo 的 WebGL 着色器逐帧输出（专辑封面作 dispImage）
     * - 雪天：柔边雪片随音频能量改变下落速度 / 风 / 亮度（sn.html 的 AUDIO REACTIVE）
     */
    private var backCoverId = 0
    private var backW = 0
    private var backH = 0
    private var backClear: Bitmap? = null
    private var backBlur: Bitmap? = null
    private var backShader: BitmapShader? = null
    private var flakeBmp: Bitmap? = null

    /** 封面底图只按「封面对象 + 输出尺寸」生成一次（清晰底 + 轻模糊底）。 */
    private fun backdrop(cover: Bitmap?, w: Int, h: Int): Boolean {
        if (cover == null || cover.isRecycled || w <= 0 || h <= 0) return false
        val id = System.identityHashCode(cover)
        if (backCoverId == id && backW == w && backH == h &&
            backClear != null && !backClear!!.isRecycled
        ) return true
        // 26.11.28：旧版缩到 1/6 再放大 6 倍，封面细节全丢，看起来就是「太糊」。
        // 改为最多 1/2 降采样（上限 1280），只做很轻的盒式模糊。
        val sw = (w / 2).coerceIn(480, 1280)
        val sh = (h / 2).coerceIn(480, 1280)
        val clear = cropSmall(cover, sw, sh)
        val blur = blurSmall(clear, 2)
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
        val clear = backClear ?: return
        val full = RectF(0f, 0f, w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // 26.11.28：所有模式都以清晰可辨的专辑封面打底，不再整屏糊成一团。
        cv.drawBitmap(clear, null, full, p)
        if (mode == "高斯模糊") {
            // 高斯模糊 = 清晰封面 + 一层适度模糊，封面轮廓仍看得见。
            val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 120 }
            backBlur?.let { cv.drawBitmap(it, null, full, bp) }
            val mist = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(10, 255, 255, 255) }
            cv.drawRect(0f, 0f, w, h, mist)
        }
        // 只留很淡的暗渐变保证文字可读，不再把封面压到看不见。
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.argb(6, 0, 0, 0), Color.argb(72, 0, 0, 0), Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w, h, shade)
        when (mode) {
            "液态玻璃" -> drawGlass(cv, w, h, scale)
            "雪天" -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(10, 170, 205, 255) }
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
     * 液态玻璃：整层连续凸透镜折射 + 边缘色散 + 玻璃截面高光 / 内阴影。
     * 26.11.27 起不再用「把模糊图放大 2.5% 再盖一层雾面」的假玻璃：
     * - 40 个同心环带按 r² 连续放大，环带是圆环不是方块，所以整屏不分割
     * - 只在 r > 0.52R 的边缘环带做红/蓝反向偏移色散，中心保持干净
     * - 径向亮边 + 暗内阴影给出玻璃厚度，斜向窄高光给出镜面反射
     * 折射源是「略微模糊的专辑封面」，整层就是一张玻璃，不切成卡片。
     */
    private fun drawGlass(cv: Canvas, w: Float, h: Float, scale: Float) {
        // 玻璃下面用清晰封面，折射源不再是一张 1/6 缩略图。
        val base = backClear ?: return
        val cx = w / 2f
        val cy = h / 2f
        val rMax = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        // 步骤一：连续径向折射
        val rings = 40
        val ringPath = Path()
        for (k in rings downTo 1) {
            val r0 = rMax * (k - 1) / rings
            val r1 = rMax * k / rings
            val q = (r0 + r1) / 2f / rMax
            val zoom = 1f + 0.032f * q * q
            ringPath.reset()
            ringPath.addCircle(cx, cy, r1, Path.Direction.CW)
            ringPath.addCircle(cx, cy, r0, Path.Direction.CCW)
            ringPath.fillType = Path.FillType.EVEN_ODD
            cv.save()
            cv.clipPath(ringPath)
            val dw = w * zoom
            val dh = h * zoom
            // 环带之间不叠加半透明层，避免整屏发雾；玻璃感交给高光与色散。
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            cv.drawBitmap(base, null, RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f), p)
            cv.restore()
        }

        // 步骤二：边缘色散：红 / 蓝通道反向偏移后以 SCREEN 叠加，只在边缘环带生效
        val edgePath = Path().apply {
            addCircle(cx, cy, rMax * 1.02f, Path.Direction.CW)
            addCircle(cx, cy, rMax * 0.52f, Path.Direction.CCW)
            fillType = Path.FillType.EVEN_ODD
        }
        val redM = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0.55f, 0f,
        ))
        val blueM = ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0.55f, 0f,
        ))
        val disp = 1.8f * scale
        cv.save()
        cv.clipPath(edgePath)
        for (triple in listOf(
            Triple(redM, disp, disp * 0.4f),
            Triple(blueM, -disp, -disp * 0.4f),
        )) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(triple.first)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
            val dx = triple.second
            val dy = triple.third
            cv.drawBitmap(base, null, RectF(dx, dy, w + dx, h + dy), p)
        }
        cv.restore()

        // 步骤三：玻璃截面：中心透明、边缘亮的高光环 + 一圈暗内阴影
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, rMax,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(5, 255, 255, 255),
                    Color.argb(42, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP,
            )
        }
        cv.drawRect(0f, 0f, w, h, rim)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, rMax,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb(0, 0, 0, 0),
                    Color.argb(32, 0, 0, 0),
                ),
                floatArrayOf(0f, 0.86f, 1f), Shader.TileMode.CLAMP,
            )
        }
        cv.drawRect(0f, 0f, w, h, inner)

        // 步骤四：一道贯穿整屏的窄镜面高光（不是独立玻璃块）
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.06f, w, h * 0.56f,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(32, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.36f, 0.50f, 0.64f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        cv.drawRect(0f, 0f, w, h, spec)
    }

    private fun frac(x: Float): Float = x - kotlin.math.floor(x)
    private fun hash11(n: Float): Float = frac(kotlin.math.sin(n * 127.1f) * 43758.5453f)

    private fun drawParticles(
        cv: Canvas, mode: String, tMs: Long, energy: Float, w: Float, h: Float, scale: Float,
    ) {
        // 雨季由 RainGlsl 的 WebGL 着色器逐帧生成，不再叠加任何自绘水滴/线条。
        if (mode == "雪天") drawSnow(cv, tMs, energy, w, h, scale)
        if (mode == "液态玻璃") drawGlassFlow(cv, tMs, w, h, scale)
    }

    /** 玻璃表面缓慢横移的柔和反光：让整层玻璃有动态，但只画两个渐变，不拖慢编码。 */
    private fun drawGlassFlow(cv: Canvas, tMs: Long, w: Float, h: Float, scale: Float) {
        val phase = (tMs % 1600L) / 1600f
        val cx = -w * 0.18f + phase * w * 1.36f
        val cy = h * 0.30f
        val r = w * 0.46f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(30, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        cv.drawRect(0f, 0f, w, h, glow)
        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - r, 0f, cx + r, h,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(34, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.42f, 0.51f, 0.62f), Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        cv.drawRect(0f, 0f, w, h, streak)
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
