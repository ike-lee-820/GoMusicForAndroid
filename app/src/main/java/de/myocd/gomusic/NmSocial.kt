@file:Suppress("UNCHECKED_CAST")

package de.myocd.gomusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 26.11.82：私信 / 通知 / 播客数据层。
 *
 * 这一批接口的响应结构在不同部署里字段名并不完全一致（data.msgs /
 * data.list / data.voices / data.radios …，资源私信还是 JSON 字符串），
 * 所以解析全部走容错：按优先键找、找不到下钻一层容器，绝不因为某个
 * 字段缺失就整页空掉。请求统一复用 Netease.socialGet（带 cookie /
 * timestamp / randomCNIP），读写接口都按文档以 GET 形式发。
 */
object NmSocial {

    // ---------------------------------------------------------------- 数据模型

    data class UserBrief(
        val id: Long = 0,
        val name: String = "",
        val avatar: String? = null,
        val signature: String = "",
    )

    data class Conversation(
        val uid: Long = 0,
        val name: String = "",
        val avatar: String? = null,
        val last: String = "",
        val time: Long = 0,
        val unread: Int = 0,
    )

    /** kind: text / song / album / playlist / other */
    data class ChatMessage(
        val id: Long = 0,
        val time: Long = 0,
        val fromSelf: Boolean = false,
        val text: String = "",
        val kind: String = "text",
        val resId: Long = 0,
        val resName: String = "",
        val resCover: String? = null,
    )

    data class Podcast(
        val id: Long = 0,
        val name: String = "",
        val cover: String? = null,
        val desc: String = "",
        val category: String = "",
        val creator: String = "",
        val voiceCount: Int = 0,
    )

    data class Voice(
        val id: Long = 0,
        val radioId: Long = 0,
        val name: String = "",
        val cover: String? = null,
        val desc: String = "",
        val duration: Long = 0,
        val createTime: Long = 0,
        val status: String = "",
        val audioUrl: String? = null,
        val orderNo: Int = 0,
    )

    data class VoiceDetail(
        val voice: Voice = Voice(),
        val audioUrl: String? = null,
        val desc: String = "",
        val categoryId: Long = 0,
        val secondCategoryId: Long = 0,
    )

