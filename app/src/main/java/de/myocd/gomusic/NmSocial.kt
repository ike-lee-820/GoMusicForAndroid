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
 * 字段缺失就整页空掉。请求统一复用 Netease.socialCall（带 cookie /
 * timestamp / randomCNIP，GET 失败自动换 POST 重试）。
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
        /** 26.11.86：服务端原始 msg，用于识别其中的链接（JSON 资源消息里也不丢） */
        val raw: String = "",
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

    private val CONTENT_KEYS = setOf(
        "list", "voicelists", "radios", "djRadios", "voices", "programs",
        "records", "result", "songs", "msgs", "detail", "voice", "playlist",
        "roomInfo", "roomUsers",
    )

    /** 不强制顶层 code==200：有业务数据就算成功，避免不同部署的包装差异让整页失效 */
    private fun isOk(r: JsonObject): Boolean {
        if (code(r) == 200) return true
        val d = obj(r["data"])
        if (d != null) {
            val dc = (d["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (dc == 200) return true
            if (d.keys.any { it in CONTENT_KEYS }) return true
        }
        return r.keys.any { it in CONTENT_KEYS }
    }

    private fun data(r: JsonObject): JsonElement = r["data"] ?: r

    private fun cover(e: JsonElement?): String? {
        val u = str(e, "picUrl", "coverUrl", "cover", "imgUrl", "avatarUrl", "img1v1Url", "image")
            .takeIf { it.startsWith("http") }
        return u?.let { hdCoverUrl(it, 500) }
    }

    /** 资源私信在 msg 里是一段 JSON 字符串，尽量解出标题 / id / 类型 */
    /** 26.11.86：从网易云网页链接里认出资源类型与 id，供气泡点击 */
    private fun idFromNeteaseLink(raw: String): Pair<String, Long> {
        if (!raw.contains("music.163.com", true)) return "" to 0L
        val kind = when {
            Regex("""/(song|#/song)""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "song"
            Regex("""/(album|#/album)""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "album"
            Regex("""/(playlist|discover/toplist|#/playlist)""", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "playlist"
            else -> ""
        }
        val id = Regex("""[?&]id=(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""/(?:song|album|playlist)/(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L
        return kind to id
    }

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
            // 26.11.91：资源私信的 msg 是一段 JSON，里面塞满了 picUrl / coverUrl
            // 等封面图地址。解析不出名字时绝不能把整段 JSON 回退成显示文本，
            // 否则气泡里会印出一大串图片链接（用户看到的就是「分享专辑显示图片链接」）。
            val clean = name.takeIf { it.isNotBlank() && !it.trim().startsWith("{") }
                ?: "分享的资源"
            Triple(clean, id, kind)
        }.getOrDefault(Triple("分享的资源", 0L, "text"))
    }

    /** 26.11.91：气泡里给人看的文本，绝不能带 JSON 或图片 CDN 链接 */
    fun cleanDisplayText(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{")) return parseResource(t).first
        // 纯文本里若只混进了图片地址，也不原样展示
        if (t.startsWith("http", true) && isImageUrl(t)) return "[图片]"
        return t
    }

    /** 图片 CDN 链接：不能当可点链接展示，也不能印在气泡正文里 */
    fun isImageUrl(u: String): Boolean {
        val low = u.lowercase()
        val path = low.substringBefore('?')
        return path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".png") || path.endsWith(".webp") ||
            path.endsWith(".gif") || path.endsWith(".bmp") ||
            low.contains("music.126.net") || low.contains("nosdn.127.net") ||
            low.contains("/obj/") ||
            (low.contains("param=") && low.contains("image"))
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
        val r = Netease.socialCall("/pl/count", false)
        if (!isOk(r)) return@runCatching 0 to 0
        val d = data(r)
        val msg = intOf(d, "msg", "privateMsg", "privateMessage", "msgCount", "newMsg", "privateMsgCount")
        val notice = intOf(d, "notification", "notice", "notify", "notificationCount", "newNotice")
        // 有些部署把未读塞在 data 的子对象里
        val msg2 = intOf(obj(d)?.get("msg"), "count", "total") +
            intOf(obj(d)?.get("privateMsg"), "count", "total")
        val notice2 = intOf(obj(d)?.get("notification"), "count", "total")
        (msg + msg2) to (notice + notice2)
    }.getOrDefault(0 to 0)

    /**
     * /msg/private：会话列表。
     *
     * 26.11.92：修「显示的用户和点进去的消息不一致」。旧版 name 取
     * fromUser.nickname、uid 却在 fromUser 没解析出 id 时用顶层
     * fromUserId/uid/userId 兜底——那些字段在部分部署里是**自己**的 id，
     * 于是列表显示对方名字、点进去却打开了自己的会话（或另一个人的）。
     * 现在 uid 与 name 必须来自同一个「对方」对象；fromUser 恰好是
     * 最后一条消息发送者且等于自己时，改取 toUser。
     */
    fun conversations(limit: Int = 30, offset: Int = 0): List<Conversation> = runCatching {
        val r = Netease.socialCall(
            "/msg/private", false,
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (!isOk(r)) return@runCatching emptyList()
        val me = runCatching { Netease.uid() }.getOrDefault(0L)
        array(r, "msgs", "list").mapNotNull { m ->
            var peer = userOf(m["fromUser"] ?: m["user"] ?: m["friend"])
            // 最后一条是自己发的：fromUser 会是自己，真正的对方在 toUser
            if (me > 0L && peer.id == me) {
                val to = userOf(m["toUser"] ?: m["to"])
                val toId = to.id.takeIf { it > 0L }
                    ?: lng(m, "toUserId", "targetId").takeIf { it > 0L }
                if (toId != null && toId != me) peer = to.copy(id = toId)
            }
            val uid = peer.id.takeIf { it > 0L && it != me }
                ?: lng(m, "fromUserId", "uid").takeIf { it > 0L && it != me }
                ?: return@mapNotNull null
            val lastRaw = str(m, "lastMsg", "msg", "lastMessage", "content")
            val (last0, _, _) = parseResource(lastRaw)
            Conversation(
                uid = uid,
                // 名字必须跟着同一个对象走，不能再拿顶层 nickname 兜底
                name = peer.name.ifBlank { str(m, "nickname", "name") },
                avatar = peer.avatar ?: cover(m),
                last = cleanDisplayText(last0),
                time = lng(m, "lastMsgTime", "time", "lastTime"),
                unread = intOf(m, "unread", "unreadCount", "newMsgCount"),
            )
        }.distinctBy { it.uid }
    }.getOrDefault(emptyList())

    /** /msg/private/history：与某人的私信内容；before 取上一页最后一条 time */
    fun history(uid: Long, limit: Int = 30, before: Long = 0): List<ChatMessage> = runCatching {
        val params = mutableListOf(
            "uid" to uid.toString(), "limit" to limit.toString(),
        )
        if (before > 0) params.add("before" to before.toString())
        val r = Netease.socialCall("/msg/private/history", false, *params.toTypedArray())
        if (!isOk(r)) return@runCatching emptyList()
        val me = runCatching { Netease.uid() }.getOrDefault(0L)
        array(r, "msgs", "list").map { m ->
            val fromId = lng(
                m["fromUser"] ?: m["user"], "userId", "uid", "id",
            ).takeIf { it > 0 } ?: lng(m, "fromUserId", "uid")
            val raw = str(m, "msg", "content", "text", "lastMsg")
            val (parsedText, resId, parsedKind) = parseResource(raw)
            val text = cleanDisplayText(parsedText)
            val resObj = listOf(m["song"], m["album"], m["playlist"], m["resource"])
                .firstOrNull { it != null }
            // 26.11.86：资源 id 可能散落在 JSON 内、附件对象、消息顶层，
            // 或干脆只写在 music.163.com 链接里 —— 全都找一遍，气泡才能点开。
            val (linkKind, linkId) = idFromNeteaseLink(raw)
            val inferredKind = when {
                parsedKind != "text" -> parsedKind
                m.containsKey("song") -> "song"
                m.containsKey("album") -> "album"
                m.containsKey("playlist") -> "playlist"
                else -> linkKind
            }
            val id = resId.takeIf { it > 0 }
                ?: lng(resObj, "id", "songId", "albumId", "playlistId").takeIf { it > 0 }
                ?: lng(m, "songId", "albumId", "playlistId", "resourceId").takeIf { it > 0 }
                ?: linkId
            val kind = if (inferredKind != "text" && id > 0L) inferredKind else "text"
            ChatMessage(
                id = lng(m, "msgId", "id", "messageId"),
                time = lng(m, "time", "createTime", "msgTime"),
                fromSelf = me > 0 && fromId == me,
                text = text,
                kind = kind,
                resId = id,
                resName = str(resObj, "name", "title").ifBlank {
                    if (kind != "text") cleanDisplayText(text) else ""
                },
                resCover = cover(resObj) ?: cover(m),
                raw = raw,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 26.11.85：尽力让服务端把该会话标记成已读。
     * 标准模块里没有这个路由（/msg/private/history 通常会顺带清未读），
     * 所以只是 best-effort：接口不存在 / 返回错误都静默忽略，
     * 真正的「清除提醒」由本地已读基线保证，红点不会复活。
     */
    fun markPrivateRead(uid: Long): Boolean = runCatching {
        if (uid <= 0) return@runCatching false
        val r = Netease.socialCall(
            "/msg/private/read", true,
            "uid" to uid.toString(), "userId" to uid.toString(),
        )
        isOk(r)
    }.getOrDefault(false)

    private fun send(path: String, vararg params: Pair<String, String>): Boolean = runCatching {
        val r = Netease.socialCall(path, true, *params)
        isOk(r)
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
        val r = Netease.socialCall("/user/detail", false, "uid" to uid.toString())
        if (!isOk(r)) return@runCatching null
        val p = r["profile"] ?: data(r)
        val u = userOf(p)
        u.takeIf { it.id > 0 || it.name.isNotBlank() }
            ?.copy(id = u.id.takeIf { it > 0 } ?: uid)
    }.getOrNull()

    /**
     * 26.11.89：好友列表（应用内分享选人用）。
     * /user/follows = 我关注的人，/user/followeds = 我的粉丝；
     * 两者合并去重后就是「所有好友」，比只有「最近会话」全得多。
     */
    fun follows(uid: Long, limit: Int = 30, offset: Int = 0, fans: Boolean = false): List<UserBrief> =
        runCatching {
            val path = if (fans) "/user/followeds" else "/user/follows"
            val r = Netease.socialCall(
                path, false,
                "uid" to uid.toString(),
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            )
            array(r, "follow", "follows", "followeds", "friends", "userprofiles", "users")
                .map { userOf(it) }
                .map { u -> u.copy(id = u.id.takeIf { it > 0 } ?: 0L) }
                .filter { it.id > 0 && it.name.isNotBlank() }
        }.getOrDefault(emptyList())

    /** 按昵称找用户：/search type=1002（用户）。文档没给，uid 精确查找失败时兜底 */
    fun searchUsers(keyword: String): List<UserBrief> = runCatching {
        val r = Netease.socialCall(
            "/search", false, "keywords" to keyword, "type" to "1002",
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
        val r = Netease.socialCall("/record/recent/dj", false, "limit" to limit.toString())
        if (!isOk(r)) return@runCatching emptyList()
        array(r, "list", "records", "voices", "programs").map { m ->
            val inner = m["data"] ?: m["program"] ?: m["voice"] ?: m
            voiceOf(obj(inner) ?: m)
        }
    }.getOrDefault(emptyList())

    /** /voicelist/search：播客列表（独立搜索，不进主搜索页） */
    fun podcastSearch(keyword: String, limit: Int = 30, offset: Int = 0): List<Podcast> = runCatching {
        val r = Netease.socialCall(
            "/voicelist/search", false, "keyword" to keyword,
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (!isOk(r)) return@runCatching emptyList()
        array(r, "list", "voicelists", "radios", "djRadios").map(::podcastOf)
            .filter { it.id > 0 }
    }.getOrDefault(emptyList())

    /** /voicelist/detail：播客封面 / 分类 / 名称 / 简介 */
    fun podcastDetail(id: Long): Podcast = runCatching {
        val r = Netease.socialCall("/voicelist/detail", false, "id" to id.toString())
        if (!isOk(r)) return@runCatching Podcast(id = id)
        val d = data(r)
        podcastOf(obj(d) ?: r).copy(id = id)
    }.getOrDefault(Podcast(id = id))

    /** /voicelist/list：播客里的声音 */
    fun voiceList(voiceListId: Long, limit: Int = 200, offset: Int = 0): List<Voice> = runCatching {
        val r = Netease.socialCall(
            "/voicelist/list", false, "voiceListId" to voiceListId.toString(),
            "limit" to limit.toString(), "offset" to offset.toString(),
        )
        if (!isOk(r)) return@runCatching emptyList()
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
        val r = Netease.socialCall("/voicelist/list/search", false, *p.toTypedArray())
        if (!isOk(r)) return@runCatching emptyList()
        array(r, "list", "voices", "programs").map(::voiceOf)
    }.getOrDefault(emptyList())

    /** /voice/detail：声音详情，优先取可播放直链 */
    fun voiceDetail(id: Long): VoiceDetail = runCatching {
        val r = Netease.socialCall("/voice/detail", false, "id" to id.toString())
        if (!isOk(r)) return@runCatching VoiceDetail(voice = Voice(id = id))
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
        val r = Netease.socialCall(
            "/voicelist/trans", true, "radioId" to radioId.toString(),
            "programId" to programId.toString(), "position" to position.toString(),
            "limit" to "200", "offset" to "0",
        )
        isOk(r)
    }.getOrDefault(false)

    /** /voice/delete：删除播客声音（ids 多个逗号隔开） */
    fun voiceDelete(ids: String): Boolean = runCatching {
        isOk(Netease.socialCall("/voice/delete", true, "ids" to ids))
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
        val r = Netease.socialCall("/voicelist/my/created", false, "limit" to limit.toString())
        if (!isOk(r)) return@runCatching emptyList()
        array(r, "list", "voicelists", "radios").map(::podcastOf).filter { it.id > 0 }
    }.getOrDefault(emptyList())

    /**
     * 26.11.84：创建播客。
     *
     * 重要：api-enhanced 的 module 目录里只有 voicelist_search / detail / list /
     * list_search / my_created / trans，**没有 voicelist_create**。用户给的文档
     * 也没有创建接口。这里做成主备路径探测：按常见命名依次尝试，任何一个
     * 返回成功就解析出 voiceListId；全部失败时返回 null，由界面明确告知
     * 「当前服务端没有创建播客接口」并给网页端入口，绝不静默假装建好了。
     */
    fun createPodcast(
        name: String,
        description: String = "",
        categoryId: Long = 0L,
    ): Long? = runCatching {
        val n = name.trim()
        if (n.isBlank()) return@runCatching null
        val p = mutableListOf(
            "name" to n, "voiceListName" to n,
            "description" to description, "desc" to description,
        )
        if (categoryId > 0L) {
            p.add("categoryId" to categoryId.toString())
            p.add("category" to categoryId.toString())
        }
        val args = p.toTypedArray()
        for (path in listOf(
            "/voicelist/create", "/voice/list/create",
            "/voicelist/add", "/voice/workbench/voicelist/create",
        )) {
            val r = Netease.socialCall(path, true, *args)
            if (!isOk(r)) continue
            val d = obj(r["data"]) ?: r
            val info = obj(childAny(d, "voicelist", "voiceList", "detail", "data")) ?: d
            val id = lng(info, "id", "voiceListId", "radioId", "djRadioId")
                .takeIf { it > 0L }
                ?: lng(d, "id", "voiceListId", "radioId", "djRadioId").takeIf { it > 0L }
            if (id != null) return@runCatching id
            // 有些部署只回 code=200 不带 id：也算建好，交给界面刷新列表
            if (code(r) == 200 || (d["code"] as? JsonPrimitive)?.content == "200") {
                return@runCatching -1L
            }
        }
        null
    }.getOrNull()

    private fun childAny(o: JsonObject, vararg keys: String): JsonElement? {
        for (k in keys) o[k]?.let { return it }
        return null
    }

}
