package de.myocd.gomusic

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 后台播放被打断的策略 */
const val FOCUS_NORMAL = 0   // 默认：可被任何应用打断（系统标准音频焦点）
const val FOCUS_CALL_ONLY = 1 // 除电话外不会被打断
const val FOCUS_ALWAYS = 2    // 不被任何应用打断，强行播放

/** 播放历史上限 */
const val MAX_HISTORY = 10000

/** 歌曲信息快照上限（历史里要能显示歌名/歌手） */
const val MAX_SNAPSHOTS = 2000

/** 歌词翻译缓存的歌曲数上限 */
const val MAX_LYRIC_TRANS = 300

/**
 * 26.11.59：最近搜索的实体记录。
 * 以前只存一串关键词字符串，界面只能画成横排胶囊；改成带类型、封面、
 * 副标题和歌词片段的行记录，才能按 Apple Music 的「最近搜索」列表展示。
 */
@Serializable
data class RecentItem(
    val key: String,
    /** song / artist / album / keyword */
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val cover: String? = null,
    val lyricLine: String = "",
    val ssid: String = "",
    val artist: String = "",
    val nmId: Long = 0,
    val nmKind: String = "",
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<String> = emptyList(),
    val cover: String? = null,
    /**
     * 关联的网易云歌单 id（0 = 本地自建）。
     * 有了它，同步时才能认出「这个本地歌单对应网易云的哪一张」，
     * 否则每次同步都会新建一份重名的歌单。
     */
    val nmId: Long = 0L,
    /**
     * 26.11.66：云端歌单的原始名字。
     * 与本地歌单重名时，本地展示名会加「云」标签，但回写云端 / 下次同步
     * 识别都要用这个原始名，不能把标签反复叠加，也不能把「云」字传到云端。
     */
    val cloudBase: String? = null,
    /**
     * 26.11.70：云端「收藏 / 订阅的歌单」和「我喜欢的音乐」不属于本人创建，
     * 网易云不允许改名 / 删除 / 加删歌 / 排序 / 换封面。本地仍可随意编辑，
     * 但所有改动不回写云端，免得每次都报失败。
     */
    val cloudReadOnly: Boolean = false,
    /**
     * 云端内容指纹（更新时间 + 歌曲数）。自动同步时指纹没变就跳过重新拉曲目，
     * 避免几十张收藏歌单每次进应用都发一轮请求；手动同步可强制全量。
     */
    val cloudStamp: String? = null,
)

