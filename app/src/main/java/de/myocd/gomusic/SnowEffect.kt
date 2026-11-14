package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音乐天气特效 · 飘雪 —— 逐行移植自 sn.html 的 <script>，实现方式与常量均未改动。
 *
 * 对应关系（左为 sn.html，右为本文件）：
 *   makeSnowSprite(radius)            -> makeSprite(radius)
 *   SNOW_LAYERS                       -> LAYERS（六项参数逐字一致）
 *   snow.{flakes,wind,intensity,max}  -> 同名成员
 *   snow.pickLayer / reset / update / render -> 同名方法
 *   energy / updateAudio / drive      -> 同名方法
 *   frame() 的 dt clamp、time += dt、updateAudio、drive、clearRect、update、render
 *                                    -> step() + renderTo()
 *
 * 平台平替（只做三处等价替换，不改算法）：
 *   1) DPR：浏览器 CSS 像素统一乘 S = min(W,H)/800 映射到输出像素；
 *      半径、速度、摆幅、边界 30/60 全部乘同一个系数。
 *   2) Web Audio AnalyserNode：Android 无该节点，由 RecAudioEnergy 用同样的
 *      fftSize=2048、smoothingTimeConstant=0.78、dB→byte 映射预计算每 100ms 的
 *      (b,m,h)；updateAudio 的分频段范围与 lerp 系数逐字一致。
 *   3) ctx.clearRect + globalCompositeOperation='source-over' 对应独立透明
 *      Bitmap 层，每帧 eraseColor(TRANSPARENT) 后画雪，再贴回主画布；
 *      页面 body 的 radial-gradient 在录制里由「专辑封面底」承担。
 */
class SnowEffect(private val W: Float, private val H: Float, seed: Long = 20261131L) {

    private val S = min(W, H) / 800f
    private val rnd = Random(seed)
    private var time = 0f

