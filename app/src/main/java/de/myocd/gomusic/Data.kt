package de.myocd.gomusic

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 服务器地址 */
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

    /**
     * IPv4 优先：部分 WiFi 下 IPv6 不通、或路由器过早回收连接，
     * 会抛 "Software caused connection abort"，优先解析 IPv4 可规避。
     */
    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                throw UnknownHostException("$hostname: ${e.message}")
            }
            if (all.isEmpty()) throw UnknownHostException(hostname)
            return all.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(ipv4First)
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

    /** 网络抖动（尤其 WiFi 刚连上、IPv6 不通）时重试，避免一上来就报加载失败 */
    private fun get(path: String, tries: Int = 3): String {
        var last: Exception? = null
        repeat(tries) { i ->
            try {
                // Connection: close —— 禁用 keep-alive 复用，规避被中途断开的 "connection abort"
                val req = Request.Builder().url(BASE + path).header("Connection", "close").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    return r.body!!.string()
                }
            } catch (e: Exception) {
                last = e
                if (i < tries - 1) {
                    runCatching { Thread.sleep(500L * (i + 1)) }
                }
            }
        }
        throw last ?: IOException("请求失败")
    }

    /** 下载到输出流（音频 / 封面） */
    fun fetch(url: String, out: OutputStream) {
        val req = Request.Builder().url(url).header("Connection", "close").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("空响应")
            body.byteStream().use { it.copyTo(out) }
        }
    }

    fun bytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).header("Connection", "close").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    } catch (e: Exception) {
        null
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

/** 图片模型：data: URI 解码为字节，本地文件/Uri 直接加载，其余补全为绝对地址 */
fun imageModel(u: String?): Any? {
    if (u.isNullOrBlank()) return null
    if (u.startsWith("data:")) {
        val i = u.indexOf("base64,")
        if (i < 0) return null
        return runCatching { Base64.decode(u.substring(i + 7), Base64.DEFAULT) }.getOrNull()
    }
    if (u.startsWith("content://") || u.startsWith("file://")) return android.net.Uri.parse(u)
    if (u.startsWith("/")) return java.io.File(u)
    return Api.abs(u)
}
