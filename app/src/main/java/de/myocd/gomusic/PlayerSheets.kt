@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package de.myocd.gomusic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
 * 26.11.89：点歌手 / 「查看歌手」的多歌手选择弹窗。
 * 先用拆分出来的名字 0 等待列出，头像与真实 id 由 VM 后台补。
 */
@Composable
fun ArtistChooserSheet(vm: MainViewModel) {
    val go = LocalGo.current
    val list = vm.artistChoices.orEmpty()
    GoSheet({ vm.closeArtistChooser() }, "选择歌手") {
        Text(
            "这首歌有多位参与者，选一位进入。头像还在加载也可以直接点名字。",
            fontSize = 12.sp, color = go.muted,
        )
        Spacer(Modifier.height(8.dp))
        if (vm.artistChoiceBusy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp), color = go.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(list, key = { it.name }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { vm.pickArtist(c) })
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(c.cover, Modifier.size(48.dp).clip(CircleShape), 24.dp, circle = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 16.sp, color = go.on, maxLines = 1)
                        Text(
                            if (c.local) "本地曲库歌手" else if (c.nmId > 0) "网易云歌手" else "按名字查找",
                            fontSize = 11.sp, color = go.muted,
                        )
                    }
                    Text("进入", fontSize = 12.sp, color = go.primary)
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 26.11.89：制作人员弹窗，逐行可长按选择复制，也能一键复制全部 */
@Composable
fun CreditsSheet(vm: MainViewModel, text: String) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    val body = text.ifBlank { "暂无制作人员信息" }
    GoSheet({ vm.closeCredits() }, "制作人员") {
        Text("长按任意一行可单独复制；下面也可以一键复制全部。", fontSize = 12.sp, color = go.muted)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            itemsIndexed(body.lines(), key = { i, _ -> "c$i" }) { _, line ->
                Box(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    SelectionContainer {
                        Text(line, fontSize = 15.sp, color = go.on)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GoButton("复制全部") {
            vm.toast(if (copyText(ctx, body)) "制作人员已复制" else "复制失败")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 26.11.89：「歌词」弹层。
 *
 * 展示全部正文歌词；长按某行选中（可继续点选多行），底部对选中内容
 * 做复制 / 系统分享 / 制作歌词卡片（应用外分享图片、应用内私聊分享文本卡片）。
 * 一行都没选时，操作对象默认是全部歌词。
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

    GoSheet({ vm.closeLyricsSheet() }, "歌词") {
        Text(
            buildString {
                append(s?.title.orEmpty().ifBlank { "歌词" })
                if (!s?.artist.isNullOrBlank()) append(" - ").append(s?.artist)
            },
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = go.on, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "共 ${all.size} 行 · 长按某行选中，可继续点选多行；未选时默认全部",
            fontSize = 11.sp, color = go.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("全选") { picked = all.indices.toSet() }
            GoButton("清空选择") { picked = emptySet() }
        }
        Spacer(Modifier.height(8.dp))
        if (all.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无歌词", fontSize = 14.sp, color = go.muted)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                itemsIndexed(all, key = { i, _ -> "l$i" }) { i, line ->
                    val sel = i in picked
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) go.surfaceHi else Color.Transparent)
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
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                    ) {
                        Text(
                            line, fontSize = 15.sp,
                            color = if (sel) go.primary else go.on,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("选中 ${chosen.size} 行", fontSize = 11.sp, color = go.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("复制") {
                vm.toast(if (copyText(ctx, text)) "已复制 ${chosen.size} 行歌词" else "复制失败")
            }
            GoButton("分享") { vm.sharePlainText(text) }
        }
        Spacer(Modifier.height(8.dp))
        Text("制作歌词卡片", fontSize = 12.sp, color = go.muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoButton("应用外分享", primary = true) {
                vm.shareLyricCardOutside(
                    chosen, s?.title.orEmpty(), s?.artist.orEmpty(),
                )
            }
            GoButton("应用内分享") {
                vm.shareLyricCardInside(
                    chosen, s?.title.orEmpty(), s?.artist.orEmpty(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
