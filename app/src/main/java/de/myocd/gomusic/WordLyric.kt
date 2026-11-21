package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 逐字歌词（YRC）与多语言轨解析。
 *
 * 数据源：GET https://mapi.myocd.de5.net/lyric/new?id=<净易云歌曲 id>
 * 响应里同时带：
 *   yrc      原文逐字（[行起ms,行时长ms](字起ms,字时长ms,flag)字…）
 *   ytlrc    逐字版译文（行级 LRC，时间戳与 yrc 行起点一致）
 *   yromalrc 逐字版罗马音（行级 LRC，时间戳与 yrc 行起点一致）
 *   tlyric   通用译文（行级 LRC，时间戳比 yrc 早 20~30ms）
 *   romalrc  通用罗马音（行级 LRC，同样有偏差）
 *   lrc      普通原文歌词（无 yrc 时回退）
 *   klyric   通常为空，忽略
 *
 * 优先级：逐字版 > 通用版；翻译与罗马音按「600ms 容差就近对齐」贴到 yrc 行，
 * 贴不上的再按行序补齐 —— tlyric 的时间戳与 yrc 一行都不相等，不做容差会全丢。
 *
 * 制作信息（作词/作曲/编曲…）是 yrc 开头的 JSON 行，标 meta=true，
 * 静态展示在歌词顶部，不参与播放位置匹配。
 */
data class WordChar(
    val startMs: Long,
    val durMs: Long,
    /** 可能不止一个字符，例如 "Oh " / "last " / "、"，必须整段当一个着色单元 */
    val text: String,
    val flag: Int = 0,
) {
    val endMs: Long get() = startMs + durMs
}

data class WordLine(
    val startMs: Long,
    val durMs: Long,
    val chars: List<WordChar>,
    val text: String,
    /** 中文译文（ytlrc > tlyric） */
    val trans: String? = null,
    /** 罗马音（yromalrc > romalrc） */
    val roman: String? = null,
    /** true = 制作信息行，不高亮不滚动 */
    val meta: Boolean = false,
)

enum class LyricSource { YRC, LRC, TEXT }

data class WordLyric(
    val lines: List<WordLine>,
    val source: LyricSource,
    val hasWordTiming: Boolean,
    val hasTranslation: Boolean,
    val hasRoman: Boolean,
) {
    /** 当前播放位置落在哪一行；制作信息行跳过 */
    fun activeIndex(posMs: Long): Int {
        var last = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (l.meta) continue
            if (posMs < l.startMs) break
            last = i
            if (posMs < l.startMs + l.durMs.coerceAtLeast(1)) return i
        }
        return last
    }

    /**
     * 退化成行级 LRC 文本。
     *
     * 第 1 期逐字渲染还没接线，播放器里的老 LyricsList 仍吃 LRC 字符串；
     * /lyric/new 的响应里 lrc 字段有时会缺（只有 yrc），这时就用逐字行拼一份，
     * 保证「新接口成功但老界面一片空白」不会发生。
     */
    fun toLrc(): String = lines
        .filter { !it.meta && it.text.isNotEmpty() }
        .joinToString("\n") { l ->
            val mm = l.startMs / 60_000L
            val ss = (l.startMs % 60_000L) / 1000L
            val f = l.startMs % 1000L
            "[${mm.toString().padStart(2, '0')}:${ss.toString().padStart(2, '0')}" +
                ".${f.toString().padStart(3, '0')}]${l.text}"
        }
}

enum class CharPhase { PENDING, ACTIVE, SUNG }

data class CharFrame(val char: WordChar, val phase: CharPhase, val progress: Float)

object WordLyricParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val YRC_LINE = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val WORD = Regex("""\((-?\d+),(\d+),(\d+)\)""")
    private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")

    /** 对齐容差：tlyric / romalrc 的时间戳普遍比 yrc 早 20~30ms，0ms 严格匹配会全丢 */
    private const val ALIGN_TOLERANCE_MS = 600L

    /** 从 /lyric/new 的完整响应构造；调用方负责 HTTP 与缓存 */
    fun fromNewLyric(o: JsonObject): WordLyric {
        fun field(k: String): String =
            (o[k] as? JsonObject)?.get("lyric")?.jsonPrimitive?.content.orEmpty()
        return parse(
            yrc = field("yrc"),
            ytlrc = field("ytlrc"),
            yromalrc = field("yromalrc"),
            tlyric = field("tlyric"),
            romalrc = field("romalrc"),
            lrc = field("lrc"),
        )
    }

    fun parse(
        yrc: String,
        ytlrc: String = "",
        yromalrc: String = "",
        tlyric: String = "",
        romalrc: String = "",
        lrc: String = "",
    ): WordLyric {
        val hasWordTiming = yrc.isNotBlank()
        val raw = if (hasWordTiming) parseYrc(yrc) else parseLrcAsWord(lrc)
        val trans = prefer(ytlrc, tlyric)
        val roman = prefer(yromalrc, romalrc)
        val lines = attach(raw, trans, roman)
        return WordLyric(
            lines = lines,
            source = when {
                hasWordTiming -> LyricSource.YRC
                lrc.isNotBlank() -> LyricSource.LRC
                else -> LyricSource.TEXT
            },
            hasWordTiming = hasWordTiming,
            hasTranslation = lines.any { !it.trans.isNullOrBlank() },
            hasRoman = lines.any { !it.roman.isNullOrBlank() },
        )
    }

    private fun prefer(a: String, b: String): List<Pair<Long, String>> {
        val x = parseLrc(a)
        return if (x.any { it.second.isNotEmpty() }) x else parseLrc(b)
    }

    /**
     * 行级 LRC → [(ms, text)]。空文本行保留：对齐时要靠它占位，
     * 渲染时再过滤掉。一行带多个时间戳时取最后一个标签后的正文。
     */
    fun parseLrc(text: String): List<Pair<Long, String>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<Long, String>>()
        for (raw in text.lines()) {
            var ms: Long? = null
            var end = 0
            for (m in LRC_TAG.findAll(raw)) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val fr = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                ms = mm * 60_000L + ss * 1000L + fr
                end = m.range.last + 1
            }
            if (ms != null) out += ms to raw.substring(end).trim()
        }
        return out
    }

    /** YRC：元数据 JSON 行 + [起,时长](字起,字时长,flag)字… */
    fun parseYrc(text: String): List<WordLine> {
        val out = ArrayList<WordLine>()
        for (raw in text.lines()) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("{")) {
                runCatching {
                    val o = json.parseToJsonElement(s).jsonObject
                    val t = o["t"]?.jsonPrimitive?.long ?: 0L
                    val arr = o["c"] as? JsonArray ?: JsonArray(emptyList())
                    val body = arr.joinToString("") { e ->
                        e.jsonObject["tx"]?.jsonPrimitive?.content.orEmpty()
                    }
                    if (body.isNotEmpty()) {
                        out += WordLine(
                            startMs = t, durMs = 0,
                            chars = listOf(WordChar(t, 0, body)),
                            text = body, meta = true,
                        )
                    }
                }
                continue
            }
            val m = YRC_LINE.matchEntire(s) ?: continue
            val start = m.groupValues[1].toLong()
            val dur = m.groupValues[2].toLong()
            val rest = m.groupValues[3]
            val hits = WORD.findAll(rest).toList()
            val chars = ArrayList<WordChar>(hits.size)
            if (hits.isEmpty()) {
                if (rest.isNotEmpty()) chars += WordChar(start, dur, rest)
            } else {
                hits.forEachIndexed { i, h ->
                    val stop = if (i + 1 < hits.size) hits[i + 1].range.first else rest.length
                    val body = rest.substring(h.range.last + 1, stop)
                    // 括号后到下一个括号之间的全部文本都属于这个字组，
                    // 不能按「一个字符」切：Can / you / last / Oh 都带尾空格。
                    if (body.isNotEmpty()) {
                        chars += WordChar(
                            h.groupValues[1].toLong(),
                            h.groupValues[2].toLong(),
                            body,
                            h.groupValues[3].toInt(),
                        )
                    }
                }
            }
            if (chars.isEmpty()) continue
            out += WordLine(start, dur, chars, chars.joinToString("") { it.text })
        }
        return out
    }

    /** 没有 yrc 时：普通 LRC 退化成「整行一个字组」，仍走同一套渲染，只是整行同时点亮 */
    private fun parseLrcAsWord(lrc: String): List<WordLine> {
        val rows = parseLrc(lrc).filter { it.second.isNotEmpty() }
        return rows.mapIndexed { i, (t, body) ->
            val next = rows.getOrNull(i + 1)?.first ?: (t + 4000L)
            WordLine(t, (next - t).coerceAtLeast(1), listOf(WordChar(t, next - t, body)), body)
        }
    }

    /**
     * 把行级译文/罗马音贴到逐字行：先按时间戳在 600ms 容差内就近取，
     * 再从没用过的行里按序补齐。两条轨各自独立，互不占用。
     */
    private fun attach(
        raw: List<WordLine>,
        trans: List<Pair<Long, String>>,
        roman: List<Pair<Long, String>>,
    ): List<WordLine> {
        val t = alignOne(raw, trans)
        val r = alignOne(raw, roman)
        return raw.mapIndexed { i, l ->
            l.copy(trans = t[i], roman = r[i])
        }
    }

    private fun alignOne(
        raw: List<WordLine>,
        track: List<Pair<Long, String>>,
    ): Map<Int, String> {
        val used = HashSet<Int>()
        val out = HashMap<Int, String>()
        for (i in raw.indices) {
            val l = raw[i]
            if (l.meta || l.text.isEmpty()) continue
            var best = ALIGN_TOLERANCE_MS + 1
            var bestJ = -1
            for (j in track.indices) {
                if (j in used || track[j].second.isEmpty()) continue
                val d = kotlin.math.abs(track[j].first - l.startMs)
                if (d < best) { best = d; bestJ = j }
            }
            if (bestJ >= 0 && best <= ALIGN_TOLERANCE_MS) {
                used += bestJ
                out[i] = track[bestJ].second
            }
        }
        val rest = track.filterIndexed { j, _ -> j !in used }.map { it.second }.filter { it.isNotEmpty() }
        var k = 0
        for (i in raw.indices) {
            val l = raw[i]
            if (l.meta || l.text.isEmpty() || out.containsKey(i)) continue
            if (k < rest.size) out[i] = rest[k++]
        }
        return out
    }

    /**
     * 一行的逐字帧。dur=0 的字（"、" / ", " / "？"）没有自己的时长，
     * 用下一个字的起点或 120ms 兜底，否则那一帧会永远停在 ACTIVE。
     */
    fun frameAt(line: WordLine, posMs: Long): List<CharFrame> {
        return line.chars.mapIndexed { i, c ->
            val nextStart = line.chars.getOrNull(i + 1)?.startMs
            val effEnd = if (c.durMs > 0) c.endMs
            else (nextStart ?: (c.startMs + 120L)).coerceAtLeast(c.startMs + 1)
            when {
                posMs < c.startMs -> CharFrame(c, CharPhase.PENDING, 0f)
                posMs >= effEnd -> CharFrame(c, CharPhase.SUNG, 1f)
                else -> CharFrame(
                    c, CharPhase.ACTIVE,
                    ((posMs - c.startMs).toFloat() / (effEnd - c.startMs)).coerceIn(0f, 1f),
                )
            }
        }
    }
}