/** 对应网页端 localStorage：play_counts / user_playlists / play_mode / quality / theme_color */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("gomusic", Context.MODE_PRIVATE)
    private val json = Api.json

    var playCounts: Map<String, Int>
        get() = runCatching {
            json.decodeFromString<Map<String, Int>>(sp.getString("play_counts", "{}")!!)
        }.getOrDefault(emptyMap())
        set(v) { sp.edit().putString("play_counts", json.encodeToString(v)).apply() }

    var playlists: List<Playlist>
        get() = runCatching {
            json.decodeFromString<List<Playlist>>(sp.getString("user_playlists", "[]")!!)
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("user_playlists", json.encodeToString(v)).apply() }

    /**
     * 歌曲信息快照：ssid -> Song。
     * 历史记录里存的是 ssid，网易云歌曲只在搜索结果里存在，
     * 换关键词或清空搜索后就查不到了 —— 存快照保证历史随时能显示。
     */
    var songSnapshots: Map<String, Song>
        get() = runCatching {
            json.decodeFromString<Map<String, Song>>(sp.getString("song_snapshots", "{}")!!)
        }.getOrDefault(emptyMap())
        set(v) {
            // 只保留最近 MAX_SNAPSHOTS 条，避免越积越大
            val trimmed = if (v.size > MAX_SNAPSHOTS) {
                v.entries.sortedByDescending { it.value.title }.take(MAX_SNAPSHOTS).associate { it.key to it.value }
            } else v
            sp.edit().putString("song_snapshots", json.encodeToString(trimmed)).apply()
        }

    // -------------------------------------------------------------------
    // 播放队列快照：服务被系统回收后重建时，靠它把队列和进度找回来
    // -------------------------------------------------------------------
    /**
     * 队列快照（JSON 数组，每项含 ssid / uri / 标题 / 歌手 / 封面）。
     *
     * 前台服务被系统杀掉后 media3 会重建，但新 player 的队列是空的 ——
     * 表现就是「后台放着放着停了，通知也没了」。存一份快照让重建时能复原。
     * 只存轻量元数据，不含音频数据；解析在 [PlaybackService] 里做。
     */
    var lastQueue: String
        get() = sp.getString("last_queue", "") ?: ""
        set(v) { sp.edit().putString("last_queue", v).apply() }

    /** 快照里当前播到第几首 */
    var lastQueueIndex: Int
        get() = sp.getInt("last_queue_index", 0)
        set(v) { sp.edit().putInt("last_queue_index", v).apply() }

    /** 快照里的播放位置（毫秒） */
    var lastQueuePos: Long
        get() = sp.getLong("last_queue_pos", 0L)
        set(v) { sp.edit().putLong("last_queue_pos", v).apply() }

    /** [FOCUS_NORMAL] / [FOCUS_CALL_ONLY] / [FOCUS_ALWAYS] */
    var playFocus: Int
        get() = sp.getInt("play_focus", FOCUS_NORMAL)
        set(v) { sp.edit().putInt("play_focus", v).apply() }

    /** 0 列表循环 1 随机 2 单曲循环 */
    var playMode: Int
        get() = sp.getInt("play_mode", 0)
        set(v) { sp.edit().putInt("play_mode", v).apply() }

    var quality: String
        get() = sp.getString("quality", "normal") ?: "normal"
        set(v) { sp.edit().putString("quality", v).apply() }

    /** null = 跟随封面 */
    var themeColor: Int?
        get() = if (sp.contains("theme_color")) sp.getInt("theme_color", 0) else null
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("theme_color") else e.putInt("theme_color", v)
            e.apply()
        }

    /** 是否接入网易云曲库，默认关闭 */
    var nmEnabled: Boolean
        get() = sp.getBoolean("nm_enabled", false)
        set(v) { sp.edit().putBoolean("nm_enabled", v).apply() }

    /** 网易云登录 cookie */
    var nmCookie: String
        get() = sp.getString("nm_cookie", "") ?: ""
        set(v) { sp.edit().putString("nm_cookie", v).apply() }

    /** 网易云账号昵称，空表示未登录 */
    /** 网易云音质档位：standard / higher / exhigh / lossless，空 = 跟随设置 */
    var nmLevel: String
        get() = sp.getString("nm_level", "")!!
        set(v) { sp.edit().putString("nm_level", v).apply() }

    /**
     * 简繁转换模式：
     * 0 转成简体（默认）/ 1 转成繁体 / 2 仅搜索统一 / 3 不转换
     */
    var zhConvert: Int
        get() = sp.getInt("zh_convert", ZhConvert.TO_SIMPLE)
        set(v) { sp.edit().putInt("zh_convert", v).apply() }

    /**
     * 全局字体：
     * 0 系统字体（默认）/ 1 思源宋体 / 2 思源黑体
     */
    var fontKind: Int
        get() = sp.getInt("font_kind", 0)
        set(v) { sp.edit().putInt("font_kind", v).apply() }

    /**
     * 可变字体的字重（wght 轴）。
     * 思源宋体 wght 250~900、思源黑体 100~900；设置页共用 250~900 滑杆，默认 450。
     */
    var fontWght: Int
        get() = sp.getInt("font_wght", 450).coerceIn(250, 900)
        set(v) { sp.edit().putInt("font_wght", v.coerceIn(250, 900)).apply() }

    // -------------------------------------------------------------------
    // 歌词排版：与全局字体分开，只作用于播放器歌词页
    // -------------------------------------------------------------------
    /** 歌词字号（sp）。默认 26 = 大字歌词登场时的既有值 */
    var lyricSize: Int
        get() = sp.getInt("lyric_size", LYRIC_SIZE_DEFAULT).coerceIn(LYRIC_SIZE_MIN, LYRIC_SIZE_MAX)
        set(v) { sp.edit().putInt("lyric_size", v.coerceIn(LYRIC_SIZE_MIN, LYRIC_SIZE_MAX)).apply() }

    /** 歌词字体：0 跟随全局 / 1 系统 / 2 思源宋体 / 3 思源黑体 */
    var lyricFont: Int
        get() = sp.getInt("lyric_font", LYRIC_FONT_FOLLOW).coerceIn(0, 3)
        set(v) { sp.edit().putInt("lyric_font", v.coerceIn(0, 3)).apply() }

    /** 歌词字重（wght）。默认 700，与录制/全局字重的 450 互不影响 */
    var lyricWght: Int
        get() = sp.getInt("lyric_wght", LYRIC_WGHT_DEFAULT).coerceIn(LYRIC_WGHT_MIN, LYRIC_WGHT_MAX)
        set(v) { sp.edit().putInt("lyric_wght", v.coerceIn(LYRIC_WGHT_MIN, LYRIC_WGHT_MAX)).apply() }

    var nmAccount: String
        get() = sp.getString("nm_account", "") ?: ""
        set(v) { sp.edit().putString("nm_account", v).apply() }

    /** 网易云 uid：同步「我的歌单」要用（/user/playlist 需要 uid） */
    var nmUid: Long
        get() = sp.getLong("nm_uid", 0L)
        set(v) { sp.edit().putLong("nm_uid", v).apply() }

    /** 播放历史（最近在前），同时记录本站与网易云，最多 [MAX_HISTORY] 条 */
    var history: List<String>
        get() = runCatching {
            json.decodeFromString<List<String>>(sp.getString("history", "[]")!!)
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("history", json.encodeToString(v.take(MAX_HISTORY))).apply() }

    /** 已下载歌曲索引（离线模式的数据来源） */
    var downloads: List<DownloadRecord>
        get() = runCatching {
            json.decodeFromString<List<DownloadRecord>>(sp.getString("downloads", "[]")!!)
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("downloads", json.encodeToString(v)).apply() }

    /**
     * 歌词翻译缓存：ssid -> 译文列表（按逻辑行顺序）。
     *
     * 翻过的歌下次打开直接读缓存，不再请求接口。
     * 上限 [MAX_LYRIC_TRANS] 首：整篇译文不小，无限堆积会撑大存储，
     * 超出时丢最旧的（写入顺序即时间顺序）。
     *
     * 存在缓存目录（[AppCache]）而不是 SharedPreferences：
     * 系统「清除缓存」能清干净，也不占用数据目录。
     * 首次读取时把旧 prefs 里的译文迁移过来，老用户已有的译文不会丢。
     */
    var lyricTrans: Map<String, List<String>>
        get() {
            AppCache.readTrans(app)?.let { return it }
            val old = runCatching {
                json.decodeFromString<Map<String, List<String>>>(sp.getString("lyric_trans", "{}")!!)
            }.getOrDefault(emptyMap())
            if (old.isNotEmpty()) {
                AppCache.writeTrans(app, old)
                sp.edit().remove("lyric_trans").apply()
            }
            return old
        }
        set(v) {
            val cut = if (v.size <= MAX_LYRIC_TRANS) v
            else v.entries.drop(v.size - MAX_LYRIC_TRANS).associate { it.key to it.value }
            AppCache.writeTrans(app, cut)
            // 已迁移，prefs 里那份不再维护，避免两边不一致
            if (sp.contains("lyric_trans")) sp.edit().remove("lyric_trans").apply()
        }

    /** 均衡器配置（预设 / 强度 / 自定义 10 段 / A/B / 设备校准） */
    var eqConfig: EqConfig
        get() = runCatching {
            json.decodeFromString<EqConfig>(sp.getString("eq_config", "")!!)
        }.getOrDefault(EQ_FLAT)
        set(v) { sp.edit().putString("eq_config", json.encodeToString(v)).apply() }

    /** 3D 环绕开关与强度（0~1），与均衡器各自独立持久化 */
    var surroundOn: Boolean
        get() = sp.getBoolean("surround_on", false)
        set(v) { sp.edit().putBoolean("surround_on", v).apply() }
    var surroundStrength: Float
        get() = sp.getFloat("surround_strength", 0.55f)
        set(v) { sp.edit().putFloat("surround_strength", v.coerceIn(0f, 1f)).apply() }

    /** 外观：0 亮色 / 1 暗色 / 2 自动（跟随系统）。默认暗色，保持升级前的观感 */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 1)
        set(v) { sp.edit().putInt("theme_mode", v.coerceIn(0, 2)).apply() }

    /** 「我的喜欢」本地集合（ssid）：与网易云「我喜欢的音乐」歌单合并后的结果 */
    var favorites: Set<String>
        get() = runCatching {
            json.decodeFromString<List<String>>(sp.getString("favorites", "[]")!!).toSet()
        }.getOrDefault(emptySet())
        set(v) { sp.edit().putString("favorites", json.encodeToString(v.toList())).apply() }

    /** 26.11.71：网易云「我喜欢的音乐」歌单 id，点星标 / 批量删除时回写云端 */
    var nmLikePid: Long
        get() = sp.getLong("nm_like_pid", 0L)
        set(v) { sp.edit().putLong("nm_like_pid", v).apply() }

    /**
     * 26.11.73：用户手动删掉的云端歌单 id。
     *
     * 云端删除 / 取消收藏偶发失败时，下次同步仍会返回这张歌单，
     * 若不屏蔽就会被重新下载回来，看起来像「删了又回来」。
     * 同步时按云端实际返回做清理：云端已消失的 id 自动移出黑名单，
     * 以后用户在网页端重新收藏还能正常同步。
     */
    var nmDeletedPids: Set<String>
        get() = runCatching {
            json.decodeFromString<List<String>>(sp.getString("nm_deleted_pids", "[]")!!).toSet()
        }.getOrDefault(emptySet())
        set(v) { sp.edit().putString("nm_deleted_pids", json.encodeToString(v.toList())).apply() }

    /** 合并指纹：云端歌单更新时间+曲目数 与 本地喜欢集合的组合，没变就跳过自动合并 */
    var nmLikeStamp: String
        get() = sp.getString("nm_like_stamp", "").orEmpty()
        set(v) { sp.edit().putString("nm_like_stamp", v).apply() }

    /** 搜索记录（最近在前），提交时才写，去重后最多 20 条 */
    var searchHistory: List<String>
        get() = runCatching {
            json.decodeFromString<List<String>>(sp.getString("search_history", "[]")!!)
        }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("search_history", json.encodeToString(v.distinct().take(20))).apply() }

    /** 26.11.59：最近搜索实体（歌曲 / 艺人 / 专辑 / 关键词），按 key 去重，最多 20 条 */
    var recentItems: List<RecentItem>
        get() = runCatching {
            json.decodeFromString<List<RecentItem>>(sp.getString("recent_items", "[]")!!)
        }.getOrDefault(emptyList())
        set(v) {
            sp.edit().putString(
                "recent_items",
                json.encodeToString(v.distinctBy { it.key }.take(20)),
            ).apply()
        }
}
