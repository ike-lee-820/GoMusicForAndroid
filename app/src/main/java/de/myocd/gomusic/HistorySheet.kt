package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 每次多渲染多少条 */
private const val HISTORY_PAGE = 200
/** 首屏渲染条数 */
private const val HISTORY_FIRST = 200

/** 历史页：展示全部播放记录（本站 + 网易云），可单条删除或清空 */
@Composable
fun HistorySheet(vm: MainViewModel) {
    val go = LocalGo.current
    val list = vm.historySongs
    GoSheet({ vm.showHistory = false }) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("播放历史", 16, Modifier.weight(1f), FontWeight.SemiBold)
                if (list.isNotEmpty()) {
                    Txt("清空", 12, Modifier.clickable { vm.clearHistory() }, color = go.muted)
                    HS(12)
                }
                IconBtn(Icons.Outlined.Close, "关闭") { vm.showHistory = false }
            }
            if (list.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt("还没有播放记录", 14, color = go.muted) }
            } else {
                // 最多 10000 条：先渲染前 200 条，滚到底再继续，避免一次性组合卡顿
                var visible by remember(list) { mutableIntStateOf(HISTORY_FIRST) }
                val shown = list.take(visible)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    item {
                        Txt(
                            "共 ${list.size} 条",
                            12,
                            Modifier.padding(start = 16.dp, bottom = 4.dp),
                            color = go.muted,
                        )
                    }
                    itemsIndexed(shown, key = { _, s -> s.ssid }) { i, s ->
                        if (i >= shown.lastIndex && visible < list.size) {
                            LaunchedEffect(visible) {
                                visible = (visible + HISTORY_PAGE).coerceAtMost(list.size)
                            }
                        }
                        HistoryItem(
                            song = s,
                            index = i,
                            onPlay = { vm.playSongs(list, i) },
                            onDelete = { vm.removeHistory(s.ssid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    song: Song,
    index: Int,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号：Material 3 风格的小圆底数字
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(go.surfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            Txt("${index + 1}", 12, color = go.muted, tnum = true)
        }
        HS(12)
        CoverWithSource(song.coverUrl, Modifier.size(44.dp), 8.dp, source = song.source)
        HS(12)
        Column(Modifier.weight(1f)) {
            Txt(titleWithSource(song.title, song.source), 15, weight = FontWeight.Medium, maxLines = 1)
            Txt(song.artist, 12, color = go.muted, maxLines = 1)
        }
        HS(8)
        IconBtn(Icons.Outlined.Delete, "删除这条记录") { onDelete() }
    }
}
