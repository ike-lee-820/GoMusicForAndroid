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

// =====================================================================
// 搜索页（图片样式）
// =====================================================================

@Composable
fun SearchPage(vm: MainViewModel) {
    val go = LocalGo.current
    val agg = vm.aggregate
    // 回车时已经一次性把三类全搜回来了；切板块只换展示数据源，
    // 绝不重新发请求，也不从「综合」那条被重排过的混合流里过滤
    // （之前那样切到歌曲板块只剩 6 首，看起来像「少了歌曲搜索」）。
    val hits: List<Netease.Aggregate.Hit> = when (vm.searchFilter) {
        1 -> agg?.artists.orEmpty().map { Netease.Aggregate.Hit.Artist(it) }
        2 -> agg?.albums.orEmpty().map { Netease.Aggregate.Hit.Album(it) }
        3 -> agg?.songs.orEmpty().map { Netease.Aggregate.Hit.SongHit(it) }
        else -> agg?.mixed.orEmpty()
    }
    // 26.11.54：横屏不再让搜索框 / 分类 / 结果行横跨整个宽屏（行被拉到
    // 两千多像素宽，非常难看）。改成左栏「搜索 + 分类」、右栏「结果」双栏；
    // 竖屏仍是原来那一列，逻辑与缓存完全不变。
    BoxWithConstraints(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        val land = maxWidth > maxHeight * 1.15f
        // 先在 BoxWithConstraintsScope 里算好，别在 RowScope 的 content
        // lambda 里直接读 maxWidth（隐式接收者会被 RowScope 顶掉）
        val sideWidth = minOf(340.dp, maxWidth * 0.34f)
        val labels = listOf("综合", "歌手", "专辑", "歌曲")
        val tabItem: @Composable (Int, String, Modifier) -> Unit = { i, l, m ->
            val sel = vm.searchFilter == i
            Box(
                m.heightIn(min = 44.dp).clickable { vm.chooseSearchFilter(i) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        l, fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = if (sel) go.primary else go.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.width(28.dp).height(2.dp)
                            .background(if (sel) go.primary else Color.Transparent),
                    )
                }
            }
        }
        val header: @Composable ColumnScope.() -> Unit = {
            Text("搜索", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = go.on)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(10.dp)).background(go.surfaceHi)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, "搜索", tint = go.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SearchField(
                    vm.query, { vm.query = it }, "歌曲、艺人、专辑",
                    Modifier.weight(1f), showIcon = false,
                    onSearch = { if (vm.query.isNotBlank()) vm.searchAll(vm.query) },
                )
                if (vm.query.isNotEmpty()) {
                    Text(
                        "✕", fontSize = 16.sp, color = go.muted,
                        modifier = Modifier.clickable { vm.query = ""; vm.searchAll("") }.padding(4.dp),
                    )
                }
            }
            if (vm.query.isBlank() && vm.searchHistory.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近搜索", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = go.on)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清除", fontSize = 13.sp, color = go.primary,
                        modifier = Modifier.clickable { vm.clearSearchHistory() }.padding(4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.searchHistory) { w ->
                        Text(
                            w, fontSize = 13.sp, color = go.on,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(go.surfaceHi)
                                .clickable { vm.query = w; vm.searchAll(w) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // 竖屏四等分横排；横屏左栏里改成竖排，避免四项被压在窄条上
            if (land) {
                Column(Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, l -> tabItem(i, l, Modifier.fillMaxWidth()) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    labels.forEachIndexed { i, l -> tabItem(i, l, Modifier.weight(1f)) }
                }
            }
            Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
        }
        val results: @Composable () -> Unit = {
            when {
                vm.query.isNotBlank() && hits.isEmpty() && agg == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在搜索…", fontSize = 15.sp, color = go.muted)
                    }
                hits.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (vm.query.isBlank()) "输入关键词开始搜索" else "没有找到「${vm.query}」",
                            fontSize = 15.sp, color = go.muted, textAlign = TextAlign.Center,
                        )
                    }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(hits) { h -> SearchHitRow(vm, h, go) }
                }
            }
        }
        if (land) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(sideWidth).fillMaxHeight()) { header() }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) { results() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                results()
            }
        }
    }
}

@Composable
private fun SearchHitRow(vm: MainViewModel, h: Netease.Aggregate.Hit, go: GoColors) {
    var menu by remember { mutableStateOf(false) }
    val title: String
    val sub: String
    val cover: String?
    val circle: Boolean
    val onClick: () -> Unit
    when (h) {
        is Netease.Aggregate.Hit.Artist -> {
            title = h.g.title; sub = "艺人"; cover = h.g.cover; circle = true
            onClick = { vm.openNmGroup(h.g) }
        }
        is Netease.Aggregate.Hit.Album -> {
            title = h.g.title; sub = "专辑 · ${h.g.artist.ifBlank { h.g.subtitle }}"
            cover = h.g.cover; circle = false
            onClick = { vm.openNmGroup(h.g) }
        }
        is Netease.Aggregate.Hit.SongHit -> {
            title = h.s.title; sub = "歌曲 · ${h.s.artist}"
            cover = h.s.coverUrl; circle = false
            onClick = { vm.playSongs(listOf(h.s), 0); vm.openPlayer() }
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().height(76.dp).clickable { onClick() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(cover, Modifier.size(60.dp), if (circle) 30.dp else 8.dp, circle = circle)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = go.on, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text(sub, fontSize = 14.sp, color = go.muted, maxLines = 1)
            }
            Box {
                Icon(
                    Icons.Outlined.MoreVert, "更多", tint = go.muted,
                    modifier = Modifier.size(24.dp).clickable { menu = true },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("播放") },
                        onClick = { menu = false; onClick() },
                    )
                    if (h is Netease.Aggregate.Hit.SongHit) {
                        DropdownMenuItem(
                            text = { Text("加入歌单") },
                            onClick = {
                                menu = false
                                vm.pick(listOf(h.s.ssid), h.s.title, h.s.coverUrl)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("下载") },
                            onClick = { menu = false; vm.download(listOf(h.s)) },
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height((0.5f).dp)
                .padding(start = 74.dp).background(go.separator)
        )
    }
}

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
        Text("我的", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = go.on)
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
        // 搜索：本地过滤，不发请求、不写搜索历史
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(10.dp)).background(go.surfaceHi)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, "搜索", tint = go.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            SearchField(
                query, { query = it }, "搜索歌曲 / 歌手 / 专辑",
                Modifier.weight(1f), showIcon = false,
            )
            if (query.isNotEmpty()) {
                Text(
                    "✕", fontSize = 16.sp, color = go.muted,
                    modifier = Modifier.clickable { query = "" }.padding(4.dp),
                )
            }
        }
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
