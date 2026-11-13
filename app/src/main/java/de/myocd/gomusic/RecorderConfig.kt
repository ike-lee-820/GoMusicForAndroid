package de.myocd.gomusic

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 录制比例（横屏播放器样式是默认 16:9，也支持竖屏 / 方形 / 4:3） */
enum class RecAspect(val label: String, val rw: Float, val rh: Float) {
    R16_9("16:9", 16f, 9f),
    R9_16("9:16", 9f, 16f),
    R1_1("1:1", 1f, 1f),
    R4_3("4:3", 4f, 3f),
}

/**
 * 画面样式：
 * 经典 = 录制功能默认的「左封面信息 + 右居中歌词」；
 * 圆盘 = 横屏播放器（WidePlayerPane）的歌词显示版本：左封面信息区 +
 * 右侧圆弧旋转歌词（ArcLyrics 的几何）。UI 上仍叫「圆盘」，但不是黑胶唱片。
 */
enum class RecStyle(val label: String) {
    CLASSIC("经典"),
    DISC("圆盘");

    companion object {
        fun of(s: String): RecStyle = values().firstOrNull { it.label == s } ?: CLASSIC
    }
}

/** 短边分辨率档位 */
val REC_RESOLUTIONS = listOf(480, 720, 1080, 1440)

/** 音质：录制时严格按所选档位取文件，不降档、不回退到别的档位 */
val REC_QUALITIES = listOf(
    "standard", "higher", "exhigh", "lossless", "hires",
    "jyeffect", "dolby", "vivid", "jymaster", "sky",
)

@Serializable
data class RecorderConfig(
    /** 比例标签，见 [RecAspect] */
    val aspect: String = "16:9",
    /** 短边像素 */
    val resolution: Int = 1080,
    val fps: Int = 30,
    /** 音质档位：录制严格取这一档的文件 */
    val quality: String = "higher",
    /** 画面样式：经典 / 圆盘 */
    val style: String = "经典",
    /** 歌词字号（sp，按输出高度归一化） */
    val lyricSizeSp: Int = 22,
    /** 左侧信息区 / 封面区宽度占比，0.18–0.60；两种样式共用，默认 0.34 与横屏播放器一致 */
    val infoRatio: Float = 0.34f,
    /** 歌词全体偏移：正数 = 整体提前（快进），负数 = 延迟，单位毫秒 */
    val lyricOffsetMs: Long = 0L,
    /** 自定义 LRC 全文；空 = 用当前歌曲原歌词，点「重置」回到空 */
    val customLrc: String = "",
    /** 原声 / 减弱人声 */
    val vocalMode: String = "原声",
    /** 人声减弱强度 0–1，1 约等于伴奏 */
    val vocalStrength: Float = 0.75f,
    /** 导入字体在私有目录的绝对路径，空 = 系统字体 */
    val fontPath: String = "",
    /** 是否把译文一起录进画面 */
    val showTrans: Boolean = true,
    /** 视频码率 Mbps */
    val bitrateMbps: Int = 8,
)

object RecorderPrefs {
    private const val SP = "gomusic_recorder"
    private val json = Api.json

    fun load(ctx: Context): RecorderConfig = runCatching {
        val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("cfg", "") ?: ""
        if (raw.isBlank()) RecorderConfig() else json.decodeFromString<RecorderConfig>(raw)
    }.getOrDefault(RecorderConfig())

    fun save(ctx: Context, c: RecorderConfig) {
        runCatching {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString("cfg", json.encodeToString(c)).apply()
        }
    }

    /** 输出宽高，强制偶数（H.264 编码器要求） */
    fun dims(c: RecorderConfig): Pair<Int, Int> {
        val a = RecAspect.values().firstOrNull { it.label == c.aspect } ?: RecAspect.R16_9
        val short = c.resolution.coerceIn(360, 2160)
        val long = (short * maxOf(a.rw, a.rh) / minOf(a.rw, a.rh)).toInt()
        val w0 = if (a.rw >= a.rh) long else short
        val h0 = if (a.rw >= a.rh) short else long
        return (w0 / 2 * 2) to (h0 / 2 * 2)
    }
}
