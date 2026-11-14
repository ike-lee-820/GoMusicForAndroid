package de.myocd.gomusic

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 * 录制歌曲视频：横竖屏共用的整页。
 * 上半部卡片式「所见即所得」预览，下半部分组设置，底部固定开始按钮。
 */
@Composable
fun RecorderScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val go = LocalGo.current
    val song = vm.currentSong
    var cfg by remember { mutableStateOf(RecorderPrefs.load(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var availableQualities by remember { mutableStateOf<List<String>>(emptyList()) }
    var qualityProbe by remember { mutableStateOf("正在识别可用音质…") }
    val scope = rememberCoroutineScope()

    // 进入即时录制页暂停播放；退出时仅当进来前正在播才恢复。
    // 不再读 vm.isPlaying 快照（异步回调可能滞后），且 pauseForRecorder 无条件发暂停。
    DisposableEffect(Unit) {
        val was = vm.pauseForRecorder()
        NmLog.note("录制页：进入，已暂停播放（进来前在播=$was）")
        onDispose {
            vm.resumeFromRecorder(was)
            NmLog.note("录制页：退出，恢复播放=$was")
        }
    }
    BackHandler { if (!busy) vm.showRecorder = false }

    fun persist(next: RecorderConfig) {
        cfg = next
        RecorderPrefs.save(ctx, next)
    }

    LaunchedEffect(Unit) { RecorderHistory.load(ctx) }
    LaunchedEffect(song?.ssid, song?.nmId) {
        val s = song
        if (s == null) {
            availableQualities = emptyList()
            qualityProbe = "没有可录制的歌曲"
            return@LaunchedEffect
        }
        qualityProbe = "正在识别可用音质…"
        val found = withContext(Dispatchers.IO) { RecorderFlow.availableQualities(ctx, s) }
        availableQualities = found
        if (found.isNotEmpty() && cfg.quality !in found) persist(cfg.copy(quality = found.last()))
        qualityProbe = if (found.isEmpty()) "未识别到可用音质，不能开始生成"
        else "识别完成：仅显示 ${found.size} 个可用音质"
        NmLog.note("录制页音质识别：${found.joinToString(",")}")
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

    val tf by remember(cfg.fontMode, cfg.fontPath, cfg.fontWeight) {
        mutableStateOf(RecorderFlow.recTypeface(ctx, cfg))
    }

    val rows: List<LyricRow> = remember(cfg.customLrc, vm.lyricRows) {
        if (cfg.customLrc.isBlank()) vm.lyricRows
        else runCatching { buildLyricRows(lyricLines(cfg.customLrc)) }.getOrDefault(emptyList())
    }
    val duration = if (vm.durationMs > 0) vm.durationMs else 180_000L
    // 预览只展示歌曲时长一半时的那一帧
    val previewPos = duration / 2L
    val runningRec = RecorderHistory.items.firstOrNull { it.state == "进行中" }

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
                    persist(cfg.copy(fontMode = "导入", fontPath = dst.absolutePath))
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
        if (availableQualities.isEmpty() || cfg.quality !in availableQualities) {
            status = qualityProbe
            return
        }
        if (RecorderHistory.items.any { it.state == "进行中" }) {
            status = "已有生成任务在后台进行，请先到「录制列表」取消或等它完成"
            return
        }
        busy = true
        status = "已提交后台生成：步骤 1 下载音频与歌词…"
        val pendingAudio = File(ctx.cacheDir, "rec_pending_${System.currentTimeMillis()}.bin")
        val job = RecJob(
            cfg = cfg,
            song = s,
            audioFile = pendingAudio,
            rows = rows,
            cover = cover,
            typeface = tf,
            durationMs = duration,
            actualQuality = cfg.quality,
        )
        RecorderJobHolder.pending = job
        // 服务被系统回收后靠这份快照重启续跑，而不是静默丢掉整个任务
        RecorderJobStore.save(ctx, job)
        runCatching {
            val i = android.content.Intent(ctx, RecorderService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }.onFailure { status = "无法启动后台生成：${it.message}" }
        Toast.makeText(ctx, "开始生成，可退到后台", Toast.LENGTH_SHORT).show()
        busy = false
    }


    val (dw, dh) = RecorderPrefs.dims(cfg)
    val ratio = dw.toFloat() / dh.toFloat()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(go.bg)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        // 预览区最多占屏高 34%、且不超过 240dp：9:16 / 横屏 16:9 都不能撑满整页
        val previewH = minOf(maxHeight * 0.34f, 240.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("录制歌曲视频", 21, weight = FontWeight.SemiBold, color = go.on)
                Txt("下载素材 → 音频处理 → 录制视频；音画从 0 对齐", 12, color = go.muted)
            }
            GoButton("录制列表") { vm.showRecHistory = true }
            Spacer(Modifier.width(8.dp))
            GoButton("关闭") { vm.showRecorder = false }
        }
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier.fillMaxWidth().height(previewH),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 按所选比例 fit：宽取 min(可用宽, 可用高×比例)，高由比例反算
                val pw = minOf(maxWidth, maxHeight * ratio)
                val ph = pw / ratio
                Box(
                    Modifier.width(pw).height(ph)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .border(1.dp, go.surfaceHi, RoundedCornerShape(20.dp)),
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
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("预览：仅显示时长一半 ${fmtShort(previewPos)} / ${fmtShort(duration)}", 12,
                Modifier.weight(1f), color = go.muted)
            Txt(qualityProbe, 11, color = go.muted)
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(go.primary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Txt(status, 12, color = go.primary) }
        }
        if (runningRec != null) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(go.surface).padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Txt("${runningRec.stage.ifBlank { "后台生成中" }} ${runningRec.progress}%", 12, color = go.primary)
                if (runningRec.detail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Txt(runningRec.detail, 11, color = go.muted, maxLines = 2)
                }
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier.fillMaxWidth().height(5.dp)
                        .clip(RoundedCornerShape(3.dp)).background(go.surfaceHi),
                ) {
                    Box(
                        Modifier.fillMaxWidth(runningRec.progress.coerceIn(0, 100) / 100f).height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(go.primary),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            RecGroup("画面") {
                RecChips("样式", cfg.style, RecStyle.values().map { it.label }) { persist(cfg.copy(style = it)) }
                RecChips("比例", cfg.aspect, RecAspect.values().map { it.label }) { persist(cfg.copy(aspect = it)) }
                RecChips("分辨率", "${cfg.resolution}p", REC_RESOLUTIONS.map { "${it}p" }) {
                    persist(cfg.copy(resolution = it.removeSuffix("p").toInt()))
                }
                RecChips("帧率", "${cfg.fps}fps", listOf("24fps", "30fps", "60fps")) {
                    persist(cfg.copy(fps = it.removeSuffix("fps").toInt()))
                }
                RecChips("码率", "${cfg.bitrateMbps} Mbps", listOf("4 Mbps", "8 Mbps", "12 Mbps", "16 Mbps")) {
                    persist(cfg.copy(bitrateMbps = it.removeSuffix(" Mbps").toInt()))
                }
                RecStep("信息区 / 唱片大小", "${(cfg.infoRatio * 100).toInt()}%",
                    { persist(cfg.copy(infoRatio = (cfg.infoRatio - 0.05f).coerceIn(0.18f, 0.60f))) },
                    { persist(cfg.copy(infoRatio = (cfg.infoRatio + 0.05f).coerceIn(0.18f, 0.60f))) })
                RecChips("背景", cfg.bgMode, REC_BG_MODES) { persist(cfg.copy(bgMode = it)) }
                Txt("背景只用一张专辑封面：原始尺寸居中、不缩放不拉伸，整层轻微模糊；雨季、雪天只叠加在背景之上", 11, color = go.muted)
                RecStep("信息字号", "${cfg.infoSizeSp} sp（$REC_INFO_MIN_SP–$REC_INFO_MAX_SP）",
                    { persist(cfg.copy(infoSizeSp = (cfg.infoSizeSp - 2).coerceIn(REC_INFO_MIN_SP, REC_INFO_MAX_SP))) },
                    { persist(cfg.copy(infoSizeSp = (cfg.infoSizeSp + 2).coerceIn(REC_INFO_MIN_SP, REC_INFO_MAX_SP))) })
            }
            RecGroup("歌词") {
                RecStep("歌词字号", "${cfg.lyricSizeSp} sp（$REC_LYRIC_MIN_SP–$REC_LYRIC_MAX_SP）",
                    { persist(cfg.copy(lyricSizeSp = (cfg.lyricSizeSp - 2).coerceIn(REC_LYRIC_MIN_SP, REC_LYRIC_MAX_SP))) },
                    { persist(cfg.copy(lyricSizeSp = (cfg.lyricSizeSp + 2).coerceIn(REC_LYRIC_MIN_SP, REC_LYRIC_MAX_SP))) })
                RecStep("全体快进 / 延迟", "${cfg.lyricOffsetMs} ms",
                    { persist(cfg.copy(lyricOffsetMs = (cfg.lyricOffsetMs - 100L).coerceIn(-5000L, 5000L))) },
                    { persist(cfg.copy(lyricOffsetMs = (cfg.lyricOffsetMs + 100L).coerceIn(-5000L, 5000L))) })
                RecChips("译文", if (cfg.showTrans) "开启" else "关闭", listOf("开启", "关闭")) {
                    persist(cfg.copy(showTrans = it == "开启"))
                }
                RecChips("字体来源", cfg.fontMode, REC_FONT_MODES) { mode ->
                    if (mode == "导入") {
                        persist(cfg.copy(fontMode = mode))
                        fontPicker.launch(arrayOf("*/*"))
                    } else {
                        persist(cfg.copy(fontMode = mode, fontPath = ""))
                    }
                }
                if (cfg.fontMode == "导入") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(if (cfg.fontPath.isBlank()) "未导入字体" else "已导入：${File(cfg.fontPath).name}",
                            11, Modifier.weight(1f), color = go.muted, maxLines = 1)
                        GoButton("重新选择") { fontPicker.launch(arrayOf("*/*")) }
                        Spacer(Modifier.width(6.dp))
                        GoButton("清除") { persist(cfg.copy(fontMode = "系统默认", fontPath = "")) }
                    }
                }
                if (cfg.fontMode != "系统默认") {
                    RecStep("可变字重", "${cfg.fontWeight}（$WGHT_MIN–$WGHT_MAX）",
                        { persist(cfg.copy(fontWeight = (cfg.fontWeight - 25).coerceIn(WGHT_MIN, WGHT_MAX))) },
                        { persist(cfg.copy(fontWeight = (cfg.fontWeight + 25).coerceIn(WGHT_MIN, WGHT_MAX))) })
                }
                Spacer(Modifier.height(8.dp))
                Txt("更改歌词（留空用原歌词）", 12, color = go.muted)
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = cfg.customLrc,
                    onValueChange = { persist(cfg.copy(customLrc = it)) },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    placeholder = { Txt("粘贴 LRC 全文…", 12, color = go.muted) },
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(6.dp))
                GoButton("重置为原歌词") { persist(cfg.copy(customLrc = "")) }
            }
            RecGroup("音频") {
                Txt(qualityProbe, 11, color = go.muted)
                if (availableQualities.isNotEmpty()) {
                    RecChips("音质（仅可用）", cfg.quality, availableQualities) { persist(cfg.copy(quality = it)) }
                } else {
                    Txt("未识别到可用音质，不能开始生成", 12, color = go.muted)
                }
                RecChips("人声", cfg.vocalMode, listOf("原声", "减弱人声")) { persist(cfg.copy(vocalMode = it)) }
                if (cfg.vocalMode == "减弱人声") {
                    RecStep("减弱强度", "${(cfg.vocalStrength * 100).toInt()}%（100%≈伴奏）",
                        { persist(cfg.copy(vocalStrength = (cfg.vocalStrength - 0.1f).coerceIn(0f, 1f))) },
                        { persist(cfg.copy(vocalStrength = (cfg.vocalStrength + 0.1f).coerceIn(0f, 1f))) })
                }
                Txt("「原声」把所选音质的音频文件直接封装进视频，不重编码；只有减弱人声才重编码。",
                    11, color = go.muted)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        GoButton(
            if (busy) "提交中…" else "开始生成（后台进行）",
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            loading = busy,
            enabled = !busy && availableQualities.isNotEmpty() && cfg.quality in availableQualities,
        ) { startRecord() }
    }
    }
}

