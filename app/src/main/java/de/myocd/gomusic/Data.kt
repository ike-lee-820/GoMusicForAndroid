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

    /**
     * 连通性探测用：短超时 + 不重试。
     * 用来快速判断「服务器到底连不连得上」，失败立刻进离线模式，不让用户干等。
     */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
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
    private fun get(path: String, tries: Int = 3, http: OkHttpClient = client): String {
        var last: Exception? = null
        repeat(tries) { i ->
            try {
                // Connection: close —— 禁用 keep-alive 复用，规避被中途断开的 "connection abort"
                val req = Request.Builder().url(BASE + path).header("Connection", "close").build()
                http.newCall(req).execute().use { r ->
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

    /** 下载到输出流；[onProgress] 回调 (已读字节, 总字节，-1 表示未知) */
    fun fetch(url: String, out: OutputStream, onProgress: ((Long, Long) -> Unit)? = null) {
        val req = Request.Builder().url(url).header("Connection", "close").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                if (onProgress == null) {
                    input.copyTo(out)
                    return
                }
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (read - last >= 128 * 1024) {
                        last = read
                        onProgress(read, total)
                    }
                }
                onProgress(read, total)
            }
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

    /**
     * [quick] = true 时用短超时客户端、只请求一次：
     * 一次失败就立刻判定为离线，不再重试等待。
     */
    fun songs(quick: Boolean = true): List<Song> = json.decodeFromString<List<Song>>(
        if (quick) get("/api/songs", tries = 1, http = probeClient)
        else get("/api/songs")
    )

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

/**
 * 文档 6：仓库内「专辑名/cover.webp」即该专辑封面，通过
 * `/api/proxy/raw/:repo/:path?o=<owner>` 代理访问。
 * 列表里 coverUrl 缺失时用这个兜底。
 */
fun guessCoverUrl(s: Song): String? {
    val repo = s.repoName?.takeIf { it.isNotBlank() } ?: return null
    val owner = s.owner?.takeIf { it.isNotBlank() } ?: return null
    val album = s.album.takeIf { it.isNotBlank() } ?: return null
    val encAlbum = java.net.URLEncoder.encode(album, "UTF-8").replace("+", "%20")
    val encOwner = java.net.URLEncoder.encode(owner, "UTF-8")
    return "$BASE/api/proxy/raw/$repo/$encAlbum/cover.webp?o=$encOwner"
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
    // 只有确实存在的本地绝对路径才当文件；
    // 服务端返回的封面常是 /api/... 这类根相对路径，必须拼上 BASE，不能当成本地文件
    if (u.startsWith("/")) {
        val f = java.io.File(u)
        if (f.exists()) return f
    }
    return Api.abs(u)
}
