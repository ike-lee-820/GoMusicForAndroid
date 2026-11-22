package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.43：Apple HIG 信息架构。
 *
 * - 底栏 / 侧栏只剩三个：我的 / 主页 / 搜索
 * - 主页内容不变（HomeTab 原样）
 * - 搜索页按用户给的 Apple Music 截图：一次输入聚合搜歌手/专辑/歌曲，
 *   Top Results 是混合纵向列表，分类 Tab 只切筛选、不重新请求
 * - 我的页：我的歌单 / 我的喜欢 / 听歌历史三个横排 + 各自详情页
 */

// =====================================================================
// 三 Tab 导航
// =====================================================================

private data class Tab3Item(val id: Int, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val filled: androidx.compose.ui.graphics.vector.ImageVector)

private val TAB3_ITEMS = listOf(
    Tab3Item(TAB3_MINE, "我的", Icons.Outlined.Person, Icons.Filled.Person),
    Tab3Item(TAB3_HOME, "主页", Icons.Outlined.Home, Icons.Filled.Home),
    Tab3Item(TAB3_SEARCH, "搜索", Icons.Outlined.Search, Icons.Filled.Search),
)

@Composable
fun NavBar3(vm: MainViewModel) {
    val go = LocalGo.current
    Column(
        Modifier.fillMaxWidth().background(go.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
    ) {
        // HIG：0.5pt 顶边
        Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            TAB3_ITEMS.forEach { t ->
                val sel = vm.tab3 == t.id && vm.stack.isEmpty()
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        vm.stack.clear()
                        vm.chooseTab3(t.id)
                        vm.query = ""
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (sel) t.filled else t.icon, t.label,
                        tint = if (sel) go.primary else go.muted,
                        modifier = Modifier.size(24.dp),
                    )
                    Txt(
                        t.label, 11,
                        weight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) go.primary else go.muted,
                    )
                }
            }
        }
    }
}

@Composable
fun NavRail3(vm: MainViewModel, startInset: Dp = 0.dp) {
    val go = LocalGo.current
    Column(
        Modifier.fillMaxHeight().width(76.dp + startInset).background(go.surface)
            .padding(start = startInset).navigationBarsPadding().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TAB3_ITEMS.forEach { t ->
            val sel = vm.tab3 == t.id && vm.stack.isEmpty()
            Column(
                Modifier.fillMaxWidth().height(72.dp).clickable {
                    vm.stack.clear()
                    vm.chooseTab3(t.id)
                    vm.query = ""
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (sel) t.filled else t.icon, t.label,
                    tint = if (sel) go.primary else go.muted,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Txt(
                    t.label, 11,
                    weight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) go.primary else go.muted,
                )
            }
        }
    }
}

// 26.11.57：搜索页按 Apple HIG 重做，实现见 SearchUi.kt

// =====================================================================
// 我的页
// =====================================================================

@Composable
fun MinePage(vm: MainViewModel) {
    val go = LocalGo.current
    var detail by remember { mutableStateOf<Int?>(null) }
    val liked = remember(vm.favorites, vm.snapshots, vm.songMap) {
        vm.favorites.mapNotNull { id ->
            vm.songMap[id] ?: vm.snapshots[id] ?: vm.historySongs.firstOrNull { it.ssid == id }
        }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = go.on)
            Spacer(Modifier.weight(1f))
            HigSettingsButton(vm, go)
        }
        Spacer(Modifier.height(18.dp))
        MineSection("我的歌单", "查看全部", go) { MineCardRow(vm.playlists.map { it.name to it.cover }, go) {
            val p = vm.playlists.getOrNull(it)
            if (p != null) vm.stack.add(Screen.PlaylistPage(p.id))
        } }
        MineSection("我的喜欢", "查看全部", go, onMore = { detail = 1 }) {
            MineCardRow(liked.map { it.title to it.coverUrl }, go) { detail = 1 }
        }
        MineSection("听歌历史", "查看全部", go, onMore = { detail = 2 }) {
            MineCardRow(vm.historySongs.take(20).map { it.title to it.coverUrl }, go) { detail = 2 }
        }
    }
    when (detail) {
        1 -> GoSheet({ detail = null }, "我的喜欢") { MineSongList(vm, liked, 1) }
        2 -> GoSheet({ detail = null }, "听歌历史") { MineSongList(vm, vm.historySongs, 2) }
    }
}

