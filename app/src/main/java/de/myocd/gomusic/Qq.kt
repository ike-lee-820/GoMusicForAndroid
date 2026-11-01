package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 音乐曲库客户端。
 *
 * 接口依据 qq-music-api 文档（适用版本 3.3.5）：
 * 搜索 `/search`、歌词 `/getLyric`、播放链接 `/getMusicPlay/:songmid`、
 * 专辑 `/getAlbumInfo`、歌手歌曲 `/singer/songs`、歌单 `/getSongListDetail`、
 * MV `/mv/url`、热搜 `/search/hot`、排行榜 `/recommend/toplists`。
 *
 * QQ 用字符串 songmid 标识歌曲（不是数字），所以 ssid 统一拼成
 * `qq` + songmid（见 [QQ_PREFIX]），播放与歌词都按 [qqMidOf] 反查。
 *
 * 网易云那一套（[Netease]）保留不动：老歌单里可能还有网易云的歌，
 * 播放时按 ssid 前缀路由，两边互不干扰。
 */
object Qq {

    const val BASE = "https://qq.mapi.myocd.de5.net"

    /**
     * 登录会话 token。
     *
     * 扫码成功后拿到，之后**所有**请求都通过 `X-QQ-Session` 头带上 ——
     * 个人歌单、取高音质播放链接都要求有效会话，匿名拿不到。
     * 由 [MainViewModel] 登录后写入，退出时清空。
     */
    var token: String = ""
        private set

    fun setToken(t: String?) { token = t.orEmpty() }

    val loggedIn: Boolean get() = token.isNotBlank()

    // ---------- 会话容器：cookie ----------
    /**
     * 登录凭证可能通过 Set-Cookie 下发，而不写在响应体里。
     *
     * 共享的 [Api.client] 没有 CookieJar，cookie 会被直接丢掉 ——
     * 表现就是扫码后服务端说「已授权」，客户端却拿不到任何凭证。
     * 所以 QQ 这边单独挂一个，把 cookie 存下来并原样回传。
     */
    private val jar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            store[url.host] = cookies
            // 顺手记下看起来像凭证的那条，供响应体里没有 token 时兜底
            cookies.firstOrNull { isCredName(it.name) }?.value
                ?.takeIf { it.length >= 4 }?.let { cookieCred = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
        fun allCookies(): List<Cookie> = store.values.flatten()
    }

    /** cookie 里存下来的凭证（响应体没有 token 时的兜底） */
    @Volatile private var cookieCred: String = ""

    /** 复用共享客户端的连接池与调度器，只补一个 CookieJar */
    private val client: OkHttpClient = Api.client.newBuilder().cookieJar(jar).build()

    private fun isCredName(n: String): Boolean {
        val k = n.lowercase().replace("_", "").replace("-", "")
        // 包含匹配：不同部署的字段名带各种前缀（qqmusic_session 就是一个例子），
        // 写成精确集合会漏掉文档里明确给出的那一个
        return k.contains("token") || k.contains("session") || k.contains("credential")
            || k.contains("ticket") || k.contains("skey")
    }

    /** 文档指定的会话 cookie 名：Node / Docker 环境下凭证通过它下发 */
    const val SESSION_COOKIE = "qqmusic_session"

