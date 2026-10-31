package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.OkHttpClient

/** 曲库来源：本站 / 网易云 */
const val SRC_LOCAL = "local"
const val SRC_NETEASE = "netease"

/** 网易云歌曲在本地索引里的 ssid 前缀 */
const val NM_PREFIX = "nm"

/** 网易云 API 站 */
const val NM_BASE = "https://mapi.myocd.de5.net"

fun isNetease(ssid: String?): Boolean = ssid?.startsWith(NM_PREFIX) == true
fun neteaseIdOf(ssid: String?): Long? =
    ssid?.takeIf { it.startsWith(NM_PREFIX) }?.removePrefix(NM_PREFIX)?.toLongOrNull()

/** 二维码轮询状态 */
data class QrStatus(val code: Int, val cookie: String? = null, val message: String? = null)

/**
 * 登录 / 注册的结果。
 *
 * 之前这类接口只返回 cookie 或 null，失败原因全丢了 —— 用户只能看到一句
 * 「登录失败」，到底是验证码错了、发得太频繁还是服务端不支持，完全看不出来。
 * 现在把服务端的 code 与 message 一起带回来。
 */
data class LoginResult(
    val cookie: String? = null,
    val code: Int = -1,
    val message: String? = null,
) {
    val ok: Boolean get() = !cookie.isNullOrBlank()
}

/** 网易云可选音质档位（从低到高） */
val NM_LEVELS = listOf(
    "standard", "higher", "exhigh", "lossless",
    "hires", "jyeffect", "dolby", "vivid", "jymaster", "sky",
)

/** 网易云音质档位中文名 */
fun nmLevelLabel(lv: String): String = when (lv) {
    "standard" -> "标准 128k"
    "higher" -> "较高 192k"
    "exhigh" -> "极高 320k"
    "lossless" -> "无损 FLAC"
    "hires" -> "Hi-Res"
    "jyeffect" -> "高清臻音"
    "dolby" -> "杜比全景声"
    "vivid" -> "臻音全景声"
    "jymaster" -> "超清母带"
    "sky" -> "沉浸环绕声"
    else -> lv
}

/** 本站音质键 -> 网易云默认档位 */
fun nmLevelOfQuality(quality: String): String = when (quality) {
    "lossless" -> "lossless"
    "high" -> "exhigh"
    "normal" -> "higher"
    else -> "standard"
}

object Netease {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 登录后拿到的 cookie，由 Prefs 注入；所有请求自动带上 */
    @Volatile
    var cookie: String = ""
        set(v) {
            field = v
            if (v.isNotBlank()) NmLog.note("已设置 cookie，包含键：${cookieKeys(v)}（长度 ${v.length}）")
        }

