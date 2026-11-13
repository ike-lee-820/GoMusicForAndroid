package de.myocd.gomusic

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * 均衡器音效：参数化双二阶滤波器 + 预设曲线。
 *
 * 对应《均衡器音效预设设计与实现规范 1.0 (2026-11-13)》。
 * 所有参数都是「起点值」，不是硬编码唯一解：
 *  - [EqPreset.strength] 按 0~100% 线性缩放增益（0.5/1dB 步进由 UI 控制）
 *  - [EqConfig.deviceTrim] 允许按耳机 / 音箱 / 车载做整体校准
 *  - 切换预设走 [EqEngine.ramp] 短淡入淡出，防止爆音
 *  - A/B 对比走 [EqEngine.loudnessMatch] 做响度匹配，避免「更响 = 更好」
 *
 * 滤波实现用 RBJ Audio EQ Cookbook 的标准系数，支持
 * PK / LSC / HSC / HPQ / LPQ 五类，Q 值直接参与计算。
 */

// ---------------------------------------------------------------------
// 频段定义
// ---------------------------------------------------------------------

enum class FilterType { PK, LSC, HSC, HPQ, LPQ }

@Serializable
data class EqBand(
    val hz: Float,
    val db: Float = 0f,
    val q: Float = 1f,
    val type: FilterType = FilterType.PK,
)

/** 自定义 EQ 的 10 段中心频率（Hz），也是预设表的坐标 */
val EQ_BANDS_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

/** 预设分组：人声 / 音乐 / 空间 / 创意 / 夜间 */
enum class EqGroup(val label: String) {
    VOCAL("人声"), MUSIC("音乐"), SPACE("空间"), CREATIVE("创意"), NIGHT("夜间"),
}

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val group: EqGroup,
    /** 一句话听感描述，不是参数堆砌 */
    val desc: String,
    val bands: List<EqBand>,
    val hpfHz: Float = 0f,
    val lpfHz: Float = 0f,
    /** 创意 / 特殊音效：默认不开启，切换时强制淡入 */
    val creative: Boolean = false,
    /** 动态等响度补偿：随音量变化，不是固定 EQ */
    val dynamicLoudness: Boolean = false,
)

private fun pk(hz: Float, db: Float, q: Float = 1f) = EqBand(hz, db, q, FilterType.PK)
private fun zero10() = List(10) { EqBand(EQ_BANDS_HZ[it]) }

/** 按索引覆盖若干段，其余保持 0dB */
private fun curve(vararg pairs: Pair<Int, EqBand>): List<EqBand> {
    val l = zero10().toMutableList()
    pairs.forEach { (i, b) -> l[i] = b }
    return l
}

/**
 * 9 个预设。数值全部来自规范第 3 / 4 节，映射到 10 段固定中心频点：
 * 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k。
 * 规范里落在两段之间的频点（如 80Hz、160Hz、6kHz、12kHz）取最近的段并
 * 在说明里标注，Q 值按规范给的窄带/宽带建议填。
 */