    /** 读指定名字的 cookie（HttpOnly 也能读到：那只限制 JS，不限制 HTTP 客户端） */
    fun cookieValue(name: String): String? =
        jar.allCookies().firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }

    /** 会话 cookie 里的凭证 */
    private fun credFromCookie(): String? {
        cookieValue(SESSION_COOKIE)?.takeIf { it.length >= 4 }?.let { return it }
        return jar.allCookies().firstOrNull { isCredName(it.name) }?.value?.takeIf { it.length >= 4 }
    }

    /**
     * 在整棵 JSON 里找凭证。
     *
     * 不同部署把凭证放在不同层级（顶层 / data / data.user …），字段名也不统一，
     * 只按固定路径取会漏掉。这里逐层找名字像凭证的字符串字段。
     */
    private fun findCred(e: JsonElement?, depth: Int = 0): String? {
        if (e == null || depth > 6) return null
        when (e) {
            is JsonObject -> {
                // 先在本层找，找不到再往下钻一层
                for ((k, v) in e) {
                    if (!isCredName(k)) continue
                    val t = (v as? JsonPrimitive)?.content?.trim()
                    if (!t.isNullOrBlank() && t.length >= 4 && t != "null") return t
                }
                for ((_, v) in e) findCred(v, depth + 1)?.let { return it }
            }
            is JsonArray -> e.forEach { findCred(it, depth + 1)?.let { return it } }
            else -> {}
        }
        return null
    }

    /** 当前可用的凭证：显式 token 优先，其次 cookie */
    fun currentCredential(): String? =
        token.takeIf { it.isNotBlank() } ?: credFromCookie() ?: cookieCred.takeIf { it.length >= 4 }

    /** 音质档位：与 /getMusicPlay 的 quality 参数一致 */
    val LEVELS = listOf("128", "320", "flac", "ape", "m4a")

    fun levelLabel(lv: String): String = when (lv) {
        "128" -> "标准 128K"
        "320" -> "高品 320K"
        "flac" -> "无损 FLAC"
        "ape" -> "无损 APE"
        "m4a" -> "M4A"
        else -> lv
    }

    /** 把应用的音质偏好映射成 QQ 的 quality 参数 */
    fun qualityOf(pref: String): String = when (pref) {
        "lossless" -> "flac"
        "high" -> "320"
        else -> "128"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- JSON 取值辅助 ----------
    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            if (!v.isString) {
                // 数字也常常有意义（比如 id），允许转字符串
                v.content.trim().takeIf { it.isNotBlank() }?.let { return it }
                continue
            }
            if (v.content.isNotBlank()) return v.content
        }
        return null
    }

    private fun JsonObject.obj(vararg keys: String): JsonObject? {
        for (k in keys) (this[k] as? JsonObject)?.let { return it }
        return null
    }

    private fun JsonObject.arr(vararg keys: String): List<JsonElement> {
        for (k in keys) {
            val v = this[k] as? JsonElement ?: continue
            // 不是数组时 jsonArray 会抛异常，这里统一兜住
            runCatching { v.jsonArray }.getOrNull()?.let { return it }
        }
        return emptyList()
    }

    private fun JsonObject.num(vararg keys: String): Double? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            v.content.trim().toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

    // ---------- 封面 ----------
    /**
     * QQ 音乐图片 CDN。
     *
     * 专辑用 T002、歌手用 T001，尺寸写在路径里（300x300 足够列表用）。
     * 没有 mid 时返回 null，交给调用方兜底。
     */
    fun coverUrl(kind: String, mid: String?): String? {
        val m = mid?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val t = if (kind == "singer") "T001" else "T002"
        return "https://y.gtimg.cn/music/photo_new/${t}R300x300M000$m.jpg"
    }

    // ---------- 请求 ----------
    private fun get(path: String, vararg params: Pair<String, String>): JsonObject? {
        val sb = StringBuilder(BASE).append(path)
        if (params.isNotEmpty()) {
            sb.append('?')
            params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        }
        val url = sb.toString().trimEnd('&', '?')
        // 失败重试一次即可：QQ 接口偶发抖动，但重试太多会把切歌拖慢
        repeat(2) { attempt ->
            try {
                val b = Request.Builder().url(url)
                if (token.isNotBlank()) b.addHeader("X-QQ-Session", token)
                val req = b.build()
                client.newCall(req).execute().use { r ->
                    val t = r.body?.string().orEmpty()
                    NmLog.req(url)
                    NmLog.res(url, r.code, t.take(400))
                    if (!r.isSuccessful || t.isBlank()) return@repeat
                    return json.parseToJsonElement(t) as? JsonObject
                }
            } catch (e: Exception) {
                NmLog.note("QQ 请求失败（第 ${attempt + 1} 次）：$path -> ${e.message}")
                try { Thread.sleep(300) } catch (ignored: Exception) { }
            }
        }
        NmLog.note("QQ 请求最终失败：$path")
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * POST JSON。
     *
     * 歌单写入接口（创建/改名/删除/增删歌曲）全是 POST + JSON。
     * 文档强调「写入不自动重试」，所以这里**只发一次**，
     * 失败交给调用方决定，避免重复写入造成脏数据。
     */
    private val JSON_MT by lazy { "application/json; charset=utf-8".toMediaType() }

    private fun post(path: String, body: String): JsonObject? {
        if (token.isBlank()) {
            NmLog.note("QQ 写入接口未登录：$path")
            return null
        }
        val url = BASE + path
        return try {
            val b = Request.Builder().url(url)
                .addHeader("X-QQ-Session", token)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MT))
            client.newCall(b.build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                NmLog.req("POST " + url + " " + body.take(200))
                NmLog.res(url, r.code, t.take(400))
                if (!r.isSuccessful || t.isBlank()) null
                else json.parseToJsonElement(t) as? JsonObject
            }
        } catch (e: Exception) {
            NmLog.note("QQ POST 失败：$path -> ${e.message}")
            null
        }
    }

    /** code == 200 才算成功（写入接口用 changed 标记是否真的有变化） */
    private fun JsonObject.ok(): Boolean = num("code")?.toInt() == 200

    // ---------- 歌曲解析 ----------
    /**
     * 把 QQ 返回的歌曲对象转成 [Song]。
     *
     * 搜索、歌单、专辑、歌手返回的歌曲结构基本一致，共用这一个解析。
     * 字段取不到就用空串，不因为个别字段缺失丢掉整首歌。
     */
    private fun toSong(o: JsonObject): Song? {
        // 搜索结果里的条目是上游原始结构，字段名随类型而变：
        // mid 可能写作 songmid / song_mid，歌名可能是 name / title / songname。
        // 只认一种会在某些类型上整列解析失败，所以这里都列出来
        val mid = o.str("mid", "songmid", "song_mid", "songMid")
            ?: o.num("id")?.toLong()?.takeIf { it > 0 }?.toString()?.let { "id$it" }
        if (mid.isNullOrBlank()) return null
        val name = o.str("name", "title", "songname", "songName", "albumName")
        // 歌名取不到也不能丢整首：用空串会让列表出现空白条目，不如保留可播的条目
        if (name.isNullOrBlank() && o.str("subtitle").isNullOrBlank()) return null
        val singers = o.arr("singer")
            .mapNotNull { it.asObj()?.str("name", "title") }
            .filter { it.isNotBlank() }
        val alb = o.obj("album")
        val albumMid = alb?.str("mid", "albumMid", "pmid")
        val albumName = alb?.str("name", "title")
        val mvVid = o.obj("mv")?.str("vid")
        val songId = o.num("id")?.toLong() ?: 0L
        return Song(
            ssid = QQ_PREFIX + mid,
            title = name?.trim()?.takeIf { it.isNotBlank() } ?: mid,
            artist = singers.distinct().joinToString("/"),
            album = albumName?.trim().orEmpty(),
            coverUrl = coverUrl("album", albumMid),
            source = SRC_QQ,
            songMid = mid,
            songId = songId,
            mvVid = mvVid ?: "",
        )
    }

    /** 数组 -> 歌曲列表，跳过解析失败的 */
    private fun songsOf(arr: List<JsonElement>): List<Song> =
        arr.mapNotNull { it.asObj()?.let(::toSong) }.distinctBy { it.songMid }

    /** 歌曲所在的容器：不同接口放在不同字段下 */
    /**
     * 取列表：先看本层，找不到再往下钻一层。
     *
     * 各接口的包裹层数不统一（`data` / `response` / `data.song.list` …），
     * 只按顶层 key 找会在嵌套时取到空 —— 搜索「什么都不显示」就出在这里。
     * 所以这里做有限深度的递归查找，而不是写死路径。
     */
    private fun itemsOf(root: JsonObject, vararg keys: String): List<JsonElement> =
        itemsOf(root, keys, 0)

    private fun itemsOf(root: JsonObject, keys: Array<out String>, depth: Int): List<JsonElement> {
        // 深度上限：响应里若出现深层嵌套，无限制的递归会拖慢解析甚至栈溢出
        if (depth > 4) return emptyList()
        for (k in keys) {
            val v = root[k] as? JsonElement ?: continue
            val a = runCatching { v.jsonArray }.getOrNull() ?: continue
            if (a.isNotEmpty()) return a
        }
        // 往下钻：只走对象容器
        for ((_, v) in root) {
            val o = v as? JsonObject ?: continue
            val got = itemsOf(o, keys, depth + 1)
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    // ---------- 搜索 ----------
    /**
     * QQ 搜索用 page 从 1 开始，最多 20 条/页；这里沿用调用方的
     * offset 语义换算过去，避免上层改动。
     */
    private fun pageOf(offset: Int, limit: Int) = offset / limit + 1

    fun searchSongs(keywords: String, limit: Int = 20, offset: Int = 0): List<Song> {
        if (keywords.isBlank()) return emptyList()
        val r = get(
            "/search",
            "keyword" to keywords,
            "type" to "song",
            "page" to pageOf(offset, limit).toString(),
            "limit" to limit.coerceAtMost(20).toString(),
        ) ?: return emptyList()
        val list = songsOf(itemsOf(r, "items", "songs", "list"))
        // 空结果时把原始响应记下来：字段结构对不上时一眼能看出数据在哪
        NmLog.note(if (list.isEmpty()) "QQ 搜索歌曲无结果：$keywords 原始=${r.toString().take(300)}"
        else "QQ 搜索歌曲：$keywords -> ${list.size}")
        return list
    }

    fun searchAlbums(keywords: String, limit: Int = 10, offset: Int = 0): List<Group> {
        if (keywords.isBlank()) return emptyList()
        val r = get(
            "/search",
            "keyword" to keywords,
            "type" to "album",
            "page" to pageOf(offset, limit).toString(),
            "limit" to limit.coerceAtMost(20).toString(),
        ) ?: return emptyList()
        val arr = itemsOf(r, "items", "albums", "list")
        val list = arr.mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            // 上游条目是原始结构，专辑的标识与名字字段名各类型不同
            // （albumMid / album_mid / mid，albumName / name / title），
            // 只认一种会导致整列专辑解析不出来
            val mid = o.str("albumMid", "album_mid", "mid", "albumMID")
                ?: o.obj("album")?.str("mid", "albumMid", "album_mid")
                ?: o.num("albumId", "albumid")?.toLong()?.takeIf { it > 0 }?.toString()?.let { "id$it" }
            if (mid.isNullOrBlank()) return@mapNotNull null
            val name = o.str("albumName", "name", "title", "album_title")
                ?: o.obj("album")?.str("name", "title")
                ?: return@mapNotNull null
            val singer = o.str("singerName", "singer_name", "artistName")
                ?: o.obj("singer")?.str("name", "title")
                ?: o.arr("singer").mapNotNull { it.asObj()?.str("name") }.firstOrNull()
            Group(
                title = name.trim(),
                subtitle = "",
                cover = coverUrl("album", mid),
                songs = emptyList(),
                artist = singer.orEmpty(),
                source = SRC_QQ,
                nmKind = "qqalbum",
                qqMid = mid,
            )
        }.distinctBy { it.qqMid }
        NmLog.note(if (list.isEmpty())
            "QQ 搜索专辑无结果：$keywords 原始=${r.toString().take(300)}"
        else "QQ 搜索专辑：$keywords -> ${list.size}")
        return list
    }

    fun searchArtists(keywords: String, limit: Int = 10, offset: Int = 0): List<Group> {
        if (keywords.isBlank()) return emptyList()
        val r = get(
            "/search",
            "keyword" to keywords,
            "type" to "singer",
            "page" to pageOf(offset, limit).toString(),
            "limit" to limit.coerceAtMost(20).toString(),
        ) ?: return emptyList()
        return itemsOf(r, "items", "singers", "artists", "list").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            // 歌手条目的标识可能是 singerMid / mid / singer_mid，
            // 也可能只有数字 id —— 只认一种会让整列歌手解析不出来
            val mid = o.str("mid", "singerMid", "singer_mid", "singerMID")
                ?: o.num("id", "singerId", "singer_id")?.toLong()?.takeIf { it > 0 }?.toString()
                ?: o.obj("singerInfo")?.str("mid")
            if (mid.isNullOrBlank()) return@mapNotNull null
            val name = o.str("name", "title", "singerName", "singer_name")
                ?: o.obj("singerInfo")?.str("name")
            if (name.isNullOrBlank()) return@mapNotNull null
            Group(
                title = name.trim(),
                subtitle = "",
                cover = coverUrl("singer", mid),
                songs = emptyList(),
                source = SRC_QQ,
                nmKind = "qqsinger",
                qqMid = mid,
            )
        }.distinctBy { it.qqMid }
    }

    fun hotSearch(): List<String> {
        val r = get("/search/hot") ?: return emptyList()
        return itemsOf(r, "hotkeys", "data", "list").mapNotNull { e ->
            e.asObj()?.str("keyword", "key", "title")
        }.filter { it.isNotBlank() }.distinct()
    }

    // ---------- 歌词 ----------
    /**
     * 歌词（LRC 文本）。
     *
     * 上游偶尔把歌词做成 base64，这里检测一下：不像 LRC（没有 "["）
     * 就试着解码，避免界面显示一串乱码。
     */
    private fun decodeLrc(s: String?): String {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return ""
        if (t.contains("[")) return t
        return runCatching {
            String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(t)
    }

    fun lyric(mid: String): String = lyricWithTrans(mid).first

    /**
     * 一次请求同时拿到主歌词与自带翻译。
     *
     * 文档：`/getLyric?trans=1` 会在同一份响应里带上翻译，
     * 分两次请求等于把歌词请求量翻倍 —— 而每首歌都要尝试取翻译，
     * 这个翻倍会直接拖慢切歌。所以合并成一次。
     */
    fun lyricWithTrans(mid: String): Pair<String, String> {
        if (mid.isBlank()) return "" to ""
        val r = get("/getLyric", "songmid" to mid, "trans" to "1", "isFormat" to "1")
            ?: return "" to ""
        val resp = r.obj("response") ?: r
        val lrc = decodeLrc(resp.str("lyric", "lrc"))
        // 每首歌都尝试取翻译：取不到是常态（并非所有歌都有），
        // 只记日志，不影响主歌词
        if (lrc.isBlank()) NmLog.note("QQ 歌词为空 mid=$mid")
        val tr = decodeLrc(resp.str("trans", "translyric", "translyric_lrc"))
        return lrc to tr
    }

    /** 自带翻译（LRC），没有就返回空串 */
    fun lyricTrans(mid: String): String = lyricWithTrans(mid).second

    // ---------- 播放链接 ----------
    /** 播放地址缓存：同一首歌短时间内重复解析不再发请求 */
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val urlMiss = ConcurrentHashMap<String, Long>()
    private const val URL_TTL_MS = 10 * 60 * 1000L
    private const val URL_MISS_TTL_MS = 30_000L

    /**
     * 取播放地址。
     *
     * 返回结构是按 songmid 索引的：data.playUrl.<songmid>.url，
     * 拿不到时会带 restriction 说明原因，这里只按 url 是否为空判断。
     */
    fun audioUrl(mid: String, quality: String): String? {
        if (mid.isBlank()) return null
        val k = "$mid|$quality"
        urlCache[k]?.let { (u, t) ->
            if (System.currentTimeMillis() - t < URL_TTL_MS) return u
            urlCache.remove(k)
        }
        urlMiss[k]?.let { t ->
            if (System.currentTimeMillis() - t < URL_MISS_TTL_MS) return null
            urlMiss.remove(k)
        }
        val r = get("/getMusicPlay/$mid", "quality" to quality, "resType" to "play")
        val data = r?.obj("data")
        // 结构一：data.playUrl 里按 songmid 索引；结构二：data 或顶层直接是对象
        val pu = data?.obj("playUrl") ?: r?.obj("playUrl") ?: data ?: r
        // 结构一：playUrl 是对象，键为 songmid
        val direct = pu?.get(mid)?.asObj()?.str("url")
            ?: pu?.values?.firstOrNull()?.asObj()?.str("url")
        val url = direct?.takeIf { it.startsWith("http") }
            ?: data?.str("url")?.takeIf { it.startsWith("http") }
            ?: r?.str("url")?.takeIf { it.startsWith("http") }
        if (url != null) {
            urlCache[k] = url to System.currentTimeMillis()
            NmLog.note("QQ 取到播放地址 mid=$mid quality=$quality")
        } else {
            urlMiss[k] = System.currentTimeMillis()
            NmLog.note("QQ 无播放地址 mid=$mid quality=$quality")
        }
        return url
    }

    fun prefetchAudioUrl(mid: String, quality: String) {
        val k = "$mid|$quality"
        if (urlCache.containsKey(k) || urlMiss.containsKey(k)) return
        // 预取不该阻塞调用方：开一个后台线程慢慢取
        Thread { runCatching { audioUrl(mid, quality) } }.apply { isDaemon = true }.start()
    }

    // ---------- 专辑 / 歌手 / 歌单 ----------
    /** 专辑详情：返回「专辑名 + 歌曲」 */
    fun albumSongs(albumMid: String): Pair<String, List<Song>>? {
        if (albumMid.isBlank()) return null
        val r = get("/getAlbumInfo", "albummid" to albumMid) ?: return null
        val resp = r.obj("response") ?: r
        val data = resp.obj("data") ?: resp
        val name = data.str("name", "albumName") ?: ""
        val songs = songsOf(itemsOf(data, "list", "songlist", "songs"))
        NmLog.note("QQ 专辑 $albumMid -> ${songs.size} 首")
        return name to songs
    }

    fun singerSongs(singerMid: String, limit: Int = 30, offset: Int = 0): List<Song> {
        if (singerMid.isBlank()) return emptyList()
        val r = get(
            "/singer/songs",
            "singermid" to singerMid,
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        ) ?: return emptyList()
        return songsOf(itemsOf(r, "songs", "list"))
    }

    /** 歌单详情（按 disstid） */
    fun playlistSongs(disstid: String): Pair<String, List<Song>>? {
        if (disstid.isBlank()) return null
        val r = get("/getSongListDetail", "disstid" to disstid) ?: return null
        val resp = r.obj("response") ?: r
        val cd = itemsOf(resp, "cdlist").firstOrNull()?.asObj() ?: return null
        val name = cd.str("dissname", "name") ?: ""
        val songs = songsOf(itemsOf(cd, "songlist", "songs", "list"))
        NmLog.note("QQ 歌单 $disstid -> ${songs.size} 首")
        return name to songs
    }

    // ---------- MV ----------
    /**
     * MV 播放地址。
     *
     * /mv/url 返回 files 数组，取第一个能播的地址（不同清晰度各一项）。
     */
    fun mvUrl(vid: String): String? {
        if (vid.isBlank()) return null
        val r = get("/mv/url", "vid" to vid) ?: return null
        val files = itemsOf(r, "files", "data", "urls")
        for (e in files) {
            val u = e.asObj()?.str("url", "playUrl", "freeflow_url")
            if (!u.isNullOrBlank() && u.startsWith("http")) return u
        }
        return r.obj("data")?.str("url")?.takeIf { it.startsWith("http") }
    }

    // ---------- 登录（扫码）----------
    /**
     * 可用的登录通道。
     *
     * 只保留 `qq`（手机 QQ）和 `wechat`（微信）两个通道 ——
     * `qqmusic`（QQ 音乐 App 扫码）走 MQTT、需要宿主提供长连接中继，
     * 在手机上不可靠，直接禁用。
     */
    fun channels(): List<String> {
        val r = get("/login/channels") ?: return listOf("qq", "wechat")
        val arr = itemsOf(r, "channels", "data", "list")
        val out = arr.mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            (o.str("channel", "id", "name") ?: (e as? JsonPrimitive)?.content)
                ?.takeIf { it == "qq" || it == "wechat" }
        }.distinct()
        return out.ifEmpty { listOf("qq", "wechat") }
    }

    /** 第一步：取二维码标识 unikey（未登录也能取，所以不带会话头校验） */
    fun qrKey(channel: String = "qq"): String? {
        val r = get("/login/qr/key", "channel" to channel) ?: return null
        return r.obj("data")?.str("unikey", "key") ?: r.str("unikey", "key")
    }

    /**
     * 第二步：取二维码图片。
     *
     * 文档明确要求「按 data URL 的实际 MIME 类型显示，不要固定假设为 PNG」，
     * 所以这里原样返回服务端给的 data URI。
     */
    fun qrImage(key: String): String? {
        val r = get("/login/qr/create", "key" to key) ?: return null
        val img = r.obj("data")?.str("qrimg") ?: r.str("qrimg")
        if (img.isNullOrBlank()) return null
        return if (img.startsWith("data:")) img else "data:image/png;base64,$img"
    }

    /** 扫码结果：状态码 + 成功后的会话 token + 提示 */
    data class QrStatus(val code: Int, val token: String?, val message: String?)

    /** 第三步：轮询。801 等待扫码 802 待确认 803 成功 800 过期或失败 */
    fun qrCheck(key: String): QrStatus {
        val r = get("/login/qr/check", "key" to key) ?: return QrStatus(0, null, null)
        val code = r.num("code")?.toInt() ?: 0
        // 凭证层级与字段名各部署不统一：先在整棵响应里找，
        // 找不到再看 cookie（可能是 Set-Cookie 下发的）
        // 顺序：响应体（可能直接给 token）→ 会话 cookie（Node/Docker 走这条）
        val tk = findCred(r) ?: credFromCookie() ?: cookieCred.takeIf { it.length >= 4 }
        if (code == 803) {
            NmLog.note("QQ 扫码成功：响应体凭证=${if (findCred(r) != null) "有" else "无"}，" +
                "cookie=${cookieValue(SESSION_COOKIE)?.let { "有(${it.length}字符)" } ?: "无"}")
        }
        val d = r.obj("data")
        val msg = d?.str("failureHint") ?: d?.str("failureReason") ?: r.str("message", "msg")
        return QrStatus(code, tk, msg)
    }

    /** 取消这个二维码（离开登录页时调用，别让服务端一直轮询） */
    fun qrCancel(key: String) {
        get("/login/qr/cancel", "key" to key)
    }

    /** 登录状态：已登录返回用户昵称，否则 null */
    fun status(): String? {
        val r = get("/login/status") ?: return null
        if (r.num("code")?.toInt() != 200) return null
        val d = r.obj("data") ?: r
        // 会话 cookie 可能在这一步才下发：拿到就记下来，别只依赖扫码那次
        if (token.isBlank()) credFromCookie()?.let { setToken(it) }
        val name = d.str("nickname", "nick", "name", "userName", "username")
        val on = d.num("loggedIn", "login", "isLogin")?.toInt() == 1
        if (!on && name.isNullOrBlank()) return null
        return name ?: "QQ 音乐用户"
    }

    /** 会话续期：距离到期不足约 6 小时才需要 */
    /**
     * 会话续期。文档：仅在距到期不足约 6 小时时尝试；
     * `rotated: true` 时必须保存新 token，否则下一次请求会用旧凭证被拒。
     */
    data class RefreshResult(val token: String?, val rotated: Boolean, val expiresAt: Long)

    fun refresh(): RefreshResult? {
        val r = post("/login/refresh", "{}") ?: return null
        if (r.num("code")?.toInt() != 200) return null
        val d = r.obj("data") ?: r
        val tk = d.str("token") ?: findCred(r)
        val rotated = d.num("rotated")?.toInt() == 1 || d.str("rotated") == "true"
        val exp = d.num("expiresAt")?.toLong() ?: 0L
        // 换了新 token 就立刻用新的
        if (!tk.isNullOrBlank() && rotated) setToken(tk)
        return RefreshResult(tk, rotated, exp)
    }

    /** 距到期不足约 6 小时才需要续期（文档要求，避免无意义地频繁续期） */
    fun needsRefresh(expiresAt: Long): Boolean =
        expiresAt > 0 && expiresAt - System.currentTimeMillis() < 6 * 60 * 60 * 1000L

    fun logout() {
        runCatching { get("/logout") }
        setToken(null)
    }

    // ---------- 个人歌单 ----------
    /** 自建与收藏歌单：tid 用字符串（QQ 的歌单 id 可能很大） */
    fun userPlaylists(): List<Group> {
        val r = get("/user/playlist") ?: return emptyList()
        return itemsOf(r, "playlists", "list", "items").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val tid = o.str("tid", "dissid", "id") ?: return@mapNotNull null
            val name = o.str("title", "name", "dissname") ?: return@mapNotNull null
            Group(
                title = name.trim(),
                subtitle = o.str("intro", "desc").orEmpty(),
                cover = o.str("cover", "picUrl", "logo", "imgurl"),
                songs = emptyList(),
                source = SRC_QQ,
                nmKind = "qqplaylist",
                qqMid = tid,
            )
        }
    }

    /** 歌单详情（本人自建，含不公开歌单） */
    fun playlistDetail(tid: String): Pair<String, List<Song>>? {
        if (tid.isBlank()) return null
        val r = get("/user/playlist-detail", "tid" to tid) ?: return null
        val songs = songsOf(itemsOf(r, "songs", "songlist", "list", "items"))
        val name = r.str("name", "title", "dissname") ?: ""
        return name to songs
    }

    data class WriteResult(val ok: Boolean, val changed: Boolean, val message: String?)

    /** 创建歌单：返回新建歌单的 tid */
    fun createPlaylist(name: String): String? {
        val body = buildString {
            append("{\"name\":\"")
            append(name.replace("\"", "\\\"").replace("\n", " "))
            append("\"}")
        }
        val r = post("/user/playlist/create", body) ?: return null
        if (!r.ok()) return null
        return r.obj("data")?.str("tid", "id") ?: r.str("tid", "id")
    }

    fun renamePlaylist(tid: String, name: String): WriteResult {
        val body = buildString {
            append("{\"tid\":\"").append(tid)
            append("\",\"name\":\"").append(name.replace("\"", "\\\"").replace("\n", " "))
            append("\"}")
        }
        val r = post("/user/playlist/rename", body)
            ?: return WriteResult(false, false, "网络失败")
        return WriteResult(r.ok(), r.num("changed")?.toInt() == 1 || r.obj("data")?.num("changed")?.toInt() == 1,
            r.str("message"))
    }

    fun deletePlaylist(tid: String): WriteResult {
        val r = post("/user/playlist/delete", "{\"tid\":\"$tid\"}")
            ?: return WriteResult(false, false, "网络失败")
        return WriteResult(r.ok(), true, r.str("message"))
    }

    /**
     * 歌单增删歌曲。
     *
     * 文档：每次 1–100 首，只支持普通歌曲类型 0。
     * 这里需要歌曲的 **songid**（数字），而搜索结果里带的是 id，
     * 取不到 id 的歌直接跳过 —— 不能猜，猜错了会加到错的歌。
     */
    fun playlistTracks(tid: String, op: String, songs: List<Song>): WriteResult {
        val items = songs.mapNotNull { s ->
            val id = s.songId.takeIf { it > 0 } ?: return@mapNotNull null
            "{\"id\":$id,\"type\":0}"
        }
        if (items.isEmpty()) return WriteResult(false, false, "没有可同步的歌曲")
        val body = "{\"tid\":\"$tid\",\"op\":\"$op\",\"songs\":[${items.joinToString(",")}]}"
        val r = post("/user/playlist/tracks", body)
            ?: return WriteResult(false, false, "网络失败")
        return WriteResult(r.ok(), true, r.str("message"))
    }

    // ---------- 推荐 / 排行榜 ----------
    /** 排行榜列表：每项是一张榜单 */
    fun topLists(): List<Group> {
        val r = get("/recommend/toplists") ?: return emptyList()
        return itemsOf(r, "toplists", "list", "groups").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val id = o.str("id")?.toLongOrNull() ?: return@mapNotNull null
            val name = o.str("title", "name") ?: return@mapNotNull null
            Group(
                title = name.trim(),
                subtitle = o.str("intro", "desc").orEmpty(),
                cover = o.str("cover", "picUrl", "headPic"),
                songs = emptyList(),
                source = SRC_QQ,
                nmKind = "qqtoplist",
                nmId = id,
            )
        }
    }

    /** 某个榜单的歌曲 */
    fun topListSongs(id: Long): List<Song> {
        val r = get("/recommend/toplist", "id" to id.toString()) ?: return emptyList()
        return songsOf(itemsOf(r, "songs", "list"))
    }

    /** 推荐歌单 */
    fun recommendPlaylists(): List<Group> {
        val r = get("/recommend/playlists") ?: return emptyList()
        return itemsOf(r, "playlists", "list").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val tid = o.str("tid", "dissid", "id") ?: return@mapNotNull null
            val name = o.str("title", "name", "dissname") ?: return@mapNotNull null
            Group(
                title = name.trim(),
                subtitle = o.str("intro", "desc").orEmpty(),
                cover = o.str("cover", "picUrl", "logo", "imgurl"),
                songs = emptyList(),
                source = SRC_QQ,
                nmKind = "qqplaylist",
                qqMid = tid,
            )
        }
    }

    /** 新歌速递 */
    fun newSongs(): List<Song> {
        val r = get("/recommend/new-songs") ?: return emptyList()
        return songsOf(itemsOf(r, "songs", "items", "list"))
    }

    /** 新碟上架 */
    fun newAlbums(): List<Group> {
        val r = get("/recommend/new-albums") ?: return emptyList()
        return itemsOf(r, "albums", "items", "list").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val mid = o.str("mid", "albumMid") ?: return@mapNotNull null
            val name = o.str("name", "title") ?: return@mapNotNull null
            Group(
                title = name.trim(),
                subtitle = "",
                cover = coverUrl("album", mid),
                songs = emptyList(),
                source = SRC_QQ,
                nmKind = "qqalbum",
                qqMid = mid,
            )
        }
    }
}
