package de.myocd.gomusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 录制任务的最小可恢复快照。
 *
 * RecorderJobHolder.pending 是内存单例，服务被系统回收后用 START_STICKY 重启时
 * intent 为 null、pending 也没了，旧版直接 stopSelf，任务就「无法继续」。
 * 这里只存可序列化的配置与歌曲标识；音频、歌词、封面在恢复后重新准备，
 * 不把 Bitmap / Typeface 这类大对象写进文件。
 */
@Serializable
data class RecJobSpec(
    val cfg: RecorderConfig,
    val ssid: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val repoName: String?,
    val owner: String?,
    val source: String,
    val nmId: Long,
    val nmAlbumId: Long,
    val nmArtistId: Long,
    val mvId: Long,
    val durationMs: Long,
    val actualQuality: String,
    val coverPath: String = "",
)

object RecorderJobStore {
    private const val NAME = "rec_resume.json"
    private val json = Api.json

    fun save(ctx: Context, job: RecJob) {
        runCatching {
            val s = job.song
            val bmp = job.cover
            val coverPath = if (bmp != null) {
                val f = File(ctx.filesDir, "rec_cover.png")
                f.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
                f.absolutePath
            } else ""
            val spec = RecJobSpec(
                cfg = job.cfg,
                ssid = s.ssid, title = s.title, artist = s.artist, album = s.album,
                coverUrl = s.coverUrl, repoName = s.repoName, owner = s.owner,
                source = s.source, nmId = s.nmId, nmAlbumId = s.nmAlbumId,
                nmArtistId = s.nmArtistId, mvId = s.mvId,
                durationMs = job.durationMs, actualQuality = job.actualQuality,
                coverPath = coverPath,
            )
            File(ctx.filesDir, NAME).writeText(json.encodeToString(spec))
        }
    }

    fun restore(ctx: Context): RecJob? = runCatching {
        val f = File(ctx.filesDir, NAME)
        if (f.exists()) {
            val spec = json.decodeFromString<RecJobSpec>(f.readText())
            val song = Song(
                ssid = spec.ssid, title = spec.title, artist = spec.artist,
                album = spec.album, coverUrl = spec.coverUrl,
                repoName = spec.repoName, owner = spec.owner, source = spec.source,
                nmId = spec.nmId, nmAlbumId = spec.nmAlbumId,
                nmArtistId = spec.nmArtistId, mvId = spec.mvId,
            )
            val cover = if (spec.coverPath.isNotBlank())
                runCatching { BitmapFactory.decodeFile(spec.coverPath) }.getOrNull() else null
            val tf = runCatching { RecorderFlow.recTypeface(ctx, spec.cfg) }
                .getOrDefault(Typeface.DEFAULT)
            RecJob(
                cfg = spec.cfg,
                song = song,
                audioFile = File(ctx.cacheDir, "rec_resume_${System.currentTimeMillis()}.bin"),
                rows = emptyList(),
                cover = cover,
                typeface = tf,
                durationMs = spec.durationMs,
                actualQuality = spec.actualQuality,
            )
        } else null
    }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, NAME).delete() }
        runCatching { File(ctx.filesDir, "rec_cover.png").delete() }
    }
}
