package de.myocd.gomusic

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dispatcher
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
    /** 曲库来源：[SRC_LOCAL] 本站 / [SRC_NETEASE] 网易云 */
    val source: String = SRC_LOCAL,
    /** 网易云歌曲 id（source = [SRC_NETEASE] 时有效） */
    val nmId: Long = 0,
    /** 网易云专辑 id（点封面时直接开网易云专辑页，不再到本地库查） */
    val nmAlbumId: Long = 0,
    /** 网易云歌手 id（点歌手时直接开网易云歌手页） */
    val nmArtistId: Long = 0,
    /**
     * MV id（0 = 没有 MV）。
     *
     * 网易云歌曲的 mv 字段就是 MV 的 id；为 0 表示这首歌没有 MV，
     * 播放器功能栏据此决定是否显示 MV 按钮。
     * 本站曲库没有 MV 数据源，默认 0。
     */
    val mvId: Long = 0,
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

/**
 * 一行歌词（逻辑行）：原句 + 可选的译文。
 *
 * 歌词有两种来源译文：
 * 1. 自带翻译 —— 原文与译文两句时间完全相同，第二句就是第一句的译文；
 * 2. 接口翻译 —— [Translate] 整篇翻出来的，按逻辑行顺序对齐。
 *
 * 渲染时 [text] 会跟着播放高亮，[trans] 永远不高亮；
 * 点 [trans] 与点 [text] 行为一致，都是跳到 [timeMs]。
 */
data class LyricRow(
    /** 原句在原始歌词行里的下标：播放位置是按原始行算的，要靠它换算 */
    val index: Int,
    /** 原句时间，< 0 表示这行没有时间轴 */
    val timeMs: Long,
    /** 原句 */
    val text: String,
    /** 译文，null = 没有译文 */
    val trans: String? = null,
)

/**
 * 非中文书写系统的字符：拉丁、西里尔、日文假名、韩文、希腊、阿拉伯、泰文。
 *
 * 用来判断「这首歌的歌词是不是外文的」——只有外文歌词才需要翻译按钮，
 * 中文歌不需要。CJK 统一表意文字（含汉字与日文汉字）不算。
 */
private val NON_CHINESE_RE = Regex(
    "[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF"
        + "\u3040-\u30FF\u31F0-\u31FF\uAC00-\uD7AF"
        + "\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F]",
)

/** 这句歌词是否含有非中文部分 */
fun hasNonChinese(s: String): Boolean = NON_CHINESE_RE.containsMatchIn(s)

/**
 * 作词 / 作曲 / 编曲 / 制作这类标注行里的字（简体与繁体各四个）。
 *
 * 这些行几乎都带英文人名（「作曲 : John Doe」「编曲 : XXX」），
 * 会把整篇误判成外文歌词、白白多出一个翻译按钮。
 * 判断「要不要翻译」时先把它们排除掉。
 */
private val CREDIT_CHARS = charArrayOf('词', '詞', '曲', '麯', '编', '編', '制', '製')

/** 是否是「词 / 曲 / 编 / 制」这类标注行 */
private fun isCreditLine(s: String): Boolean = s.any { it in CREDIT_CHARS }

/**
 * 歌词是否含非中文部分（含则提供翻译按钮）。
 *
 * 排除标注行，避免英文人名触发；全中文的歌词自然不会命中，不翻译。
 */
fun lyricNeedsTranslation(rows: List<LyricRow>): Boolean =
    rows.any { !isCreditLine(it.text) && hasNonChinese(it.text) }

/**
 * 把原始歌词行整理成逻辑行。
 *
 * 自带翻译的歌词里，原句和译文两句的时间完全相同：
 * 第一句视为原句（会高亮），第二句视为它的译文（不高亮），两行合成一个逻辑行。
 *
 * 时间 < 0 的纯文本歌词不参与配对 —— 那种情况每行时间都一样，
 * 整篇会被误判成「一行原文一行译文」。
 *
 * [trans] 是接口翻译的结果，按逻辑行顺序对齐。
 */
fun buildLyricRows(lines: List<LrcLine>, trans: List<String>? = null): List<LyricRow> {
    val out = ArrayList<LyricRow>(lines.size)
    var i = 0
    while (i < lines.size) {
        val a = lines[i]
        val b = lines.getOrNull(i + 1)
        if (a.timeMs >= 0 && b != null && b.timeMs == a.timeMs) {
            out.add(LyricRow(i, a.timeMs, a.text, b.text.trim().takeIf { it.isNotEmpty() }))
            i += 2
        } else {
            out.add(LyricRow(i, a.timeMs, a.text, null))
            i += 1
        }
    }
    if (trans == null) return out
    return out.mapIndexed { k, r ->
        if (r.trans != null) r
        else r.copy(trans = trans.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() })
    }
}

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

    /**
     * 共享客户端。
     *
     * 超时：连接 10s（连不上早放弃）、读取 30s（接口慢时给足时间）、
     * 单次调用 60s 上限。之前收到 8/15/25s 太激进，正常请求也会被判超时
     * 而重试，反而更慢 —— 现在是「快失败」和「别误判」之间的折中。
     * 并发从默认 5 提到 16：批量取地址/探测音质时不再排队等连接。
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(ipv4First)
        .apply {
            dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
            )
        }
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
    // 高规格音质
    "hires" -> "Hi-Res"
    "jyeffect" -> "高清臻音"
    "dolby" -> "杜比全景声"
    "vivid" -> "臻音全景声"
    "jymaster" -> "超清母带"
    "sky" -> "沉浸环绕声"
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
