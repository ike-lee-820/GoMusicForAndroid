package de.myocd.gomusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
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
                val req = Request.Builder().url(url).build()
                Api.client.newCall(req).execute().use { r ->
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

    // ---------- 歌曲解析 ----------
    /**
     * 把 QQ 返回的歌曲对象转成 [Song]。
     *
     * 搜索、歌单、专辑、歌手返回的歌曲结构基本一致，共用这一个解析。
     * 字段取不到就用空串，不因为个别字段缺失丢掉整首歌。
     */
    private fun toSong(o: JsonObject): Song? {
        val mid = o.str("mid", "songmid", "song_mid")
        if (mid.isNullOrBlank()) return null
        val name = o.str("name", "title", "songname") ?: return null
        val singers = o.arr("singer")
            .mapNotNull { it.asObj()?.str("name", "title") }
            .filter { it.isNotBlank() }
        val alb = o.obj("album")
        val albumMid = alb?.str("mid", "albumMid", "pmid")
        val albumName = alb?.str("name", "title")
        val mvVid = o.obj("mv")?.str("vid")
        return Song(
            ssid = QQ_PREFIX + mid,
            title = name.trim(),
            artist = singers.distinct().joinToString("/"),
            album = albumName?.trim().orEmpty(),
            coverUrl = coverUrl("album", albumMid),
            source = SRC_QQ,
            songMid = mid,
            mvVid = mvVid ?: "",
        )
    }

    /** 数组 -> 歌曲列表，跳过解析失败的 */
    private fun songsOf(arr: List<JsonElement>): List<Song> =
        arr.mapNotNull { it.asObj()?.let(::toSong) }.distinctBy { it.songMid }

    /** 歌曲所在的容器：不同接口放在不同字段下 */
    private fun itemsOf(root: JsonObject, vararg keys: String): List<JsonElement> {
        for (k in keys) {
            val v = root[k] as? JsonElement ?: continue
            val a = runCatching { v.jsonArray }.getOrNull() ?: continue
            if (a.isNotEmpty()) return a
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
        NmLog.note("QQ 搜索歌曲：$keywords -> ${list.size}")
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
        return itemsOf(r, "items", "albums", "list").mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val mid = o.str("mid", "albumMid", "album_mid") ?: return@mapNotNull null
            val name = o.str("name", "title") ?: return@mapNotNull null
            val singer = o.obj("singer")?.str("name", "title")
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
            val mid = o.str("mid", "singerMid", "singer_mid") ?: return@mapNotNull null
            val name = o.str("name", "title", "singerName") ?: return@mapNotNull null
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

    fun lyric(mid: String): String {
        if (mid.isBlank()) return ""
        val r = get("/getLyric", "songmid" to mid, "trans" to "1", "isFormat" to "1")
            ?: return ""
        val resp = r.obj("response") ?: r
        val lrc = decodeLrc(resp.str("lyric", "lrc"))
        if (lrc.isBlank()) NmLog.note("QQ 歌词为空 mid=$mid")
        return lrc
    }

    /** 自带翻译（LRC），没有就返回空串 */
    fun lyricTrans(mid: String): String {
        if (mid.isBlank()) return ""
        val r = get("/getLyric", "songmid" to mid, "trans" to "1", "isFormat" to "1")
            ?: return ""
        val resp = r.obj("response") ?: r
        return decodeLrc(resp.str("trans", "translyric"))
    }

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
        val pu = data?.obj("playUrl") ?: r?.obj("playUrl")
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
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { audioUrl(mid, quality) }
        }
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
