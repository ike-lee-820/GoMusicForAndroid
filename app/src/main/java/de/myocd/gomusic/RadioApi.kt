package de.myocd.gomusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 26.11.87：电台页数据层。
 *
 * 原「播客」页整体换成「电台」，只接与电台有关的接口（用户明确「无关不实现」）：
 * 用户电台 / 订阅 / 推荐 / 分类 / 榜单 / 详情 / 节目 / 广播电台 / DIFM /
 * 声音搜索与排序 / 电台搜索(type=1009)。评论、动态、分享、点赞、MV 等不在本页。
 *
 * 所有请求走 Netease.socialCall（GET 主、POST 备，自动带 cookie/timestamp），
 * 响应包字段在不同部署里差别很大，统一用 collect() 从常见数组键里捞对象，
 * 再映射成 RadioItem，避免某个接口换字段就整页空白。
 */

data class RadioItem(
    val id: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val cover: String? = null,
    /** station / program / category / broadcast / difm / track / voice */
    val kind: String = "station",
    /** 节目音频 id（mainTrackId）、所属电台 id、DIFM source 等 */
    val extra: String = "",
    val desc: String = "",
)

data class RadioFeed(
    val key: String,
    val title: String,
    val path: String,
    val params: List<Pair<String, String>> = emptyList(),
    val kind: String = "station",
    /** 需要用户填的参数占位符名（不含花括号）；{uid} 自动取当前账号 */
    val paramName: String = "",
    val paramDefault: String = "",
    val note: String = "",
)

object RadioApi {

    private val ARRAY_KEYS = listOf(
        "data", "result", "list", "djRadios", "radios", "programs", "morePrograms",
        "recommend", "playlists", "channels", "toplist", "toplists", "hotRadios",
        "categories", "subList", "subs", "records", "voices", "banners", "banner",
        "rank", "dataList", "songs", "albums", "artist", "dj", "program", "radio",
        "collectList", "channelList", "categoryList", "regionList", "styles",
        "playlist", "programList", "radioList", "voiceList", "tracks",
    )