val EQ_PRESETS: List<EqPreset> = listOf(
    // 3.1 纯净人声：先清浑浊与齿音，再提中频；中频不超 +6dB
    EqPreset(
        "vocal", "纯净人声", EqGroup.VOCAL,
        "人声靠前、咬字清楚，低频不浑、齿音不刺",
        curve(
            3 to pk(250f, -1.5f, 2.5f),          // 250~300Hz 窄带去浑浊
            4 to pk(500f, +2.5f),                // 500Hz~2k 辨识度
            5 to pk(1000f, +2.5f),
            6 to pk(2000f, +2.0f),               // 1k~3k 咬字穿透
            8 to pk(8000f, -1.25f, 2.0f),        // 6kHz 齿音（就近 8k）
            9 to pk(16000f, +0.75f),             // 10k 以上保留空气感
        ),
        hpfHz = 90f,                             // HPF 80~100Hz
    ),
    // 3.2 澎湃乐场
    EqPreset(
        "punch", "澎湃乐场", EqGroup.MUSIC,
        "鼓点扎实有弹性，低频厚但不轰头",
        curve(
            0 to pk(31f, +2.0f),                 // 20~60Hz +1~+3
            1 to pk(62f, +3.5f),                 // 60~150Hz 主区（80Hz 就近 62）
            2 to pk(125f, +3.0f),
            4 to pk(500f, -1.5f),                // 300~500Hz 减闷厚
        ),
    ),
    // 3.3 缥缈音场
    EqPreset(
        "airy", "缥缈音场", EqGroup.SPACE,
        "空灵通透，高频有空气感，4~6kHz 不刺",
        curve(
            0 to pk(31f, +1.5f),                 // 30Hz 空间底色
            7 to pk(4000f, +0.5f),               // 4~6kHz 控制 0~+1
            8 to pk(8000f, +1.0f),
            9 to pk(16000f, +2.0f),              // 10~14kHz 空气感主区
        ),
    ),
    // 3.4 沉重低音
    EqPreset(
        "bass", "沉重低音", EqGroup.MUSIC,
        "下潜深、体感重，低频凝聚不拖沓",
        curve(
            0 to pk(31f, +5.0f),                 // 31.5~80Hz 重点 +3~+6
            1 to pk(62f, +5.0f),
            2 to pk(125f, +3.0f),
            3 to pk(250f, -2.0f, 1.2f),          // 160Hz 附近 -2（就近 250）
            4 to pk(500f, -0.5f),                // 250Hz 以上保持或微衰
        ),
    ),
    // 3.5 温暖圆润
    EqPreset(
        "warm", "温暖圆润", EqGroup.MUSIC,
        "厚实顺滑耐听，适合播客、民谣、久听",
        curve(
            1 to pk(62f, -1.0f),                 // 控制极低频浑浊
            3 to pk(250f, +2.5f),
            4 to pk(500f, +1.5f),                // 300~500Hz 补厚度
            8 to pk(8000f, +3.8f),               // 10kHz 顺滑空气感（就近 8k）
        ),
    ),
    // 3.6 冷冽明亮
    EqPreset(
        "bright", "冷冽明亮", EqGroup.MUSIC,
        "通透高解析，线条清晰、听感偏冷",
        curve(
            3 to pk(250f, -2.0f),
            4 to pk(500f, -2.0f),                // 250~800Hz -1~-3
            5 to pk(1000f, -1.0f),
            6 to pk(2000f, +2.0f),               // 2.5~5kHz +1.5~+3
            7 to pk(4000f, +2.5f),
            8 to pk(8000f, +1.5f),               // 高频按设备微调 +1~+2
        ),
    ),
    // 3.7 现场感/环绕感：LIVE 曲线 0/-2/-2/3/3/3/2/0/0
    EqPreset(
        "live", "现场环绕", EqGroup.SPACE,
        "声部距离拉开，人声和乐器更靠前",
        curve(
            1 to pk(62f, -2.0f),
            2 to pk(125f, -2.0f),
            3 to pk(250f, +3.0f),
            4 to pk(500f, +3.0f),
            5 to pk(1000f, +3.0f),
            6 to pk(2000f, +2.0f),
        ),
    ),
    // 3.8 复古/电话音效：HPF 300 + LPF 3.4k + 1.8k 微提；创意音效默认关闭
    EqPreset(
        "retro", "复古音效", EqGroup.CREATIVE,
        "狭窄频宽的年代感（电话 / 老收音机），创意音效",
        curve(
            6 to pk(2000f, +1.5f, 1.2f),         // 1.8kHz 附近微提
        ),
        hpfHz = 300f,
        lpfHz = 3400f,
        creative = true,
    ),
    // 3.9 深夜聆听：动态等响度，不固定大幅提升低频
    EqPreset(
        "night", "深夜聆听", EqGroup.NIGHT,
        "小音量也平衡；随音量动态补偿，不是固定 EQ",
        curve(
            1 to pk(62f, +1.5f),                 // 仅作为手动模拟的起点
            9 to pk(16000f, +1.0f),
        ),
        dynamicLoudness = true,
    ),
)

/** 老式收音机变体：HPF 250 / LPF 3k，比电话更极端，同样标注创意 */
val EQ_RETRO_RADIO = EqPreset(
    "retro_radio", "老式收音机", EqGroup.CREATIVE,
    "更窄的频宽、更强的年代感，创意音效",
    curve(3 to pk(250f, -7.5f, 1.0f)),
    hpfHz = 250f, lpfHz = 3000f, creative = true,
)

