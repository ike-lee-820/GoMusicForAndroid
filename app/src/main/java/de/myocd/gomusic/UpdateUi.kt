package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏更新弹窗。
 *
 * 检测到新版本时盖住整个界面，给三个选择：
 * - 应用内更新（推荐）：下载 APK 后走系统安装，需要「安装未知应用」权限
 * - 应用外更新：交给浏览器 / 系统下载器
 * - 取消：本次先不更新
 */
@Composable
fun UpdateScreen(vm: MainViewModel) {
    val go = LocalGo.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val info = vm.updateInfo ?: return
    val prog = vm.updateProgress

    // 完整文档双保险：release body 已是全文，再后台拉一次仓库归档 md。
    // 两条都失败时至少还能显示 body 全文，不会再被 600 字截断。
    var fullNotes by remember(info.latest) { mutableStateOf<String?>(null) }
    var notesLoading by remember(info.latest) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(info.latest) {
        notesLoading = true
        val t = withContext(Dispatchers.IO) {
            runCatching { Updater.fetchNotes(info) }.getOrNull()
        }
        if (!t.isNullOrBlank()) fullNotes = t
        notesLoading = false
    }
    // 取更完整的一份：fetchNotes 已保证不会用残片替换全文，这里再加一层保险。
    // 只有仓库归档 md 确实不短于 release body 时才覆盖它。
    val notes = remember(fullNotes, info.notes) {
        val a = info.notes
        val b = fullNotes
        when {
            b == null -> a
            b.length >= a.length -> b
            else -> a
        }
    }

    Box(
        Modifier.fillMaxSize().background(go.bg),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Txt("发现新版本", 22, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            // 版本对比
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(vm.appVersion, 14, color = go.muted)
                Spacer(Modifier.width(10.dp))
                Txt("→", 14, color = go.muted)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(go.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Txt(info.latest, 14, color = go.primary, weight = FontWeight.SemiBold) }
            }
            if (info.size > 0) {
                Spacer(Modifier.height(8.dp))
                Txt("安装包 ${"%.1f".format(info.size / 1024f / 1024f)} MB", 12, color = go.muted)
            }
            Spacer(Modifier.height(20.dp))
            // 更新说明：全文 + Markdown 排版，占满剩余高度、可滚动、长按可复制
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(16.dp)).background(go.surfaceHi)
                    .padding(14.dp),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        if (notes.isBlank()) {
                            Txt("暂无更新说明", 13, color = go.on)
                        } else {
                            MarkdownDoc(notes)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    if (notesLoading) "正在载入完整文档…" else "共 ${notes.length} 字 · Markdown 排版 · 长按可复制",
                    11, color = go.muted,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("更新说明", notes))
                    android.widget.Toast.makeText(
                        ctx, "已复制完整更新说明", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }) { Txt("复制全文", 12, color = go.primary) }
                TextButton(onClick = {
                    notesLoading = true
                    scope.launch {
                        val t = withContext(Dispatchers.IO) {
                            runCatching { Updater.fetchNotes(info) }.getOrNull()
                        }
                        if (!t.isNullOrBlank() && t.length > notes.length) {
                            fullNotes = t
                            android.widget.Toast.makeText(
                                ctx, "已载入完整文档", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                ctx, "已是完整文档", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        notesLoading = false
                    }
                }) { Txt("查看完整文档", 12, color = go.primary) }
            }
            Spacer(Modifier.height(12.dp))
            if (!info.hasUpdate) {
                Txt("当前已是最新版本，此处仅查看更新说明", 11, color = go.muted)
                Spacer(Modifier.height(18.dp))
            } else if (prog != null) {
                // 应用内下载中：显示进度，此时不重复触发下载
                val (done, total) = prog
                val pct = if (total > 0) (done * 100f / total) else 0f
                Txt(
                    buildString {
                        append("正在下载… ${"%.1f".format(done / 1024f / 1024f)}")
                        append(if (total > 0) " / ${"%.1f".format(total / 1024f / 1024f)} MB" else " MB")
                        if (total > 0) append("  ${pct.toInt()}%")
                    },
                    13, color = go.muted, tnum = true,
                )
                Spacer(Modifier.height(10.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = go.primary,
                        trackColor = go.surfaceHi,
                    )
                } else {
                    CircularProgressIndicator(
                        color = go.primary, modifier = Modifier.size(28.dp), strokeWidth = 3.dp,
                    )
                }
                Spacer(Modifier.height(20.dp))
            } else {
                // 应用内更新（推荐）
                GoButton(
                    "应用内更新（推荐）",
                    primary = true,
                    enabled = vm.updateInfo != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { vm.updateInApp() }
                Spacer(Modifier.height(10.dp))
                Txt("下载后自动拉起安装，覆盖升级会保留数据；需允许安装未知应用", 11, color = go.muted)
                Spacer(Modifier.height(18.dp))
                // 应用外更新
                GoButton(
                    "应用外更新",
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    val u = vm.updateByBrowser()
                    if (u != null) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(u),
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Txt("用浏览器打开下载页，经 v6.gh-proxy 加速", 11, color = go.muted)
            }
            Spacer(Modifier.height(18.dp))
            // 取消
            GoButton(
                if (info.hasUpdate) "取消" else "关闭",
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) { vm.closeUpdate() }
            Spacer(Modifier.height(16.dp))
        }
    }
}