    /** 日志里只记 cookie 的键名，不记值，避免泄露登录凭证 */
    private fun cookieKeys(c: String): String =
        c.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotBlank() }.joinToString(",")

    /** 音质：本站质量键 -> 网易云 level（从高到低，逐个尝试直到拿到地址） */
    private fun levels(quality: String): List<String> = when (quality) {
        "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
        "high" -> listOf("exhigh", "higher", "standard")
        "normal" -> listOf("higher", "standard")
        else -> listOf("standard")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * cookie 必须用百分号编码（空格 -> %20），不能用 URLEncoder。
     *
     * URLEncoder 会把空格编成 "+"，而服务端用 decodeURIComponent 解析时
     * "+" 不会被还原成空格，cookie 里的 "; " 会变成 ";+"，
     * 导致整串 cookie 解析失败 —— 表现就是「登录了却取不到播放地址」。
     */
    private fun encCookie(s: String): String {
        val out = StringBuilder(s.length + 32)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val keep = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '!'.code || c == '~'.code || c == '*'.code ||
                c == '('.code || c == ')'.code
            if (keep) out.append(c.toChar())
            else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    /**
     * 发一次 GET。
     * 完整 URL 与完整响应都会写进日志文件（见 [NmLog]）。
     * 失败时记日志并返回空对象，绝不让异常冒到调用方造成闪退。
     */
    private fun get(path: String, vararg params: Pair<String, String>): JsonObject {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        // unblock=true：启用解灰（变灰歌曲匹配第三方音源）。
        // 注意：/song/url/v1 不支持自定义解灰源（会返回 msg 警告并忽略），
        // 这种情况要走 /song/url/match，见 audioUrl 的兜底。
        sb.append("unblock=true&")
        if (cookie.isNotBlank()) sb.append("cookie=").append(encCookie(cookie)).append('&')
        sb.append("timestamp=").append(System.currentTimeMillis())
        val url = sb.toString()
        NmLog.req(url)
        var last: Exception? = null
        // 失败自动重试：网络抖动 / 服务端偶发错误时多试几次，间隔递增
        val delays = longArrayOf(400, 900, 1600)
        for (i in 0..delays.size) {
            try {
                val req = Request.Builder().url(url).header("Connection", "close").build()
                Api.client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    NmLog.res(url, r.code, t)
                    if (!r.isSuccessful || t.isBlank()) {
                        throw IOException("HTTP ${r.code} ${if (t.isBlank()) "(空响应)" else ""}")
                    }
                    val el = runCatching { json.parseToJsonElement(t) }.getOrNull()
                    if (el is JsonObject) return el
                    throw IOException("响应不是 JSON 对象")
                }
            } catch (e: Exception) {
                last = e
                NmLog.note("请求第 ${i + 1} 次失败：$path -> ${e.message}")
                NmLog.err(url, e)
                if (i < delays.size) runCatching { Thread.sleep(delays[i]) }
            }
        }
        NmLog.note("请求最终失败：$path -> ${last?.message}")
        return JsonObject(emptyMap())
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive
            if (v != null && v.isString && v.content.isNotBlank()) return v.content
        }
        return null
    }

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    /**
     * 递归找 cookie：不同接口返回的位置不一样（顶层 cookie / data.cookie / cookies），
     * 找不到就返回空，避免漏掉登录态。
     */
    private fun findCookie(o: JsonObject): String? {
        val direct = listOf("cookie", "cookies").firstNotNullOfOrNull { k ->
            (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it.contains("=") }
        }
        if (direct != null) return direct
        for ((_, v) in o) {
            if (v is JsonObject) {
                val r = findCookie(v)
                if (r != null) return r
            }
        }
        return null
    }

    private fun JsonObject.code(): Int =
        (this["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    /** 封面缩略图：网易云图片支持 ?param=宽y高 */
    private fun pic(u: String?, size: Int = 300): String? {
        if (u.isNullOrBlank()) return null
        if (!u.startsWith("http")) return null
        return if (u.contains("?")) u else "$u?param=${size}y$size"
    }

    /** 从 al / artists / ar 里取歌手名 */
    private fun artistsOf(o: JsonObject): String {
        (o["ar"] as? JsonElement)?.jsonArray?.let { arr ->
            val s = arr.mapNotNull { (it as? JsonObject)?.str("name") }.joinToString("/")
            if (s.isNotBlank()) return s
        }
        (o["artists"] as? JsonElement)?.jsonArray?.let { arr ->
            val s = arr.mapNotNull { (it as? JsonObject)?.str("name") }.joinToString("/")
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun albumNameOf(o: JsonObject): String =
        o.obj("al")?.str("name") ?: o.obj("album")?.str("name") ?: ""

    private fun coverOf(o: JsonObject): String? =
        pic(o.obj("al")?.str("picUrl"))
            ?: pic(o.obj("album")?.str("picUrl", "blurPicUrl", "pic"))
            ?: pic(o.str("picUrl", "img1v1Url", "cover"))

    private fun idOf(o: JsonObject?, key: String = "id"): Long =
        ((o?.get(key) as? JsonPrimitive)?.content?.toLongOrNull())
            ?: ((o?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong())
            ?: 0L

    private fun toSong(o: JsonObject): Song? {
        val id = idOf(o)
        if (id <= 0L) return null
        val al = o.obj("al") ?: o.obj("album")
        val ar = (o["ar"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
            ?: (o["artists"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
        return Song(
            ssid = "$NM_PREFIX$id",
            title = o.str("name") ?: "",
            artist = artistsOf(o),
            album = albumNameOf(o),
            coverUrl = coverOf(o),
            source = SRC_NETEASE,
            nmId = id,
            nmAlbumId = idOf(al),
            nmArtistId = idOf(ar),
            // mv 字段就是 MV 的 id，0 表示没有 MV
            mvId = idOf(o, "mv"),
        )
    }

    // ================= 搜索 =================

    /** type: 1 单曲 10 专辑 100 歌手；offset 用于翻页 */
    private fun rawSearch(keywords: String, type: Int, limit: Int, offset: Int = 0): JsonObject =
        get(
            "/search",
            "keywords" to keywords,
            "type" to type.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        )

    /** 搜索结果的数组：兼容 result.xxx 与顶层 xxx 两种结构 */
    private fun resultArray(root: JsonObject, key: String): List<JsonObject> {
        val fromResult = (root.obj("result")?.get(key) as? JsonElement)?.jsonArray
        val direct = (root[key] as? JsonElement)?.jsonArray
        val arr = fromResult ?: direct ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    /**
     * 用 /song/detail 按 id 补全信息。
     * 搜索接口返回的 al.picUrl 经常是空的，详情接口能拿到完整封面 / 专辑 / 歌手。
     */
    fun enrich(songs: List<Song>): List<Song> {
        val ids = songs.mapNotNull { it.nmId.takeIf { id -> id > 0 } }.distinct()
        if (ids.isEmpty()) return songs
        val detail = runCatching { songDetail(ids) }.getOrDefault(emptyMap())
        if (detail.isEmpty()) {
            NmLog.note("song/detail 无返回，沿用搜索结果")
            return songs
        }
        return songs.map { s ->
            val d = s.nmId.takeIf { it > 0 }?.let { detail[it] }
            if (d == null) {
                s
            } else {
                s.copy(
                    title = d.title.takeIf { it.isNotBlank() } ?: s.title,
                    artist = d.artist.takeIf { it.isNotBlank() } ?: s.artist,
                    album = d.album.takeIf { it.isNotBlank() } ?: s.album,
                    coverUrl = d.coverUrl ?: s.coverUrl,
                    // 搜索结果里的 mv 字段有时是 0，详情里能拿到真的。
                    // 补上才能「第一次放这首歌 MV 按钮就在」
                    mvId = s.mvId.takeIf { it > 0L } ?: d.mvId,
                )
            }
        }
    }

    /** /song/detail?ids=1,2,3 -> id -> 详情 */
    private fun songDetail(ids: List<Long>): Map<Long, Song> {
        val root = get("/song/detail", "ids" to ids.joinToString(","))
        val arr = resultArray(root, "songs")
        if (arr.isEmpty()) NmLog.note("song/detail 没有 songs 字段")
        return arr.mapNotNull { o ->
            val s = toSong(o) ?: return@mapNotNull null
            val id = s.nmId
            if (id <= 0) null else id to s
        }.toMap()
    }

    /** 单曲详情：播放前按 id 取完整封面 / 专辑 / 歌手 */
    fun detailOf(id: Long): Song? {
        val m = runCatching { songDetail(listOf(id)) }.getOrDefault(emptyMap())
        return m[id]
    }

    fun searchSongs(keywords: String, limit: Int = 20, offset: Int = 0): List<Song> {
        val root = rawSearch(keywords, 1, limit, offset)
        val arr = resultArray(root, "songs")
        if (arr.isEmpty()) {
            NmLog.note("搜索单曲无结果：keywords=$keywords offset=$offset")
            return emptyList()
        }
        val list = arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } }
        NmLog.note("搜索单曲 offset=$offset 得到 ${list.size} 首，开始用 song/detail 补全信息")
        return enrich(list)
    }

    /**
     * 专辑搜索。
     * type=10 经常返回空（或需要登录），这时用单曲结果里的专辑兜底，
     * 保证「专辑」标签在网易云曲库下也能出内容。
     */
    fun searchAlbums(keywords: String, limit: Int = 10, offset: Int = 0): List<Group> {
        val out = LinkedHashMap<String, Group>()
        val arr = resultArray(rawSearch(keywords, 10, limit, offset), "albums")
        NmLog.note("搜索专辑 type=10 返回 ${arr.size} 条")
        arr.forEach { o ->
            val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
            val name = o.str("name").orEmpty()
            if (name.isBlank()) return@forEach
            out["al$id"] = Group(
                title = name,
                subtitle = o.obj("artist")?.str("name")
                    ?: o.obj("ar")?.str("name") ?: "",
                cover = pic(o.str("picUrl", "blurPicUrl", "pic")),
                songs = emptyList(),
                album = name,
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "album",
            )
        }
        if (out.isEmpty()) {
            // 兜底：拿单曲结果里出现的专辑聚合成专辑卡片
            NmLog.note("专辑搜索为空，改用单曲结果聚合专辑")
            resultArray(rawSearch(keywords, 1, 30), "songs").forEach { o ->
                val al = o.obj("al") ?: o.obj("album") ?: return@forEach
                val id = (al["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
                val name = al.str("name").orEmpty()
                if (name.isBlank()) return@forEach
                out["al$id"] = Group(
                    title = name,
                    subtitle = artistsOf(o),
                    cover = pic(al.str("picUrl", "blurPicUrl", "pic")),
                    songs = emptyList(),
                    album = name,
                    source = SRC_NETEASE,
                    nmId = id,
                    nmKind = "album",
                )
            }
            NmLog.note("聚合得到 ${out.size} 个专辑")
        }
        return out.values.toList()
    }

    /**
     * 歌手搜索。type=100 返回空时，用单曲结果里的歌手聚合兜底，
     * 保证「歌手」标签在网易云曲库下一定出内容。
     */
    fun searchArtists(keywords: String, limit: Int = 10, offset: Int = 0): List<Group> {
        val arr = resultArray(rawSearch(keywords, 100, limit, offset), "artists")
        NmLog.note("搜索歌手 type=100 返回 ${arr.size} 条")
        val out = LinkedHashMap<Long, Group>()
        arr.forEach { o ->
            val id = idOf(o)
            val name = o.str("name").orEmpty()
            if (id <= 0L || name.isBlank()) return@forEach
            out[id] = Group(
                title = name,
                subtitle = "网易云歌手",
                cover = pic(o.str("picUrl", "img1v1Url")),
                songs = emptyList(),
                artist = name,
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "artist",
            )
        }
        if (out.isEmpty()) {
            NmLog.note("歌手搜索为空，改用单曲结果聚合歌手")
            resultArray(rawSearch(keywords, 1, 30), "songs").forEach { o ->
                val ar = (o["ar"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
                    ?: (o["artists"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
                    ?: return@forEach
                val id = idOf(ar)
                val name = ar.str("name").orEmpty()
                if (id <= 0L || name.isBlank()) return@forEach
                if (out.containsKey(id)) return@forEach
                out[id] = Group(
                    title = name,
                    subtitle = "网易云歌手",
                    cover = pic(ar.str("picUrl", "img1v1Url")),
                    songs = emptyList(),
                    artist = name,
                    source = SRC_NETEASE,
                    nmId = id,
                    nmKind = "artist",
                )
            }
            NmLog.note("聚合得到 ${out.size} 个歌手")
        }
        return out.values.toList()
    }

    // ================= 播放 / 歌词 / 详情 =================

    /**
     * 取播放地址。
     *
     * 只用新版接口 /song/url/v1（level 音质档位），不再走已废弃的 /song/url。
     * 每个音质档位依次尝试：
     * 1. v1（已带 unblock=true，解灰匹配第三方音源）
     * 2. v1 + randomCNIP（随机国内 IP，绕开网易对境外 IP 的 460 cheating）
     * 3. v1 + realIP（显式指定国内 IP）
     * 都拿不到就降一档音质继续，直到 standard。
     */
    /**
     * 探测这首歌实际有哪些音质：每个档位都试一次，能拿到地址的才返回。
     *
     * 每个档位只走一次 v1 基础请求（不再叠加 randomCNIP / realIP），
     * 避免探测一次就发几十个请求拖慢播放。
     */
    fun availableLevels(id: Long): List<String> {
        val out = LinkedHashSet<String>()
        for (lv in NM_LEVELS) {
            val r = runCatching {
                get("/song/url/v1", "id" to id.toString(), "level" to lv)
            }.getOrNull() ?: continue
            if (r.code() != 200) continue
            val first = (r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
            val u = first?.str("url")
                ?: first?.str("freeTrialInfo", "url")?.takeIf { it.startsWith("http") }
            if (!u.isNullOrBlank()) out.add(lv)
        }
        NmLog.note("音质探测 id=$id -> ${out.toList()}")
        return out.toList()
    }

    /**
     * 音源地址缓存。
     *
     * 取一次地址最坏要串行试 9 个音质档位 × 3 种参数，二十几次请求，
     * 而这步是在 ExoPlayer 的准备线程上同步做的，等于点一首歌要干等
     * 二十多个 HTTP 往返才开始出声——这就是「加载极慢」的原因。
     * 缓存后切歌回来、重播都是秒开。地址有时效，过期时间放短一点。
     */
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** 缓存有效期：网易云 CDN 地址一般能撑几十分钟，留足余量取 10 分钟 */
    private const val URL_TTL_MS = 10 * 60 * 1000L

    private fun cacheKey(id: Long, quality: String, level: String?): String =
        "$id|$quality|${level.orEmpty()}|${cookie.take(8).hashCode()}"

    /** 预取音源地址：在后台提前把地址取好，等真正播放时直接命中缓存 */
    fun prefetchAudioUrl(id: Long, quality: String, level: String? = null) {
        val k = cacheKey(id, quality, level)
        val hit = urlCache[k]
        if (hit != null && System.currentTimeMillis() - hit.second < URL_TTL_MS) return
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { audioUrl(id, quality, level) }
                .onFailure { NmLog.note("预取音源失败：${it.message}") }
        }
    }

    fun audioUrl(id: Long, quality: String, level: String? = null): String? =
        audioWithLevel(id, quality, level)?.first

    /**
     * 取地址并带回**实际拿到的档位**。
     *
     * 下载必须用这个：请求 lossless 时歌曲可能只有 320k，
     * resolveAudioUrl 会一路降档，但返回的只有地址 ——
     * 如果仍按请求的档位记录，下载记录里的音质就是假的
     * （存着「无损」，实际是 320k，离线播放与文件命名都会错）。
     */
    fun audioWithLevel(id: Long, quality: String, level: String? = null): Pair<String, String>? {
        // 命中缓存直接返回，不再走那一长串串行试档位
        val k = cacheKey(id, quality, level)
        urlCache[k]?.let { (u, t) ->
            if (System.currentTimeMillis() - t < URL_TTL_MS) {
                NmLog.note("音源命中缓存 id=$id")
                return u to (level?.takeIf { it.isNotBlank() } ?: quality)
            }
            urlCache.remove(k)
        }
        val hit = resolveAudioUrl(id, quality, level)
        if (hit != null) urlCache[k] = hit.first to System.currentTimeMillis()
        return hit
    }

    /** 取播放地址专用客户端：短超时。取地址要快，失败就换下一种，不能干等 */
    private val urlClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 单次请求：不重试、不 sleep。
     *
     * 通用 get() 会重试 4 次并递增 sleep（约 3 秒），而取播放地址时
     * 一个档位拿不到就该立刻换下一个，重试只会把时间白白耗掉。
     */
    private fun getOnce(path: String, vararg params: Pair<String, String>): JsonObject? {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        sb.append("unblock=true&")
        if (cookie.isNotBlank()) sb.append("cookie=").append(encCookie(cookie)).append('&')
        sb.append("timestamp=").append(System.currentTimeMillis())
        val url = sb.toString()
        return try {
            val req = Request.Builder().url(url).header("Connection", "close").build()
            urlClient.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req(url)
                NmLog.res(url, r.code, t)
                if (!r.isSuccessful || t.isBlank()) null
                else json.parseToJsonElement(t) as? JsonObject
            }
        } catch (e: Exception) {
            NmLog.note("单次请求失败：$path -> ${e.message}")
            null
        }
    }

    private data class UrlHit(val lv: String, val url: String)

    /**
     * 并发探测：把「档位 × 3 种参数变体」的请求一起发出去。
     *
     * 原来是一档一档串行试，最多 15 个档位 × 3 种参数 = 45 次串行请求，
     * 每次还要重试 4 遍，等于点一首歌要干等几十个 HTTP 往返 —— 这就是
     * 「加载极慢」的根因。改成并发后，耗时从「所有请求之和」变成
     * 「最慢那一个请求」，通常一两秒就好。
     */
    private fun probeLevels(id: Long, levels: List<String>): List<UrlHit> {
        if (levels.isEmpty()) return emptyList()
        val jobs = levels.flatMap { lv ->
            listOf(
                lv to listOf("id" to id.toString(), "level" to lv),
                lv to listOf("id" to id.toString(), "level" to lv, "randomCNIP" to "true"),
                lv to listOf("id" to id.toString(), "level" to lv, "realIP" to FALLBACK_CN_IP),
            )
        }
        return runBlocking(Dispatchers.IO) {
            jobs.map { (lv, ps) ->
                async {
                    val r = getOnce("/song/url/v1", *ps.toTypedArray()) ?: return@async null
                    val first = (r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
                    val u = first?.str("url")
                        ?: first?.str("freeTrialInfo", "url")?.takeIf { it.startsWith("http") }
                    if (u.isNullOrBlank()) null else UrlHit(lv, u)
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * 解析播放地址，返回「地址 + 实际档位」。
     * 档位一路降档时以实际拿到的那一档为准（见 [audioWithLevel]）。
     */
    private fun resolveAudioUrl(id: Long, quality: String, level: String?): Pair<String, String>? {
        NmLog.note("开始取播放地址 id=$id quality=$quality level=$level（并发探测）")
        // 候选档位：指定的档位优先，然后按音质设置降档。
        // 不再追加全部 NM_LEVELS：那会多出十来个几乎用不上的档位，
        // levels(quality) 本身已经一路降到 standard
        val cands = (listOfNotNull(level?.takeIf { it.isNotBlank() }) + levels(quality)).distinct()
        val hits = probeLevels(id, cands)
        // 按档位优先级取能用的最高音质
        for (lv in cands) {
            hits.firstOrNull { it.lv == lv }?.let {
                NmLog.note("拿到地址（$lv）：${it.url}")
                return it.url to lv
            }
        }
        // v1 拿不到时走 /song/url/match 解灰匹配（歌曲变灰时 v1 的地址会是空）
        runCatching {
            val r = getOnce("/song/url/match", "id" to id.toString())
            val u = ((r?.get("data") as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject)?.str("url")
            if (!u.isNullOrBlank()) {
                NmLog.note("match 解灰拿到地址：$u")
                // 解灰接口不返回档位，按请求的档位记（能拿到就说明这一档可用）
                return u to (level?.takeIf { it.isNotBlank() } ?: quality)
            }
        }
        // 兜底：拿游客 cookie 再试一次（未登录/登录态失效时网易会返回 301 / 400 需验证）
        NmLog.note("登录态下取不到地址，尝试游客 cookie 再试一次")
        val anon = runCatching { anonCookie() }.getOrNull()
        if (!anon.isNullOrBlank()) {
            val saved = cookie
            cookie = anon
            try {
                probeLevels(id, levels(quality)).firstOrNull()?.let {
                    NmLog.note("游客 cookie 拿到地址（${it.lv}）：${it.url}")
                    return it.url to it.lv
                }
            } finally {
                cookie = saved
            }
        }
        NmLog.note("所有方式都没有拿到播放地址 id=$id")
        return null
    }

    /** 游客 cookie：未登录状态下部分接口会报「需要验证」，用它规避 */
    fun anonCookie(): String? {
        val r = get("/register/anonimous")
        val ck = r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
        NmLog.note("游客 cookie：${if (ck.isNullOrBlank()) "未拿到" else "已拿到"}")
        return ck
    }

    /** realIP 兜底用的国内 IP（网易对境外 IP 会返回 460 cheating） */
    private const val FALLBACK_CN_IP = "116.25.146.177"

    fun lyric(id: Long): String {
        val r = get("/lyric", "id" to id.toString())
        val lrc = r.obj("lrc")?.str("lyric")
        if (lrc.isNullOrBlank()) NmLog.note("歌词为空 id=$id")
        return lrc ?: ""
    }

    /**
     * 专辑 / 歌手的歌曲列表。
     * 专辑走 /album，歌手走 /artist/songs；拿到后再用 song/detail 补全。
     */
    /** 专辑 / 歌手的歌曲列表。歌手支持 offset 分页（滑到底继续加载） */
    fun songsOf(kind: String, id: Long, limit: Int = 30, offset: Int = 0): List<Song> {
        NmLog.note("取 $kind 的歌曲 id=$id offset=$offset")
        val root = if (kind == "album") {
            get("/album", "id" to id.toString())
        } else if (kind == "playlist") {
            // 榜单 / 歌单：tracks 里就是歌曲
            val r = get("/playlist/detail", "id" to id.toString())
            val arr = (r.obj("playlist")?.get("tracks") as? JsonElement)?.jsonArray
                ?: resultArray(r, "tracks")
            if (arr.isEmpty()) NmLog.note("歌单 $id 无歌曲 code=${r.code()}")
            return enrich(arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } })
        } else {
            get(
                "/artist/songs", "id" to id.toString(),
                "limit" to limit.toString(), "offset" to offset.toString(),
            )
        }
        val arr = resultArray(root, "songs")
        if (arr.isEmpty()) {
            // 歌手接口可能返回 hotSongs，专辑也可能换字段
            val alt = resultArray(root, "hotSongs")
            NmLog.note("songs 为空，hotSongs ${alt.size} 条")
            return enrich(alt.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } })
        }
        return enrich(arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } })
    }

    /** 歌手的专辑列表（分页） */
    fun artistAlbums(id: Long, limit: Int = 30, offset: Int = 0): List<Group> {
        val root = get(
            "/artist/album", "id" to id.toString(),
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        val arr = resultArray(root, "hotAlbums")
            .ifEmpty { resultArray(root, "albums") }
        NmLog.note("歌手专辑 id=$id offset=$offset 返回 ${arr.size} 条")
        return arr.mapNotNull { o ->
            val aid = idOf(o)
            val name = o.str("name").orEmpty()
            if (aid <= 0L || name.isBlank()) return@mapNotNull null
            Group(
                title = name,
                subtitle = o.obj("artist")?.str("name") ?: o.obj("ar")?.str("name") ?: "",
                cover = pic(o.str("picUrl", "blurPicUrl", "pic")),
                songs = emptyList(),
                album = name,
                source = SRC_NETEASE,
                nmId = aid,
                nmKind = "album",
            )
        }.distinctBy { it.nmId }
    }

    /** 榜单 /toplist：id + 名称 + 封面，点进去是该榜单的歌曲 */
    fun topList(): List<Group> {
        val root = get("/toplist")
        val arr = resultArray(root, "list")
        NmLog.note("榜单返回 ${arr.size} 个")
        return arr.mapNotNull { o ->
            val id = idOf(o)
            val name = o.str("name").orEmpty()
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            Group(
                title = name,
                subtitle = o.str("updateFrequency") ?: "",
                cover = pic(o.str("coverImgUrl", "picUrl", "cover")),
                songs = emptyList(),
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "playlist",
            )
        }.distinctBy { it.nmId }
    }

    /** 热门歌手 /top/artists，支持 offset 分页 */
    fun topArtists(limit: Int = 30, offset: Int = 0): List<Group> {
        val root = get("/top/artists", "offset" to offset.toString(), "limit" to limit.toString())
        val arr = resultArray(root, "artists")
            .ifEmpty { resultArray(root, "list") }
        NmLog.note("热门歌手 offset=$offset 返回 ${arr.size} 条")
        return arr.mapNotNull { o ->
            val id = idOf(o)
            val name = o.str("name").orEmpty()
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            Group(
                title = name,
                subtitle = "网易云歌手",
                cover = pic(o.str("picUrl", "img1v1Url", "pic")),
                songs = emptyList(),
                artist = name,
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "artist",
            )
        }.distinctBy { it.nmId }
    }

    /** 最新专辑（新碟上架） /album/newest */
    fun newestAlbums(limit: Int = 30): List<Group> {
        val root = get("/album/newest")
        val arr = resultArray(root, "albums")
        NmLog.note("最新专辑返回 ${arr.size} 条")
        return arr.take(limit).mapNotNull { o ->
            val id = idOf(o)
            val name = o.str("name").orEmpty()
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            val ar = o.obj("artist") ?: o.obj("ar")
            Group(
                title = name,
                subtitle = ar?.str("name") ?: "",
                cover = pic(o.str("picUrl", "blurPicUrl", "pic")),
                songs = emptyList(),
                artist = ar?.str("name") ?: "",
                album = name,
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "album",
            )
        }.distinctBy { it.nmId }
    }

    /** 专辑详情：拿歌手名，让专辑页副标题显示真实歌手 */
    fun albumArtist(id: Long): String? {
        val root = get("/album", "id" to id.toString())
        val d = root.obj("album") ?: return null
        d.obj("artist")?.str("name")?.takeIf { it.isNotBlank() }?.let { return it }
        val arr = (d["artists"] as? JsonElement)?.jsonArray ?: return null
        return arr.firstNotNullOfOrNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
    }

    /** 歌手详情：拿歌手自己的头像，避免误用专辑封面 */
    fun artistDetail(id: Long): Group? {
        val root = get("/artist/detail", "id" to id.toString())
        val d = root.obj("data") ?: return null
        val artist = d.obj("artist") ?: return null
        val aid = idOf(artist)
        val name = artist.str("name").orEmpty()
        if (aid <= 0L || name.isBlank()) return null
        return Group(
            title = name,
            subtitle = "网易云歌手",
            cover = pic(artist.str("picUrl", "img1v1Url", "cover")),
            songs = emptyList(),
            artist = name,
            source = SRC_NETEASE,
            nmId = aid,
            nmKind = "artist",
        )
    }

    // ================= 首页 =================

    /** 每日推荐歌曲（需要登录） */
    fun recommendSongs(refresh: Boolean = false): List<Song> {
        val r = get(
            "/recommend/songs/v1",
            *buildList {
                if (refresh) add("ispush" to "true")
            }.toTypedArray(),
        )
        val arr = (r.obj("data")?.get("dailySongs") as? JsonElement)?.jsonArray
            ?: (r["data"] as? JsonElement)?.jsonArray
            ?: resultArray(r, "dailySongs")
        if (arr.isEmpty()) NmLog.note("每日推荐无数据 code=${r.code()}")
        val list = arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } }
        NmLog.note("每日推荐 ${list.size} 首")
        return enrich(list)
    }

    /** 新歌速递：type 0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国 */
    fun topSongs(type: Int = 0): List<Song> {
        val r = get("/top/song", "type" to type.toString())
        val arr = (r["data"] as? JsonElement)?.jsonArray ?: resultArray(r, "data")
        if (arr.isEmpty()) NmLog.note("新歌速递无数据 code=${r.code()}")
        val list = arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } }
        NmLog.note("新歌速递 ${list.size} 首")
        return enrich(list)
    }

    /** 热搜列表（简略）：返回搜索词 */
    fun hotSearch(): List<String> {
        val r = get("/search/hot")
        val arr = ((r.obj("result")?.get("hots") as? JsonElement)?.jsonArray)
            ?: ((r["hots"] as? JsonElement)?.jsonArray)
            ?: ((r["data"] as? JsonElement)?.jsonArray)
            ?: emptyList()
        val out = arr.mapNotNull { o ->
            (o as? JsonObject)?.str("first", "searchWord", "name")
        }
        if (out.isEmpty()) NmLog.note("热搜无数据 code=${r.code()}")
        return out
    }

    /** 热搜列表（详细）：带分数与热度的完整结构，取不到时退回简略版 */
    fun hotSearchDetail(): List<Pair<String, Int>> {
        val r = get("/search/hot/detail")
        val arr = ((r["data"] as? JsonElement)?.jsonArray) ?: emptyList()
        val out = arr.mapNotNull { o ->
            val j = o as? JsonObject ?: return@mapNotNull null
            val w = j.str("searchWord", "name") ?: return@mapNotNull null
            val score = ((j["score"] as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0
            w to score
        }
        if (out.isEmpty()) NmLog.note("热搜详情无数据，退回简略版")
        return out.ifEmpty { hotSearch().map { it to 0 } }
    }

    // ================= 登录 =================

    /** 第一步：取二维码 key */
    fun qrKey(): String? {
        val r = get("/login/qr/key")
        return r.obj("data")?.str("unikey", "key")
    }

    /** 第二步：取二维码图片（data: URI，可直接交给 Coil） */
    fun qrImage(key: String): String? {
        val r = get("/login/qr/create", "key" to key, "qrimg" to "true")
        val img = r.obj("data")?.str("qrimg") ?: return null
        return if (img.startsWith("data:")) img else "data:image/png;base64,$img"
    }

    /** 第三步：轮询。800 过期 801 等待扫码 802 待确认 803 成功 */
    fun qrCheck(key: String): QrStatus {
        val r = get("/login/qr/check", "key" to key)
        // 文档：扫码后返回 502 需加 noCookie=true
        if (r.code() == 502) {
            NmLog.note("qr/check 返回 502，改用 noCookie 重试")
            val r2 = get("/login/qr/check", "key" to key, "noCookie" to "true")
            val ck2 = r2.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r2)
            return QrStatus(r2.code(), ck2, r2.str("message"))
        }
        val ck = r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
        return QrStatus(r.code(), ck, r.str("message"))
    }

    /**
     * 手机号清洗：只留数字，去掉 +86 / 0086 / 86 前缀与分隔符。
     * 用户常从通讯录粘贴「+86 138 0013 8000」这样的号码，服务端只认纯数字，
     * 原样传过去会被判成无效手机号。
     */
    private fun cleanPhone(p: String): String {
        var s = p.filter { it.isDigit() }
        if (s.startsWith("0086")) s = s.removePrefix("0086")
        else if (s.startsWith("86") && s.length > 11) s = s.removePrefix("86")
        return s
    }

    private fun cookieOf(r: JsonObject): String? =
        r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)

    private fun msgOf(r: JsonObject): String? = r.str("message", "msg")

    /**
     * 发送短信验证码。返回 null 表示已发送，否则是失败原因。
     *
     * /captcha/sent 与 /captcha/sent/v1 都能发，不同部署上可用情况不一样
     * （有的只认其中一条），所以第一条失败就换另一条再试一次。
     */
    fun sendCaptcha(phone: String): String? {
        val p = cleanPhone(phone)
        val r1 = get("/captcha/sent", "phone" to p, "ctcode" to "86")
        if (r1.code() == 200) return null
        val r2 = get("/captcha/sent/v1", "phone" to p, "ctcode" to "86")
        if (r2.code() == 200) return null
        return (msgOf(r2).takeIf { !it.isNullOrBlank() } ?: msgOf(r1))
            ?: "发送失败（code ${r1.code()}）"
    }

    /**
     * 验证码登录。
     *
     * 之前这里多做了一步 /captcha/verify 再登录。该接口属于注册流程，
     * 调用后会把验证码标记为已核销，紧接着用同一个验证码去
     * /login/cellphone 就被判失效 —— 这正是「收到验证码却登录不了」的原因。
     * 正确流程是 /captcha/sent 之后直接 /login/cellphone。
     */
    fun loginByCaptcha(phone: String, captcha: String): LoginResult {
        val r = get(
            "/login/cellphone",
            "phone" to cleanPhone(phone),
            "captcha" to captcha.trim(),
            "countrycode" to "86",
        )
        return LoginResult(cookieOf(r), r.code(), msgOf(r))
    }

    /** 注册（注册成功即登录） */
    fun register(phone: String, password: String, captcha: String, nickname: String): LoginResult {
        val r = get(
            "/register/cellphone",
            "phone" to cleanPhone(phone), "password" to password,
            "captcha" to captcha.trim(), "nickname" to nickname, "countrycode" to "86",
        )
        return LoginResult(cookieOf(r), r.code(), msgOf(r))
    }

    /** 当前登录用户的 uid；取不到返回 0（未登录 / 登录态失效） */
    fun uid(): Long {
        if (cookie.isBlank()) return 0L
        val r = get("/user/account")
        if (r.code() != 200) return 0L
        val p = r.obj("profile")
        val v = (p?.get("userId") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (r.obj("account")?.get("id") as? JsonPrimitive)?.content?.toLongOrNull()
            ?: (r["userId"] as? JsonPrimitive)?.content?.toLongOrNull()
        NmLog.note("取 uid -> $v")
        return v ?: 0L
    }

    /**
     * 我的网易云歌单（/user/playlist?uid=）。
     *
     * 只保留「我创建的」：前面那些是「喜欢的音乐」和收藏的歌单，
     * 收藏的歌单不属于我，混进来会和本地歌单对不上。
     * 判断依据是 creator.userId == uid。
     */
    fun userPlaylists(uid: Long): List<Group> {
        if (uid <= 0L) return emptyList()
        val root = get("/user/playlist", "uid" to uid.toString(), "limit" to "1000", "offset" to "0")
        val arr = resultArray(root, "playlist")
        NmLog.note("我的歌单返回 ${arr.size} 个")
        return arr.mapNotNull { o ->
            val id = idOf(o)
            val name = o.str("name").orEmpty()
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            val creatorId = (o.obj("creator")?.get("userId") as? JsonPrimitive)?.content?.toLongOrNull()
            // creator 取不到时不筛（接口偶尔不给），至少不会全丢
            if (creatorId != null && creatorId != uid) return@mapNotNull null
            Group(
                title = name,
                subtitle = "网易云歌单 · ${o.str("trackCount")?.let { "$it 首" } ?: ""}",
                cover = pic(o.str("coverImgUrl", "picUrl", "cover")),
                songs = emptyList(),
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "playlist",
            )
        }.distinctBy { it.nmId }
    }

    /**
     * MV 播放地址（/mv/url?id=）。
     *
     * 返回的可能是不同分辨率的地址，这里直接取接口给的 url；
     * 取不到（需要付费 / 已下架 / 未登录）就返回 null，界面提示。
     */
    fun mvUrl(mvId: Long): String? {
        if (mvId <= 0L) return null
        val r = get("/mv/url", "id" to mvId.toString())
        val u = r.obj("data")?.str("url") ?: r.str("url")
        val ok = u?.takeIf { it.startsWith("http") }
        NmLog.note("取 MV 地址 mv=$mvId -> ${if (ok == null) "无" else "有"}")
        return ok
    }

    /**
     * 这首歌到底有没有 MV（三态）。
     *
     * mv 字段非 0 只说明接口给了一个 id，不等于真能播：
     * MV 下架 / 需要付费 / 地区限制时 /mv/url 是拿不到地址的。
     * 所以按钮不能只看 mvId，要确认一次。
     *
     * 用 getOnce（不重试）判断：拿不到响应说明是网络问题，返回 null 表示
     * 「无法判断」，调用方不能据此把按钮藏掉。
     */
    fun mvExists(mvId: Long): Boolean? {
        if (mvId <= 0L) return false
        val r = getOnce("/mv/url", "id" to mvId.toString()) ?: return null
        // 接口自己报错（不是 200）说明是服务端/网络问题，不能当成「没有 MV」
        val code = r["code"]?.toString()?.trim('"')?.toIntOrNull()
        if (code != null && code != 200) return null
        val u = r.obj("data")?.str("url") ?: r.str("url")
        return !u.isNullOrBlank() && u.startsWith("http")
    }

    /**
     * 往网易云歌单加歌（/playlist/tracks?op=add）。
     *
     * 只接受网易云歌曲 id：本站曲库的歌在网易云那边不存在，
     * 传过去会被忽略或报错，调用方已经先过滤过。
     */
    fun playlistAdd(pid: Long, trackIds: List<Long>): Boolean {
        if (pid <= 0L || trackIds.isEmpty()) return false
        val r = get(
            "/playlist/tracks",
            "op" to "add", "pid" to pid.toString(), "tracks" to trackIds.joinToString(","),
        )
        val ok = r.code() == 200
        NmLog.note("歌单加歌 pid=$pid ids=${trackIds.size} -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /** 从网易云歌单删歌（/playlist/tracks?op=del） */
    fun playlistDel(pid: Long, trackIds: List<Long>): Boolean {
        if (pid <= 0L || trackIds.isEmpty()) return false
        val r = get(
            "/playlist/tracks",
            "op" to "del", "pid" to pid.toString(), "tracks" to trackIds.joinToString(","),
        )
        val ok = r.code() == 200
        NmLog.note("歌单删歌 pid=$pid ids=${trackIds.size} -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /** 改网易云歌单名（/playlist/update） */
    fun playlistRename(pid: Long, name: String): Boolean {
        if (pid <= 0L || name.isBlank()) return false
        val r = get("/playlist/update", "id" to pid.toString(), "name" to name)
        val ok = r.code() == 200
        NmLog.note("歌单改名 pid=$pid -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /** 用 cookie 换取账号昵称；失败返回 null 表示登录态失效 */
    fun accountName(): String? {
        val r = get("/user/account")
        if (r.code() != 200) return null
        val p = r.obj("profile")
        return p?.str("nickname")
            ?: r.obj("account")?.str("userName")
            ?: (p?.get("userId") as? JsonPrimitive)?.content
    }

    /** 登录态是否有效：只看账号信息是否存在，不强制要求 profile */
    fun loggedIn(): Boolean {
        if (cookie.isBlank()) return false
        val r = get("/login/status")
        val d = r.obj("data")
        val account = d?.obj("account") ?: r.obj("account") ?: r.obj("profile")
        val ok = account != null
        NmLog.note("登录态检查：${if (ok) "有效" else "无效"}")
        return ok
    }
}
