package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Dispatcher
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
    /** 易盾安全验证页（code -462 / data.url），需用户在浏览器完成后重试 */
    val verifyUrl: String? = null,
    /** 服务端给的跳转页，例如「当前登录存在安全风险」的 phoneReuse 页 */
    val redirectUrl: String? = null,
    /** 命中风控：无论有没有跳转页都要明确提示，不能静默失败 */
    val risk: Boolean = false,
    /** 发送验证码用：true 表示短信已下发 */
    val sent: Boolean = false,
) {
    val ok: Boolean get() = !cookie.isNullOrBlank()
    val blocked: Boolean get() = risk || !verifyUrl.isNullOrBlank() || !redirectUrl.isNullOrBlank()
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

/**
 * 音质名归一化：请求发的是英文档位（standard / hires …），服务端返回的
 * level 可能是英文，也可能是中文音质名。归一到同一套英文 key 才能比对。
 * 对照：standard=>标准 higher=>较高 exhigh=>极高 lossless=>无损 hires=>Hi-Res
 * jyeffect=>高清臻音 dolby=>杜比全景声 vivid=>臻音全景声
 * jymaster=>超清母带 sky=>沉浸环绕声
 */
fun nmLevelKey(name: String): String = when (name.trim().lowercase()) {
    "standard", "标准" -> "standard"
    "higher", "较高" -> "higher"
    "exhigh", "极高" -> "exhigh"
    "lossless", "无损" -> "lossless"
    "hires", "hi-res" -> "hires"
    "jyeffect", "高清臻音" -> "jyeffect"
    "dolby", "杜比全景声" -> "dolby"
    "vivid", "臻音全景声" -> "vivid"
    "jymaster", "超清母带" -> "jymaster"
    "sky", "沉浸环绕声" -> "sky"
    else -> name.trim().lowercase()
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
            // 服务端 /login/cellphone 返回的 cookie 是一整串 Set-Cookie 文本，
            // 里面夹着 Max-Age / Expires / Path 等属性，还有大量同名重复项。
            // 直接拼进请求会让整串 cookie 解析失败（登录了却拿不到高音质）。
            // 统一在这里清洗：只留真正的 key=value，去重，并强制带 os=pc
            // （网易云按 PC 端才给无损 / Hi-Res 等档位）。
            field = if (v.isBlank()) "" else normalizeCookie(v)
            if (field.isNotBlank()) NmLog.note("已设置 cookie，包含键：${cookieKeys(field)}（长度 ${field.length}，os=pc）")
        }

    /** 日志里只记 cookie 的键名，不记值，避免泄露登录凭证 */
    private fun cookieKeys(c: String): String =
        c.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotBlank() }.joinToString(",")

    /** Set-Cookie 属性名，不是 cookie 键，必须剔除 */
    private val COOKIE_ATTRS = setOf(
        "max-age", "expires", "path", "domain", "secure",
        "httponly", "samesite", "priority", "partitioned", "version",
    )

    /** 把原始 cookie 串洗干净，并补上 os=pc 保证能拿到高音质 */
    private fun normalizeCookie(raw: String): String {
        val map = LinkedHashMap<String, String>()
        raw.split(";").forEach { seg ->
            val t = seg.trim()
            if (t.isEmpty()) return@forEach
            val k = t.substringBefore("=").trim()
            val v = t.substringAfter("=", "").trim()
            if (k.isEmpty() || k.lowercase() in COOKIE_ATTRS) return@forEach
            // 同名重复（MUSIC_A_T / MUSIC_R_T 会出现十几次）只留非空值
            if (v.isNotEmpty() || !map.containsKey(k)) map[k] = v
        }
        map["os"] = "pc"
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 从指定档位开始，按 NM_LEVELS 的排列「逐级往回退一档」直到 standard。
     *
     * 之前降档只走 levels(quality) 那 4 个老档位：用户手动选了 jymaster
     * 取不到时直接跳到 lossless，中间的 vivid / dolby / jyeffect / hires
     * 全被跳过 —— 明明有稍低一档能播，却一下掉到标准。现在按完整档位表
     * 一档一档退，某一档请求失败就换它的上一音质。
     */
    private fun descendingLevels(start: String): List<String> {
        val key = nmLevelKey(start)
        var i = NM_LEVELS.indexOf(key)
        if (i < 0) i = NM_LEVELS.indexOf(nmLevelOfQuality(start))
        if (i < 0) i = 0
        return (i downTo 0).map { NM_LEVELS[it] }
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
    private fun get(
        path: String,
        vararg params: Pair<String, String>,
        http: OkHttpClient = Api.client,
        tries: Int = 2,
        /** 26.11.81：退出登录时本地 cookie 已经清空，但要让服务端注销的是旧凭证 */
        cookieOverride: String? = null,
    ): JsonObject {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        // unblock=true：启用解灰（变灰歌曲匹配第三方音源）。
        // 注意：/song/url/v1 不支持自定义解灰源（会返回 msg 警告并忽略），
        // 这种情况要走 /song/url/match，见 audioUrl 的兜底。
        // 登录 / 发验证码这类「换凭证」的接口按服务端验证过的形式发：
        // 不带 unblock（解灰参数对登录无意义）、不自动带旧 cookie
        // （带着旧登录态更容易被判「当前登录存在安全风险」）。
        val cred = isCredentialPath(path)
        if (!cred) sb.append("unblock=true&")
        // 所有网易云 API 请求（含登录、搜索、取地址、歌词）统一带随机国内 IP，
        // 绕开网易对境外 IP 的 460 cheating。调用方若已显式带则不重复追加。
        if (params.none { it.first.equals("randomCNIP", ignoreCase = true) }) {
            sb.append("randomCNIP=true&")
        }
        val ck = cookieOverride ?: cookie
        if (!cred && ck.isNotBlank()) sb.append("cookie=").append(encCookie(ck)).append('&')
        sb.append("timestamp=").append(System.currentTimeMillis())
        val url = sb.toString()
        NmLog.req(url)
        var last: Exception? = null
        // 失败自动重试：网络抖动 / 服务端偶发错误时多试一次。
        // 不再 sleep 300/700ms —— 一次接口抖动就白等 1 秒，切歌、搜索、
        // 打开页面都会跟着卡。失败立刻重发，靠短超时客户端兜底。
        val times = tries.coerceAtLeast(1)
        for (i in 0 until times) {
            try {
                // 不要再带 Connection: close —— 它关掉了连接复用，
                // 每个请求都要重新 TCP + TLS 握手，是「网易云请求慢」的主因
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    NmLog.res(url, r.code, t)
                    if (t.isBlank()) throw IOException("HTTP ${r.code} (空响应)")
                    val el = runCatching { json.parseToJsonElement(t) }.getOrNull()
                    if (el is JsonObject) {
                        // HTTP 400/403 常带着完整的业务错误（例如 -462 易盾验证、
                        // 10004 安全风险）。以前一律当网络错误重试 3 次，body 被丢掉，
                        // 上层只拿到空对象 → code -1、msg null，既没提示也没跳转。
                        // 这类是确定性结果，重试只会换一个新的 verifyToken 并白等几秒，
                        // 直接把 JSON 交给调用方按 code 处理。
                        if (!r.isSuccessful) {
                            NmLog.note("接口返回业务错误 HTTP ${r.code} code=${el.code()}，不再重试")
                        }
                        return el
                    }
                    if (r.isSuccessful) throw IOException("响应不是 JSON 对象")
                    throw IOException("HTTP ${r.code}")
                }
            } catch (e: Exception) {
                last = e
                NmLog.note("请求第 ${i + 1} 次失败：$path -> ${e.message}")
                NmLog.err(url, e)
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

    /** 26.11.70：读取数值字段（userId / trackCount / updateTime / specialType） */
    private fun JsonObject.num(vararg keys: String): Long? {
        for (k in keys) {
            val v = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
            if (v != null) return v
        }
        return null
    }

    private fun JsonObject.flag(k: String): Boolean =
        (this[k] as? JsonPrimitive)?.content?.let { it == "true" || it == "1" } ?: false

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

    /**
     * 封面高清化：网易云图片支持 ?param=宽y高。
     *
     * 默认 1000x1000（原来 300），播放器大封面在 3x 屏上不再被放大三倍。
     * 上游自带的 ?param=100y100 也会被强制覆盖，具体见 [hdCoverUrl]。
     */
    private fun pic(u: String?, size: Int = 2000): String? {
        if (u.isNullOrBlank()) return null
        if (!u.startsWith("http")) return null
        return hdCoverUrl(u, size)
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
            http = searchClient,
            tries = 1,
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

    /**
     * /song/detail?ids=1,2,3 -> id -> 详情。
     *
     * 26.11.70：按 100 个一批切分。收藏歌单动辄上千首，一次性把全部 id
     * 拼进 URL 会超出服务端限制直接 400，整张歌单就只剩前几首。
     */
    private fun songDetail(ids: List<Long>, client: OkHttpClient = searchClient): Map<Long, Song> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Song>()
        ids.distinct().chunked(100).forEach { chunk ->
            val root = runCatching {
                get(
                    "/song/detail", "ids" to chunk.joinToString(","),
                    http = client, tries = 1,
                )
            }.getOrNull() ?: return@forEach
            val arr = resultArray(root, "songs")
            if (arr.isEmpty()) NmLog.note("song/detail 没有 songs 字段")
            arr.forEach { o ->
                val so = toSong(o) ?: return@forEach
                if (so.nmId > 0) out[so.nmId] = so
            }
        }
        return out
    }

    /** 单曲详情：播放前按 id 取完整封面 / 专辑 / 歌手 */
    fun detailOf(id: Long): Song? {
        val m = runCatching { songDetail(listOf(id)) }.getOrDefault(emptyMap())
        return m[id]
    }

    fun searchSongs(
        keywords: String,
        limit: Int = 20,
        offset: Int = 0,
        enrichResult: Boolean = true,
        rootOverride: JsonObject? = null,
    ): List<Song> {
        val root = rootOverride ?: rawSearch(keywords, 1, limit, offset)
        val arr = resultArray(root, "songs")
        if (arr.isEmpty()) {
            NmLog.note("搜索单曲无结果：keywords=$keywords offset=$offset")
            return emptyList()
        }
        val list = arr.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } }
        // 26.11.63：聚合搜索走异步快路径，不再在这条关键链上串行等一次
        // /song/detail（原来 rawSearch 5s + detail 5s 超过 8s 截止，歌曲常被砍空）。
        // 其余调用点（翻页、专辑/歌手歌曲列表）保持补全行为不变。
        if (!enrichResult) return list
        NmLog.note("搜索单曲 offset=$offset 得到 ${list.size} 首，开始用 song/detail 补全信息")
        return enrich(list)
    }

    /**
     * 专辑搜索。
     * type=10 经常返回空（或需要登录），这时用单曲结果里的专辑兜底，
     * 保证「专辑」标签在网易云曲库下也能出内容。
     */
    /**
     * 26.11.87：首页推荐位通用解析。
     *
     * /personalized、/recommend/resource、/top/playlist、/top/playlist/highquality
     * 把列表放在 result / recommend / playlists / data 里，/personalized/mv 与
     * /mv/first 则是 result / data 里的 MV 对象。统一收成 Group：
     * 歌单 nmKind=playlist（点开走已有歌单详情页），MV nmKind=mv（点开取地址播放）。
     */
    fun homeGroups(path: String, vararg params: Pair<String, String>): List<Group> = runCatching {
        val root = get(path, *params)
        val all = LinkedHashMap<Long, JsonObject>()
        fun collect(o: JsonObject?) {
            if (o == null) return
            listOf("playlists", "recommend", "result", "data", "list", "mvList", "mvs")
                .forEach { k ->
                    val arr = runCatching { (o[k] as? JsonElement)?.jsonArray }.getOrNull()
                    arr?.forEach { e -> (e as? JsonObject)?.let { x ->
                        val id = x.num("id") ?: 0L
                        if (id > 0L) all.putIfAbsent(id, x)
                    } }
                }
        }
        collect(root)
        collect(root.obj("data"))
        collect(root.obj("result"))
        val isMv = path.contains("mv", ignoreCase = true)
        all.values.mapNotNull { o ->
            val id = o.num("id") ?: return@mapNotNull null
            val name = o.str("name", "title").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val cnt = (o.num("trackCount", "trackCnt") ?: 0L).toInt()
            val creator = o.obj("creator")?.str("nickname", "name")
                ?: o.obj("dj")?.str("nickname")
            val artist = o.str("artistName", "artistsName", "author").orEmpty()
            Group(
                title = name,
                subtitle = when {
                    artist.isNotBlank() -> artist
                    !creator.isNullOrBlank() -> creator
                    cnt > 0 -> "$cnt 首"
                    else -> o.str("copywriter", "desc").orEmpty()
                },
                cover = pic(o.str("coverImgUrl", "picUrl", "imgurl", "cover", "imgurl1")),
                songs = emptyList(),
                source = SRC_NETEASE,
                nmId = id,
                nmKind = if (isMv || artist.isNotBlank()) "mv" else "playlist",
                trackCount = cnt,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 26.11.87：搜索歌单（type=1000）。
     * 走独立请求，不塞进 searchAllStream 的三路主链，避免拖慢首屏；
     * 返回统一 Group（nmKind=playlist），点开后复用已有歌单详情页。
     */
    fun searchPlaylistGroups(keywords: String, limit: Int = 20, offset: Int = 0): List<Group> =
        runCatching {
            val root = get(
                "/search",
                "keywords" to keywords,
                "type" to "1000",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            )
            resultArray(root, "playlists").mapNotNull { o ->
                val id = o.num("id") ?: return@mapNotNull null
                val name = o.str("name", "title").orEmpty()
                if (id <= 0L || name.isBlank()) return@mapNotNull null
                val cnt = (o.num("trackCount", "trackCnt") ?: 0L).toInt()
                Group(
                    title = name,
                    subtitle = o.obj("creator")?.str("nickname", "name").orEmpty()
                        .ifBlank { if (cnt > 0) "$cnt 首" else "歌单" },
                    cover = pic(o.str("coverImgUrl", "picUrl", "imgurl", "cover")),
                    songs = emptyList(),
                    source = SRC_NETEASE,
                    nmId = id,
                    nmKind = "playlist",
                    trackCount = cnt,
                )
            }
        }.getOrDefault(emptyList())

    fun searchAlbums(
        keywords: String,
        limit: Int = 10,
        offset: Int = 0,
        fallback: Boolean = true,
        rootOverride: JsonObject? = null,
    ): List<Group> {
        val out = LinkedHashMap<String, Group>()
        val arr = resultArray(rootOverride ?: rawSearch(keywords, 10, limit, offset), "albums")
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
        if (out.isEmpty() && fallback) {
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
    fun searchArtists(
        keywords: String,
        limit: Int = 10,
        offset: Int = 0,
        fallback: Boolean = true,
        rootOverride: JsonObject? = null,
    ): List<Group> {
        val arr = resultArray(rootOverride ?: rawSearch(keywords, 100, limit, offset), "artists")
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
        if (out.isEmpty() && fallback) {
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
    /**
     * 音质探测结果。
     *
     * 三态是关键：请求失败不等于「没有音源」。
     * 之前只分「有 / 无」，请求失败被当成「无」，而调用方拿到空列表时
     * 又回退成显示全部档位 —— 两头都不准，于是出现「没有的也显示有」。
     */
    data class LevelProbe(
        /** 确认有音源（接口返回了地址） */
        val ok: List<String>,
        /** 确认没有（接口正常返回但地址为空） */
        val absent: List<String>,
    )

    fun availableLevels(id: Long): LevelProbe {
        // 7 个档位并发探测：串行要 7 个来回，菜单里「哪些档位有音源」
        // 要等很久才显示得出来。并发后总耗时等于最慢的那一个请求。
        // 每个档位返回三态：true=有 / false=没有 / null=没探出来（请求失败）
        val res = runBlocking(Dispatchers.IO) {
            NM_LEVELS.map { lv ->
                lv to async {
                    fun probe(): Boolean? {
                        val r = runCatching {
                            getOnce(
                                "/song/url/v1", "id" to id.toString(), "level" to lv,
                                client = prefetchClient,
                            )
                        }.getOrNull() ?: return null
                        // 非 200 是接口报错，同样算没探出来，不能当成「没有音源」
                        if (r.code() != 200) return null
                        val first =
                            (r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
                        val u = first?.str("url")
                            ?: first?.str("freeTrialInfo", "url")?.takeIf { it.startsWith("http") }
                        if (u.isNullOrBlank()) return false
                        // 关键：请求 hires 时服务端可能降档返回 standard 的地址。
                        // 以前只要有 url 就算这一档「有音源」，于是菜单把没有的
                        // 档位也显示成有，切过去听到的其实还是低音质。
                        // 现在要求返回的音质名与发送的音质名相同才算有；
                        // 明确不同 = 这一档没有；压根没返回 level = 没探出来
                        // （返回 null，既不置灰也不冒充有）。
                        val got = first?.str("level", "levelName", "quality")
                            ?.takeIf { it.isNotBlank() }?.let { nmLevelKey(it) }
                        if (got == null) return null
                        if (got != lv) {
                            NmLog.note("音质探测 $lv 被降档为 $got，视为无音源")
                            return false
                        }
                        return true
                    }
                    // 只探一次，不重试：7 个档位各重试一次就是 14 个请求，
                    // 而它只是用来给菜单置灰「无音源」，不值得为偶发抖动
                    // 把请求量翻倍。探不出来按 unknown 处理（不置灰）
                    probe()
                }
            }.map { (lv, d) -> lv to d.await() }
        }
        val ok = res.filter { it.second == true }.map { it.first }
        val absent = res.filter { it.second == false }.map { it.first }
        NmLog.note("音质探测 id=$id -> 有=$ok 无=$absent")
        return LevelProbe(ok, absent)
    }

    /**
     * 音源地址缓存。
     *
     * 取一次地址最坏要串行试 9 个音质档位 × 3 种参数，二十几次请求，
     * 而这步是在 ExoPlayer 的准备线程上同步做的，等于点一首歌要干等
     * 二十多个 HTTP 往返才开始出声——这就是「加载极慢」的原因。
     * 缓存后切歌回来、重播都是秒开。地址有时效，过期时间放短一点。
     */
    private val urlCache = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Long>>()

    /**
     * 负缓存：取不到地址时记一笔，短时间内的重复请求直接放弃。
     *
     * 没有它时，一首取不到音源的歌被点一次就要重新走一遍十几个请求，
     * 连续点/自动切歌会把连接池占满，连正常歌曲也跟着变慢。
     */
    private val urlMiss = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 缓存有效期：网易云 CDN 地址一般能撑几十分钟，留足余量取 10 分钟 */
    private const val URL_TTL_MS = 10 * 60 * 1000L

    /** 负缓存有效期：取不到往往只是暂时的（登录态、限流），30 秒后允许重试 */
    private const val URL_MISS_TTL_MS = 8_000L

    /** 在途请求：同一首歌同时被预取和播放解析时共用一次探测，不重复发 */
    private val urlInFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    private val inflightLock = Any()

    private fun cacheKey(id: Long, quality: String, level: String?): String =
        "$id|$quality|${level.orEmpty()}|${cookie.take(8).hashCode()}"

    /**
     * 预取音源地址：调用方须已在 IO 线程。
     *
     * 26.11.55：以前这里内部又起一个 GlobalScope，函数立刻返回 —— 于是
     * Prefetcher 的 awaitAll 立刻完成、jobs 立刻 inactive，切歌时
     * transition 与 timelineChanged 两个回调各发一轮预取，且 cancel 根本
     * 取消不掉真正跑着的请求。十几路请求涌向同一个 host，把正在解析的
     * 播放地址挤到队列后面，切下一首就卡半天。改成同步执行后，预取任务
     * 的「在途」状态才真实，去重与取消才生效。
     */
    fun prefetchAudioUrl(id: Long, quality: String, level: String? = null) {
        val k = cacheKey(id, quality, level)
        val hit = urlCache[k]
        if (hit != null && System.currentTimeMillis() - hit.third < URL_TTL_MS) return
        // 26.11.69：预取走独立客户端，绝不与正在解析的播放地址抢连接
        runCatching { audioUrl(id, quality, level, prefetchClient) }
            .onFailure { NmLog.note("预取音源失败：${it.message}") }
    }

    /** 该档位地址是否已在缓存且未过期：预取调度用它跳过已完成的任务 */
    fun hasAudioUrl(id: Long, quality: String, level: String? = null): Boolean {
        val k = cacheKey(id, quality, level)
        val hit = urlCache[k] ?: return false
        return System.currentTimeMillis() - hit.third < URL_TTL_MS
    }

    fun audioUrl(
        id: Long,
        quality: String,
        level: String? = null,
        client: OkHttpClient = urlClient,
    ): String? = audioWithLevel(id, quality, level, client)?.first

    /**
     * 严格单档取地址：只请求这一个档位，不降档、不解灰、不换游客 cookie。
     *
     * 录制必须把「录制界面所选音质的那个文件」原样嵌进视频：[audioWithLevel]
     * 会自动沿 NM_LEVELS 逐级回退，请求 jymaster 却拿到 standard，成品音质就
     * 与选项不一致。这里用 [tryLevel] 只打一档，且服务端返回的档位与请求
     * 不同（降档返回）时直接判为不可用，由界面提示换档或先下载，不静默换文件。
     */
    fun audioExactLevel(id: Long, level: String): String? {
        val lv = nmLevelKey(level)
        if (lv !in NM_LEVELS) return null
        return when (val r = tryLevel(id, lv)) {
            is LevelResult.Hit -> if (r.hit.lv == lv) r.hit.url else null
            else -> null
        }
    }

    /**
     * 取地址并带回**实际拿到的档位**。
     *
     * 下载必须用这个：请求 lossless 时歌曲可能只有 320k，
     * resolveAudioUrl 会一路降档，但返回的只有地址 ——
     * 如果仍按请求的档位记录，下载记录里的音质就是假的
     * （存着「无损」，实际是 320k，离线播放与文件命名都会错）。
     */
    fun audioWithLevel(
        id: Long,
        quality: String,
        level: String? = null,
        client: OkHttpClient = urlClient,
    ): Pair<String, String>? {
        // 命中缓存直接返回，并把缓存里记录的「实际档位」带回去。
        // 之前缓存只存 url，命中时拿请求档位冒充实际档位，下载记录会写错。
        val k = cacheKey(id, quality, level)
        urlCache[k]?.let { (u, lv, t) ->
            if (System.currentTimeMillis() - t < URL_TTL_MS) {
                NmLog.note("音源命中缓存 id=$id 实际档位=$lv")
                return u to lv
            }
            urlCache.remove(k)
        }
        // 负缓存：刚取过且没取到，不再重复打一轮请求
        val missAt = urlMiss[k]
        if (missAt != null) {
            if (System.currentTimeMillis() - missAt < URL_MISS_TTL_MS) {
                NmLog.note("音源负缓存命中，跳过 id=$id")
                return null
            }
            urlMiss.remove(k)
        }
        // 在途去重：预取和播放解析同时到时，后来的直接等前一次的结果，
        // 不再各发一轮（否则同一首歌瞬间打出二十几个请求）
        val lock = urlInFlight.computeIfAbsent(k) { java.util.concurrent.locks.ReentrantLock() }
        if (!lock.tryLock()) {
            // 26.11.55：预取正在跑时，播放解析以前只等 3 秒就 return null。
            // 预取整条降档链最长 8 秒，等不到就被判「没有可用音源」→
            // 播放器报错 → 服务层兜底重新 prepare → 再等一轮，体感就是
            // 「切下一首卡半天」。改成等满整条链；若仍无结果就拿回锁自己算，
            // 绝不让正在切歌的这一次解析空手而归。
            var waited = 0
            // 26.11.69：整条降档链现在最多 6 秒，等满 6.5 秒就够，
            // 不再是旧版那个 9 秒的空等上限。
            while (waited < 6_500 && !urlCache.containsKey(k) && !urlMiss.containsKey(k)) {
                runCatching { Thread.sleep(25) }
                waited += 25
            }
            urlCache[k]?.let { (u, lv, t) ->
                if (System.currentTimeMillis() - t < URL_TTL_MS) return u to lv
            }
            if (urlMiss.containsKey(k)) return null
            lock.lock()
        }
        return try {
            val hit = resolveAudioUrl(id, quality, level, client)
            if (hit != null) urlCache[k] = Triple(hit.first, hit.second, System.currentTimeMillis())
            else urlMiss[k] = System.currentTimeMillis()
            hit
        } finally {
            lock.unlock()
            urlInFlight.remove(k)
        }
    }

    /**
     * 26.11.63：搜索专用客户端。
     *
     * 之前用 Api.client.newBuilder()，会和播放、预取、下载、更新检查共享同一个
     * Dispatcher 与连接池；一批慢请求排前面，搜索就被挤到队尾。现在给搜索独立的
     * 调度池与连接池，超时也压到「连不上就立刻换」的程度。
     */
    private val searchDispatcher = Dispatcher().apply {
        maxRequests = 24
        maxRequestsPerHost = 12
    }
    private val searchClient: OkHttpClient = Api.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dispatcher(searchDispatcher)
        .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    /** 单次搜索请求的硬性截止：到点就真断开，不再让旧请求占着线程 */
    private const val SEARCH_CALL_MS = 6000L

    /**
     * 26.11.70：歌单同步专用客户端。
     *
     * 「同步所有云端歌单」会连着打 /user/playlist + 每张歌单 /playlist/detail +
     * 若干 /song/detail。若沿用 Api.client，会和播放解析、搜索、预取抢同一个
     * dispatcher，同步一跑整首歌起播就变慢。这里物理隔开，且只发一次不重试。
     */
    private val syncClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .connectionPool(ConnectionPool(6, 30, TimeUnit.SECONDS))
        .build()

    /** 取播放地址专用客户端：短超时。取地址要快，失败就换下一种，不能干等 */
    private val urlClient: OkHttpClient = OkHttpClient.Builder()
        // 26.11.69：一档拿不到就尽快换下一档，别让一次 HTTP 抖动拖住整首歌
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            }
        )
        .build()

    /**
     * 26.11.69：预取专用客户端。
     *
     * Prefetcher 里早就声明了一个「独立 client」，但预取实际调用的
     * Netease.prefetchAudioUrl → getOnce 写死用 urlClient，于是预热后两首
     * 和当前这首的播放解析抢同一个 dispatcher / 连接池：预取一跑就是
     * 两三条降档链，正在解析的播放地址被挤到队尾 —— 这才是「点一首歌
     * 要等半天」最实在的根因。这里真正把预取链路分到独立的调度池与连接池。
     */
    private val prefetchClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 12
            maxRequestsPerHost = 6
        })
        .connectionPool(ConnectionPool(6, 30, TimeUnit.SECONDS))
        .build()

    /** 歌词专用客户端：/lyric/new、/lyric 不再和取地址抢连接 */
    private val lyricClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply {
            maxRequests = 8
            maxRequestsPerHost = 4
        })
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build()

    /**
     * 单次请求：不重试、不 sleep。
     *
     * 通用 get() 会重试 4 次并递增 sleep（约 3 秒），而取播放地址时
     * 一个档位拿不到就该立刻换下一个，重试只会把时间白白耗掉。
     */
    private fun getOnce(
        path: String,
        vararg params: Pair<String, String>,
        allowErrorBody: Boolean = false,
        client: OkHttpClient = urlClient,
    ): JsonObject? {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        sb.append("unblock=true&")
        if (params.none { it.first.equals("randomCNIP", ignoreCase = true) }) {
            sb.append("randomCNIP=true&")
        }
        if (cookie.isNotBlank()) sb.append("cookie=").append(encCookie(cookie)).append('&')
        sb.append("timestamp=").append(System.currentTimeMillis())
        val url = sb.toString()
        return try {
            // 同样不带 Connection: close，保持连接复用
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req(url)
                NmLog.res(url, r.code, t)
                if (t.isBlank()) {
                    null
                } else {
                    val el = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
                    // 非 2xx 也可能带完整业务错误（-462 易盾验证、10004 安全风险、
                    // 401 需要登录）。音质链据此判断「换档也没用」并停止逐档刷；
                    // 默认仍返回 null，保持其它调用方的旧行为。
                    if (!r.isSuccessful && !allowErrorBody) null else el
                }
            }
        } catch (e: Exception) {
            NmLog.note("单次请求失败：$path -> ${e.message}")
            null
        }
    }

    private data class UrlHit(val lv: String, val url: String)

    /** 单个档位的结果：命中 / 这一档没有 / 命中风控（换档也无意义） */
    private sealed class LevelResult {
        data class Hit(val hit: UrlHit) : LevelResult()
        object Miss : LevelResult()
        object Blocked : LevelResult()
    }

    /** 业务错误：换任何音质都拿不到地址，不该把整条降档链刷一遍 */
    private fun isBlocking(o: JsonObject): Boolean {
        val c = o.code()
        if (c in listOf(-462, 10004, 301, 401, 403, 460, 800001)) return true
        val s = o.str("message", "msg", "error", "blockText").orEmpty()
        val d = o.obj("data")
        val hasVerify = listOf(
            o.str("verifyUrl", "redirectUrl"),
            d?.str("verifyUrl", "url", "redirectUrl"),
        ).any { it?.contains("verify", ignoreCase = true) == true }
        return hasVerify || s.contains("验证") || s.contains("登录") ||
            s.contains("风险") || s.contains("Login required", ignoreCase = true)
    }

    /**
     * 试一个档位：档内两个参数变体并发，档与档之间串行。
     * 只有「这一档确实失败」才返回 Miss，由调用方更换上一音质。
     */
    private fun tryLevel(id: Long, lv: String, client: OkHttpClient = urlClient): LevelResult {
        val variants = listOf(
            listOf("id" to id.toString(), "level" to lv),
            listOf("id" to id.toString(), "level" to lv, "realIP" to FALLBACK_CN_IP),
        )
        val rs = runBlocking(Dispatchers.IO) {
            variants.map { ps ->
                async {
                    getOnce(
                        "/song/url/v1", *ps.toTypedArray(),
                        allowErrorBody = true, client = client,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        if (rs.any { isBlocking(it) }) {
            NmLog.note("音质 $lv 命中风控/需登录，停止逐档降档（code=${rs.first { isBlocking(it) }.code()}）")
            return LevelResult.Blocked
        }
        for (r in rs) {
            if (r.code() != 200) continue
            val first = (r["data"] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject
            val u = first?.str("url")
                ?: first?.str("freeTrialInfo", "url")?.takeIf { it.startsWith("http") }
            if (u.isNullOrBlank()) continue
            // 服务端降档返回时，实际档位以它返回的为准，
            // 否则界面会把 standard 的地址标成 Hi-Res。
            val got = first?.str("level", "levelName", "quality")
                ?.takeIf { it.isNotBlank() }?.let { nmLevelKey(it) }
            val real = got?.takeIf { it in NM_LEVELS } ?: lv
            return LevelResult.Hit(UrlHit(real, u))
        }
        return LevelResult.Miss
    }

    /**
     * 解析播放地址，返回「地址 + 实际档位」。
     *
     * 从指定档位起沿 NM_LEVELS 往回退：前 3 档与 standard 并发探测，
     * 结果仍严格按音质从高到低选取，剩下的档位才串行补探。
     * 某一档请求失败（网络异常 / 非 200 / 业务码异常 / 地址为空）就自动
     * 更换上一音质，直到 standard 或拿到可用地址。风控、需要登录这类错误
     * 换档无意义，直接终止，避免把 9 个档位各打一遍把服务端打爆。
     */
    private fun resolveAudioUrl(
        id: Long,
        quality: String,
        level: String?,
        client: OkHttpClient,
    ): Pair<String, String>? {
        val start = level?.takeIf { it.isNotBlank() }?.let { nmLevelKey(it) }
            ?: nmLevelOfQuality(quality)
        val chain = descendingLevels(start)
        // 26.11.69：整条链最多等 6 秒，单次请求超时也已压到 4 秒
        val deadline = System.currentTimeMillis() + 6_000L
        NmLog.note("开始取播放地址 id=$id 起始档位=$start 降档链=${chain.joinToString("→")}")

        // 26.11.69：档与档之间不再严格串行。
        //
        // 以前选了无损但这首歌只有 320k，要一档一档等完几个 HTTP 往返才降到
        // 底 —— 这就是「点一首歌转半天」最实在的地方。现在把最可能命中的前
        // 3 档连同 standard 一起并发发出，一次 RTT 就能拿到结果。
        //
        // 关键是**按 chain 顺序取第一个命中**：不能一看到 standard 有地址就
        // 直接用，否则请求 hires 时会跳过中间的 lossless / exhigh，下载记录
        // 和音质显示都会错。并发只是「同时问」，选档仍然严格从高到低。
        val probe = (chain.take(3) + "standard").distinct()
        val probed = runBlocking(Dispatchers.IO) {
            probe.map { lv -> lv to async { tryLevel(id, lv, client) } }
                .map { (lv, d) -> lv to d.await() }
        }
        // 任一档命中风控 / 需登录，换任何档位都没意义，立即终止
        if (probed.any { it.second is LevelResult.Blocked }) {
            NmLog.note("取地址命中风控，停止降档 id=$id")
            return null
        }
        for (lv in chain) {
            val r = probed.firstOrNull { it.first == lv }?.second
            if (r is LevelResult.Hit) {
                NmLog.note("拿到地址（${r.hit.lv}）：${r.hit.url}")
                return r.hit.url to r.hit.lv
            }
        }
        NmLog.note("并发探测未命中，继续检查剩余档位 id=$id")

        // 剩余档位仍按高到低串行，且整条链受 6 秒总截止约束
        val rest = chain.filterNot { it in probe }
        for (lv in rest) {
            if (System.currentTimeMillis() > deadline) {
                NmLog.note("取地址超过 6 秒，停止逐档降档 id=$id")
                return null
            }
            when (val r = tryLevel(id, lv, client)) {
                is LevelResult.Hit -> {
                    NmLog.note("拿到地址（${r.hit.lv}）：${r.hit.url}")
                    return r.hit.url to r.hit.lv
                }
                LevelResult.Blocked -> return null
                LevelResult.Miss -> NmLog.note("音质 $lv 请求失败，继续降档")
            }
        }
        // v1 全部档位都拿不到时走 /song/url/match 解灰匹配（变灰歌曲 v1 地址为空）
        runCatching {
            val r = getOnce("/song/url/match", "id" to id.toString(), client = client)
            val u = ((r?.get("data") as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject)?.str("url")
            if (!u.isNullOrBlank()) {
                NmLog.note("match 解灰拿到地址：$u")
                return u to (level?.takeIf { it.isNotBlank() } ?: quality)
            }
        }
        // 兜底：拿游客 cookie 再走一遍降档链（未登录/登录态失效时常用）
        NmLog.note("登录态下取不到地址，尝试游客 cookie 再试一次")
        val anon = runCatching { anonCookie() }.getOrNull()
        if (!anon.isNullOrBlank()) {
            val saved = cookie
            cookie = anon
            try {
                for (lv in chain) {
                    if (System.currentTimeMillis() > deadline) break
                    when (val r = tryLevel(id, lv, client)) {
                        is LevelResult.Hit -> {
                            NmLog.note("游客 cookie 拿到地址（${r.hit.lv}）：${r.hit.url}")
                            return r.hit.url to r.hit.lv
                        }
                        LevelResult.Blocked -> break
                        LevelResult.Miss -> Unit
                    }
                }
            } finally {
                cookie = saved
            }
        }
        NmLog.note("所有档位与兜底方式都没有拿到播放地址 id=$id")
        return null
    }

    /** 游客 cookie 缓存：一次会话里重复取只会拖慢取地址，缓存 10 分钟 */
    private var anonCache: Pair<String, Long>? = null

    /**
     * 游客 cookie：未登录状态下部分接口会报「需要验证」，用它规避。
     *
     * 走 getOnce 而不是 get()：get() 失败会重试两次并 sleep（约 1 秒），
     * 而这个接口常在取地址的兜底路径上被调用，多等一秒就是「半天播不出来」。
     */
    fun anonCookie(): String? {
        anonCache?.let { (ck, t) ->
            if (System.currentTimeMillis() - t < 10 * 60 * 1000L) return ck
        }
        val r = getOnce("/register/anonimous") ?: return null
        val ck = r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
        NmLog.note("游客 cookie：${if (ck.isNullOrBlank()) "未拿到" else "已拿到"}")
        if (!ck.isNullOrBlank()) anonCache = ck to System.currentTimeMillis()
        return ck
    }

    /** realIP 兜底用的国内 IP（网易对境外 IP 会返回 460 cheating） */
    private const val FALLBACK_CN_IP = "116.25.146.177"

    /**
     * 歌词：/lyric 是免登录接口，走一条独立的快速通道。
     *
     * 通用 get() / getOnce() 会追加 unblock、randomCNIP、登录 cookie、timestamp，
     * 失败还要重试两次并 sleep；带上整串清洗后的 cookie 后 URL 接近 4KB。
     * 而切歌时歌词原本排在 /song/detail 之后才发出，详情一慢就跟着干等 ——
     * 这两处叠起来就是「歌词半天不出来」。
     *
     * 这里只发 /lyric?id=<id>：不带登录态、不带解灰、不重试，一次往返。
     * 主请求拿不到才补一次带 randomCNIP 的请求（境外 IP 的 460 兜底），
     * 同样不带 cookie、不带 unblock、不重试。其余网易云接口的
     * randomCNIP 规则不变。响应按标准结构取 lrc.lyric。
     */
    fun lyric(id: Long): String {
        fun once(url: String): String = try {
            val req = Request.Builder().url(url).build()
            // 26.11.69：歌词走独立客户端，不占取地址的连接池
            lyricClient.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req(url)
                NmLog.res(url, r.code, t)
                val o = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
                    ?: return ""
                o.obj("lrc")?.str("lyric")?.takeIf { it.isNotBlank() }
                    ?: o.str("lyric").orEmpty()
            }
        } catch (e: Exception) {
            NmLog.note("歌词独立请求失败 id=$id ${e.message}")
            ""
        }
        val main = once("$NM_BASE/lyric?id=$id")
        if (main.isNotBlank()) return main
        NmLog.note("歌词主请求为空，补一次 randomCNIP 请求 id=$id")
        val fallback = once("$NM_BASE/lyric?id=$id&randomCNIP=true")
        if (fallback.isBlank()) NmLog.note("歌词为空 id=$id")
        return fallback
    }

    // ================= 逐字歌词 /lyric/new =================

    /** 逐字歌词结果：解析后的模型 + 行级 LRC 文本（老歌词列表仍吃它） */
    data class LyricResult(val word: WordLyric, val lrc: String)

    /**
     * 新歌词接口 /lyric/new：yrc 逐字原文 + ytlrc / yromalrc 逐字版多语言轨
     * + tlyric / romalrc 通用兜底轨。
     *
     * 任何失败都返回 null，由 [lyricWordWithFallback] 退回老 /lyric：
     * 网络异常 / HTTP 非 2xx / 空响应 / code 明确非 200 /
     * yrc 与 lrc 都为空 / JSON 解析异常。
     *
     * 和老歌词一样走免登录快速通道：不带 cookie、不带 unblock、不重试、一次往返。
     */
    fun lyricNew(id: Long): LyricResult? {
        val url = "$NM_BASE/lyric/new?id=$id"
        val body = try {
            val req = Request.Builder().url(url).build()
            // 26.11.69：逐字歌词同样走独立客户端
            lyricClient.newCall(req).execute().use { r ->
                NmLog.req(url)
                val b = r.body?.string().orEmpty()
                NmLog.res(url, r.code, b)
                if (!r.isSuccessful || b.isBlank()) null else b
            }
        } catch (e: Exception) {
            NmLog.note("逐字歌词请求失败 id=$id ${e.message}")
            null
        } ?: return null

        return try {
            val o = json.parseToJsonElement(body) as? JsonObject ?: return null
            val code = o["code"]?.jsonPrimitive?.content?.toIntOrNull()
            if (code != null && code != 200) {
                NmLog.note("逐字歌词业务码异常 id=$id code=$code，退回老歌词")
                return null
            }
            fun field(k: String): String =
                (o[k] as? JsonObject)?.get("lyric")?.jsonPrimitive?.content.orEmpty()
            val yrc = field("yrc")
            val lrc = field("lrc")
            if (yrc.isBlank() && lrc.isBlank()) {
                NmLog.note("逐字歌词 yrc/lrc 都为空 id=$id，退回老歌词")
                return null
            }
            val w = WordLyricParser.fromNewLyric(o)
            // 新接口有时只给 yrc 不给 lrc：用逐字行拼一份行级 LRC，
            // 否则第 1 期还在用的老歌词列表会显示「暂无歌词」。
            val text = lrc.ifBlank { w.toLrc() }
            NmLog.note(
                "逐字歌词生效 id=$id 行=${w.lines.size} 逐字=${w.hasWordTiming} " +
                    "译文=${w.hasTranslation} 罗马音=${w.hasRoman} lrc=${text.length}字"
            )
            LyricResult(w, text)
        } catch (e: Exception) {
            NmLog.note("逐字歌词解析失败 id=$id ${e.message}，退回老歌词")
            null
        }
    }

    /**
     * 歌词总入口：新 /lyric/new 优先，**任何失败一律退回老 /lyric**（再退本地文件）。
     * 老接口拿到的 LRC 也过一遍 WordLyricParser，保证逐字渲染链路统一，
     * 第 3 期换 UI 时不用再改数据层。
     */
    fun lyricWordWithFallback(id: Long): LyricResult {
        val nw = lyricNew(id)
        if (nw != null) return nw
        NmLog.note("退回老歌词接口 /lyric?id=$id")
        val old = runCatching { lyric(id) }.getOrDefault("")
        return LyricResult(WordLyricParser.parse(lrc = old), old)
    }

    // ================= 聚合搜索（一次性搜歌手 / 专辑 / 歌曲） =================

    /**
     * 一次输入同时把三类实体都搜出来，三路并发，总耗时等于最慢那一路。
     * 第 2 期搜索页直接渲染 [Aggregate.mixed]，不再「先出歌曲再补专辑」。
     */
    data class Aggregate(
        val songs: List<Song>,
        val albums: List<Group>,
        val artists: List<Group>,
        /** 26.11.87：搜索分类新增「歌单」(type=1000)，默认空不影响三路主链 */
        val playlists: List<Group> = emptyList(),
    ) {
        /** Apple Music「Top Results」式混合结果：不同实体类型在同一条列表里 */
        sealed class Hit {
            data class Artist(val g: Group) : Hit()
            data class SongHit(val s: Song) : Hit()
            data class Album(val g: Group) : Hit()
        }

        val mixed: List<Hit>
            get() = buildList {
                artists.firstOrNull()?.let { add(Hit.Artist(it)) }
                songs.take(6).forEach { add(Hit.SongHit(it)) }
                albums.take(2).forEach { add(Hit.Album(it)) }
                songs.drop(6).forEach { add(Hit.SongHit(it)) }
            }
    }

    /** 异步单次请求：真可取消，到点直接 call.cancel()，不再留同步线程空转 */
    private suspend fun getSearch(
        path: String,
        vararg params: Pair<String, String>,
    ): JsonObject? {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        sb.append("unblock=true&")
        if (params.none { it.first.equals("randomCNIP", ignoreCase = true) }) {
            sb.append("randomCNIP=true&")
        }
        // /search 与 /song/detail 都不需要登录态。旧 get() 会把整串 cookie
        // 拼进 URL（接近 4KB），代理转发更慢、也更容易触发风控。
        sb.append("timestamp=").append(System.currentTimeMillis())
        val url = sb.toString()
        val call = searchClient.newCall(Request.Builder().url(url).build())
        val done = CompletableDeferred<JsonObject?>()
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                NmLog.note("搜索请求失败：$path -> ${e.message}")
                done.complete(null)
            }
            override fun onResponse(c: Call, r: Response) {
                var el: JsonObject? = null
                var code = 0
                var body = ""
                try {
                    r.use { resp ->
                        body = resp.body?.string().orEmpty()
                        code = resp.code
                        el = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                    }
                } catch (e: Exception) {
                    NmLog.note("搜索响应读取失败：$path -> ${e.message}")
                }
                // 26.11.64：结果先交给界面，日志放到 complete 之后。
                // NmLog 已改为异步队列，但顺序上也要保证任何日志动作都不挡首屏。
                done.complete(el)
                NmLog.req(url)
                NmLog.res(url, code, body)
            }
        })
        return try {
            withTimeoutOrNull(SEARCH_CALL_MS) { done.await() }
        } finally {
            // 超时或上层取消搜索时，把还在飞的请求真正断掉
            if (!done.isCompleted) call.cancel()
        }
    }

    private suspend fun rawSearchAsync(
        keywords: String,
        type: Int,
        limit: Int,
        offset: Int = 0,
    ): JsonObject? =
        getSearch(
            "/search",
            "keywords" to keywords,
            "type" to type.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        )

    /** 单个分类的一页结果：滑到底时按当前分类继续取，不重发另外两类 */
    data class SearchPage(
        val songs: List<Song> = emptyList(),
        val albums: List<Group> = emptyList(),
        val artists: List<Group> = emptyList(),
        /** 这一页是否成功返回（超时/异常时 false，调用方据此决定是否允许再试） */
        val ok: Boolean = false,
    ) {
        val size: Int get() = songs.size + albums.size + artists.size
    }

    /** type: 1 歌曲 / 10 专辑 / 100 歌手 */
    suspend fun searchPage(
        keywords: String,
        type: Int,
        limit: Int,
        offset: Int,
    ): SearchPage {
        val root = rawSearchAsync(keywords, type, limit, offset) ?: return SearchPage()
        return when (type) {
            1 -> SearchPage(
                songs = runCatching {
                    searchSongs(keywords, limit, offset, enrichResult = false, rootOverride = root)
                }.getOrDefault(emptyList()),
                ok = true,
            )
            10 -> SearchPage(
                albums = runCatching {
                    searchAlbums(keywords, limit, offset, fallback = false, rootOverride = root)
                }.getOrDefault(emptyList()),
                ok = true,
            )
            else -> SearchPage(
                artists = runCatching {
                    searchArtists(keywords, limit, offset, fallback = false, rootOverride = root)
                }.getOrDefault(emptyList()),
                ok = true,
            )
        }
    }

    /**
     * 26.11.63：流式聚合搜索。
     *
     * 旧实现三个坑叠一起，界面必须等满 8 秒才出结果：
     * 1. 同步 execute() 不响应协程取消，withTimeoutOrNull 到点只是让 await 返回，
     *    底层线程还在跑，连续搜几次就堆住；
     * 2. 歌曲路在 /search 之后还串行等一次 /song/detail，5+5s 必然撞上 8s 截止，
     *    歌曲结果经常被整批砍空，然后这个残缺结果还被写进缓存；
     * 3. 三路全部 await 完才一次性合并，首屏时间等于最慢那一路。
     *
     * 现在每路独立异步、可真取消，谁先回来就先回调谁；一轮最多 3 个请求，
     * 不做详情补全、不做二次兜底。封面补全与兜底聚合交给上层后台处理。
     */
    suspend fun searchAllStream(
        keywords: String,
        limit: Int = 20,
        onPart: (songs: List<Song>?, albums: List<Group>?, artists: List<Group>?) -> Unit,
    ): Aggregate = coroutineScope {
        val s = async(Dispatchers.IO) {
            val root = rawSearchAsync(keywords, 1, limit) ?: return@async emptyList<Song>()
            runCatching {
                searchSongs(keywords, limit, 0, enrichResult = false, rootOverride = root)
            }.getOrDefault(emptyList())
        }
        val a = async(Dispatchers.IO) {
            val root = rawSearchAsync(keywords, 10, 10) ?: return@async emptyList<Group>()
            runCatching {
                searchAlbums(keywords, 10, 0, fallback = false, rootOverride = root)
            }.getOrDefault(emptyList())
        }
        val r = async(Dispatchers.IO) {
            val root = rawSearchAsync(keywords, 100, 10) ?: return@async emptyList<Group>()
            runCatching {
                searchArtists(keywords, 10, 0, fallback = false, rootOverride = root)
            }.getOrDefault(emptyList())
        }
        // 一路回来就立刻推给界面，不等另外两路；null 表示「这一路还没更新」
        launch { runCatching { onPart(s.await(), null, null) } }
        launch { runCatching { onPart(null, a.await(), null) } }
        launch { runCatching { onPart(null, null, r.await()) } }
        Aggregate(s.await(), a.await(), r.await())
    }

    /** 兼容旧调用点：不需要流式更新时的同步包装 */
    fun searchAll(keywords: String, limit: Int = 20): Aggregate =
        runBlocking(Dispatchers.IO) { searchAllStream(keywords, limit) { _, _, _ -> } }

    /**
     * 专辑 / 歌手的歌曲列表。
     * 专辑走 /album，歌手走 /artist/songs；拿到后再用 song/detail 补全。
     */
    /** 专辑 / 歌手的歌曲列表。歌手支持 offset 分页（滑到底继续加载） */
    /**
     * 26.11.70：取一张网易云歌单的**全部**曲目，按云端 trackIds 顺序返回。
     *
     * /playlist/detail 的 playlist.tracks 经常只给前 10 首，完整 id 列表在
     * playlist.trackIds。以前直接拿 tracks 用，收藏歌单同步下来只有十首，
     * 点开歌单页也一样。这里用 trackIds 做骨架，缺的歌按 100 个一批走
     * /song/detail 补齐，最后 enrich 补封面 / 专辑 / 歌手。
     */
    fun playlistSongs(id: Long, client: OkHttpClient = syncClient): List<Song> {
        val root = runCatching {
            get("/playlist/detail", "id" to id.toString(), http = client, tries = 1)
        }.getOrNull() ?: return emptyList()
        val pl = root.obj("playlist") ?: root.obj("result") ?: root
        val trackArr = ((pl["tracks"] as? JsonElement)?.jsonArray
            ?: (root["tracks"] as? JsonElement)?.jsonArray)
            ?.mapNotNull { e -> (e as? JsonObject)?.let { toSong(it) } }
            .orEmpty()
        val ids = ((pl["trackIds"] as? JsonElement)?.jsonArray)
            ?.mapNotNull { e ->
                (e as? JsonObject)?.num("id")
                    ?: (e as? JsonPrimitive)?.content?.toLongOrNull()
            }
            .orEmpty().filter { it > 0L }
        if (ids.isEmpty()) {
            NmLog.note("歌单 $id 无 trackIds，沿用 tracks ${trackArr.size} 首")
            return enrich(trackArr)
        }
        val byId = HashMap<Long, Song>()
        trackArr.forEach { so -> if (so.nmId > 0L) byId[so.nmId] = so }
        val missing = ids.filterNot { byId.containsKey(it) }
        if (missing.isNotEmpty()) {
            NmLog.note("歌单 $id 共 ${ids.size} 首，补齐缺失 ${missing.size} 首")
            missing.chunked(100).forEach { chunk ->
                runCatching { songDetail(chunk, client) }.getOrDefault(emptyMap())
                    .forEach { (k, v) -> if (!byId.containsKey(k)) byId[k] = v }
            }
        }
        val ordered = ids.mapNotNull { byId[it] }.ifEmpty { trackArr }
        // 只对信息不完整的歌补详情。songDetail 已经把 trackIds 里缺的那些
        // 完整取回来了，再对整张歌单调一遍 enrich 会把请求量直接翻倍。
        val incomplete = ordered.filter {
            it.coverUrl.isNullOrBlank() || it.album.isBlank() || it.artist.isBlank()
        }
        if (incomplete.isEmpty()) return ordered
        val detail = runCatching {
            songDetail(incomplete.map { it.nmId }, client)
        }.getOrDefault(emptyMap())
        return ordered.map { s ->
            val d = detail[s.nmId] ?: return@map s
            s.copy(
                title = d.title.takeIf { it.isNotBlank() } ?: s.title,
                artist = d.artist.takeIf { it.isNotBlank() } ?: s.artist,
                album = d.album.takeIf { it.isNotBlank() } ?: s.album,
                coverUrl = d.coverUrl ?: s.coverUrl,
                mvId = s.mvId.takeIf { it > 0L } ?: d.mvId,
            )
        }
    }

    fun songsOf(kind: String, id: Long, limit: Int = 30, offset: Int = 0): List<Song> {
        NmLog.note("取 $kind 的歌曲 id=$id offset=$offset")
        val root = if (kind == "album") {
            get("/album", "id" to id.toString())
        } else if (kind == "playlist") {
            // 26.11.70：走全量曲目拉取，见 [playlistSongs]
            return playlistSongs(id)
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

    /**
     * 登录凭证：优先取响应里的 cookie 字段（/login/cellphone 返回的是一整串
     * Set-Cookie 文本，setter 会清洗并补 os=pc）。有些部署 cookie 字段缺失，
     * 但顶层给了 token，用它构造 MUSIC_U 作为兜底，不至于「登录 200 却没登录态」。
     */
    private fun cookieOf(r: JsonObject): String? {
        val raw = r.str("cookie")?.takeIf { it.contains("=") } ?: findCookie(r)
        if (!raw.isNullOrBlank()) return raw
        val tk = r.str("token")?.takeIf { it.isNotBlank() && it.length > 20 }
        return tk?.let { "MUSIC_U=$it" }
    }

    private fun msgOf(r: JsonObject): String? = r.str("message", "msg")

    /** 只有「换取登录凭证」的接口才精简参数；扫码类与 login/status 等仍走常规形式 */
    private fun isCredentialPath(path: String): Boolean =
        path == "/login/cellphone" || path == "/register/cellphone" ||
            path.startsWith("/captcha/")

    /**
     * 是否命中网易风控。三种形态：
     * - code -462：易盾滑块，data.url 是验证页，data.verifyToken 是一次性 token
     * - code 10004 / hitType 800001：「当前登录存在安全风险」，带 redirectUrl
     * - 顶层直接给了 redirectUrl 或 data 里带 verifyToken
     */
    private fun isRisk(r: JsonObject): Boolean {
        val c = r.code()
        if (c == -462 || c == 10004) return true
        if (r.str("redirectUrl")?.startsWith("http") == true) return true
        val d = r.obj("data") ?: return false
        return d.containsKey("verifyToken") || d.containsKey("verifyUrl") || d["verifyId"] != null
    }

    /** 把风控响应解析成可展示的结果：提示文案 + 可打开的验证页 */
    private fun riskResult(r: JsonObject): LoginResult {
        val d = r.obj("data")
        val verify = (d?.str("url") ?: d?.str("verifyUrl") ?: r.str("verifyUrl"))
            ?.takeIf { it.startsWith("http") }
        val redirect = r.str("redirectUrl")?.takeIf { it.startsWith("http") }
        val block = d?.str("blockText")?.trim()?.takeIf { it.isNotBlank() }
        val msg = msgOf(r) ?: d?.str("message") ?: block
            ?: "当前登录存在安全风险，请先完成验证后再登录"
        NmLog.note(
            "命中网易风控 code=${r.code()} 验证页=${if (verify != null) "有" else "无"} " +
                "跳转页=${if (redirect != null) "有" else "无"} 提示=$msg",
        )
        return LoginResult(
            code = r.code(), message = msg,
            verifyUrl = verify, redirectUrl = redirect, risk = true,
        )
    }

    /**
     * 发送短信验证码。返回 null 表示已发送，否则是失败原因。
     *
     * /captcha/sent 与 /captcha/sent/v1 都能发，不同部署上可用情况不一样
     * （有的只认其中一条），所以第一条失败就换另一条再试一次。
     */
    fun sendCaptcha(phone: String): LoginResult {
        val p = cleanPhone(phone)
        val r1 = get("/captcha/sent", "phone" to p, "ctcode" to "86")
        if (r1.code() == 200) return LoginResult(sent = true, code = 200)
        // 风控是确定性结果，重试另一条 sent 接口只会再拿一个新 token，别浪费
        if (isRisk(r1)) return riskResult(r1)
        val r2 = get("/captcha/sent/v1", "phone" to p, "ctcode" to "86")
        if (r2.code() == 200) return LoginResult(sent = true, code = 200)
        if (isRisk(r2)) return riskResult(r2)
        return LoginResult(
            code = r2.code(),
            message = (msgOf(r2).takeIf { !it.isNullOrBlank() } ?: msgOf(r1))
                ?: "发送失败（code ${r1.code()}）",
        )
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
        val p = cleanPhone(phone)
        val c = captcha.trim()
        // 按实际可用的调用形式来：/login/cellphone?phone=..&captcha=..&randomCNIP=true
        // 不带 countrycode（部分部署带 86 反而校验失败），验证码本身就是完整凭据。
        // randomCNIP 已由 get() 统一追加，登录接口尤其需要它。
        var r = get("/login/cellphone", "phone" to p, "captcha" to c)
        // 风控立刻返回：绝不能重试，也不能去 /captcha/verify。
        // 重试会刷掉刚才那个 verifyToken，verify 还会把验证码核销，
        // 用户验证完回来反而彻底登不上。
        if (isRisk(r)) return riskResult(r)
        var ck = cookieOf(r)
        if (ck == null) {
            NmLog.note("验证码登录首轮未拿到凭证（code=${r.code()}），补 countrycode 重试")
            r = get("/login/cellphone", "phone" to p, "captcha" to c, "countrycode" to "86")
            if (isRisk(r)) return riskResult(r)
            ck = cookieOf(r)
        }
        val first = LoginResult(ck, r.code(), msgOf(r))
        if (first.ok) return first
        // 少数部署要求先 /captcha/verify 再登录（虽然多数会因此核销验证码）
        val v = get("/captcha/verify", "phone" to p, "captcha" to c, "ctcode" to "86")
        if (isRisk(v)) return riskResult(v)
        if (v.code() == 200) {
            val r2 = get("/login/cellphone", "phone" to p, "captcha" to c)
            if (isRisk(r2)) return riskResult(r2)
            val ck2 = cookieOf(r2)
            if (ck2 != null) return LoginResult(ck2, r2.code(), msgOf(r2))
        }
        NmLog.note("验证码登录失败 code=${first.code} msg=${first.message}")
        return first
    }

    /** 注册（注册成功即登录） */
    fun register(phone: String, password: String, captcha: String, nickname: String): LoginResult {
        val r = get(
            "/register/cellphone",
            "phone" to cleanPhone(phone), "password" to password,
            "captcha" to captcha.trim(), "nickname" to nickname, "countrycode" to "86",
        )
        if (isRisk(r)) return riskResult(r)
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
     * 26.11.70：网易云账号下**全部**歌单（自建 + 收藏/订阅 + 我喜欢的音乐）。
     *
     * 以前按 creator.userId == uid 把收藏歌单整批过滤掉了，注释还写着
     * 「收藏的歌单不属于我」——用户要的就是「所有网易云歌单」也同步到本地，
     * 所以这里不再过滤，只把「不是我创建的」标成 readOnly：这类歌单云端
     * 不允许改名 / 删除 / 加删歌 / 排序 / 换封面，本地编辑不回写即可。
     *
     * 分页拉到 more=false 或返回不足一页，避免账号歌单超过 1000 张时被截断。
     */
    fun userPlaylists(uid: Long): List<Group> {
        if (uid <= 0L) return emptyList()
        val out = LinkedHashMap<Long, Group>()
        val limit = 1000
        for (page in 0 until 10) {
            val root = runCatching {
                get(
                    "/user/playlist",
                    "uid" to uid.toString(),
                    "limit" to limit.toString(),
                    "offset" to (page * limit).toString(),
                    http = syncClient, tries = 1,
                )
            }.getOrNull() ?: break
            val arr = resultArray(root, "playlist")
            arr.forEach { o ->
                val id = idOf(o)
                val name = o.str("name").orEmpty()
                if (id <= 0L || name.isBlank() || out.containsKey(id)) return@forEach
                val creatorId = o.obj("creator")?.num("userId")
                val subscribed = o.flag("subscribed") ||
                    (creatorId != null && creatorId != uid)
                val special = o.num("specialType") ?: 0L
                // 26.11.71：specialType=5 是「我喜欢的音乐」，合并进本地「我的喜欢」
                // 而不是当成普通歌单再下载一份；名字兜底防接口不给 specialType。
                val cloudLike = special == 5L || name == "我喜欢的音乐"
                val readOnly = subscribed || special > 0L
                val count = (o.num("trackCount") ?: 0L).toInt()
                val stamp = "${o.num("updateTime") ?: 0L}-$count"
                out[id] = Group(
                    title = name,
                    subtitle = "${if (readOnly) "网易云收藏歌单" else "网易云歌单"} · ${count} 首",
                    cover = pic(o.str("coverImgUrl", "picUrl", "cover")),
                    songs = emptyList(),
                    source = SRC_NETEASE,
                    nmId = id,
                    nmKind = "playlist",
                    readOnly = readOnly,
                    cloudStamp = stamp,
                    trackCount = count,
                    cloudLike = cloudLike,
                )
            }
            NmLog.note("我的歌单第 $page 页返回 ${arr.size} 个，累计 ${out.size} 个")
            // more 字段缺失时不能当成「没有下一页」，否则第一页就被截断
            val more = (root["more"] as? JsonPrimitive)?.content
            if (arr.size < limit || more == "false") break
        }
        return out.values.toList()
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

    /**
     * 26.11.71：把一批歌曲加进「我喜欢的音乐」歌单。
     *
     * 这个歌单虽然 specialType=5，但 /playlist/tracks?op=add 对它是可用的；
     * 一次别拼太多 id，按 50 个一批发。整批失败时退到 /like 单曲接口，
     * 避免某个 id 不被接受导致整批红心都加不上。
     */
    fun likeAdd(pid: Long, ids: List<Long>): Int {
        if (pid <= 0L || ids.isEmpty()) return 0
        var ok = 0
        ids.distinct().chunked(50).forEach { batch ->
            if (playlistAdd(pid, batch)) ok += batch.size
            else ok += likeBatch(batch, true)
        }
        NmLog.note("上传红心 $ok/${ids.size} 首 pid=$pid")
        return ok
    }

    /**
     * 26.11.74：从「我喜欢的音乐」批量移除，返回**真正确认删掉**的歌曲 id。
     *
     * 以前优先 /playlist/tracks?op=del：它对 specialType=5 的红心歌单会回
     * code 200 但红心状态不变，应用以为成功、云端其实没删，下次同步又把
     * 这些歌并回本地。现在以 /like?like=false 的结果为准，只有它失败的
     * 那几首才用 playlistDel 批量兜底。
     */
    fun likeRemoveIds(pid: Long, ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val all = ids.distinct()
        val ok = likeBatchIds(all, false).toMutableSet()
        val failed = all - ok
        if (failed.isNotEmpty() && pid > 0L) {
            failed.chunked(50).forEach { batch ->
                if (playlistDel(pid, batch)) ok += batch
            }
        }
        NmLog.note("移除红心 ${ok.size}/${all.size} 首 pid=$pid（/like 权威）")
        return ok
    }

    fun likeRemove(pid: Long, ids: List<Long>): Int = likeRemoveIds(pid, ids).size

    /**
     * /like 逐首并发（6 个一组）。走同步专用客户端，不抢播放链路的连接。
     * 返回真正返回 200 的 id，调用方据此判断哪些还要兜底 / 留在重试队列。
     */
    private fun likeBatchIds(ids: List<Long>, like: Boolean): Set<Long> =
        runBlocking(Dispatchers.IO) {
            val out = LinkedHashSet<Long>()
            ids.distinct().chunked(6).forEach { grp ->
                grp.map { id -> async { id to likeSong(id, like) } }
                    .awaitAll().forEach { (id, ok) -> if (ok) out += id }
            }
            out
        }

    /** 整批 /playlist/tracks 失败时才用；保留给 likeAdd 的兜底计数 */
    private fun likeBatch(ids: List<Long>, like: Boolean): Int =
        likeBatchIds(ids, like).size

    /** /like?id=&like=true|false：红心歌单的单曲写入兜底 */
    fun likeSong(id: Long, like: Boolean): Boolean {
        if (id <= 0L) return false
        val r = runCatching {
            get(
                "/like", "id" to id.toString(), "like" to like.toString(),
                http = syncClient, tries = 1,
            )
        }.getOrNull() ?: return false
        return r.code() == 200
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

    // -------------------------------------------------------------------
    // 26.11.66：云端歌单完整同步
    //
    // 所有写接口都复用 get()：它已经自动带 cookie、timestamp、randomCNIP
    // 与 unblock，/playlist/tracks 要求的 timestamp 不需要额外拼。
    // -------------------------------------------------------------------

    /** 新建云端歌单，返回新歌单 id；失败返回 0 */
    fun playlistCreate(name: String): Long {
        if (name.isBlank()) return 0L
        val r = get("/playlist/create", "name" to name)
        if (r.code() != 200) {
            NmLog.note("新建云端歌单失败 code=${r.code()}")
            return 0L
        }
        val v = r.obj("playlist")?.let { idOf(it) }
            ?: r.obj("data")?.let { idOf(it) }
            ?: idOf(r)
        NmLog.note("新建云端歌单「$name」-> id=$v")
        return v
    }

    /** 删除云端歌单（id 可多个，这里一次删一个） */
    /**
     * 26.11.73：取消收藏（取消订阅）别人的歌单。
     *
     * 收藏 / 订阅歌单不能走 /playlist/delete（那是删自己创建的），
     * 要用 /playlist/subscribe?t=0。用户点「删除」时本地和云端一起移除，
     * 下次同步才不会又把它下载回来。
     */
    fun playlistUnsubscribe(pid: Long): Boolean {
        if (pid <= 0L) return false
        val r = get("/playlist/subscribe", "t" to "0", "id" to pid.toString())
        val ok = r.code() == 200
        NmLog.note("取消收藏歌单 pid=$pid -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    fun playlistDelete(pid: Long): Boolean {
        if (pid <= 0L) return false
        val r = get("/playlist/delete", "id" to pid.toString())
        val ok = r.code() == 200
        NmLog.note("删除云端歌单 pid=$pid -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /** 全量更新歌单（描述 / 标签为空时只改名，避免把云端原有信息清掉） */
    fun playlistUpdate(pid: Long, name: String, desc: String = "", tags: String = ""): Boolean {
        if (pid <= 0L || name.isBlank()) return false
        val params = ArrayList<Pair<String, String>>()
        params += "id" to pid.toString()
        params += "name" to name
        if (desc.isNotBlank()) params += "desc" to desc
        if (tags.isNotBlank()) params += "tags" to tags
        val r = get("/playlist/update", *params.toTypedArray())
        return r.code() == 200
    }

    /** 调整云端「歌单列表」顺序：ids 为全部云端歌单 id 的目标顺序 */
    fun playlistOrder(ids: List<Long>): Boolean {
        val clean = ids.filter { it > 0L }
        if (clean.isEmpty()) return false
        val r = get("/playlist/order/update", "ids" to clean.joinToString(",", "[", "]"))
        val ok = r.code() == 200
        NmLog.note("云端歌单排序 ${clean.size} 个 -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /** 调整云端歌单内「歌曲」顺序：ids 为该歌单全部歌曲 id 的目标顺序 */
    fun songOrder(pid: Long, ids: List<Long>): Boolean {
        val clean = ids.filter { it > 0L }
        if (pid <= 0L || clean.isEmpty()) return false
        val r = get(
            "/song/order/update",
            "pid" to pid.toString(),
            "ids" to clean.joinToString(",", "[", "]"),
        )
        val ok = r.code() == 200
        NmLog.note("云端歌曲排序 pid=$pid ${clean.size} 首 -> ${if (ok) "成功" else "失败 code=${r.code()}"}")
        return ok
    }

    /**
     * 上传歌单封面：multipart/form-data，字段名 imgFile。
     * 参考 api-enhanced/public/playlist_cover_update.html。
     */
    fun playlistCover(pid: Long, bytes: ByteArray, mime: String = "image/jpeg"): Boolean {
        if (pid <= 0L || bytes.isEmpty()) return false
        val type = runCatching { mime.toMediaType() }
            .getOrDefault("image/jpeg".toMediaType())
        val fileBody = bytes.toRequestBody(type)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("imgFile", "cover.jpg", fileBody)
            .build()
        val sb = StringBuilder(NM_BASE)
            .append("/playlist/cover/update?id=").append(pid)
            .append("&imgSize=300&imgX=0&imgY=0")
            .append("&timestamp=").append(System.currentTimeMillis())
        if (cookie.isNotBlank()) sb.append("&cookie=").append(encCookie(cookie))
        val url = sb.toString()
        return try {
            val req = Request.Builder().url(url).post(body).build()
            Api.client.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req(url)
                NmLog.res(url, r.code, t)
                val el = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
                val ok = r.isSuccessful && (el == null || el.code() == 200 || el.code() == -1)
                NmLog.note("上传歌单封面 pid=$pid -> ${if (ok) "成功" else "失败 HTTP ${r.code}"}")
                ok
            }
        } catch (e: Exception) {
            NmLog.err(url, e)
            false
        }
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

    /**
     * 26.11.81：退出登录必须调用 /logout 注销服务端会话。
     * 之前 nmLogout 只清本地 cookie，服务端那边的凭证仍然有效 —— 用同一份
     * cookie 还能继续访问账号数据。这里显式带上旧凭证，因为调用时本地
     * Netease.cookie 已经被清空了。
     */
    fun logout(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val r = runCatching { get("/logout", cookieOverride = cookie) }
            .getOrDefault(JsonObject(emptyMap()))
        val ok = r.code() == 200
        NmLog.note("退出登录 /logout 返回 code=${r.code()}，${if (ok) "已注销" else "未确认"}")
        return ok
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

    // ================= 专辑 / 歌手简介 =================

    /** ugc 简介接口返回的可用信息（字段名按常见写法容错提取） */
    data class UgcInfo(
        val desc: String = "",
        val cover: String? = null,
        val title: String = "",
        val artist: String = "",
        val artistId: Long = 0,
        /** 26.11.80：接口确实通了（哪怕这张专辑没有简介正文），用于区分「暂无简介」和「获取失败」 */
        val ok: Boolean = false,
    )

    /** 歌手详细信息：简述 + 分段介绍 + 相关专栏标题 */
    data class ArtistDescInfo(
        val brief: String = "",
        val sections: List<Pair<String, String>> = emptyList(),
        val topics: List<String> = emptyList(),
        val ok: Boolean = false,
    )

    /** 简介正文可能落在这些键里，不同部署的字段名不完全一致 */
    private val descKeys = listOf(
        "description", "desc", "briefDesc", "introduction", "content",
        "albumDesc", "artistDesc", "descption", "brief", "summary", "detail", "descInfo",
    )

    /** 把任意 JSON 值转成可显示的纯文本：字符串直接用，数组分段拼，对象取 ti/txt */
    private fun textOfValue(v: JsonElement?): String = when (v) {
        is JsonPrimitive -> v.content.takeUnless { it.isBlank() || it == "null" } ?: ""
        is JsonObject -> {
            val body = v.str("txt", "text", "content", "description", "desc", "briefDesc").orEmpty()
            val ti = v.str("ti", "title")
            if (body.isBlank()) "" else if (ti.isNullOrBlank()) body else "【$ti】\n$body"
        }
        else -> runCatching {
            v?.jsonArray?.mapNotNull { e -> textOfValue(e).takeIf { it.isNotBlank() } }
                ?.joinToString("\n").orEmpty()
        }.getOrDefault("")
    }

    /** 在当前层按优先键找简介，找不到再下钻一层常见容器 */
    private fun descText(o: JsonObject): String {
        for (k in descKeys) {
            val t = textOfValue(o[k])
            if (t.isNotBlank()) return t.trim()
        }
        for (k in listOf("data", "album", "artist", "info", "detail", "ugc", "resource")) {
            val sub = o[k] as? JsonObject ?: continue
            val t = descText(sub)
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun artistIdOf(o: JsonObject): Long {
        val direct = idOf(o, "artistId")
        if (direct > 0) return direct
        o.obj("artist")?.let { a -> idOf(a).takeIf { it > 0 }?.let { return it } }
        for (k in listOf("ar", "artists")) {
            val first = runCatching { (o[k] as? JsonElement)?.jsonArray?.firstOrNull() as? JsonObject }
                .getOrNull()
            if (first != null) idOf(first).takeIf { it > 0 }?.let { return it }
        }
        return 0
    }

    /** ugc 简介：/ugc/album/get 与 /ugc/artist/get 结构一致，统一解析 */
    private fun ugc(path: String, id: Long): UgcInfo {
        if (id <= 0) return UgcInfo()
        val r = get(path, "id" to id.toString())
        if (r.code() != 200) {
            NmLog.note("简介接口 $path 返回 code=${r.code()}")
            return UgcInfo()
        }
        val d = r.obj("data") ?: r
        val desc = descText(d).ifBlank { descText(r) }
        val artist = artistsOf(d).ifBlank {
            d.obj("artist")?.str("name") ?: d.str("artist", "artistName", "singer") ?: ""
        }
        NmLog.note("简介接口 $path id=$id 简介长度=${desc.length}")
        return UgcInfo(
            desc = desc,
            cover = pic(d.str("picUrl", "cover", "img1v1Url", "avatar", "pic")),
            title = d.str("name", "title", "albumName", "artistName") ?: "",
            artist = artist,
            artistId = artistIdOf(d),
            ok = true,
        )
    }

    // ================= 26.11.82：私信 / 播客通用桥 =================
    /**
     * 私信、通知、播客这一批接口都走同一套 GET（自动带 cookie / timestamp /
     * randomCNIP / unblock）。get 是私有的，这里给 NmSocial 一个公开入口，
     * 不复制一遍 URL 拼接与重试逻辑。
     */
    fun socialGet(path: String, vararg params: Pair<String, String>): JsonObject =
        get(path, *params)

    /**
     * 26.11.83：官方「一起听」示例里大量接口是 POST + form data（cookie 也放在
     * body 里），而 get() 只会拼 query。这里补一个同规格的 POST 入口，
     * 日志 / JSON 解析 / 异常兜底与 get() 完全一致。
     */
    fun socialPost(path: String, vararg params: Pair<String, String>): JsonObject {
        val ts = System.currentTimeMillis().toString()
        val url = "$NM_BASE$path?timestamp=$ts"
        val form = FormBody.Builder().apply {
            params.forEach { add(it.first, it.second) }
            if (cookie.isNotBlank()) add("cookie", cookie)
            add("timestamp", ts)
        }.build()
        NmLog.req(url)
        return try {
            val req = Request.Builder().url(url).post(form).build()
            Api.client.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.res(url, r.code, t)
                (runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject)
                    ?: JsonObject(emptyMap())
            }
        } catch (e: Exception) {
            NmLog.err(url, e)
            JsonObject(emptyMap())
        }
    }

    /**
     * 一起听各接口在示例里 GET / POST 混用，不同部署支持的 method 也不一致。
     * 先按示例发一次；不像成功就换另一种 method 再发，返回更像成功的那份。
     */
    fun socialCall(
        path: String,
        postFirst: Boolean,
        vararg params: Pair<String, String>,
    ): JsonObject {
        fun looksOk(r: JsonObject): Boolean {
            val c = (r["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (c == 200) return true
            // 明确的业务错误（301/400/404/460…）不能因为带 data 就算成功，
            // 否则不会换另一种 method 重试
            if (c != null && c > 0) return false
            val d = r["data"] as? JsonObject
            if (d != null && d.isNotEmpty()) return true
            return r.containsKey("roomInfo") || r.containsKey("result") ||
                r.containsKey("list") || r.containsKey("voicelists") ||
                r.containsKey("voices") || r.containsKey("programs") ||
                r.containsKey("records") || r.containsKey("songs") ||
                r.containsKey("msgs") || r["success"] == JsonPrimitive(true)
        }
        val first = if (postFirst) socialPost(path, *params) else socialGet(path, *params)
        if (looksOk(first)) return first
        val second = if (postFirst) socialGet(path, *params) else socialPost(path, *params)
        return if (looksOk(second) || second.size > first.size) second else first
    }

    /** 通用 multipart 上传：声音文件 / 封面共用（字段名由调用方给） */
    fun socialUpload(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        form: Map<String, String> = emptyMap(),
        fileField: String,
        fileName: String,
        bytes: ByteArray,
        mime: String,
    ): Boolean {
        if (bytes.isEmpty()) return false
        val type = runCatching { mime.toMediaType() }
            .getOrDefault("application/octet-stream".toMediaType())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(fileField, fileName, bytes.toRequestBody(type))
        }.build()
        val sb = StringBuilder(NM_BASE).append(path)
        query.forEach { (k, v) ->
            sb.append(if (sb.indexOf('?') < 0) '?' else '&')
                .append(k).append('=').append(enc(v))
        }
        sb.append(if (sb.indexOf('?') < 0) '?' else '&')
            .append("timestamp=").append(System.currentTimeMillis())
        if (cookie.isNotBlank()) sb.append("&cookie=").append(encCookie(cookie))
        val url = sb.toString()
        return try {
            val req = Request.Builder().url(url).post(body).build()
            Api.client.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req(url); NmLog.res(url, r.code, t)
                val el = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject
                val ok = r.isSuccessful && (el == null || el.code() == 200 || el.code() == -1)
                NmLog.note("上传 $path -> ${if (ok) "成功" else "失败 HTTP ${r.code}"}")
                ok
            }
        } catch (e: Exception) { NmLog.err(url, e); false }
    }

    fun ugcAlbum(id: Long): UgcInfo = ugc("/ugc/album/get", id)
    fun ugcArtist(id: Long): UgcInfo = ugc("/ugc/artist/get", id)

    /**
     * 26.11.80：专辑简介的主来源改走 /album。
     *
     * 之前简介页只请求 /ugc/album/get：那个端点要登录、部分部署根本不提供，
     * 而未登录时 loadIntro 干脆连请求都不发 —— 结果「登录后才能查看简介」
     * 或永远「暂无简介」。
     *
     * /album 不需要登录，专辑页取曲目用的就是它，返回 album.description /
     * briefDesc、picUrl、artist，顺手把封面和歌手名一起补齐；ugc 接口退为
     * 登录后的补充来源，两边取更长的那段简介。
     */
    fun albumIntro(id: Long): UgcInfo {
        if (id <= 0) return UgcInfo()
        val r = get("/album", "id" to id.toString())
        if (r.code() != 200) {
            NmLog.note("专辑详情 /album 返回 code=${r.code()}")
            return UgcInfo()
        }
        val a = r.obj("album") ?: r.obj("data")?.obj("album")
            ?: r.obj("data") ?: r
        val desc = descText(a).ifBlank { descText(r) }
        val artist = artistsOf(a).ifBlank {
            a.obj("artist")?.str("name") ?: a.str("artist", "artistName", "singer") ?: ""
        }
        val cover = pic(a.str("picUrl", "blurPicUrl", "cover", "pic"))
            ?: pic(r.obj("album")?.str("picUrl", "blurPicUrl", "cover"))
        NmLog.note("专辑详情 /album id=$id 简介长度=${desc.length} 封面=${!cover.isNullOrBlank()}")
        return UgcInfo(
            desc = desc,
            cover = cover,
            title = a.str("name", "title", "albumName") ?: "",
            artist = artist,
            artistId = artistIdOf(a),
            ok = true,
        )
    }

    /** 歌手详细信息：无论是否登录都能取，失败时返回空结构由 UI 提示 */
    fun artistDesc(id: Long): ArtistDescInfo {
        if (id <= 0) return ArtistDescInfo()
        val r = get("/artist/desc", "id" to id.toString())
        if (r.code() != 200) {
            NmLog.note("歌手详情 /artist/desc 返回 code=${r.code()}")
            return ArtistDescInfo()
        }
        val brief = r.str("briefDesc") ?: ""
        val sections = runCatching {
            r["introduction"]?.jsonArray?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val txt = o.str("txt") ?: ""
                if (txt.isBlank()) null else (o.str("ti") ?: "") to txt
            } ?: emptyList()
        }.getOrDefault(emptyList())
        val topics = runCatching {
            r["topicData"]?.jsonArray?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                (o.obj("topic") ?: o).str("mainTitle", "title")
            } ?: emptyList()
        }.getOrDefault(emptyList())
        NmLog.note("歌手详情 id=$id 简述=${brief.length} 分段=${sections.size} 专栏=${topics.size}")
        return ArtistDescInfo(brief, sections, topics, true)
    }
}
