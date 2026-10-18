package de.myocd.gomusic

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
}
