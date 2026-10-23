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

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<String> = emptyList(),
    val cover: String? = null,
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
    var nmAccount: String
        get() = sp.getString("nm_account", "") ?: ""
        set(v) { sp.edit().putString("nm_account", v).apply() }

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
