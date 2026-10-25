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
)

/** 对应网页端 localStorage：play_counts / user_playlists / play_mode / quality / theme_color */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("gomusic", Context.MODE_PRIVATE)
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
     * 0 系统字体（默认）/ 1 思源宋体
     */
    var fontKind: Int
        get() = sp.getInt("font_kind", 0)
        set(v) { sp.edit().putInt("font_kind", v).apply() }

    /**
     * 可变字体的字重（wght 轴）。
     * 思源宋体的 wght 轴范围是 250~900，这里默认 450。
     */
    var fontWght: Int
        get() = sp.getInt("font_wght", 450).coerceIn(250, 900)
        set(v) { sp.edit().putInt("font_wght", v.coerceIn(250, 900)).apply() }

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
}
