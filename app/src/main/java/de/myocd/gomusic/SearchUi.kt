package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 26.11.57：搜索页按 Apple HIG 重做。
 *
 * - 去掉 34sp 巨标题与四等分下划线 Tab，改成 iOS 搜索框 + Segmented Control
 * - 综合页不再是「一条扁平列表」，而是「最佳匹配 / 歌曲 / 艺人 / 专辑」分区：
 *   最佳匹配是大卡片，歌曲是紧凑列表，艺人圆形头像横排，专辑封面卡横排
 * - 分类页展示该板块全量结果，仍只切筛选、不重新发请求，缓存逻辑不变
 * - 横屏仍是左「搜索 + 分类」右「结果」双栏，行不会被拉到整块宽屏
 */
@Composable
fun SearchPage(vm: MainViewModel) {
    val go = LocalGo.current
    val agg = vm.aggregate
    val songs = agg?.songs.orEmpty()
    val albums = agg?.albums.orEmpty()
    val artists = agg?.artists.orEmpty()
    val labels = listOf("综合", "歌手", "专辑", "歌曲")

    BoxWithConstraints(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        val land = maxWidth > maxHeight * 1.15f
        val sideWidth = minOf(320.dp, maxWidth * 0.34f)

        val header: @Composable ColumnScope.() -> Unit = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("搜索", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = go.on)
                Spacer(Modifier.weight(1f))
                HigSettingsButton(vm, go)
            }
            Spacer(Modifier.height(10.dp))
            // 26.11.58：单层槽，不再套 SearchField 的第二层背景和圆角
            HigSearchBar(
                value = vm.query,
                onChange = {
                    vm.query = it
                    if (it.isEmpty()) vm.searchAll("")
                },
                placeholder = "歌曲、艺人、专辑",
                go = go,
                onSearch = { if (vm.query.isNotBlank()) vm.searchAll(vm.query) },
            )
            if (vm.query.isBlank() && vm.searchHistory.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近搜索", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = go.muted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清除", fontSize = 13.sp, color = go.primary,
                        modifier = Modifier.clickable { vm.clearSearchHistory() }.padding(4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.searchHistory) { w ->
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .background(go.surfaceHi)
                                .clickable { vm.query = w; vm.searchAll(w) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Search, null, tint = go.muted, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(w, fontSize = 13.sp, color = go.on)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SearchSegmented(labels, vm.searchFilter, go) { vm.chooseSearchFilter(it) }
            Spacer(Modifier.height(8.dp))
        }

        val results: @Composable () -> Unit = {
            SearchResults(vm, go, agg, songs, albums, artists)
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
                Box(Modifier.weight(1f).fillMaxWidth()) { results() }
            }
        }
    }
}

/** iOS Segmented Control：轨道灰、选中块按亮/暗主题取对比色 */
@Composable
private fun SearchSegmented(
    labels: List<String>,
    selected: Int,
    go: GoColors,
    onSelect: (Int) -> Unit,
) {
    val isLight = go.on.red < 0.5f
    val thumb = if (isLight) Color.White else Color(0xFF3A3A3C)
    Row(
        Modifier.fillMaxWidth().height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(go.surfaceHi)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel) thumb else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    l, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) go.on else go.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    vm: MainViewModel,
    go: GoColors,
    agg: Netease.Aggregate?,
    songs: List<Song>,
    albums: List<Group>,
    artists: List<Group>,
) {
    when {
        vm.query.isBlank() -> CenteredHint(go, "输入关键词开始搜索", "试试歌名、歌手或专辑名")
        agg == null -> CenteredHint(go, "正在搜索…", null)
        songs.isEmpty() && albums.isEmpty() && artists.isEmpty() ->
            CenteredHint(go, "没有找到「${vm.query}」", "换个关键词试试")
        else -> when (vm.searchFilter) {
            0 -> MixedResults(vm, go, songs, albums, artists)
            1 -> ArtistResults(vm, go, artists)
            2 -> AlbumResults(vm, go, albums)
            else -> SongResults(vm, go, songs)
        }
    }
}

