package de.myocd.gomusic

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/** 前台服务 / 录制页 / 录制列表共用的详细进度 */
data class RecProgress(
    val percent: Int,
    val stage: String,
    val detail: String,
)

/** 步骤 1 下载完成后交给编码器的素材 */
data class RecInputs(
    val audioFile: File,
    val rows: List<LyricRow>,
    val quality: String,
)

/**
 * 26.11.22 录制流水线：
 * 识别可用音质 → 下载严格同档位音频与歌词 → 引擎做可选人声处理 → 录制视频。
 */
object RecorderFlow {

    /** 只返回确认可用的档位；探不出来 / 服务端降档的档位一律不显示 */
    fun availableQualities(ctx: Context, song: Song): List<String> {
        val remote = if (song.source == SRC_NETEASE && song.nmId > 0) {
            runCatching { Netease.availableLevels(song.nmId).ok }.getOrDefault(emptyList())
        } else emptyList()
        val detail = if (remote.isEmpty()) {
            runCatching {
                Api.detail(song.ssid).audio.keys.map { nmLevelKey(nmLevelOfQuality(it)) }
            }.getOrDefault(emptyList())
        } else emptyList()
        val local = runCatching {
            Prefs(ctx).downloads
                .filter { it.ssid == song.ssid && it.audio.isNotBlank() }
                .map { nmLevelKey(it.quality) }
        }.getOrDefault(emptyList())
        val set = (remote + detail + local)
            .map { nmLevelKey(it) }
            .filter { it in REC_QUALITIES }
            .toSet()
        val list = REC_QUALITIES.filter { it in set }
        NmLog.note("录制可用音质识别 id=${song.nmId} ssid=${song.ssid} -> ${list.joinToString(",")}")
        return list
    }

    /** 三段式生成的第 1 段：下载对应音频 + 歌词 */
    fun prepareInputs(
        ctx: Context,
        cfg: RecorderConfig,
        song: Song,
        onProgress: (RecProgress) -> Unit,
    ): RecInputs {
        val lv = nmLevelKey(cfg.quality)
        if (lv !in REC_QUALITIES) error("音质 $lv 不在可选列表")
        onProgress(RecProgress(2, "步骤 1/3 · 下载素材", "确认所选音质：$lv"))
        val audio = downloadExactAudio(ctx, song, lv, onProgress)
        onProgress(RecProgress(24, "步骤 1/3 · 下载素材", "请求歌词 /lyric?id=${song.nmId}"))
        val rows = downloadLyricRows(ctx, cfg, song)
        onProgress(RecProgress(30, "步骤 1/3 · 下载素材", "音频与歌词下载完成，准备音频处理"))
        return RecInputs(audio, rows, lv)
    }

    /** 严格同档位：本地已下载文件优先，否则只请求这一档，不降档、不解灰、不换文件 */
    private fun downloadExactAudio(
        ctx: Context,
        song: Song,
        lv: String,
        onProgress: (RecProgress) -> Unit,
    ): File {
        val safeSsid = song.ssid.filter { it.isLetterOrDigit() }.take(40)
        val out = File(ctx.cacheDir, "rec_audio_${safeSsid}_${lv}_${System.currentTimeMillis()}.bin")
        if (out.exists()) out.delete()

        val local = runCatching {
            Prefs(ctx).downloads.firstOrNull {
                it.ssid == song.ssid && it.audio.isNotBlank() && nmLevelKey(it.quality) == lv
            }
        }.getOrNull()
        if (local != null) {
            val uri = runCatching { Uri.parse(local.audio) }.getOrNull()
            if (uri != null) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out, false).use { o -> input.copyTo(o) }
                }
                if (out.length() > 1024) {
                    onProgress(RecProgress(22, "步骤 1/3 · 下载素材", "复用本地 $lv 音频：${out.length() / 1024L}KB"))
                    return out
                }
            }
        }

        val url = if (song.source == SRC_NETEASE && song.nmId > 0) {
            Netease.audioExactLevel(song.nmId, lv)
                ?: error("音质 $lv 不可用：服务端降档或无地址")
        } else {
            val d = Api.detail(song.ssid)
            d.audio[lv]?.firstOrNull() ?: error("音质 $lv 在详情里不可用")
        }
        FileOutputStream(out, false).use { o ->
            Api.fetch(url, o) { read, total ->
                val ratio = if (total > 0) (read * 18L / total).toInt().coerceIn(0, 18) else 0
                val kb = read / 1024L
                val totalKb = if (total > 0) "/${total / 1024L}KB" else ""
                onProgress(RecProgress(2 + ratio, "步骤 1/3 · 下载素材", "音频 $lv：${kb}KB$totalKb"))
            }
        }
        if (out.length() <= 1024) error("下载到的音频文件为空")
        onProgress(RecProgress(22, "步骤 1/3 · 下载素材", "音频 $lv 已下载：${out.length() / 1024L}KB"))
        return out
    }

    /** 下载 /lyric 原始 LRC 与 tlyric；自定义 LRC 仍可覆盖原文 */
    private fun downloadLyricRows(ctx: Context, cfg: RecorderConfig, song: Song): List<LyricRow> {
        val id = if (song.source == SRC_NETEASE) song.nmId else 0L
        val rawBytes = if (id > 0) Api.bytes("$NM_BASE/lyric?id=$id") else null
        val raw = rawBytes?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val root = if (raw.isBlank()) null else runCatching {
            Api.json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull()
        var lrc = lyricField(root, "lrc")
        val tLrc = lyricField(root, "tlyric")
        if (lrc.isBlank() && id > 0) lrc = Netease.lyric(id)
        if (lrc.isBlank()) lrc = runCatching { Api.detail(song.ssid).lyrics }.getOrDefault("")
        val finalLrc = cfg.customLrc.takeIf { it.isNotBlank() } ?: lrc
        runCatching { File(ctx.cacheDir, "rec_lyric_${id}_${System.currentTimeMillis()}.lrc").writeText(finalLrc) }
        runCatching { File(ctx.cacheDir, "rec_tlyric_${id}_${System.currentTimeMillis()}.lrc").writeText(tLrc) }
        val base = runCatching { buildLyricRows(lyricLines(finalLrc)) }.getOrDefault(emptyList())
        val transMap = runCatching {
            lyricLines(tLrc).associate { it.timeMs to it.text }
        }.getOrDefault(emptyMap<Long, String>())
        return base.map { r ->
            if (r.trans != null) r
            else r.copy(trans = transMap[r.timeMs]?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    private fun lyricField(root: JsonObject?, name: String): String {
        val obj = root?.get(name) as? JsonObject ?: return ""
        val p = obj["lyric"] as? JsonPrimitive ?: return ""
        return if (p.isString) p.content else ""
    }

    /** 录制 Canvas 用的 Typeface；字重轴由 RecorderRenderer 统一写到 Paint 上 */
    fun recTypeface(ctx: Context, c: RecorderConfig): Typeface = when (c.fontMode) {
        "思源宋体" -> runCatching { ctx.resources.getFont(R.font.song) }.getOrDefault(Typeface.DEFAULT)
        "导入" -> if (c.fontPath.isNotBlank())
            runCatching { Typeface.createFromFile(c.fontPath) }.getOrDefault(Typeface.DEFAULT)
        else Typeface.DEFAULT
        else -> Typeface.DEFAULT
    }
}