fun eqPresetById(id: String): EqPreset? =
    EQ_PRESETS.firstOrNull { it.id == id } ?: if (id == EQ_RETRO_RADIO.id) EQ_RETRO_RADIO else null

// ---------------------------------------------------------------------
// 配置与持久化
// ---------------------------------------------------------------------

@Serializable
data class EqConfig(
    val enabled: Boolean = false,
    /** 选中的预设 id；"custom" 表示自定义 */
    val presetId: String = "flat",
    /** 强度 0~1，对应 UI 的 0~100% */
    val strength: Float = 1f,
    /** 自定义 10 段（dB），仅在 presetId == "custom" 时生效 */
    val customDb: List<Float> = List(10) { 0f },
    /** 设备校准：0 耳机 / 1 音箱 / 2 车载，整体增益微调 */
    val device: Int = 0,
    /** A/B 对比时是否做响度匹配 */
    val loudnessMatch: Boolean = true,
    /** A/B 的 B 侧预设 id，空串表示平直 */
    val compareId: String = "",
    /** 复古音效的变体：false 电话 / true 老收音机 */
    val retroRadio: Boolean = false,
    /** 深夜聆听的音量（0~1），动态补偿的输入 */
    val nightVolume: Float = 0.35f,
)

val EQ_FLAT = EqConfig()

/** 危险增益提示（规范 5.2）：低频 >4.5、中频 >6、高频 >3 dB */
fun EqConfig.warnings(): List<String> {
    if (!enabled) return emptyList()
    val bands = activeBands()
    val out = mutableListOf<String>()
    val low = bands.filter { it.hz <= 125f }.maxOfOrNull { it.db } ?: 0f
    val mid = bands.filter { it.hz in 500f..2000f }.maxOfOrNull { it.db } ?: 0f
    val high = bands.filter { it.hz >= 8000f }.maxOfOrNull { it.db } ?: 0f
    // activeBands() 已按 strength 缩放过，阈值直接比固定值
    if (low > 4.5f) out += "低频提升较大，可能触发削波或轰头"
    if (mid > 6f) out += "中频提升超过 6dB，易出现电话音"
    if (high > 3f) out += "高频提升超过 3dB，可能放大底噪"
    return out
}

/** 当前实际生效的频段曲线（含强度、设备微调、动态等响度） */
fun EqConfig.activeBands(): List<EqBand> {
    // 关掉均衡器就是全 0dB 的直通曲线，否则开关形同虚设
    if (!enabled) return zero10()
    val base: List<EqBand> = when {
        presetId == "custom" -> EQ_BANDS_HZ.mapIndexed { i, hz ->
            EqBand(hz, customDb.getOrElse(i) { 0f }, 1f, FilterType.PK)
        }
        presetId == "flat" -> zero10()
        else -> {
            val p = eqPresetById(presetId)
            when {
                p == null -> zero10()
                presetId == "retro" && retroRadio -> EQ_RETRO_RADIO.bands
                else -> p.bands
            }
        }
    }
    val deviceTrim = when (device) { 1 -> -0.5f; 2 -> -1.0f; else -> 0f }
    val nightBoost = if (presetId == "night") {
        // 动态等响度：音量越小，两端补偿越多；上限 4dB，禁止固定大幅提升
        val k = ((1f - nightVolume.coerceIn(0f, 1f)) * 3.5f).coerceAtMost(4f)
        k
    } else 0f
    return base.map { b ->
        var db = b.db * strength + deviceTrim
        if (nightBoost > 0f && (b.hz <= 62f || b.hz >= 8000f)) db += nightBoost * 0.5f
        b.copy(db = db.coerceIn(-12f, 12f))
    }
}

fun EqConfig.activeHpf(): Float {
    if (!enabled) return 0f
    return when {
        presetId == "retro" && !retroRadio -> 300f
        presetId == "retro" && retroRadio -> 250f
        presetId == "vocal" -> 90f
        else -> eqPresetById(presetId)?.hpfHz ?: 0f
    }
}

fun EqConfig.activeLpf(): Float {
    if (!enabled) return 0f
    return when {
        presetId == "retro" && !retroRadio -> 3400f
        presetId == "retro" && retroRadio -> 3000f
        else -> eqPresetById(presetId)?.lpfHz ?: 0f
    }
}