@Composable
private fun CenteredHint(go: GoColors, title: String, sub: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = go.on)
            if (sub != null) {
                Spacer(Modifier.height(6.dp))
                Text(sub, fontSize = 13.sp, color = go.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SectionTitle(
    go: GoColors,
    title: String,
    more: String? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = go.on)
        Spacer(Modifier.weight(1f))
        if (more != null && onMore != null) {
            Row(
                Modifier.clickable { onMore() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(more, fontSize = 13.sp, color = go.primary)
                Icon(Icons.Outlined.ChevronRight, null, tint = go.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// =====================================================================
// 综合：分区展示，不再是扁平一条列表
// =====================================================================

@Composable
private fun MixedResults(
    vm: MainViewModel,
    go: GoColors,
    songs: List<Song>,
    albums: List<Group>,
    artists: List<Group>,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { SectionTitle(go, "最佳匹配") }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                songs.firstOrNull()?.let { s ->
                    TopCard(
                        go, s.title, "歌曲 · ${s.artist}", s.coverUrl,
                        circle = false, play = true,
                    ) { vm.playSongs(listOf(s), 0); vm.openPlayer() }
                }
                val second = artists.firstOrNull() ?: albums.firstOrNull()
                if (second != null) {
                    TopCard(
                        go, second.title,
                        if (artists.firstOrNull() === second) "艺人" else "专辑 · ${second.artist.ifBlank { second.subtitle }}",
                        second.cover,
                        circle = artists.firstOrNull() === second,
                        play = false,
                    ) { vm.openNmGroup(second) }
                }
            }
        }
        if (songs.isNotEmpty()) {
            item { SectionTitle(go, "歌曲", "查看全部") { vm.chooseSearchFilter(3) } }
            items(songs.take(6)) { s -> SongRow(vm, go, s) }
        }
        if (artists.isNotEmpty()) {
            item { SectionTitle(go, "艺人") }
            item { ArtistRow(vm, go, artists) }
        }
        if (albums.isNotEmpty()) {
            item { SectionTitle(go, "专辑") }
            item { AlbumRow(vm, go, albums) }
        }
    }
}

@Composable
private fun RowScope.TopCard(
    go: GoColors,
    title: String,
    sub: String,
    cover: String?,
    circle: Boolean,
    play: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(go.surfaceHi.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Cover(
            cover,
            Modifier.size(if (circle) 60.dp else 64.dp),
            if (circle) 30.dp else 10.dp,
            circle = circle,
            hd = true,
        )
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = go.on, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(sub, fontSize = 12.sp, color = go.muted, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(go.primary.copy(alpha = 0.9f))
                    .clickable { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (play) Icons.Outlined.PlayArrow else Icons.Outlined.ChevronRight,
                    null, tint = if (go.on.red < 0.5f) Color.White else Color.Black,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (play) "播放" else "查看", fontSize = 12.sp, color = go.primary)
        }
    }
}

@Composable
private fun ArtistRow(vm: MainViewModel, go: GoColors, list: List<Group>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(list) { g ->
            Column(
                Modifier.width(88.dp).clickable { vm.openNmGroup(g) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Cover(g.cover, Modifier.size(76.dp), 38.dp, circle = true, hd = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    g.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = go.on, maxLines = 1, textAlign = TextAlign.Center,
                )
                Text("艺人", fontSize = 11.sp, color = go.muted)
            }
        }
    }
}

@Composable
private fun AlbumRow(vm: MainViewModel, go: GoColors, list: List<Group>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list) { g ->
            Column(Modifier.width(132.dp).clickable { vm.openNmGroup(g) }) {
                Cover(g.cover, Modifier.size(132.dp), 12.dp, hd = true)
                Spacer(Modifier.height(6.dp))
                Text(g.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = go.on, maxLines = 1)
                Text(
                    g.artist.ifBlank { g.subtitle }, fontSize = 11.sp,
                    color = go.muted, maxLines = 1,
                )
            }
        }
    }
}

// =====================================================================
// 分类：展示该板块全量结果
// =====================================================================

@Composable
private fun ArtistResults(vm: MainViewModel, go: GoColors, list: List<Group>) {
    if (list.isEmpty()) { CategoryEmpty(go, "没有找到相关歌手"); return }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(list.chunked(3)) { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { g -> ArtistTile(vm, go, g, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ArtistTile(vm: MainViewModel, go: GoColors, g: Group, modifier: Modifier) {
    Column(
        modifier.clickable { vm.openNmGroup(g) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(g.cover, Modifier.size(76.dp), 38.dp, circle = true, hd = true)
        Spacer(Modifier.height(6.dp))
        Text(
            g.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = go.on, maxLines = 1, textAlign = TextAlign.Center,
        )
        Text("艺人", fontSize = 11.sp, color = go.muted)
    }
}

@Composable
private fun AlbumResults(vm: MainViewModel, go: GoColors, list: List<Group>) {
    if (list.isEmpty()) { CategoryEmpty(go, "没有找到相关专辑"); return }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(list.chunked(2)) { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { g ->
                    Column(
                        Modifier.weight(1f).clickable { vm.openNmGroup(g) },
                    ) {
                        Cover(
                            g.cover,
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            12.dp, hd = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            g.title, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = go.on, maxLines = 1,
                        )
                        Text(
                            g.artist.ifBlank { g.subtitle }, fontSize = 11.sp,
                            color = go.muted, maxLines = 1,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SongResults(vm: MainViewModel, go: GoColors, list: List<Song>) {
    if (list.isEmpty()) { CategoryEmpty(go, "没有找到相关歌曲"); return }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        items(list) { s -> SongRow(vm, go, s) }
    }
}

@Composable
private fun CategoryEmpty(go: GoColors, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = go.muted)
    }
}

@Composable
private fun SongRow(vm: MainViewModel, go: GoColors, s: Song) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().height(62.dp)
                .clickable { vm.playSongs(listOf(s), 0); vm.openPlayer() }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(s.coverUrl, Modifier.size(48.dp), 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = go.on, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text("歌曲 · ${s.artist}", fontSize = 12.sp, color = go.muted, maxLines = 1)
            }
            Box {
                Icon(
                    Icons.Outlined.MoreVert, "更多", tint = go.muted,
                    modifier = Modifier.size(22.dp).clickable { menu = true },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("播放") },
                        onClick = {
                            menu = false
                            vm.playSongs(listOf(s), 0); vm.openPlayer()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("加入歌单") },
                        onClick = {
                            menu = false
                            vm.pick(listOf(s.ssid), s.title, s.coverUrl)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("下载") },
                        onClick = { menu = false; vm.download(listOf(s)) },
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height((0.5f).dp)
                .padding(start = 60.dp).background(go.separator),
        )
    }
}
