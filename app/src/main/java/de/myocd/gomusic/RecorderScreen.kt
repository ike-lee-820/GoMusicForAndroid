package de.myocd.gomusic

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/** 预览 View 与编码器共用的一份绘制参数 */
private data class RecDraw(val st: RecFrameState, val cfg: RecorderConfig, val tf: Typeface?)

/**
 * 录制歌曲视频：横竖屏共用的整页（不是弹层，避免误滑）。
 * 上半部是「所见即所得」预览，下半部是全部可选项，底部开始录制。
 */
@Composable
fun RecorderScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val go = LocalGo.current
    val song = vm.currentSong
    var cfg by remember { mutableStateOf(RecorderPrefs.load(ctx)) }
    var previewPos by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BackHandler { if (!busy) vm.showRecorder = false }

    fun persist(next: RecorderConfig) {
        cfg = next
        RecorderPrefs.save(ctx, next)
    }

    val cover by produceState<Bitmap?>(null, song?.coverUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val url = song?.coverUrl ?: return@runCatching null
                val c = OkHttpClient()
                val req = Request.Builder().url(url).build()
                c.newCall(req).execute().use { r ->
                    r.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }
    }

    val tf by remember(cfg.fontPath) {
        mutableStateOf(
            if (cfg.fontPath.isBlank()) null
            else runCatching { Typeface.createFromFile(cfg.fontPath) }.getOrNull()
        )
    }

    val rows: List<LyricRow> = remember(cfg.customLrc, vm.lyricRows) {
        if (cfg.customLrc.isBlank()) vm.lyricRows
        else runCatching { buildLyricRows(lyricLines(cfg.customLrc)) }.getOrDefault(emptyList())
    }
    val duration = if (vm.durationMs > 0) vm.durationMs else 180_000L

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "fonts")
            dir.mkdirs()
            val dst = File(dir, "rec_font.ttf")
            val ok = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dst, false).use { out -> input.copyTo(out) }
                }
                dst.length() > 1024
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (ok) {
                    persist(cfg.copy(fontPath = dst.absolutePath))
                    Toast.makeText(ctx, "字体已导入", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "字体导入失败，请选 ttf / otf", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startRecord() {
        if (busy) return
        val s = song
        if (s == null) {
            status = "当前没有正在播放的歌曲"
            return
        }
        busy = true
        status = "正在准备音频…"
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { prepareRecAudio(ctx, s, cfg) }
            if (prepared == null) {
                busy = false
                status = "没找到可用音频：请先下载这首歌，或检查网络与音质档位"
                return@launch
            }
            val job = RecJob(
                cfg = cfg,
                song = s,
                audioFile = prepared,
                rows = rows,
                cover = cover,
                typeface = tf,
                durationMs = duration,
            )
            RecorderJobHolder.pending = job
            runCatching {
                val i = android.content.Intent(ctx, RecorderService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            }
            status = "已在后台开始录制，可关闭此页；通知栏可查看进度 / 取消"
            Toast.makeText(ctx, "开始录制，可退到后台", Toast.LENGTH_SHORT).show()
            busy = false
        }
    }

    val (dw, dh) = RecorderPrefs.dims(cfg)
    val ratio = dw.toFloat() / dh.toFloat()

    Column(
        Modifier
            .fillMaxSize()
            .background(go.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("录制歌曲视频", 20, color = go.on)
            Spacer(Modifier.weight(1f))
            GoButton("关闭") { vm.showRecorder = false }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(androidx.compose.ui.graphics.Color.Black),
        ) {
            val draw = RecDraw(RecFrameState(song ?: Song(""), cover, rows, previewPos, duration), cfg, tf)
            AndroidView(
                factory = { c ->
                    object : View(c) {
                        override fun onDraw(cv: android.graphics.Canvas) {
                            (tag as? RecDraw)?.let { renderRecFrame(cv, it.st, it.cfg, it.tf) }
                        }
                    }
                },
                update = { v -> v.tag = draw; v.invalidate() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("预览位置 ${fmtShort(previewPos)}", 12, color = go.muted)
            Spacer(Modifier.weight(1f))
            GoButton("−5s") { previewPos = (previewPos - 5000L).coerceAtLeast(0L) }
            Spacer(Modifier.width(8.dp))
            GoButton("+5s") { previewPos = (previewPos + 5000L).coerceAtMost(duration) }
            Spacer(Modifier.width(8.dp))
            GoButton("回到当前") { previewPos = vm.positionMs }
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Txt(status, 12, color = go.primary)
        }
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            RecPick("比例", cfg.aspect, RecAspect.values().map { it.label }) { persist(cfg.copy(aspect = it)) }
            RecPick("分辨率", "${cfg.resolution}p", REC_RESOLUTIONS.map { "${it}p" }) {
                persist(cfg.copy(resolution = it.removeSuffix("p").toInt()))
            }
            RecPick("音质", cfg.quality, REC_QUALITIES) { persist(cfg.copy(quality = it)) }
            RecPick("帧率", "${cfg.fps}fps", listOf("24", "30", "60")) {
                persist(cfg.copy(fps = it.removeSuffix("fps").toInt()))
            }
            RecStep("歌词字号", "${cfg.lyricSizeSp} sp",
                { persist(cfg.copy(lyricSizeSp = (cfg.lyricSizeSp - 2).coerceIn(12, 48))) },
                { persist(cfg.copy(lyricSizeSp = (cfg.lyricSizeSp + 2).coerceIn(12, 48))) })
            RecStep("信息区大小", "${(cfg.infoRatio * 100).toInt()}%",
                { persist(cfg.copy(infoRatio = (cfg.infoRatio - 0.05f).coerceIn(0.18f, 0.60f))) },
                { persist(cfg.copy(infoRatio = (cfg.infoRatio + 0.05f).coerceIn(0.18f, 0.60f))) })
            RecStep("歌词快进/延迟", "${cfg.lyricOffsetMs} ms",
                { persist(cfg.copy(lyricOffsetMs = (cfg.lyricOffsetMs - 100L).coerceIn(-5000L, 5000L))) },
                { persist(cfg.copy(lyricOffsetMs = (cfg.lyricOffsetMs + 100L).coerceIn(-5000L, 5000L))) })
            RecPick("人声", cfg.vocalMode, listOf("原声", "减弱人声")) { persist(cfg.copy(vocalMode = it)) }
            if (cfg.vocalMode == "减弱人声") {
                RecStep("减弱强度", "${(cfg.vocalStrength * 100).toInt()}%（100%≈伴奏）",
                    { persist(cfg.copy(vocalStrength = (cfg.vocalStrength - 0.1f).coerceIn(0f, 1f))) },
                    { persist(cfg.copy(vocalStrength = (cfg.vocalStrength + 0.1f).coerceIn(0f, 1f))) })
            }
            RecPick("译文", if (cfg.showTrans) "开启" else "关闭", listOf("开启", "关闭")) {
                persist(cfg.copy(showTrans = it == "开启"))
            }
            RecPick("码率", "${cfg.bitrateMbps} Mbps", listOf("4", "8", "12", "16")) {
                persist(cfg.copy(bitrateMbps = it.removeSuffix(" Mbps").toInt()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("歌词字体", 13, color = go.on, modifier = Modifier.width(96.dp))
                GoButton("导入字体") { fontPicker.launch(arrayOf("*/*")) }
                Spacer(Modifier.width(8.dp))
                GoButton("清除") { persist(cfg.copy(fontPath = "")) }
            }
            Spacer(Modifier.height(8.dp))
            Txt("更改歌词（留空用原歌词）", 13, color = go.on)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = cfg.customLrc,
                onValueChange = { persist(cfg.copy(customLrc = it)) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = { Txt("粘贴 LRC 全文…", 12, color = go.muted) },
                minLines = 4,
                maxLines = 8,
            )
            Spacer(Modifier.height(6.dp))
            GoButton("重置为原歌词") { persist(cfg.copy(customLrc = "")) }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        GoButton(
            if (busy) "准备中…" else "开始录制（后台进行）",
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            loading = busy,
        ) { startRecord() }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RecPick(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 13, color = go.on, modifier = Modifier.width(96.dp))
        GoButton(value) {
            val i = options.indexOf(value)
            onChange(options[if (i < 0) 0 else (i + 1) % options.size])
        }
    }
}

@Composable
private fun RecStep(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 13, color = go.on, modifier = Modifier.width(96.dp))
        GoButton("−") { onMinus() }
        Spacer(Modifier.width(8.dp))
        Txt(value, 12, color = go.muted)
        Spacer(Modifier.width(8.dp))
        GoButton("+") { onPlus() }
    }
}

private fun fmtShort(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 优先用已下载的同档位音频；没有就按档位取地址下载到缓存，返回本地文件 */
private fun prepareRecAudio(ctx: Context, song: Song, cfg: RecorderConfig): File? {
    return runCatching {
        val recs = Prefs(ctx).downloads.filter { it.ssid == song.ssid && it.audio.isNotBlank() }
        val rec = recs.firstOrNull { it.quality == cfg.quality } ?: recs.firstOrNull()
        val uri = rec?.audio?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val out = File(ctx.cacheDir, "rec_audio_${song.ssid.filter { it.isLetterOrDigit() }}.bin")
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out, false).use { o -> input.copyTo(o) }
            } ?: return null
            if (out.length() > 1024) return out
        }
        val url = if (song.source == SRC_NETEASE) {
            Netease.audioWithLevel(song.nmId, cfg.quality)?.first
        } else {
            val d = Api.detail(song.ssid)
            d.audio[cfg.quality]?.firstOrNull()
                ?: d.audio.values.firstOrNull()?.firstOrNull()
        } ?: return null
        FileOutputStream(out, false).use { o -> Api.fetch(url, o) }
        out.takeIf { it.length() > 1024 }
    }.getOrNull()
}
