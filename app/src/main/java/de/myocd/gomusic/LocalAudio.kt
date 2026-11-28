package de.myocd.gomusic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * 设备本地音频文件来源。与还原字符导入 / 已下载的本地歌单区分：
 * 这类歌的歌名、歌手、专辑、封面、歌词全部来自文件本身，不查任何服务端。
 */
const val SRC_DEVICE = "device"

/**
 * 系统「打开方式 / 分享」送进来的本地音频。
 *
 * 只用 MediaMetadataRetriever 读容器里的 Meta 与内嵌封面，
 * 不引第三方解析库、不做联网匹配。同名 .lrc 与内嵌歌词按 UTF-8 / GBK 兜底读取后
 * 落到应用私有目录，避免每次切歌都重新解码文件。
 */
object LocalAudio {
    const val SSID_PREFIX = "dev:"

    private val AUDIO_EXT = setOf(
        "mp3", "flac", "ape", "wav", "m4a", "aac", "ogg", "oga", "opus",
        "wma", "aiff", "aif", "dsf", "dff", "mp4", "m4b", "amr", "mid", "midi",
    )
    private val LRC_TAG = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]""")

    fun isDevice(id: String?): Boolean = id?.startsWith(SSID_PREFIX) == true

    /** 同一个 Uri 稳定映射到同一个 ssid，历史与快照才能对得上 */
    private fun ssidOf(uri: Uri): String {
        val md = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
        return SSID_PREFIX + md.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isAudioName(u: Uri): Boolean {
        val n = u.lastPathSegment ?: return false
        val d = n.lastIndexOf('.')
        return d in 0 until n.lastIndex && n.substring(d + 1).lowercase() in AUDIO_EXT
    }

    /** 从系统 Intent 里取出所有音频 Uri（单曲 / 多曲 / 打开方式） */
    fun urisFrom(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { out.add(it) }
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        val audioMime = intent.type?.startsWith("audio/", ignoreCase = true) == true
        return out.filter { u ->
            audioMime || isAudioName(u) ||
                // ACTION_VIEW 的 content:// 常常不带 MIME 也没有扩展名，交给解码器判断
                (intent.action == Intent.ACTION_VIEW && intent.type == null &&
                    (u.scheme == "content" || u.scheme == "file"))
        }
    }

    /** 尽量长期持有 content:// 的读权限，历史里以后再点也能播 */
    fun takeReadable(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        val q = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull()
        return q ?: runCatching { File(uri.path ?: "").name }.getOrNull()
    }

    private fun sidecarLrc(uri: Uri): String? {
        val path = uri.path ?: return null
        val f = File(path)
        val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc")
        if (!lrc.isFile) return null
        val b = lrc.readBytes()
        val u = String(b, Charsets.UTF_8)
        return if ('\uFFFD' in u) runCatching { String(b, Charset.forName("GBK")) }.getOrDefault(u) else u
    }

    private fun lyricFile(ctx: Context, id: String): File =
        File(File(ctx.filesDir, "devlyric"), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".lrc")

    private fun cacheLyric(ctx: Context, id: String, text: String) {
        runCatching {
            val f = lyricFile(ctx, id)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    }

    /** 本地音乐歌词：优先随文件读到的，其次下载目录里的兜底由调用方处理 */
    fun lyricOf(ctx: Context, id: String): String =
        runCatching { lyricFile(ctx, id).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

    /** 26.11.35：缓存为空时按 Uri 重新解一次标签，点「暂无歌词」重试才有效 */
    fun loadLyric(ctx: Context, id: String, audioUri: String?): String {
        val cached = lyricOf(ctx, id)
        if (cached.isNotBlank()) return cached
        val u = audioUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return ""
        val t = TagLyrics.best(ctx, u, 0L)?.text.orEmpty()
        if (t.isNotBlank()) {
            cacheLyric(ctx, id, t)
            NmLog.note("本地歌词重试：重新解析标签得到 ${t.length} 字")
        }
        return t
    }

    fun clearLyric(ctx: Context, id: String) {
        runCatching { lyricFile(ctx, id).takeIf { it.isFile }?.delete() }
    }

    /** 读 Meta 生成 Song；无法解码时返回 null */
    fun songOf(ctx: Context, uri: Uri): Song? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
        } catch (e: Exception) {
            r.release()
            return null
        }
        val id = ssidOf(uri)
        val title: String
        val artist: String
        val album: String
        var cover: String? = null
        try {
            val disp = displayName(ctx, uri)
            val base = disp?.substringBeforeLast('.', disp) ?: "本地音乐"
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: base.substringAfter(" - ", base)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                ?: base.substringBefore(" - ", "").takeIf { it.isNotBlank() }
                ?: "未知歌手"
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "本地音乐"

            val pic = r.getEmbeddedPicture()
            if (pic != null) {
                val bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size)
                if (bmp != null) {
                    val dir = File(ctx.cacheDir, "devcover")
                    dir.mkdirs()
                    val f = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    cover = Uri.fromFile(f).toString()
                }
            }

            // 26.11.35：MediaMetadataRetriever 根本没有歌词 key，
            // 旧版从 WRITER/AUTHOR/GENRE/COMPOSER 里找时间戳几乎永远落空。
            // 改为直接解容器标签：ID3v2 USLT/SYLT、APEv2、FLAC/Ogg
            // Vorbis Comment、MP4 ©lyr；纯文本自动补时间轴，交给现有 parseLrc。
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val tagged = TagLyrics.best(ctx, uri, durationMs)
            val sidecar = sidecarLrc(uri)
            val lrc = sidecar ?: tagged?.text
            if (!lrc.isNullOrBlank()) {
                cacheLyric(ctx, id, lrc)
                NmLog.note(
                    "本地歌词：来源=${if (sidecar != null) "同名 .lrc" else (tagged?.source ?: "内嵌")}" +
                        " 长度=${lrc.length} 行数=${lrc.lines().size}" +
                        " 时间戳=${LRC_TAG.containsMatchIn(lrc)}"
                )
            } else {
                NmLog.note("本地歌词：未读到内嵌标签，也没有同名 .lrc（已查 ID3v2/APEv2/FLAC/Ogg/MP4）")
            }
        } finally {
            r.release()
        }
        return Song(
            ssid = id,
            title = title,
            artist = artist,
            album = album,
            coverUrl = cover,
            source = SRC_DEVICE,
            audioUri = uri.toString(),
        )
    }
}