    private fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
    private fun rand(a: Double, b: Double): Float = (a + rnd.nextDouble() * (b - a)).toFloat()
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 1. 雪花精灵（核心实、光晕小 → 清晰不糊） */
    private fun makeSprite(radius: Float): Bitmap {
        val sz = ceil(radius * 2.6f).toInt() + 4
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val cx = sz / 2f
        val cy = sz / 2f
        val R = radius * 1.25f
        val grd = RadialGradient(
            cx, cy, R,
            intArrayOf(
                Color.argb(255, 255, 255, 255),   // 0.00 rgba(255,255,255,1)
                Color.argb(245, 246, 251, 255),   // 0.50 rgba(246,251,255,0.96)
                Color.argb(107, 222, 239, 255),   // 0.80 rgba(222,239,255,0.42)
                Color.argb(0, 205, 230, 255),     // 1.00 rgba(205,230,255,0)
            ),
            floatArrayOf(0.00f, 0.50f, 0.80f, 1.00f),
            Shader.TileMode.CLAMP,
        )
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grd }
        cv.drawCircle(cx, cy, R, g)
        return b
    }

    private data class L(
        val r: Float, val speed: Float, val alpha: Float,
        val swayAmp: Float, val drift: Float, val spawn: Float,
    )

    private val LAYERS = listOf(
        L(3.6f * S, 78f * S, 0.95f, 22f * S, 100f * S, 0.18f),
        L(2.1f * S, 48f * S, 0.72f, 14f * S, 74f * S, 0.32f),
        L(1.1f * S, 26f * S, 0.42f, 8f * S, 50f * S, 0.50f),
    )
    private val sprites = LAYERS.map { makeSprite(it.r) }

    /** 2. 雪系统 */
    private data class F(
        var layer: Int = 0,
        var speed: Float = 0f,
        var alpha: Float = 0f,
        var swayAmp: Float = 0f,
        var swayFreq: Float = 0f,
        var phase: Float = 0f,
        var drift: Float = 0f,
        var x: Float = 0f,
        var y: Float = 0f,
    )

    private val flakes = ArrayList<F>(450)
    private var wind = 0.1f
    private var intensity = 0.55f
    private val max = 450

    private fun pickLayer(): Int {
        val r = rnd.nextFloat()
        var acc = 0f
        for (i in LAYERS.indices) {
            acc += LAYERS[i].spawn
            if (r <= acc) return i
        }
        return LAYERS.size - 1
    }

    private fun reset(f: F, initial: Boolean): F {
        f.layer = pickLayer()
        val L = LAYERS[f.layer]
        f.speed = L.speed * rand(0.8, 1.25)
        f.alpha = L.alpha * rand(0.75, 1.0)
        f.swayAmp = L.swayAmp * rand(0.6, 1.3)
        f.swayFreq = rand(0.6, 1.8)
        f.phase = rand(0.0, PI * 2)
        f.drift = L.drift
        f.x = rand(-0.1, 1.1) * W
        f.y = if (initial) rand(-0.15, 1.0) * H else rand(-0.15, -0.02) * H
        return f
    }

    private fun update(dt: Float) {
        val target = (max * intensity).roundToInt()
        while (flakes.size < target) flakes.add(reset(F(), true))
        while (flakes.size > target) flakes.removeAt(flakes.size - 1)
        for (i in flakes.indices) {
            val f = flakes[i]
            f.x += (sin(time * f.swayFreq + f.phase) * f.swayAmp + wind * f.drift) * dt
            f.y += f.speed * dt
            if (f.y > H + 30f * S || f.x < -60f * S || f.x > W + 60f * S) reset(f, false)
        }
    }

    private fun render(ctx: Canvas, p: Paint) {
        for (i in flakes.indices) {
            val f = flakes[i]
            val sp = sprites[f.layer]
            val twinkle = 0.9f + 0.1f * sin(time * 2.2f + f.phase)
            p.alpha = (f.alpha * twinkle * 255f).toInt().coerceIn(0, 255)
            ctx.drawBitmap(sp, f.x - sp.width / 2f, f.y - sp.height / 2f, p)
        }
        p.alpha = 255
    }

    /** 3. 音频分析：b/m/h 已按参考 b/=7*255、m/=52*255、h/=(n*.8-60)*255 归一化 */
    private var eBass = 0f
    private var eMid = 0f
    private var eHigh = 0f

    private fun updateAudio(b: Float, m: Float, h: Float) {
        eBass = lerp(eBass, b, 0.28f)
        eMid = lerp(eMid, m, 0.20f)
        eHigh = lerp(eHigh, h, 0.16f)
    }

    private fun drive() {
        val breathe = 0.5f + 0.5f * sin(time * 0.3f)
        intensity = clamp(0.32f + eMid * 0.85f + breathe * 0.10f, 0.15f, 1f)
        wind = clamp(sin(time * 0.16f) * 0.4f + eHigh * 0.75f, -0.8f, 0.8f)
    }

    /** 4. 主循环：对应 frame(now) 的 dt clamp 0.05 / time += dt / updateAudio / drive */
    fun step(dt: Float, b: Float, m: Float, h: Float) {
        val d = if (dt > 0.05f) 0.05f else dt
        time += d
        updateAudio(b, m, h)
        drive()
        update(d)
    }

    /** 预览跳到歌曲时长一半：等价于在该时刻重新打开页面，flakes 由 initial 生成。 */
    fun seek(tSec: Float) {
        reset()
        time = tSec
    }

    fun reset() {
        flakes.clear()
        wind = 0.1f
        intensity = 0.55f
        eBass = 0f
        eMid = 0f
        eHigh = 0f
        time = 0f
    }

    /** clearRect + source-over + snow.render()：独立透明层，不擦掉主画布的封面底。 */
    fun renderTo(dst: Canvas) {
        val w = W.toInt().coerceAtLeast(1)
        val h = H.toInt().coerceAtLeast(1)
        var layer = layerBmp
        if (layer == null || layer!!.isRecycled || layer!!.width != w || layer!!.height != h) {
            layer?.let { if (!it.isRecycled) it.recycle() }
            layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            layerBmp = layer
        }
        val b = layer!!
        b.eraseColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        render(Canvas(b), p)
        dst.drawBitmap(b, 0f, 0f, null)
    }

    private var layerBmp: Bitmap? = null
}