    private fun JsonObject?.txt(vararg keys: String): String {
        if (this == null) return ""
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val s = v.content
            if (s.isNotBlank() && s != "null") return s
        }
        return ""
    }

    private fun JsonObject?.num(vararg keys: String): Long {
        if (this == null) return 0L
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val n = v.content.toLongOrNull() ?: continue
            if (n != 0L) return n
        }
        return 0L
    }

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    /** 从响应里把所有疑似列表对象捞出来，去重后交给 toItem */
    private fun collect(root: JsonObject): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(e: JsonElement?, depth: Int) {
            if (e == null || depth > 3) return
            when (e) {
                is JsonArray -> e.forEach { x -> if (x is JsonObject) out.add(x) }
                is JsonObject -> {
                    for (k in ARRAY_KEYS) {
                        val v = e[k] ?: continue
                        if (v is JsonArray) {
                            v.forEach { x -> if (x is JsonObject) out.add(x) }
                        }
                    }
                    // 包在 data/result 里的对象再下钻一层
                    listOf("data", "result", "djRadio", "radio", "program", "voice")
                        .forEach { k -> e[k]?.let { if (it is JsonObject) walk(it, depth + 1) } }
                }
                else -> Unit
            }
        }
        walk(root, 0)
        // 有些分类 / 榜单对象没有 id，全用 name 作兜底，别把一堆 0 合成一条
        return out.distinctBy {
            val id = it.num("id", "rid", "djRadioId", "radioId", "voiceId", "programId", "channelId")
            if (id > 0L) "i$id" else "n" + it.txt("name", "title", "radioName", "nickname")
        }
    }

    private fun cover(o: JsonObject): String? =
        o.txt(
            "coverImgUrl", "picUrl", "coverUrl", "imgUrl", "imgurl", "cover",
            "avatarUrl", "intervenePicUrl", "pic", "radioCover", "programCover",
        ).takeIf { it.startsWith("http") }
            ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }

    private fun toItem(o: JsonObject, kind: String): RadioItem? {
        val id = o.num(
            "id", "rid", "djRadioId", "radioId", "voiceId", "programId",
            "mainTrackId", "channelId", "brandId", "categoryId", "cateId",
        )
        val title = o.txt(
            "name", "title", "radioName", "djName", "nickname", "voiceName",
            "programName", "channelName", "categoryName", "titleName",
        ).ifBlank { o.txt("desc", "description") }
        if (title.isBlank() && id <= 0L) return null
        val creator = obj(o["dj"])?.txt("nickname", "name")
            ?: obj(o["creator"])?.txt("nickname", "name")
            ?: obj(o["user"])?.txt("nickname", "name")
        val sub = buildString {
            if (!creator.isNullOrBlank()) append(creator)
            val cnt = o.num("programCount", "trackCount", "subCount", "playCount", "count")
            if (cnt > 0L) append(if (isEmpty()) "$cnt" else " · $cnt")
            val cat = o.txt("category", "categoryName", "secondCategory", "rcmdText")
            if (cat.isNotBlank()) append(if (isEmpty()) cat else " · $cat")
        }.ifBlank { o.txt("desc", "description", "copywriter").take(40) }
        val main = o.num("mainTrackId", "mainSongId", "trackId")
        val radioId = o.num("rid", "radioId", "djRadioId")
        val extra = when (kind) {
            "program", "voice" -> (main.takeIf { it > 0L } ?: id).toString() +
                (if (radioId > 0L) "|$radioId" else "")
            else -> radioId.takeIf { it > 0L }?.toString().orEmpty()
        }
        return RadioItem(
            id = id,
            title = title.ifBlank { "电台 $id" },
            subtitle = sub,
            cover = cover(o),
            kind = kind,
            extra = extra,
            desc = o.txt("desc", "description", "detail", "briefDesc"),
        )
    }

    /** 把一个 feed 解析成列表；postFirst=false 即先 GET 后 POST */
    fun fetch(feed: RadioFeed, resolved: List<Pair<String, String>>): List<RadioItem> =
        runCatching {
            val r = Netease.socialCall(feed.path, false, *resolved.toTypedArray())
            collect(r).mapNotNull { toItem(it, feed.kind) }
                .filter { it.title.isNotBlank() }
                .take(60)
        }.getOrDefault(emptyList())

    /** 电台详情 / 广播电台信息 / 节目详情：返回可直接展示的一段文字 */
    fun detailText(path: String, resolved: List<Pair<String, String>>): String = runCatching {
        val r = Netease.socialCall(path, false, *resolved.toTypedArray())
        val list = collect(r)
        val o = list.firstOrNull() ?: r
        val name = o.txt("name", "title", "radioName", "programName")
        val desc = o.txt("desc", "description", "detail", "briefDesc", "content")
        val dj = obj(o["dj"])?.txt("nickname") ?: obj(o["creator"])?.txt("nickname")
        val cnt = o.num("programCount", "subCount", "playCount")
        buildString {
            if (name.isNotBlank()) append(name).append('\n')
            if (!dj.isNullOrBlank()) append("主播：$dj\n")
            if (cnt > 0L) append("节目/订阅：$cnt\n")
            val cat = o.txt("category", "categoryName", "secondCategory")
            if (cat.isNotBlank()) append("分类：$cat\n")
            append(desc.ifBlank { "（该接口没有返回简介）" })
        }
    }.getOrDefault("详情获取失败，点重试")

    /** 收藏/订阅动作，返回是否成功；不同接口命名分别处理 */
    fun act(path: String, resolved: List<Pair<String, String>>): Boolean = runCatching {
        val r = Netease.socialCall(path, true, *resolved.toTypedArray())
        val c = (r["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        c == 200 || r.containsKey("data") || r.containsKey("result")
    }.getOrDefault(false)

    /** 搜索电台：/search type=1009 */
    fun search(keyword: String): List<RadioItem> = runCatching {
        val r = Netease.socialCall(
            "/search", false,
            "keywords" to keyword, "type" to "1009",
            "limit" to "30", "offset" to "0",
        )
        collect(r).mapNotNull { toItem(it, "station") }
            .filter { it.title.isNotBlank() }.take(40)
    }.getOrDefault(emptyList())
}

/** 电台页所有可用的接口目录，按子页分组。只含电台相关接口。 */
object RadioFeeds {

    fun groups(): List<Pair<String, List<RadioFeed>>> = listOf(
        "发现" to listOf(
            RadioFeed("banner", "电台 Banner", "/dj/banner", kind = "station"),
            RadioFeed("rec", "推荐电台", "/dj/recommend", kind = "station"),
            RadioFeed("progrec", "推荐节目", "/program/recommend", listOf("limit" to "10"), "program"),
            RadioFeed("djprog", "推荐电台节目", "/personalized/djprogram", kind = "program"),
            RadioFeed("personal", "个性推荐", "/dj/personalize/recommend", listOf("limit" to "6")),
            RadioFeed("today", "今日优选", "/dj/today/perfered"),
            RadioFeed("hot", "热门电台", "/dj/hot", listOf("limit" to "30")),
            RadioFeed("progtoplist", "节目榜", "/dj/program/toplist", listOf("limit" to "20")),
            RadioFeed("hoursprog", "24 小时节目榜", "/dj/program/toplist/hours", listOf("limit" to "20")),
            RadioFeed("hoursdj", "24 小时主播榜", "/dj/toplist/hours", listOf("limit" to "20")),
            RadioFeed("newcomer", "主播新人榜", "/dj/toplist/newcomer", listOf("limit" to "20")),
            RadioFeed("popular", "最热主播榜", "/dj/toplist/popular", listOf("limit" to "20")),
            RadioFeed("toplistnew", "新晋电台榜", "/dj/toplist", listOf("type" to "new", "limit" to "20")),
            RadioFeed("toplisthot", "热门电台榜", "/dj/toplist", listOf("type" to "hot", "limit" to "20")),
            RadioFeed("paygift", "付费精品", "/dj/toplist/pay", listOf("limit" to "20")),
            RadioFeed("paylist", "付费精选", "/dj/paygift", listOf("limit" to "20")),
            RadioFeed("rank", "电台排行榜", "/djRadio/top",
                listOf("sortIndex" to "1", "dataGapDays" to "7", "dataType" to "3",
                    "djRadioId" to "{djRadioId}"),
                paramName = "djRadioId", paramDefault = "336355127",
                note = "可填电台 id，不填则用示例 id"),
        ),
        "分类" to listOf(
            RadioFeed("catelist", "电台分类", "/dj/catelist", kind = "category"),
            RadioFeed("catrec", "推荐类型", "/dj/category/recommend", kind = "category"),
            RadioFeed("catex", "非热门类型", "/dj/category/excludehot", kind = "category"),
            RadioFeed("bytype", "分类推荐", "/dj/recommend/type",
                listOf("type" to "{type}"), paramName = "type", paramDefault = "1",
                note = "填分类 id，默认 1（明星做主播）"),
            RadioFeed("radiohot", "类别热门电台", "/dj/radio/hot",
                listOf("cateId" to "{cateId}", "limit" to "20"),
                paramName = "cateId", paramDefault = "2001",
                note = "填类别 id，默认 2001（创作|翻唱）"),
        ),
        "我的" to listOf(
            RadioFeed("userdj", "用户电台", "/user/dj", listOf("uid" to "{uid}")),
            RadioFeed("useraudio", "用户创建的电台", "/user/audio", listOf("uid" to "{uid}")),
            RadioFeed("sublist", "订阅的电台", "/dj/sublist"),
            RadioFeed("bccollect", "广播收藏", "/broadcast/channel/collect/list",
                listOf("limit" to "100"), kind = "broadcast"),
            RadioFeed("difmsub", "DIFM 收藏频道", "/dj/difm/subscribe/channels/get",
                listOf("sources" to "[0]"), kind = "difm"),
        ),
        "广播" to listOf(
            RadioFeed("bccat", "分类 / 地区", "/broadcast/category/region/get", kind = "category"),
            RadioFeed("bclist", "全部广播电台", "/broadcast/channel/list",
                listOf("categoryId" to "{categoryId}", "regionId" to "0"),
                kind = "broadcast", paramName = "categoryId", paramDefault = "0",
                note = "填类别 id，默认 0（全部）"),
            RadioFeed("bcinfo", "电台信息", "/broadcast/channel/currentinfo",
                listOf("id" to "{id}"), kind = "broadcast",
                paramName = "id", paramDefault = "5", note = "填广播电台 id"),
        ),
        "DIFM" to listOf(
            RadioFeed("difmall", "频道分类", "/dj/difm/all/style/channel",
                listOf("sources" to "[0]"), kind = "difm"),
            RadioFeed("difmplay", "播放列表", "/dj/difm/playing/tracks/list",
                listOf("source" to "0", "channelId" to "{channelId}", "limit" to "10"),
                kind = "track", paramName = "channelId", paramDefault = "1012",
                note = "填频道 id，默认 1012"),
        ),
        "声音" to listOf(
            RadioFeed("voicesearch", "声音搜索", "/voicelist/list/search",
                listOf("name" to "{name}", "limit" to "30", "offset" to "0"),
                kind = "voice", paramName = "name", paramDefault = "",
                note = "填声音名称关键词，留空取全部"),
            RadioFeed("voicetrans", "声音排序", "/voicelist/trans",
                listOf("radioId" to "{radioId}", "programId" to "{programId}",
                    "position" to "1", "limit" to "200", "offset" to "0"),
                kind = "voice", paramName = "radioId", paramDefault = "",
                note = "填电台 id / 声音 id（用英文逗号隔开：radioId,programId）"),
        ),
        "搜索" to listOf(
            RadioFeed("searchdj", "搜索电台", "/search",
                listOf("keywords" to "{name}", "type" to "1009", "limit" to "30"),
                paramName = "name", paramDefault = "", note = "输入电台名称"),
        ),
    )
}
