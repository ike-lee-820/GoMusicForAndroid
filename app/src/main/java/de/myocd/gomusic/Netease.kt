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

/** 网易云可选音质档位（从低到高） */
val NM_LEVELS = listOf("standard", "higher", "exhigh", "lossless")

/** 网易云音质档位中文名 */
fun nmLevelLabel(lv: String): String = when (lv) {
    "standard" -> "标准 128k"
    "higher" -> "较高 192k"
    "exhigh" -> "极高 320k"
    "lossless" -> "无损 FLAC"
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
        repeat(2) { i ->
            try {
                val req = Request.Builder().url(url).header("Connection", "close").build()
                Api.client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    NmLog.res(url, r.code, t)
                    if (!r.isSuccessful || t.isBlank()) {
                        throw IOException("HTTP ${r.code} ${if (t.isBlank()) "(空响应)" else ""}")
                    }
                    val el = runCatching { json.parseToJsonElement(t) }.getOrNull()
                    return if (el is JsonObject) el else JsonObject(emptyMap())
                }
            } catch (e: Exception) {
                last = e
                NmLog.err(url, e)
                if (i == 0) runCatching { Thread.sleep(400) }
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

    fun searchArtists(keywords: String, limit: Int = 10, offset: Int = 0): List<Group> {
        val arr = resultArray(rawSearch(keywords, 100, limit, offset), "artists")
        NmLog.note("搜索歌手返回 ${arr.size} 条")
        return arr.mapNotNull { o ->
            val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val name = o.str("name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            Group(
                title = name,
                subtitle = "网易云歌手",
                cover = pic(o.str("picUrl", "img1v1Url", "img1v1Url")),
                songs = emptyList(),
                artist = name,
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "artist",
            )
        }
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
    fun audioUrl(id: Long, quality: String, level: String? = null): String? {
        NmLog.note("开始取播放地址 id=$id quality=$quality level=$level（新版 v1 + unblock）")
        // 指定了档位就优先试它，试不到再按设置降档
        val order = if (!level.isNullOrBlank()) {
            (listOf(level) + levels(quality) + NM_LEVELS).distinct()
        } else {
            levels(quality)
        }
        for (lv in order) {
            val tries = listOf(
                "v1" to listOf("id" to id.toString(), "level" to lv),
                "v1+randomCNIP" to listOf("id" to id.toString(), "level" to lv, "randomCNIP" to "true"),
                "v1+realIP" to listOf("id" to id.toString(), "level" to lv, "realIP" to FALLBACK_CN_IP),
            )
            for ((name, ps) in tries) {
                val r = get("/song/url/v1", *ps.toTypedArray())
                if (r.code() != 200) continue
                val arr = (r["data"] as? JsonElement)?.jsonArray
                val first = arr?.firstOrNull()?.let { it as? JsonObject }
                val u = first?.str("url")
                if (!u.isNullOrBlank()) {
                    NmLog.note("拿到地址（$name/$lv）：$u")
                    return u
                }
                // 解灰有时会把地址放在别的字段里
                val alt = first?.str("freeTrialInfo", "url")
                if (!alt.isNullOrBlank() && alt.startsWith("http")) {
                    NmLog.note("拿到地址（$name/$lv/备用字段）：$alt")
                    return alt
                }
            }
            NmLog.note("level=$lv 三种方式都没有返回地址，继续降音质")
        }
        // v1 拿不到时走 /song/url/match 解灰匹配（歌曲变灰时 v1 的地址会是空）
        runCatching {
            val r = get("/song/url/match", "id" to id.toString())
            val u = ((r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject)?.str("url")
            if (!u.isNullOrBlank()) {
                NmLog.note("match 解灰拿到地址：$u")
                return u
            }
        }
        // 兜底：拿游客 cookie 再试一次（未登录/登录态失效时网易会返回 301 / 400 需验证）
        NmLog.note("登录态下取不到地址，尝试游客 cookie 再试一次")
        val anon = runCatching { anonCookie() }.getOrNull()
        if (!anon.isNullOrBlank()) {
            val saved = cookie
            cookie = anon
            try {
                for (lv in levels(quality)) {
                    val r = get("/song/url/v1", "id" to id.toString(), "level" to lv, "randomCNIP" to "true")
                    val u = ((r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject)?.str("url")
                    if (!u.isNullOrBlank()) {
                        NmLog.note("游客 cookie 拿到地址（$lv）：$u")
                        return u
                    }
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
    fun songsOf(kind: String, id: Long, limit: Int = 50): List<Song> {
        NmLog.note("取 $kind 的歌曲 id=$id")
        val root = if (kind == "album") {
            get("/album", "id" to id.toString())
        } else {
            get("/artist/songs", "id" to id.toString(), "limit" to limit.toString(), "offset" to "0")
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

    /** 发送短信验证码 */
    fun sendCaptcha(phone: String): Boolean = get("/captcha/sent", "phone" to phone).code() == 200

    /** 校验验证码 */
    fun verifyCaptcha(phone: String, captcha: String): Boolean =
        get("/captcha/verify", "phone" to phone, "captcha" to captcha).code() == 200

    /** 验证码登录 */
    fun loginByCaptcha(phone: String, captcha: String): String? {
        val r = get("/login/cellphone", "phone" to phone, "captcha" to captcha)
        if (r.code() != 200) return null
        return r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
    }

    /** 注册（注册成功即登录） */
    fun register(phone: String, password: String, captcha: String, nickname: String): String? {
        val r = get(
            "/register/cellphone",
            "phone" to phone, "password" to password,
            "captcha" to captcha, "nickname" to nickname,
        )
        if (r.code() != 200) return null
        return r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
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
