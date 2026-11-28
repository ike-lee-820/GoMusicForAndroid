@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.myocd.gomusic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun copyText(ctx: Context, text: String): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GoMusic", text))
    true
}.getOrDefault(false)

/**
 * 26.11.90：三个播放器弹层共用的底部卡片。
 *
 * 上一版套的是整页 GoSheet（56dp 顶栏 + 全屏铺白），看起来像又开了一个
 * 设置页，和播放器完全脱节。现在改成半透明遮罩 + 圆角底部卡片：小把手、
 * 标题/副标题、关闭按钮，内容自带限高，和「更多」菜单是同一套语言。
 */
@Composable
private fun PlayerCardSheet(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    val go = LocalGo.current
    BackHandler(enabled = true) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismiss() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(go.surface)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp).height(4.dp)
                    .clip(CircleShape)
                    .background(go.muted.copy(alpha = 0.35f)),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = go.on)
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(subtitle, fontSize = 11.sp, color = go.muted, maxLines = 2)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(go.surfaceHi)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close, "关闭", tint = go.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                content = content,
            )
        }
    }
}

/** 26.11.90：点歌手 /「查看歌手」的多歌手选择弹窗 */
@Composable
fun ArtistChooserSheet(vm: MainViewModel) {
    val go = LocalGo.current
    val list = vm.artistChoices.orEmpty()
    val s = vm.currentSong
    val sub = listOfNotNull(s?.title?.takeIf { it.isNotBlank() }, s?.artist?.takeIf { it.isNotBlank() })
        .joinToString(" · ").ifBlank { "这首歌有多位参与者" }
    PlayerCardSheet(
        onDismiss = { vm.closeArtistChooser() },
        title = "选择歌手",
        subtitle = "$sub · 选一位进入",
    ) {
        if (vm.artistChoiceBusy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp), color = go.primary,
            )
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 390.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.name }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(go.surfaceHi)
                        .combinedClickable(onClick = { vm.pickArtist(c) })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(c.cover, Modifier.size(52.dp).clip(CircleShape), 26.dp, circle = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = go.on, maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                c.local -> "本地曲库歌手"
                                c.nmId > 0 -> "网易云歌手 · 已匹配"
                                else -> "按名字查找"
                            },
                            fontSize = 11.sp, color = go.muted,
                        )
                    }
                    Text("进入 ›", fontSize = 12.sp, color = go.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "头像还在加载也可以直接点名字；选谁就进谁的歌手页。",
            fontSize = 11.sp, color = go.muted,
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** 26.11.90：制作人员卡片，字段与内容分栏，长按内容单独复制 */
@Composable
fun CreditsSheet(vm: MainViewModel, text: String) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    val body = text.ifBlank { "暂无制作人员信息" }
    PlayerCardSheet(
        onDismiss = { vm.closeCredits() },
        title = "制作人员",
        subtitle = "长按右侧内容可单独复制 · 也可一键复制全部",
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(body.lines(), key = { i, _ -> "c$i" }) { _, line ->
                val m = Regex("""^\s*([^：:]{1,12})[：:]\s*(.+)$""").find(line)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(go.surfaceHi)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (m != null) {
                        Text(
                            m.groupValues[1].trim(), fontSize = 12.sp, color = go.muted,
                            modifier = Modifier.width(64.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        SelectionContainer {
                            Text(
                                if (m != null) m.groupValues[2].trim() else line,
                                fontSize = 15.sp, color = go.on,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GoButton("复制全部", primary = true) {
            vm.toast(if (copyText(ctx, body)) "制作人员已复制" else "复制失败")
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 26.11.90：「歌词」卡片。
 *
 * 展示全部正文歌词；长按某行选中（可继续点选多行），对选中内容
 * 做复制 / 文本分享 / 歌词卡片（应用外 PNG / 应用内私信）。
 */
@Composable
fun LyricsSheet(vm: MainViewModel) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    val s = vm.currentSong
    val all = vm.lyricShareLines()
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val chosen = if (picked.isEmpty()) all else all.filterIndexed { i, _ -> i in picked }
    val text = chosen.joinToString("\n")

    PlayerCardSheet(
        onDismiss = { vm.closeLyricsSheet() },
        title = "歌词",
        subtitle = "长按某行选中，可继续点选多行；未选时默认全部",
    ) {
        // 歌曲信息小卡
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(go.surfaceHi)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(s?.coverUrl, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s?.title.orEmpty().ifBlank { "未播放" },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = go.on, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${all.size} 行" + if (picked.isEmpty()) " · 默认全部" else " · 已选 ${picked.size} 行",
                    fontSize = 11.sp, color = go.muted,
                )
                if (!s?.artist.isNullOrBlank()) {
                    Text(s?.artist.orEmpty(), fontSize = 11.sp, color = go.muted, maxLines = 1)
                }
            }
            Text(
                if (picked.isEmpty()) "全选" else "清空",
                fontSize = 12.sp, color = go.primary,
                modifier = Modifier.clickable {
                    picked = if (picked.isEmpty()) all.indices.toSet() else emptySet()
                }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (all.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无歌词", fontSize = 14.sp, color = go.muted)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(all, key = { i, _ -> "l$i" }) { i, line ->
                    val sel = i in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) go.primary.copy(alpha = 0.13f) else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (picked.isNotEmpty()) {
                                        picked = if (sel) picked - i else picked + i
                                    }
                                },
                                onLongClick = {
                                    picked = if (sel) picked - i else picked + i
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(3.dp).height(20.dp)
                                .clip(CircleShape)
                                .background(if (sel) go.primary else Color.Transparent),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            line, fontSize = 15.sp,
                            color = if (sel) go.primary else go.on,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("对 ${chosen.size} 行歌词操作", fontSize = 11.sp, color = go.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("复制") {
                vm.toast(if (copyText(ctx, text)) "已复制 ${chosen.size} 行歌词" else "复制失败")
            }
            GoButton("分享文本") { vm.sharePlainText(text) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("歌词卡片·应用外", primary = true) {
                vm.shareLyricCardOutside(
                    chosen, s?.title.orEmpty(), s?.artist.orEmpty(),
                )
            }
            GoButton("歌词卡片·应用内") {
                vm.shareLyricCardInside(
                    chosen, s?.title.orEmpty(), s?.artist.orEmpty(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