@Composable
private fun RecGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val go = LocalGo.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp)).background(go.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Txt(title, 13, color = go.primary, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun RecChips(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    val go = LocalGo.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Txt(label, 12, color = go.muted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { o ->
                val sel = o == value
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (sel) go.primary else go.surfaceHi)
                        .clickable { onChange(o) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Txt(o, 12, color = if (sel) OnPrimary else go.on) }
            }
        }
    }
}

@Composable
private fun RecStep(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 12, Modifier.weight(1f), color = go.muted)
        GoButton("−") { onMinus() }
        Spacer(Modifier.width(8.dp))
        Txt(value, 12, color = go.on)
        Spacer(Modifier.width(8.dp))
        GoButton("+") { onPlus() }
    }
}

private fun fmtShort(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * 严格按录制界面所选音质准备文件：
 * 1) 本地已下载的同档位文件；2) 否则按该档位重新取地址下载。
 * 不降档、不回退到别的档位、不复用旧缓存文件 —— 成品音质与选项严格一致。
 */
private fun prepareRecAudio(ctx: Context, song: Song, cfg: RecorderConfig): Pair<File, String>? {
    return runCatching {
        val lv = nmLevelKey(cfg.quality)
        val safeSsid = song.ssid.filter { it.isLetterOrDigit() }.take(40)
        val out = File(ctx.cacheDir, "rec_audio_${safeSsid}_${lv}_${System.currentTimeMillis()}.bin")
        if (out.exists()) out.delete()

        val recs = Prefs(ctx).downloads.filter { it.ssid == song.ssid && it.audio.isNotBlank() }
        val local = recs.firstOrNull { nmLevelKey(it.quality) == lv }
        if (local != null) {
            val uri = runCatching { Uri.parse(local.audio) }.getOrNull()
            if (uri != null) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out, false).use { o -> input.copyTo(o) }
                }
                if (out.length() > 1024) return@runCatching (out to lv)
            }
        }

        val url = if (song.source == SRC_NETEASE && song.nmId > 0) {
            Netease.audioExactLevel(song.nmId, lv)
        } else {
            val d = Api.detail(song.ssid)
            d.audio[lv]?.firstOrNull() ?: d.audio[cfg.quality]?.firstOrNull()
        } ?: return@runCatching null

        FileOutputStream(out, false).use { o -> Api.fetch(url, o) }
        if (out.length() > 1024) (out to lv) else null
    }.getOrNull()
}