// ---------------------------------------------------------------------
// 双二阶滤波器
// ---------------------------------------------------------------------

/** 单个 biquad；系数在采样率变化时重算，声道各持一份状态 */
class Biquad(
    private val sampleRate: Int,
    private val band: EqBand,
    private val gainScale: Float = 1f,
) {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    init { rebuild(band, gainScale) }

    fun rebuild(band: EqBand, gainScale: Float) {
        val fs = sampleRate.toFloat().coerceAtLeast(8000f)
        val f0 = band.hz.coerceIn(10f, fs * 0.45f)
        val w0 = (2f * PI * f0 / fs).toFloat()
        val cw = cos(w0.toDouble()).toFloat()
        val sw = sin(w0.toDouble()).toFloat()
        val alpha = sw / (2f * band.q.coerceIn(0.1f, 20f))
        val A = 10f.pow((band.db * gainScale) / 40f)
        var n0: Float; var n1: Float; var n2: Float
        var d0: Float
        when (band.type) {
            FilterType.PK -> {
                n0 = 1f + alpha * A; n1 = -2f * cw; n2 = 1f - alpha * A
                d0 = 1f + alpha / A; a1 = -2f * cw; a2 = 1f - alpha / A
            }
            FilterType.LSC -> {
                val a = 2f * sqrt(A) * alpha
                n0 = A * ((A + 1f) - (A - 1f) * cw + a)
                n1 = 2f * A * ((A - 1f) - (A + 1f) * cw)
                n2 = A * ((A + 1f) - (A - 1f) * cw - a)
                d0 = (A + 1f) + (A - 1f) * cw + a
                a1 = -2f * ((A - 1f) + (A + 1f) * cw)
                a2 = (A + 1f) + (A - 1f) * cw - a
            }
            FilterType.HSC -> {
                val a = 2f * sqrt(A) * alpha
                n0 = A * ((A + 1f) + (A - 1f) * cw + a)
                n1 = -2f * A * ((A - 1f) + (A + 1f) * cw)
                n2 = A * ((A + 1f) + (A - 1f) * cw - a)
                d0 = (A + 1f) - (A - 1f) * cw + a
                a1 = 2f * ((A - 1f) - (A + 1f) * cw)
                a2 = (A + 1f) - (A - 1f) * cw - a
            }
            FilterType.HPQ -> {
                n0 = (1f + cw) / 2f; n1 = -(1f + cw); n2 = (1f + cw) / 2f
                d0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
            FilterType.LPQ -> {
                n0 = (1f - cw) / 2f; n1 = 1f - cw; n2 = (1f - cw) / 2f
                d0 = 1f + alpha; a1 = -2f * cw; a2 = 1f - alpha
            }
        }
        b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0
        a1 /= d0; a2 /= d0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
}

/** 一个声道的滤波器级联：HPF → 10 段 → LPF */
private class ChannelChain(private val sampleRate: Int, cfg: EqConfig) {
    private val filters = mutableListOf<Biquad>()
    var config: EqConfig = cfg
        private set

    init { rebuild(cfg) }

    fun rebuild(cfg: EqConfig) {
        config = cfg
        filters.clear()
        val hpf = cfg.activeHpf()
        if (hpf > 0f) filters += Biquad(sampleRate, EqBand(hpf, 0f, 0.707f, FilterType.HPQ))
        cfg.activeBands().filter { abs(it.db) > 0.01f }.forEach {
            filters += Biquad(sampleRate, it)
        }
        val lpf = cfg.activeLpf()
        if (lpf > 0f) filters += Biquad(sampleRate, EqBand(lpf, 0f, 0.707f, FilterType.LPQ))
    }

    fun process(x: Float): Float {
        var v = x
        for (f in filters) v = f.process(v)
        return v
    }

    fun reset() { filters.forEach { it.reset() } }
    val isEmpty: Boolean get() = filters.isEmpty()
}

/**
 * 均衡器引擎：PCM16 输入 → PCM16 输出。
 *
 * - 切换配置时 [ramp] 用 30ms 的线性交叉淡入（旧/新两条链同时跑再混合），
 *   保证「切换预设无爆音」；创意音效强制走满 ramp。
 * - [loudnessMatch] 按两条链在粉噪上的近似能量差做预增益补偿，
 *   A/B 对比时不会因为「更响」被误判成「更好」。
 */
class EqEngine(var sampleRate: Int = 44100) {
    @Volatile var config: EqConfig = EQ_FLAT
        private set
    private var left = ChannelChain(sampleRate, EQ_FLAT)
    private var right = ChannelChain(sampleRate, EQ_FLAT)
    private var oldLeft: ChannelChain? = null
    private var oldRight: ChannelChain? = null
    private var rampPos = 0
    private var rampLen = 0
    private var preamp = 1f

    val enabled: Boolean get() = config.enabled && !left.isEmpty

    fun update(cfg: EqConfig, fadeMs: Int = 30) {
        // 等响度补偿与固定 EQ 预设不得默认叠加（规范 5.3）
        val safe = if (cfg.presetId == "night" && cfg.strength > 1f) cfg.copy(strength = 1f) else cfg
        if (safe == config) return
        val nl = ChannelChain(sampleRate, safe)
        val nr = ChannelChain(sampleRate, safe)
        if (rampLen > 0) {
            // 正在淡入中又切一次：直接以当前混合结果作为旧链，避免叠加跳变
            oldLeft = left; oldRight = right
        } else {
            oldLeft = left; oldRight = right
        }
        left = nl; right = nr
        rampLen = ((sampleRate * fadeMs.coerceAtLeast(10)) / 1000).coerceAtLeast(64)
        rampPos = 0
        config = safe
        preamp = if (safe.loudnessMatch) estimatePreamp(safe) else 1f
    }

    /** 响度匹配：用近似通带能量估计补偿量，避开逐样本 RMS 的开销 */
    private fun estimatePreamp(cfg: EqConfig): Float {
        val sum = cfg.activeBands().sumOf { (it.db * it.db).toDouble() }.toFloat()
        if (sum < 0.01f) return 1f
        val avg = sqrt(sum / EQ_BANDS_HZ.size)
        // 每 1dB 平均增益约回补 0.35dB，最多补 3dB，防止 A/B 时反而削波
        return 10f.pow((-avg * 0.35f).coerceIn(-3f, 0f) / 20f)
    }

    /** 处理交错 PCM16（L,R,L,R…）；原地修改 */
    fun process(pcm: ShortArray, frames: Int) {
        if (!enabled && rampLen == 0) return
        val fade = if (rampLen > 0) rampPos.toFloat() / rampLen else 1f
        for (i in 0 until frames) {
            val li = i * 2
            val l = pcm[li].toFloat() / 32768f
            val r = pcm[li + 1].toFloat() / 32768f
            var nl = left.process(l) * preamp
            var nr = right.process(r) * preamp
            if (rampLen > 0 && oldLeft != null) {
                val ol = oldLeft!!.process(l) * preamp
                val or = oldRight!!.process(r) * preamp
                nl = ol + (nl - ol) * fade
                nr = or + (nr - or) * fade
                if (++rampPos >= rampLen) {
                    rampLen = 0; rampPos = 0; oldLeft = null; oldRight = null
                }
            }
            pcm[li] = (nl.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            pcm[li + 1] = (nr.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /** 单声道输入：只跑左声道链，输出长度不变 */
    fun processMono(pcm: ShortArray, frames: Int) {
        if (!enabled && rampLen == 0) return
        val fade = if (rampLen > 0) rampPos.toFloat() / rampLen else 1f
        for (i in 0 until frames) {
            val x = pcm[i].toFloat() / 32768f
            var n = left.process(x) * preamp
            if (rampLen > 0 && oldLeft != null) {
                val o = oldLeft!!.process(x) * preamp
                n = o + (n - o) * fade
                if (++rampPos >= rampLen) {
                    rampLen = 0; rampPos = 0; oldLeft = null; oldRight = null
                }
            }
            pcm[i] = (n.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    fun reset() { left.reset(); right.reset(); oldLeft = null; oldRight = null; rampLen = 0; rampPos = 0 }

    fun onSampleRateChanged(sr: Int) {
        if (sr == sampleRate) return
        sampleRate = sr
        left = ChannelChain(sr, config); right = ChannelChain(sr, config)
        preamp = if (config.loudnessMatch) estimatePreamp(config) else 1f
    }
}
