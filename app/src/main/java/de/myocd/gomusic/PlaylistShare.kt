package de.myocd.gomusic

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 歌单导入 / 导出。
 *
 * 还原字符格式：`GM1:<base64(deflate(json))>`
 * - 导出：生成还原字符，复制后发给别人
 * - 导入：粘贴还原字符
 *
 * 带版本号前缀，方便后续换格式时区分。
 */
object PlaylistShare {
    private const val PREFIX = "GM1:"
    private val json = Json { ignoreUnknownKeys = true }

    /** 导出用的歌单快照：只带歌名歌手专辑封面，不依赖本地曲库 */
    @Serializable
    data class Snapshot(
        val name: String,
        val songs: List<Item> = emptyList(),
    )

    @Serializable
    data class Item(
        val ssid: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val cover: String? = null,
        val source: String = SRC_LOCAL,
    )

    /** 把歌单编码成还原字符 */
    fun encode(p: Playlist, songs: List<Song>): String {
        val snap = Snapshot(
            name = p.name,
            songs = songs.map {
                Item(it.ssid, it.title, it.artist, it.album, it.coverUrl, it.source)
            },
        )
        val raw = json.encodeToString(snap).toByteArray(Charsets.UTF_8)
        val zipped = deflate(raw)
        return PREFIX + Base64.encodeToString(zipped, Base64.NO_WRAP)
    }

    /** 解析还原字符；格式不对返回 null */
    fun decode(text: String): Snapshot? {
        val t = text.trim().removePrefix(PREFIX).trim()
        if (t.isBlank()) return null
        return runCatching {
            val zipped = Base64.decode(t, Base64.DEFAULT)
            val raw = inflate(zipped)
            json.decodeFromString<Snapshot>(raw.toString(Charsets.UTF_8))
        }.onFailure {
            NmLog.note("歌单还原字符解析失败：${it.message}")
        }.getOrNull()
    }

    private fun deflate(src: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(src)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(src: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(src)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        i.end()
        return out.toByteArray()
    }
}
