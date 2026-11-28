@file:Suppress("UNCHECKED_CAST")

package de.myocd.gomusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 26.11.83：一起听改成网易云**官方房间**协议（#1676 + 主机模式示例）。
 *
 * 26.11.82 那版因为拿不到协议正文，做的是局域网 HTTP 主机模式；这次用户
 * 把示例 HTML 与 Issue 全文都贴进来了，于是按真实接口重做：
 *
 *  创建房间   /listentogether/room/create            (GET)
 *  房间检查   /listentogether/room/check            (POST)
 *  加入房间   /listentogether/accept                (POST)
 *  房间状态   /listentogether/status              (GET)
 *  同步歌单   /listentogether/sync/list/command    (POST，REPLACE)
 *  读取歌单   /listentogether/sync/playlist/get    (POST)
 *  播放指令   /listentogether/play/command         (POST)
 *  心跳       /listentogether/heatbeat             (POST，文档拼写即 heatbeat)
 *  结束房间   /listentogether/end                  (POST)
 *
 * 请求统一走 [Netease.socialCall]：示例里 GET / POST 混用，cookie 有时在
 * query 有时在 form body，双 method 兼容不因部署差异把房间流程卡死。
 * 响应字段全部按优先键容错下钻，某个字段缺失不会让整个房间空掉。
 */

data class ListenAccount(
    val login: Boolean = false,
    val userId: Long = 0L,
    val nickname: String = "未登录",
)

