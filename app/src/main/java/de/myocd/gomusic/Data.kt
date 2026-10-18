package de.myocd.gomusic

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val BASE = "https://gomusic.myocd.de5.net"

@Serializable
data class Song(
    val ssid: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
    val repoName: String? = null,
    val owner: String? = null,
    val locked: Boolean? = null,
)

@Serializable
data class ArtistInfo(val image: String? = null, val bio: String? = null)

data class SongDetail(
    val ssid: String,
    val lyrics: String,
    val cover: String?,
    /** 音质 -> 播放地址数组（多段文件按顺序） */
    val audio: Map<String, List<String>>,
)

data class LrcLine(val timeMs: Long, val text: String)

object Api {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val detailCache = ConcurrentHashMap<String, SongDetail>()

    /** 音质从高到低的已知排序，未知键排在最后 */
    private val ORDER = listOf("normal", "high", "low", "extreme", "source", "lossless")
    fun rank(k: String): Int = ORDER.indexOf(k).let { if (it < 0) 99 else it }

    fun abs(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http") || u.startsWith("data:") -> u
        u.startsWith("/") -> BASE + u
        else -> "$BASE/$u"
    }

    private fun get(path: String): String {
        val req = Request.Builder().url(BASE + path).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.string()
        }
    }

    fun songs(): List<Song> = json.decodeFromString<List<Song>>(get("/api/songs"))

    fun artists(): Map<String, ArtistInfo> =
        json.decodeFromString<Map<String, ArtistInfo>>(get("/api/artists"))

    fun lyrics(ssid: String): String = get("/api/lyrics/$ssid")

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun urls(e: JsonElement): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { abs(it.str()) }
        is JsonPrimitive -> listOfNotNull(abs(e.str()))
        else -> emptyList()
    }

    fun detail(ssid: String): SongDetail {
        detailCache[ssid]?.let { return it }
        val obj = json.parseToJsonElement(get("/api/song/$ssid")) as JsonObject
        val audio = linkedMapOf<String, List<String>>()
        when (val a = obj["audio"]) {
            is JsonObject -> a.forEach { (k, v) ->
                val l = urls(v)
                if (l.isNotEmpty()) audio[k] = l
            }
            is JsonArray -> urls(a).takeIf { it.isNotEmpty() }?.let { audio["normal"] = it }
            else -> {}
        }
        val d = SongDetail(
            ssid = ssid,
            lyrics = obj["lyrics"].str() ?: obj["lrc"].str() ?: "",
            cover = abs(obj["cover"].str() ?: obj["coverUrl"].str()),
            audio = audio,
        )
        detailCache[ssid] = d
        return d
    }

    /** 选择最接近所需音质的播放地址（多段文件当前只取第一段） */
    fun audioUrl(ssid: String, quality: String): String {
        val d = detail(ssid)
        if (d.audio.isEmpty()) throw IOException("没有可用音频")
        val key = d.audio.keys.minByOrNull { kotlin.math.abs(rank(it) - rank(quality)) }!!
        return d.audio[key]!!.first()
    }
}

/** 标签中文名，未知键原样显示 */
fun qualityLabel(k: String): String = when (k) {
    "lossless" -> "无损"
    "high" -> "高品质"
    "normal" -> "标准"
    "low", "saver", "economy", "lite" -> "省流"
    else -> k
}

fun parseLrc(t: String): List<LrcLine> {
    val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
    val out = mutableListOf<LrcLine>()
    for (line in t.lines()) {
        val ms = re.findAll(line).toList()
        if (ms.isEmpty()) continue
        val text = line.replace(re, "").trim()
        for (m in ms) {
            val mm = m.groupValues[1].toLong()
            val ss = m.groupValues[2].toLong()
            val fr = m.groupValues[3]
            val f = if (fr.isEmpty()) 0L else fr.padEnd(3, '0').take(3).toLong()
            out += LrcLine(mm * 60000 + ss * 1000 + f, text)
        }
    }
    return out.filter { it.text.isNotEmpty() }.sortedBy { it.timeMs }
}

/** 有时间戳按 LRC 解析，否则当作纯文本逐行显示（timeMs = -1） */
fun lyricLines(t: String): List<LrcLine> {
    val p = parseLrc(t)
    if (p.isNotEmpty()) return p
    return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LrcLine(-1, it) }
}

/** 图片模型：data: URI 解码为字节，其余补全为绝对地址 */
fun imageModel(u: String?): Any? {
    if (u.isNullOrBlank()) return null
    if (u.startsWith("data:")) {
        val i = u.indexOf("base64,")
        if (i < 0) return null
        return runCatching { Base64.decode(u.substring(i + 7), Base64.DEFAULT) }.getOrNull()
    }
    return Api.abs(u)
}
