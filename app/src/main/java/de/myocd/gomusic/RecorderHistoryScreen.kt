package de.myocd.gomusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置 → 录制列表：全部历史 + 进行中进度，可取消 / 打开 / 删除 / 清空。 */
@Composable
fun RecorderHistoryScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val go = LocalGo.current
    LaunchedEffect(Unit) { RecorderHistory.load(ctx) }
    // 进行中置顶，其余按开始时间倒序
    val list = RecorderHistory.items.sortedWith(
        compareBy<RecRecord> { it.state != "进行中" }.thenByDescending { it.startedAt }
    )

    Column(
        Modifier.fillMaxSize().background(go.bg)
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("录制列表", 21, weight = FontWeight.SemiBold, color = go.on)
                Txt("历史与进行中进度", 12, color = go.muted)
            }
            if (RecorderHistory.items.isNotEmpty()) {
                GoButton("清空") { RecorderHistory.clear(ctx) }
                Spacer(Modifier.width(8.dp))
            }
            GoButton("关闭") { vm.showRecHistory = false }
        }
        Spacer(Modifier.height(14.dp))

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("还没有录制记录", 15, color = go.muted)
                    Spacer(Modifier.height(6.dp))
                    Txt("在播放器控制栏点「录制视频」开始", 12, color = go.muted)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { r -> RecHistoryCard(ctx, r) }
            }
        }
    }
}

@Composable
private fun RecHistoryCard(ctx: Context, r: RecRecord) {
    val go = LocalGo.current
    val running = r.state == "进行中"
    val stateColor = when (r.state) {
        "已完成" -> go.primary
        "进行中" -> go.on
        "失败" -> androidx.compose.ui.graphics.Color(0xFFFF8A80)
        else -> go.muted
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(go.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(r.title.ifBlank { "未命名歌曲" }, 15, Modifier.weight(1f),
                weight = FontWeight.Medium, color = go.on, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Txt(r.state, 12, color = stateColor)
        }
        if (r.artist.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Txt(r.artist, 12, color = go.muted, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Txt(
            "${r.style} · ${r.quality} · ${r.resolution}p · ${r.aspect} · ${r.fps}fps · ${fmtRecDate(r.startedAt)}",
            11, color = go.muted,
        )

        if (running) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(go.surfaceHi),
            ) {
                Box(
                    Modifier.fillMaxWidth((r.progress.coerceIn(0, 100)) / 100f).height(5.dp)
                        .clip(RoundedCornerShape(3.dp)).background(go.primary),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("后台录制中 ${r.progress}%", 12, Modifier.weight(1f), color = go.on)
                GoButton("取消") {
                    runCatching {
                        ctx.startService(
                            Intent(ctx, RecorderService::class.java)
                                .setAction(RecorderService.ACTION_CANCEL)
                        )
                    }
                }
            }
        } else {
            if (r.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Txt(r.note, 11, color = go.muted, maxLines = 2)
            }
            if (r.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Txt(r.output, 11, color = go.muted, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (r.state == "已完成" && r.output.isNotBlank()) {
                    GoButton("打开") { openRecVideo(ctx, r.output) }
                    Spacer(Modifier.width(8.dp))
                }
                GoButton("删除") { RecorderHistory.remove(ctx, r.id) }
            }
        }
    }
}

private fun openRecVideo(ctx: Context, output: String) {
    runCatching {
        val uri = if (output.startsWith("content://") || output.startsWith("file://"))
            Uri.parse(output) else Uri.parse("file://$output")
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

private fun fmtRecDate(ms: Long): String {
    if (ms <= 0L) return ""
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
}
