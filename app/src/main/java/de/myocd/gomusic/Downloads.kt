package de.myocd.gomusic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import java.io.File
import java.io.OutputStream

/** 一条下载记录：音频 + 封面 + 歌词 + 信息文件的位置 */
@Serializable
data class DownloadRecord(
    val ssid: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val quality: String = "",
    /** 音频：content:// 或 file:// */
    val audio: String = "",
    val cover: String = "",
    val lyric: String = "",
    val meta: String = "",
    val time: Long = 0L,
)

object LocalLib {
    /** 公共下载目录下的子目录名 */
    const val ROOT = "GoMusic"
    const val AUDIO_DIR = "音乐"
    const val COVER_DIR = "封面"
    const val LYRIC_DIR = "歌词"
    const val META_DIR = "歌曲信息"

    fun dirText(): String = "Download/$ROOT/"

    private fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifBlank { "unknown" }

    /** 打开公共 Download/GoMusic/... 下的输出目标，返回 (MediaStore Uri, 旧版本 File) */
    private fun openOut(ctx: Context, sub: String, name: String, mime: String): Pair<Uri?, File?> {
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            u to null
        } else {
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$ROOT/$sub",
            )
            if (!d.exists()) d.mkdirs()
            null to File(d, name)
        }
    }

    private fun stream(ctx: Context, u: Uri?, f: File?): OutputStream? =
        runCatching { u?.let { ctx.contentResolver.openOutputStream(it) } ?: f?.outputStream() }.getOrNull()

    private fun finish(ctx: Context, u: Uri?) {
        if (u == null || Build.VERSION.SDK_INT < 29) return
        runCatching {
            val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(u, v, null, null)
        }
    }

    private fun uriText(u: Uri?, f: File?): String =
        u?.toString() ?: f?.let { Uri.fromFile(it).toString() } ?: ""

    private fun write(ctx: Context, sub: String, name: String, mime: String, data: ByteArray): String {
        val (u, f) = openOut(ctx, sub, name, mime)
        val s = stream(ctx, u, f) ?: return ""
        runCatching { s.use { it.write(data) } }
        finish(ctx, u)
        return uriText(u, f)
    }

    /** 下载一首歌：音频 + 封面 + 歌词 + 歌曲信息（meta.json），返回记录 */
    fun download(
        ctx: Context,
        song: Song,
        quality: String,
        onProgress: ((readBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): DownloadRecord {
        // 网易云歌曲：地址与歌词都走网易云接口，其余（封面、信息文件）流程与本站一致
        val nmId = neteaseIdOf(song.ssid)
        val isNm = nmId != null
        val d = if (isNm) {
            SongDetail(song.ssid, runCatching { Netease.lyric(nmId!!) }.getOrDefault(""), song.coverUrl, emptyMap())
        } else try {
            Api.detail(song.ssid)
        } catch (e: Exception) {
            // 离线也能补信息：用列表里已有的封面
            SongDetail(song.ssid, "", Api.abs(song.coverUrl), emptyMap())
        }
        val url = if (isNm) {
            Netease.audioUrl(nmId!!, quality) ?: throw java.io.IOException("网易云没有可用音源")
        } else if (d.audio.isEmpty()) {
            throw java.io.IOException("没有可用音频")
        } else {
            val key = d.audio.keys.minByOrNull { kotlin.math.abs(Api.rank(it) - Api.rank(quality)) }!!
            d.audio[key]!!.first()
        }
        val base = safe("${song.artist} - ${song.title}")
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3")
            .takeIf { it.length in 2..4 } ?: "mp3"

        // 1. 音频
        val (au, af) = openOut(ctx, AUDIO_DIR, "$base.$ext", "audio/$ext")
        val os = stream(ctx, au, af) ?: throw java.io.IOException("无法写入下载目录")
        os.use { Api.fetch(url, it, onProgress) }
        finish(ctx, au)
        val audioRef = uriText(au, af)

        // 2. 封面
        var coverRef = ""
        val coverUrl = d.cover ?: Api.abs(song.coverUrl)
        if (!coverUrl.isNullOrBlank()) {
            Api.bytes(coverUrl)?.let {
                coverRef = write(ctx, COVER_DIR, "$base.jpg", "image/jpeg", it)
            }
        }

        // 3. 歌词
        var lrc = d.lyrics
        if (lrc.isBlank() && !isNm) lrc = runCatching { Api.lyrics(song.ssid) }.getOrDefault("")
        var lyricRef = ""
        if (lrc.isNotBlank()) {
            lyricRef = write(ctx, LYRIC_DIR, "$base.lrc", "application/octet-stream", lrc.toByteArray())
        }

        // 4. 歌曲信息
        val rec = DownloadRecord(
            ssid = song.ssid,
            title = song.title,
            artist = song.artist,
            album = song.album,
            quality = quality,
            audio = audioRef,
            cover = coverRef,
            lyric = lyricRef,
            meta = "",
            time = System.currentTimeMillis(),
        )
        val metaText = buildString {
            appendLine("标题：${song.title}")
            appendLine("歌手：${song.artist}")
            appendLine("专辑：${song.album}")
            appendLine("ssid：${song.ssid}")
            appendLine("音质：$quality")
            appendLine("音频地址：$url")
            appendLine("封面：${song.coverUrl ?: ""}")
            appendLine("歌词：${if (lyricRef.isEmpty()) "无" else "$base.lrc"}")
            appendLine("下载时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(rec.time))}")
            appendLine()
            appendLine(lrc)
        }
        val metaRef = write(ctx, META_DIR, "$base.txt", "text/plain", metaText.toByteArray())
        return rec.copy(meta = metaRef)
    }

    /** 已下载的歌曲转换成列表用的 Song（封面指向本地文件） */
    fun songs(recs: List<DownloadRecord>): List<Song> =
        recs.map {
            Song(
                ssid = it.ssid,
                title = it.title,
                artist = it.artist,
                album = it.album,
                coverUrl = it.cover.ifBlank { null },
            )
        }

    fun audioUri(ctx: Context, ssid: String): Uri? =
        Prefs(ctx).downloads.firstOrNull { it.ssid == ssid }
            ?.audio?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 读取本地歌词 / 信息文本 */
    fun readText(ctx: Context, ref: String): String {
        if (ref.isBlank()) return ""
        return runCatching {
            val u = Uri.parse(ref)
            val bytes = if (u.scheme == "content" || u.scheme == "file") {
                ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
            } else {
                u.path?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
            }
            bytes?.decodeToString() ?: ""
        }.getOrDefault("")
    }

    fun delete(ctx: Context, rec: DownloadRecord) {
        listOf(rec.audio, rec.cover, rec.lyric, rec.meta).forEach { ref ->
            if (ref.isBlank()) return@forEach
            runCatching {
                val u = Uri.parse(ref)
                if (u.scheme == "content") ctx.contentResolver.delete(u, null, null)
                else u.path?.let { File(it).delete() }
            }
        }
    }
}