    // ---------------------------------------------------------------- JSON 容错工具

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun str(e: JsonElement?, vararg keys: String): String {
        val o = e as? JsonObject
        if (o == null) {
            val p = e as? JsonPrimitive ?: return ""
            return p.content.takeUnless { it.isBlank() || it == "null" }.orEmpty()
        }
        for (k in keys) {
            val t = str(o[k])
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun lng(e: JsonElement?, vararg keys: String): Long {
        val o = e as? JsonObject
            ?: return str(e).trim().toLongOrNull() ?: 0L
        for (k in keys) {
            val n = lng(o[k])
            if (n != 0L) return n
        }
        return 0L
    }

    private fun intOf(e: JsonElement?, vararg keys: String): Int = lng(e, *keys).toInt()

    private fun array(e: JsonElement?, vararg keys: String): List<JsonObject> {
        (e as? JsonArray)?.let { return it.mapNotNull { it as? JsonObject } }
        val o = e as? JsonObject ?: return emptyList()
        for (k in keys) {
            val r = array(o[k], *keys)
            if (r.isNotEmpty()) return r
        }
        for (k in listOf(
            "data", "result", "list", "msgs", "messages", "voices", "programs",
            "radios", "voicelists", "djRadios", "records", "detail", "voice",
        )) {
            val r = array(o[k], *keys)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun code(r: JsonObject): Int =
        (r["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    private fun data(r: JsonObject): JsonElement = r["data"] ?: r

    private fun cover(e: JsonElement?): String? {
        val u = str(e, "picUrl", "coverUrl", "cover", "imgUrl", "avatarUrl", "img1v1Url", "image")
            .takeIf { it.startsWith("http") }
        return u?.let { hdCoverUrl(it, 500) }
    }

    /** 资源私信在 msg 里是一段 JSON 字符串，尽量解出标题 / id / 类型 */
    private fun parseResource(raw: String): Triple<String, Long, String> {
        if (!raw.trim().startsWith("{")) return Triple(raw, 0L, "text")
        return runCatching {
            val o = Netease.json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching Triple(raw, 0L, "text")
            val kind = when {
                str(o, "type").contains("song", true) || o.containsKey("song") -> "song"
                str(o, "type").contains("album", true) || o.containsKey("album") -> "album"
                str(o, "type").contains("playlist", true) || o.containsKey("playlist") -> "playlist"
                else -> "text"
            }
            val res = when (kind) {
                "song" -> o["song"] ?: o
                "album" -> o["album"] ?: o
                "playlist" -> o["playlist"] ?: o
                else -> o
            }
            val name = str(res, "name", "title", "songName", "albumName", "playlistName")
                .ifBlank { str(o, "msg", "text", "content") }
            val id = lng(res, "id", "songId", "albumId", "playlistId", "voiceId")
            Triple(name.ifBlank { raw }, id, kind)
        }.getOrDefault(Triple(raw, 0L, "text"))
    }

    private fun userOf(e: JsonElement?): UserBrief = UserBrief(
        id = lng(e, "userId", "uid", "id"),
        name = str(e, "nickname", "name", "userName", "nick"),
        avatar = cover(e),
        signature = str(e, "signature", "describe"),
    )

    // ---------------------------------------------------------------- 私信 / 通知

    /** /pl/count：私信与通知未读数。字段在不同部署里叫 msg / privateMsg / notification 等 */
    fun plCount(): Pair<Int, Int> = runCatching {
        val r = Netease.socialGet("/pl/count")
        if (code(r) != 200) return@runCatching 0 to 0
        val d = data(r)
        val msg = intOf(d, "msg", "privateMsg", "privateMessage", "msgCount", "newMsg", "privateMsgCount")
        val notice = intOf(d, "notification", "notice", "notify", "notificationCount", "newNotice")
        // 有些部署把未读塞在 data 的子对象里
        val msg2 = intOf(obj(d)?.get("msg"), "count", "total") +
            intOf(obj(d)?.get("privateMsg"), "count", "total")
        val notice2 = intOf(obj(d)?.get("notification"), "count", "total")
        (msg + msg2) to (notice + notice2)
    }.getOrDefault(0 to 0)

    /** /msg/private：会话列表 */
    fun conversations(limit: Int = 30, offset: Int = 0): List<Conversation> = runCatching {
        val r = Netease.socialGet(
            "/msg/private",
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "msgs", "list").map { m ->
            val from = userOf(m["fromUser"] ?: m["user"] ?: m["friend"])
            val lastRaw = str(m, "lastMsg", "msg", "lastMessage", "content")
            val (last, _, _) = parseResource(lastRaw)
            Conversation(
                uid = from.id.takeIf { it > 0 } ?: lng(m, "fromUserId", "uid", "userId"),
                name = from.name.ifBlank { str(m, "nickname", "name") },
                avatar = from.avatar ?: cover(m),
                last = last,
                time = lng(m, "lastMsgTime", "time", "lastTime"),
                unread = intOf(m, "unread", "unreadCount", "newMsgCount"),
            )
        }.filter { it.uid > 0 || it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    /** /msg/private/history：与某人的私信内容；before 取上一页最后一条 time */
    fun history(uid: Long, limit: Int = 30, before: Long = 0): List<ChatMessage> = runCatching {
        val params = mutableListOf(
            "uid" to uid.toString(), "limit" to limit.toString(),
        )
        if (before > 0) params.add("before" to before.toString())
        val r = Netease.socialGet("/msg/private/history", *params.toTypedArray())
        if (code(r) != 200) return@runCatching emptyList()
        val me = runCatching { Netease.uid() }.getOrDefault(0L)
        array(r, "msgs", "list").map { m ->
            val fromId = lng(
                m["fromUser"] ?: m["user"], "userId", "uid", "id",
            ).takeIf { it > 0 } ?: lng(m, "fromUserId", "uid")
            val raw = str(m, "msg", "content", "text", "lastMsg")
            val (text, resId, kind) = parseResource(raw)
            val resObj = listOf(m["song"], m["album"], m["playlist"], m["resource"])
                .firstOrNull { it != null }
            ChatMessage(
                id = lng(m, "msgId", "id", "messageId"),
                time = lng(m, "time", "createTime", "msgTime"),
                fromSelf = me > 0 && fromId == me,
                text = text,
                kind = kind,
                resId = if (resId > 0) resId else lng(resObj, "id", "songId", "albumId", "playlistId"),
                resName = str(resObj, "name", "title").ifBlank {
                    if (kind != "text") text else ""
                },
                resCover = cover(resObj) ?: cover(m),
            )
        }
    }.getOrDefault(emptyList())

    private fun send(path: String, vararg params: Pair<String, String>): Boolean = runCatching {
        val r = Netease.socialGet(path, *params)
        code(r) == 200
    }.getOrDefault(false)

    fun sendText(userIds: String, msg: String): Boolean =
        send("/send/text", "user_ids" to userIds, "msg" to msg)

    fun sendSong(userIds: String, id: Long, msg: String): Boolean =
        send("/send/song", "user_ids" to userIds, "id" to id.toString(), "msg" to msg)

    fun sendAlbum(userIds: String, id: Long, msg: String): Boolean =
        send("/send/album", "user_ids" to userIds, "id" to id.toString(), "msg" to msg)

    fun sendPlaylist(userIds: String, playlist: Long, msg: String): Boolean =
        send(
            "/send/playlist", "user_ids" to userIds,
            "playlist" to playlist.toString(), "msg" to msg,
        )

    /** /user/detail：按 uid 取用户资料 */
    fun userDetail(uid: Long): UserBrief? = runCatching {
        val r = Netease.socialGet("/user/detail", "uid" to uid.toString())
        if (code(r) != 200) return@runCatching null
        val p = r["profile"] ?: data(r)
        val u = userOf(p)
        u.takeIf { it.id > 0 || it.name.isNotBlank() }
            ?.copy(id = u.id.takeIf { it > 0 } ?: uid)
    }.getOrNull()

    /** 按昵称找用户：/search type=1002（用户）。文档没给，uid 精确查找失败时兜底 */
    fun searchUsers(keyword: String): List<UserBrief> = runCatching {
        val r = Netease.socialGet(
            "/search", "keywords" to keyword, "type" to "1002",
            "limit" to "20", "offset" to "0",
        )
        array(r, "userprofiles", "users", "list").map { userOf(it) }
            .filter { it.id > 0 }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- 播客

    private fun podcastOf(m: JsonObject): Podcast = Podcast(
        id = lng(m, "id", "voiceListId", "radioId", "djRadioId"),
        name = str(m, "name", "title", "radioName", "voiceListName"),
        cover = cover(m),
        desc = str(m, "desc", "description", "briefDesc", "introduction"),
        category = str(m, "category", "categoryName", "secondCategory"),
        creator = str(m["dj"] ?: m["user"] ?: m["creator"], "nickname", "name"),
        voiceCount = intOf(m, "programCount", "voiceCount", "count", "trackCount"),
    )

    private fun voiceOf(m: JsonObject): Voice = Voice(
        id = lng(m, "id", "voiceId", "programId", "mainSongId"),
        radioId = lng(m, "radioId", "voiceListId", "djRadioId"),
        name = str(m, "name", "title", "voiceName", "songName"),
        cover = cover(m) ?: cover(m["cover"] ?: m["album"]),
        desc = str(m, "description", "desc", "introduction"),
        duration = lng(m, "duration", "durationMs", "length"),
        createTime = lng(m, "createTime", "publishTime", "scheduledPublishTime"),
        status = str(m, "displayStatus", "status"),
        audioUrl = str(m, "url", "playUrl", "audioUrl", "mp3Url").takeIf { it.startsWith("http") },
        orderNo = intOf(m, "orderNo", "position", "serialNum"),
    )

    /** /record/recent/dj：最近播放的播客声音 */
    fun recentDj(limit: Int = 100): List<Voice> = runCatching {
        val r = Netease.socialGet("/record/recent/dj", "limit" to limit.toString())
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "list", "records", "voices", "programs").map { m ->
            val inner = m["data"] ?: m["program"] ?: m["voice"] ?: m
            voiceOf(obj(inner) ?: m)
        }
    }.getOrDefault(emptyList())

    /** /voicelist/search：播客列表（独立搜索，不进主搜索页） */
    fun podcastSearch(keyword: String, limit: Int = 30, offset: Int = 0): List<Podcast> = runCatching {
        val r = Netease.socialGet(
            "/voicelist/search", "keyword" to keyword,
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "list", "voicelists", "radios", "djRadios").map(::podcastOf)
            .filter { it.id > 0 }
    }.getOrDefault(emptyList())

    /** /voicelist/detail：播客封面 / 分类 / 名称 / 简介 */
    fun podcastDetail(id: Long): Podcast = runCatching {
        val r = Netease.socialGet("/voicelist/detail", "id" to id.toString())
        if (code(r) != 200) return@runCatching Podcast(id = id)
        val d = data(r)
        podcastOf(obj(d) ?: r).copy(id = id)
    }.getOrDefault(Podcast(id = id))

    /** /voicelist/list：播客里的声音 */
    fun voiceList(voiceListId: Long, limit: Int = 200, offset: Int = 0): List<Voice> = runCatching {
        val r = Netease.socialGet(
            "/voicelist/list", "voiceListId" to voiceListId.toString(),
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "list", "voices", "programs").map { voiceOf(it).copy(radioId = voiceListId) }
    }.getOrDefault(emptyList())

    /** /voicelist/list/search：搜索播客里的声音 */
    fun voiceSearch(
        radioId: Long? = null, name: String? = null, displayStatus: String? = null,
        type: String? = null, voiceFeeType: Int? = null, limit: Int = 20, offset: Int = 0,
    ): List<Voice> = runCatching {
        val p = mutableListOf(
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        radioId?.let { p.add("radioId" to it.toString()) }
        name?.takeIf { it.isNotBlank() }?.let { p.add("name" to it) }
        displayStatus?.let { p.add("displayStatus" to it) }
        type?.let { p.add("type" to it) }
        voiceFeeType?.let { p.add("voiceFeeType" to it.toString()) }
        val r = Netease.socialGet("/voicelist/list/search", *p.toTypedArray())
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "list", "voices", "programs").map(::voiceOf)
    }.getOrDefault(emptyList())

    /** /voice/detail：声音详情，优先取可播放直链 */
    fun voiceDetail(id: Long): VoiceDetail = runCatching {
        val r = Netease.socialGet("/voice/detail", "id" to id.toString())
        if (code(r) != 200) return@runCatching VoiceDetail(voice = Voice(id = id))
        val d = data(r)
        val v = voiceOf(obj(d) ?: r).copy(id = id)
        val url = str(d, "url", "playUrl", "audioUrl", "mp3Url")
            .takeIf { it.startsWith("http") }
            ?: str(obj(d)?.get("playInfo") ?: obj(d)?.get("audio"), "url", "playUrl")
                .takeIf { it.startsWith("http") }
        VoiceDetail(
            voice = v,
            audioUrl = url ?: v.audioUrl,
            desc = str(d, "description", "desc").ifBlank { v.desc },
            categoryId = lng(d, "categoryId"),
            secondCategoryId = lng(d, "secondCategoryId"),
        )
    }.getOrDefault(VoiceDetail(voice = Voice(id = id)))

    /** /voicelist/trans：调整声音在播客里的顺序 */
    fun voiceSort(radioId: Long, programId: Long, position: Int): Boolean = runCatching {
        val r = Netease.socialGet(
            "/voicelist/trans", "radioId" to radioId.toString(),
            "programId" to programId.toString(), "position" to position.toString(),
            "limit" to "200", "offset" to "0",
        )
        code(r) == 200
    }.getOrDefault(false)

    /** /voice/delete：删除播客声音（ids 多个逗号隔开） */
    fun voiceDelete(ids: String): Boolean = runCatching {
        code(Netease.socialGet("/voice/delete", "ids" to ids)) == 200
    }.getOrDefault(false)

    /** /voice/upload：multipart 上传声音，字段名 songFile；可同时带 imgFile */
    fun voiceUpload(
        voiceListId: Long, categoryId: Long, secondCategoryId: Long,
        description: String, bytes: ByteArray, mime: String, fileName: String,
        songName: String = "", coverBytes: ByteArray? = null,
    ): Boolean {
        if (bytes.isEmpty() || voiceListId <= 0) return false
        val form = linkedMapOf(
            "voiceListId" to voiceListId.toString(),
            "categoryId" to categoryId.toString(),
            "secondCategoryId" to secondCategoryId.toString(),
            "description" to description,
            "orderNo" to "1",
        )
        if (songName.isNotBlank()) form["songName"] = songName
        // socialUpload 只支持一个文件字段；imgFile 封面同传暂未实现，
        // coverBytes 参数先保留给后续接入，本版只上传声音文件。
        return Netease.socialUpload(
            "/voice/upload", emptyList(), form,
            "songFile", fileName, bytes, mime,
        )
    }

    /** /voicelist/my/created：我创建的播客声音 / 播客 */
    fun myCreated(limit: Int = 20): List<Podcast> = runCatching {
        val r = Netease.socialGet("/voicelist/my/created", "limit" to limit.toString())
        if (code(r) != 200) return@runCatching emptyList()
        array(r, "list", "voicelists", "radios").map(::podcastOf).filter { it.id > 0 }
    }.getOrDefault(emptyList())
}
