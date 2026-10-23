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

object Netease {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 登录后拿到的 cookie，由 Prefs 注入；所有请求自动带上 */
    @Volatile
    var cookie: String = ""

    /** 音质：本站质量键 -> 网易云 level（从高到低，逐个尝试直到拿到地址） */
    private fun levels(quality: String): List<String> = when (quality) {
        "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
        "high" -> listOf("exhigh", "higher", "standard")
        "normal" -> listOf("higher", "standard")
        else -> listOf("standard")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String, vararg params: Pair<String, String>): JsonObject {
        val sb = StringBuilder(NM_BASE).append(path).append('?')
        params.forEach { sb.append(it.first).append('=').append(enc(it.second)).append('&') }
        if (cookie.isNotBlank()) sb.append("cookie=").append(enc(cookie)).append('&')
        sb.append("timestamp=").append(System.currentTimeMillis())
        val req = Request.Builder().url(sb.toString()).header("Connection", "close").build()
        var last: Exception? = null
        repeat(2) { i ->
            try {
                Api.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val t = r.body!!.string()
                    val el = json.parseToJsonElement(t)
                    return if (el is JsonObject) el else JsonObject(emptyMap())
                }
            } catch (e: Exception) {
                last = e
                if (i == 0) runCatching { Thread.sleep(400) }
            }
        }
        throw last ?: IOException("请求失败")
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive
            if (v != null && v.isString && v.content.isNotBlank()) return v.content
        }
        return null
    }

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.code(): Int =
        (this["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    /** 封面缩略图：网易云图片支持 ?param=宽y高 */
    private fun pic(u: String?, size: Int = 200): String? {
        if (u.isNullOrBlank()) return null
        if (!u.startsWith("http")) return null
        return if (u.contains("?")) u else "$u?param=${size}y$size"
    }

    // ================= 搜索 =================

    /** type: 1 单曲 10 专辑 100 歌手 */
    private fun rawSearch(keywords: String, type: Int, limit: Int): JsonObject =
        get("/search", "keywords" to keywords, "type" to type.toString(), "limit" to limit.toString(), "offset" to "0")

    fun searchSongs(keywords: String, limit: Int = 20): List<Song> {
        val root = rawSearch(keywords, 1, limit)
        val songs = (root.obj("result")?.get("songs") as? JsonElement)?.jsonArray ?: return emptyList()
        return songs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val artists = (o["ar"] as? JsonElement)?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.joinToString("/") ?: ""
            val al = o.obj("al")
            Song(
                ssid = "$NM_PREFIX$id",
                title = o.str("name") ?: "",
                artist = artists,
                album = al?.str("name") ?: "",
                coverUrl = pic(al?.str("picUrl")),
                source = SRC_NETEASE,
                nmId = id,
            )
        }
    }

    fun searchAlbums(keywords: String, limit: Int = 10): List<Group> {
        val root = runCatching { rawSearch(keywords, 10, limit) }.getOrNull() ?: return emptyList()
        val arr = (root.obj("result")?.get("albums") as? JsonElement)?.jsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            Group(
                title = o.str("name") ?: "",
                subtitle = o.obj("artist")?.str("name") ?: "",
                cover = pic(o.str("picUrl")),
                songs = emptyList(),
                album = o.str("name") ?: "",
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "album",
            )
        }
    }

    fun searchArtists(keywords: String, limit: Int = 10): List<Group> {
        val root = runCatching { rawSearch(keywords, 100, limit) }.getOrNull() ?: return emptyList()
        val arr = (root.obj("result")?.get("artists") as? JsonElement)?.jsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            Group(
                title = o.str("name") ?: "",
                subtitle = "网易云歌手",
                cover = pic(o.str("picUrl", "img1v1Url")),
                songs = emptyList(),
                artist = o.str("name") ?: "",
                source = SRC_NETEASE,
                nmId = id,
                nmKind = "artist",
            )
        }
    }

    // ================= 播放 / 歌词 / 详情 =================

    /** 取播放地址，按音质从高到低尝试；失败返回 null */
    fun audioUrl(id: Long, quality: String): String? {
        for (lv in levels(quality)) {
            val r = runCatching {
                get("/song/url/v1", "id" to id.toString(), "level" to lv)
            }.getOrNull() ?: continue
            if (r.code() != 200) continue
            val d = r["data"] as? JsonElement ?: continue
            val arr = d.jsonArray
            val first = arr.firstOrNull() as? JsonObject ?: continue
            val u = first.str("url")
            if (!u.isNullOrBlank()) return u
        }
        return null
    }

    fun lyric(id: Long): String {
        val r = runCatching { get("/lyric", "id" to id.toString()) }.getOrNull() ?: return ""
        val lrc = r.obj("lrc")?.str("lyric")
        return lrc ?: ""
    }

    /** 专辑 / 歌手的歌曲列表 */
    fun songsOf(kind: String, id: Long, limit: Int = 50): List<Song> {
        val root = if (kind == "album") {
            runCatching { get("/album", "id" to id.toString()) }.getOrNull()
        } else {
            runCatching {
                get("/artist/songs", "id" to id.toString(), "limit" to limit.toString(), "offset" to "0")
            }.getOrNull()
        } ?: return emptyList()
        val arr = (root["songs"] as? JsonElement)?.jsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sid = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val artists = (o["ar"] as? JsonElement)?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.str("name") }
                ?.joinToString("/") ?: ""
            val al = o.obj("al")
            Song(
                ssid = "$NM_PREFIX$sid",
                title = o.str("name") ?: "",
                artist = artists,
                album = al?.str("name") ?: "",
                coverUrl = pic(al?.str("picUrl")),
                source = SRC_NETEASE,
                nmId = sid,
            )
        }
    }

    // ================= 登录 =================

    /** 第一步：取二维码 key */
    fun qrKey(): String? {
        val r = runCatching { get("/login/qr/key") }.getOrNull() ?: return null
        return r.obj("data")?.str("unikey", "key")
    }

    /** 第二步：取二维码图片（data: URI，可直接交给 Coil） */
    fun qrImage(key: String): String? {
        val r = runCatching { get("/login/qr/create", "key" to key, "qrimg" to "true") }.getOrNull()
            ?: return null
        val img = r.obj("data")?.str("qrimg") ?: return null
        return if (img.startsWith("data:")) img else "data:image/png;base64,$img"
    }

    /** 第三步：轮询。800 过期 801 等待扫码 802 待确认 803 成功 */
    fun qrCheck(key: String): QrStatus {
        val r = runCatching { get("/login/qr/check", "key" to key) }.getOrNull()
            ?: return QrStatus(-1, null, "网络异常")
        return QrStatus(r.code(), r.str("cookie"), r.str("message"))
    }

    /** 发送短信验证码 */
    fun sendCaptcha(phone: String): Boolean {
        val r = runCatching { get("/captcha/sent", "phone" to phone) }.getOrNull() ?: return false
        return r.code() == 200
    }

    /** 校验验证码 */
    fun verifyCaptcha(phone: String, captcha: String): Boolean {
        val r = runCatching { get("/captcha/verify", "phone" to phone, "captcha" to captcha) }.getOrNull()
            ?: return false
        return r.code() == 200
    }

    /** 验证码登录 */
    fun loginByCaptcha(phone: String, captcha: String): String? {
        val r = runCatching { get("/login/cellphone", "phone" to phone, "captcha" to captcha) }.getOrNull()
            ?: return null
        if (r.code() != 200) return null
        return r.str("cookie")
    }

    /** 注册（注册成功即登录） */
    fun register(phone: String, password: String, captcha: String, nickname: String): String? {
        val r = runCatching {
            get(
                "/register/cellphone",
                "phone" to phone,
                "password" to password,
                "captcha" to captcha,
                "nickname" to nickname,
            )
        }.getOrNull() ?: return null
        if (r.code() != 200) return null
        return r.str("cookie")
    }

    /** 用 cookie 换取账号昵称；失败返回 null 表示登录态失效 */
    fun accountName(): String? {
        val r = runCatching { get("/user/account") }.getOrNull() ?: return null
        if (r.code() != 200) return null
        val p = r.obj("profile")
        return p?.str("nickname") ?: r.obj("account")?.str("userName")
    }

    /** 登录态是否有效 */
    fun loggedIn(): Boolean {
        if (cookie.isBlank()) return false
        val r = runCatching { get("/login/status") }.getOrNull() ?: return false
        val d = r.obj("data")
        val code = d?.obj("account")?.let { 200 } ?: r.code()
        return code == 200 && d?.obj("profile") != null
    }
}