@Composable
private fun MineSection(
    title: String, action: String, go: GoColors,
    onMore: () -> Unit = {}, content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = go.on)
            Spacer(Modifier.weight(1f))
            Text(
                "$action ›", fontSize = 14.sp, color = go.primary,
                modifier = Modifier.clickable { onMore() }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun MineCardRow(items: List<Pair<String, String?>>, go: GoColors, onClick: (Int) -> Unit) {
    if (items.isEmpty()) {
        Text("还没有内容", fontSize = 14.sp, color = go.muted)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.size) { i ->
            val (name, cover) = items[i]
            Column(
                Modifier.width(140.dp).clickable { onClick(i) },
            ) {
                Cover(cover, Modifier.size(140.dp), 8.dp, hd = true)
                Spacer(Modifier.height(6.dp))
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = go.on, maxLines = 1)
            }
        }
    }
}

/**
 * 26.11.49：我的喜欢 / 听歌历史详情页。
 * 之前只有一个能点播放的列表，没有搜索、多选、批量删除、清空。
 * kind = 1 我的喜欢 / 2 听歌历史，删除与清空走各自的持久化集合。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.MineSongList(
    vm: MainViewModel, songs: List<Song>, kind: Int,
) {
    val go = LocalGo.current
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val checked = remember { mutableStateListOf<String>() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val shown = remember(songs, query) {
        val q = query.trim()
        if (q.isEmpty()) songs
        else songs.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 26.11.58：本地过滤，不发请求、不写搜索历史；只画一层槽
        HigSearchBar(
            value = query,
            onChange = { query = it },
            placeholder = "搜索歌曲 / 歌手 / 专辑",
            go = go,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                val all = shown.isNotEmpty() && shown.all { it.ssid in checked }
                Text(
                    if (all) "取消全选" else "全选", fontSize = 14.sp, color = go.primary,
                    modifier = Modifier.clickable {
                        if (all) checked.clear()
                        else shown.forEach { if (it.ssid !in checked) checked.add(it.ssid) }
                    }.padding(6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "删除（${checked.size}）", fontSize = 14.sp,
                    color = if (checked.isEmpty()) go.muted else Color(0xFFFF453A),
                    modifier = Modifier.clickable(enabled = checked.isNotEmpty()) { confirmDelete = true }
                        .padding(6.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "完成", fontSize = 14.sp, color = go.primary,
                    modifier = Modifier.clickable { selecting = false; checked.clear() }.padding(6.dp),
                )
            } else {
                Text(
                    "选择", fontSize = 14.sp, color = go.primary,
                    modifier = Modifier.clickable { selecting = true }.padding(6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "清空", fontSize = 14.sp, color = Color(0xFFFF453A),
                    modifier = Modifier.clickable { confirmClear = true }.padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "还没有内容" else "没有找到「$query」",
                    fontSize = 15.sp, color = go.muted,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.ssid }) { s ->
                    val on = s.ssid in checked
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selecting) {
                                        if (on) checked.remove(s.ssid) else checked.add(s.ssid)
                                    } else {
                                        vm.playSongs(shown, shown.indexOf(s)); vm.openPlayer()
                                    }
                                },
                                onLongClick = {
                                    if (!selecting) { selecting = true; checked.add(s.ssid) }
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            // 自绘选择圈，不依赖图标库版本
                            Box(
                                Modifier.size(22.dp).clip(CircleShape)
                                    .background(if (on) go.primary else go.surfaceHi),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) {
                                    Text(
                                        "✓", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, color = Color.White,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Cover(s.coverUrl, Modifier.size(48.dp), 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 16.sp, color = go.on, maxLines = 1)
                            Text(s.artist, fontSize = 13.sp, color = go.muted, maxLines = 1)
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height((0.5f).dp)
                            .padding(start = 76.dp).background(go.separator),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        HigConfirm(
            if (kind == 1) "清空我的喜欢？" else "清空听歌历史？",
            "清空",
            onDismiss = { confirmClear = false },
            onOk = {
                confirmClear = false
                if (kind == 1) vm.clearFavorites() else vm.clearHistory()
                checked.clear(); selecting = false
            },
        )
    }
    if (confirmDelete) {
        HigConfirm(
            "删除选中的 ${checked.size} 首？",
            "删除",
            onDismiss = { confirmDelete = false },
            onOk = {
                confirmDelete = false
                val ids = checked.toSet()
                if (kind == 1) vm.removeFavorites(ids) else vm.removeHistoryBatch(ids)
                checked.clear(); selecting = false
            },
        )
    }
}