data class RoomUser(
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

data class ListenTrack(
    val id: Long = 0L,
    val name: String = "",
    val cover: String? = null,
    val artist: String = "",
)

data class ListenPlaylist(
    val name: String = "未获取",
    val trackIds: List<Long> = emptyList(),
    val tracks: List<ListenTrack> = emptyList(),
)

/** 26.11.86：一起听里可选/可搜的歌单摘要 */
data class PlaylistBrief(
    val id: Long = 0,
    val name: String = "",
    val cover: String? = null,
    val trackCount: Int = 0,
    val creator: String = "",
)

/** 房间状态：是否还在房间里 + 在线用户 */
data class RoomStatus(
    val inRoom: Boolean = false,
    val users: List<RoomUser> = emptyList(),
    val joinable: Boolean = true,
    val status: String = "",
)

object ListenTogetherApi {

    // ---------------------------------------------------------------- JSON 容错工具

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun arr(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> emptyList()
        else -> emptyList()
    }

    private fun text(e: JsonElement?, vararg keys: String): String {
        val p = e as? JsonPrimitive
        if (p != null) return p.content.takeUnless { it.isBlank() || it == "null" }.orEmpty()
        val o = e as? JsonObject ?: return ""
        for (k in keys) {
            val t = text(o[k])
            if (t.isNotBlank()) return t
        }
        return ""
    }

    private fun num(e: JsonElement?, vararg keys: String): Long {
        val p = e as? JsonPrimitive
        if (p != null) return p.content.trim().toLongOrNull() ?: 0L
        val o = e as? JsonObject ?: return 0L
        for (k in keys) {
            val n = num(o[k])
            if (n != 0L) return n
        }
        return 0L
    }

    private fun child(o: JsonObject?, vararg keys: String): JsonElement? {
        if (o == null) return null
        for (k in keys) o[k]?.let { return it }
        return null
    }

    private fun code(r: JsonObject): Int =
        (r["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    private fun ok(r: JsonObject): Boolean {
        val c = code(r)
        if (c == 200) return true
        // 创建房间文档示例没有 code，只有 type / roomInfo
        return r.containsKey("roomInfo") || obj(r["data"])?.containsKey("roomInfo") == true ||
            obj(r["data"])?.get("success") == JsonPrimitive(true)
    }

    private fun call(
        path: String,
        postFirst: Boolean,
        vararg params: Pair<String, String>,
    ): JsonObject = Netease.socialCall(path, postFirst, *params)

    // ---------------------------------------------------------------- 账号

    /** 示例里的 login()：/login/status 取 userId / nickname */
    fun account(): ListenAccount = runCatching {
        val r = call("/login/status", false, "timestamp" to System.currentTimeMillis().toString())
        val d = obj(r["data"]) ?: r
        val profile = obj(child(d, "profile", "account"))
            ?: obj(child(r, "profile", "account")) ?: d
        val innerCode = num(d, "code")
        val id = num(profile, "userId", "id", "uid")
        val name = text(profile, "nickname", "userName", "name")
        val logged = (code(r) == 200 || innerCode == 200L || id > 0L) && id > 0L
        ListenAccount(logged, id, name.ifBlank { if (logged) "网易云用户" else "未登录" })
    }.getOrDefault(ListenAccount())

    // ---------------------------------------------------------------- 房间

    /**
     * 第一步：创建房间。文档返回
     * {"type":"NEW_ROOM","roomInfo":{"creatorId":1,"roomId":"$ROOM_ID"}}
     * 不同部署也可能把 roomInfo 包在 data 里，按两种都找。
     */
    fun createRoom(): String? = runCatching {
        val r = call("/listentogether/room/create", false)
        if (!ok(r)) return null
        val d = obj(r["data"]) ?: r
        val info = obj(child(d, "roomInfo")) ?: obj(child(r, "roomInfo")) ?: d
        val id = text(info, "roomId", "id").ifBlank { num(info, "roomId", "id").toString() }
        id.takeIf { it.isNotBlank() && it != "0" }
    }.getOrNull()

    /** 获取房间情况：joinable / status */
    fun checkRoom(roomId: String): RoomStatus = runCatching {
        val r = call("/listentogether/room/check", true, "roomId" to roomId)
        val d = obj(r["data"]) ?: r
        RoomStatus(
            inRoom = code(r) == 200,
            joinable = text(d, "joinable") != "false" && r["joinable"] != JsonPrimitive(false),
            status = text(d, "status", "type"),
        )
    }.getOrDefault(RoomStatus())

    /** 加入房间：accept + room/check + sync/playlist/get */
    fun acceptRoom(roomId: String, inviterId: Long): Boolean = runCatching {
        val r = call(
            "/listentogether/accept", true,
            "roomId" to roomId, "inviterId" to inviterId.toString(),
        )
        ok(r) || code(r) == 200
    }.getOrDefault(false)

    /** 获取一起听状态：inRoom + roomInfo.roomUsers */
    fun status(): RoomStatus = runCatching {
        val r = call("/listentogether/status", false)
        val d = obj(r["data"]) ?: r
        val info = obj(child(d, "roomInfo")) ?: d
        val inRoom = (r["inRoom"] == JsonPrimitive(true)) ||
            (d["inRoom"] == JsonPrimitive(true)) ||
            (code(r) == 200 && (d.containsKey("roomInfo") || d.containsKey("roomUsers")))
        val users = (arr(child(info, "roomUsers", "users")) +
            arr(child(d, "roomUsers", "users"))).distinctBy {
            num(it, "userId", "uid", "id")
        }.map {
            RoomUser(
                userId = num(it, "userId", "uid", "id"),
                nickname = text(it, "nickname", "name", "userName"),
                avatarUrl = text(it, "avatarUrl", "avatar", "picUrl").takeIf { u -> u.startsWith("http") },
            )
        }.filter { it.userId > 0L || it.nickname.isNotBlank() }
        RoomStatus(inRoom, users)
    }.getOrDefault(RoomStatus())

    /** 结束房间 */
    fun endRoom(roomId: String): Boolean = runCatching {
        val r = call("/listentogether/end", true, "roomId" to roomId)
        ok(r)
    }.getOrDefault(false)

    /** 分享链接（手动拼接，示例格式） */
    fun shareLink(songId: Long, roomId: String, inviterId: Long): String =
        "https://st.music.163.com/listen-together/share/" +
            "?songId=$songId&roomId=$roomId&inviterId=$inviterId"

    /** 示例里的播放直链（App 内播放优先用自己的 audioUrl，这个留作兜底/展示） */
    fun outerUrl(songId: Long): String =
        "https://music.163.com/song/media/outer/url?id=$songId.mp3"

    // ---------------------------------------------------------------- 歌单同步

    /**
     * 发送播放列表信息。文档写的是 /listentogether/sync/list，示例 loadPlaylist
     * 用的是 /listentogether/sync/list/command —— 两个都试，先示例后文档。
     */
    fun syncList(
        roomId: String,
        userId: Long,
        version: Int,
        displayList: String,
        randomList: String = displayList,
        playMode: String = "ORDER_LOOP",
        commandType: String = "REPLACE",
    ): Boolean = runCatching {
        val p = arrayOf(
            "roomId" to roomId,
            "commandType" to commandType,
            "userId" to userId.toString(),
            "version" to version.toString(),
            "playMode" to playMode,
            "displayList" to displayList,
            "randomList" to randomList,
        )
        val r = call("/listentogether/sync/list/command", true, *p)
        if (ok(r)) return true
        call("/listentogether/sync/list", true, *p).let { ok(it) }
    }.getOrDefault(false)

    /**
     * 获取播放列表：data.playlist.displayList.result 是音乐 id 数组。
     * 也兼容逗号分隔字符串 / displayList 直接是数组。
     */
    fun syncPlaylistGet(roomId: String): List<Long> = runCatching {
        val r = call("/listentogether/sync/playlist/get", true, "roomId" to roomId)
        val d = obj(r["data"]) ?: r
        val pl = obj(child(d, "playlist", "list")) ?: d
        val dl = child(pl, "displayList") ?: child(d, "displayList")
        val raw = text(dl, "result", "list", "ids")
        val ids = arr(dl).map { num(it, "id", "songId") } +
            arr(obj(dl)?.get("result")).map { num(it, "id", "songId") }
        (ids + raw.split(',', ' ', '\n').mapNotNull { it.trim().toLongOrNull() })
            .filter { it > 0L }.distinct()
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- 播放指令

    /** 播放 / 暂停 / 切歌 / 同步进度，progress 单位毫秒 */
    fun playCommand(
        roomId: String,
        commandType: String,
        songId: Long,
        progress: Long,
        playStatus: String,
        clientSeq: Int,
        formerSongId: String = "-1",
    ): Boolean = runCatching {
        val r = call(
            "/listentogether/play/command", true,
            "roomId" to roomId,
            "progress" to progress.toString(),
            "commandType" to commandType,
            "formerSongId" to formerSongId,
            "targetSongId" to songId.toString(),
            "clientSeq" to clientSeq.toString(),
            "playStatus" to playStatus,
        )
        ok(r)
    }.getOrDefault(false)

    /** 心跳包：每首歌开始时发，文档拼写即 heatbeat；同时兼容 heartbeat */
    fun heartbeat(
        roomId: String,
        songId: Long,
        playStatus: String,
        progress: Long,
    ): Boolean = runCatching {
        val p = arrayOf(
            "roomId" to roomId,
            "songId" to songId.toString(),
            "playStatus" to playStatus,
            "progress" to progress.toString(),
        )
        val r = call("/listentogether/heatbeat", true, *p)
        if (ok(r)) return true
        call("/listentogether/heartbeat", true, *p).let { ok(it) }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 歌单 / 歌曲详情

    /** 26.11.86：搜索歌单（type=1000），结果进「一起听」的候选列表 */
    fun searchPlaylists(keyword: String, limit: Int = 20, offset: Int = 0): List<PlaylistBrief> =
        runCatching {
            val k = keyword.trim()
            if (k.isBlank()) return@runCatching emptyList()
            val r = call(
                "/search", false,
                "keywords" to k, "type" to "1000",
                "limit" to limit.toString(), "offset" to offset.toString(),
            )
            val d = obj(r["data"]) ?: obj(r["result"]) ?: r
            val plArr = arr(child(d, "playlists")) +
                arr(child(obj(r["result"]), "playlists")) +
                arr(child(obj(r["data"]), "playlists"))
            plArr.distinctBy { num(it, "id") }
                .map(::toPlaylistBrief)
                .filter { it.id > 0L }
        }.getOrDefault(emptyList())

    /** 26.11.86：我的歌单（自建 + 收藏），直接选来同步进房间 */
    fun myPlaylists(uid: Long): List<PlaylistBrief> = runCatching {
        if (uid <= 0L) return@runCatching emptyList()
        val r = call(
            "/user/playlist", false,
            "uid" to uid.toString(), "limit" to "1000", "offset" to "0",
        )
        val plArr = arr(child(r, "playlist")) + arr(child(obj(r["data"]), "playlist"))
        plArr.distinctBy { num(it, "id") }.map(::toPlaylistBrief).filter { it.id > 0L }
    }.getOrDefault(emptyList())

    private fun toPlaylistBrief(o: JsonObject): PlaylistBrief = PlaylistBrief(
        id = num(o, "id"),
        name = text(o, "name", "title"),
        cover = text(o, "coverImgUrl", "picUrl", "cover", "imgUrl")
            .takeIf { it.startsWith("http") },
        trackCount = num(o, "trackCount", "trackCnt", "count").toInt(),
        creator = obj(child(o, "creator"))?.let {
            text(it, "nickname", "name", "userName")
        }.orEmpty(),
    )

    /** /playlist/detail + /song/detail，返回歌单名与可点的曲目 */
    fun playlistDetail(id: Long): ListenPlaylist = runCatching {
        val r = call("/playlist/detail", true, "id" to id.toString())
        val pl = obj(child(r, "playlist", "result")) ?: r
        val name = text(pl, "name", "playlistName").ifBlank { "歌单 $id" }
        val ids = arr(child(pl, "trackIds")).map { num(it, "id", "songId") }
            .filter { it > 0L }
        val tracks = if (ids.isNotEmpty()) songDetail(ids) else
            arr(child(pl, "tracks")).map(::toTrack).filter { it.id > 0L }
        ListenPlaylist(name, ids.ifEmpty { tracks.map { it.id } }, tracks)
    }.getOrDefault(ListenPlaylist())

    /** 按 id 列表补歌曲信息（sync/playlist/get 加入房间后用） */
    fun songDetail(ids: List<Long>): List<ListenTrack> = runCatching {
        if (ids.isEmpty()) return emptyList()
        val r = call("/song/detail", true, "ids" to ids.joinToString(","))
        arr(child(r, "songs")).map(::toTrack).filter { it.id > 0L }
    }.getOrDefault(emptyList())

    private fun toTrack(o: JsonObject): ListenTrack {
        val al = obj(o["al"] ?: o["album"])
        val ar = arr(child(o, "ar", "artists")).map { text(it, "name") }
            .filter { it.isNotBlank() }
        return ListenTrack(
            id = num(o, "id", "songId"),
            name = text(o, "name", "title"),
            cover = text(al, "picUrl", "cover", "imgUrl").takeIf { it.startsWith("http") }
                ?: text(o, "picUrl").takeIf { it.startsWith("http") },
            artist = ar.joinToString("/").ifBlank { text(o, "artist", "artists") },
        )
    }
}
