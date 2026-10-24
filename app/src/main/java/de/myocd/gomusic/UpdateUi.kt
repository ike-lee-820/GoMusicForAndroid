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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
            // 更新说明
            if (info.notes.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(16.dp)).background(go.surfaceHi)
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                ) { Txt(info.notes, 13, color = go.on) }
                Spacer(Modifier.height(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (prog != null) {
                // 应用内下载中：显示进度，此时不重复触发下载
                val (done, total) = prog
                val pct = if (total > 0) (done * 100f / total) else 0f
                Txt(
                    "正在下载… ${"%.1f".format(done / 1024f / 1024f)}" +
                        if (total > 0) " / ${"%.1f".format(total / 1024f / 1024f)} MB" else " MB",
                    13, color = go.muted,
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
                Txt("用浏览器打开下载页，经 gh-proxy 加速", 11, color = go.muted)
            }
            Spacer(Modifier.height(18.dp))
            // 取消
            GoButton("取消", modifier = Modifier.fillMaxWidth().height(44.dp)) { vm.closeUpdate() }
            Spacer(Modifier.height(16.dp))
        }
    }
}
