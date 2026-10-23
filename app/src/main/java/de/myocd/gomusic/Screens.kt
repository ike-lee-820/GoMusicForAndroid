@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package de.myocd.gomusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, null))
}

// =====================================================================
// 主界面骨架
// =====================================================================

@Composable
fun MainScreen(vm: MainViewModel) {
    val go = LocalGo.current
    val listStates = listOf(
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState()
    )
    val top = vm.stack.lastOrNull()

    // 歌词或当前行变化时，把内容推给悬浮窗
    LaunchedEffect(vm.lyric, vm.lyricIndex, vm.floatOn) { vm.pushFloatLyric() }

    BackHandler(enabled = vm.showLyrics) { vm.showLyrics = false }
    BackHandler(enabled = vm.showPlayer && !vm.showLyrics) { vm.showPlayer = false }
    BackHandler(enabled = !vm.showPlayer && vm.stack.isNotEmpty()) { vm.stack.removeAt(vm.stack.lastIndex) }

    Box(Modifier.fillMaxSize().background(go.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    // 进入二级页时不再组合主页长列表：省掉整屏重组，打开歌单/专辑更快
                    if (top == null) HomeContent(vm, listStates)
                    AnimatedContent(
                        targetState = top,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 40 }) togetherWith fadeOut(tween(120))
                        },
                        label = "page",
                    ) { s ->
                        if (s != null) {
                            Box(
                                Modifier.fillMaxSize().background(go.bg)
                                    .pointerInput(Unit) { detectTapGestures { } }
                            ) {
                                when (s) {
                                    is Screen.Album -> AlbumPage(vm, s)
                                    is Screen.Artist -> ArtistPage(vm, s)
                                    is Screen.PlaylistPage -> PlaylistPage(vm, s)
                                    is Screen.NmSongs -> NmSongsPage(vm, s)
                                }
                            }
                        } else {
                            Spacer(Modifier.size(0.dp))
                        }
                    }
                }
            }
            MiniPlayer(vm)
            NavBar(vm)
        }

        GoToast(
            vm.toastMsg,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 56.dp + 64.dp + 16.dp),
        )

        AnimatedVisibility(
            vm.showPlayer,
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(180)),
        ) { FullPlayer(vm) }

        AnimatedVisibility(
            vm.showLyrics,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) { LyricsPage(vm) }

        if (vm.showQueue) QueueSheet(vm)
        if (vm.showSettings) SettingsSheet(vm)
        if (vm.showNmLogin) NmLoginSheet(vm)
        if (vm.showDownloads) DownloadsSheet(vm)
        vm.pickReq?.let { PickSheet(vm, it) }
    }
}

@Composable
fun NavBar(vm: MainViewModel) {
    val go = LocalGo.current
    val tabs = listOf(
        "歌曲" to Icons.Outlined.MusicNote,
        "专辑" to Icons.Outlined.Album,
        "歌手" to Icons.Outlined.Person,
        "歌单" to Icons.Outlined.LibraryMusic,
    )
    Column(Modifier.background(go.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            tabs.forEachIndexed { i, (label, icon) ->
                val sel = vm.tab == i && vm.stack.isEmpty()
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        vm.stack.clear()
                        vm.tab = i
                        vm.query = ""
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, label, tint = if (sel) go.primary else go.muted, modifier = Modifier.size(22.dp))
                    Txt(label, 11, weight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) go.primary else go.muted)
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: MainViewModel) {
    val s = vm.currentSong ?: return
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .height(64.dp).clip(RoundedCornerShape(16.dp)).background(go.surfaceHi)
            .clickable { vm.showPlayer = true }.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(s.coverUrl, Modifier.size(40.dp), 8.dp)
        HS(12)
        Column(Modifier.weight(1f)) {
            Txt(s.title, 15, weight = FontWeight.Medium, maxLines = 1)
            Txt(s.artist, 12, color = go.muted, maxLines = 1)
        }
        IconBtn(
            if (vm.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (vm.isPlaying) "暂停" else "播放", size = 44.dp,
        ) { vm.togglePlay() }
        IconBtn(Icons.AutoMirrored.Outlined.QueueMusic, "播放列表") { vm.showQueue = true }
    }
}

// =====================================================================
// 主页四个标签
// =====================================================================

@Composable
fun HomeContent(vm: MainViewModel, listStates: List<LazyListState>) {
    val go = LocalGo.current
    val titles = listOf("歌曲", "专辑", "歌手", "歌单")
    val hints = listOf("搜索歌名或歌手", "搜索专辑名或歌手", "搜索歌手", "搜索歌单")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(titles[vm.tab], 20, Modifier.weight(1f), FontWeight.Bold)
                IconBtn(Icons.Outlined.Settings, "设置", size = 44.dp) { vm.showSettings = true }
            }
            VS(8)
            SearchField(vm.query, { vm.query = it }, hints[vm.tab])
            VS(8)
            if (vm.offline) OfflineBar(vm)
            DownloadBar(vm)
        }
        when {
            vm.loading && vm.songs.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = go.primary)
                }
            vm.error != null && vm.songs.isEmpty() ->
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.CloudOff, null, tint = go.muted,
                        modifier = Modifier.size(40.dp),
                    )
                    VS(12)
                    Txt("${vm.error}", 14, color = go.muted, align = TextAlign.Center)
                    VS(8)
                    if (vm.downloads.isEmpty()) {
                        Txt("还没有下载歌曲，联网后可在歌曲右侧点下载", 13, color = go.muted, align = TextAlign.Center)
                        VS(12)
                    }
                    GoButton("重试", primary = true) { vm.reconnect() }
                }
            else -> when (vm.tab) {
                0 -> SongsTab(vm, listStates[0])
                1 -> AlbumsTab(vm, listStates[1])
                2 -> ArtistsTab(vm)
                else -> PlaylistsTab(vm, listStates[3])
            }
        }
    }
}

@Composable
fun DownloadBar(vm: MainViewModel) {
    val d = vm.dlStatus ?: return
    val go = LocalGo.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Download, null, tint = go.primary, modifier = Modifier.size(18.dp))
            HS(8)
            Txt(
                "正在下载 ${d.index}/${d.total} · ${d.title}", 13,
                Modifier.weight(1f), color = go.muted, maxLines = 1,
            )
            Txt(Noti.speed(d.bps).ifBlank { "准备中" }, 13, color = go.primary, tnum = true)
        }
        VS(6)
        if (d.percent >= 0) {
            LinearProgressIndicator(
                progress = d.percent / 100f,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = go.primary,
                trackColor = go.surfaceHi,
            )
            VS(4)
            Txt(
                "${Noti.size(d.done)} / ${Noti.size(d.size)} · ${d.percent}%",
                12, color = go.muted, tnum = true,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = go.primary,
                trackColor = go.surfaceHi,
            )
            VS(4)
            Txt("${Noti.size(d.done)} 已下载", 12, color = go.muted, tnum = true)
        }
        VS(8)
    }
}

@Composable
fun OfflineBar(vm: MainViewModel) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(go.surfaceHi)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CloudOff, null, tint = go.primary, modifier = Modifier.size(20.dp))
        HS(8)
        Txt("离线模式 · 已下载 ${vm.downloads.size} 首", 13, Modifier.weight(1f), color = go.muted)
        GoButton("重试", primary = true) { vm.reconnect() }
    }
    VS(8)
}

@Composable
fun SongsTab(vm: MainViewModel, state: LazyListState) {
    val q = vm.query.trim()
    val ranked = vm.rankedSongs
    val local = remember(ranked, q) {
        if (q.isEmpty()) ranked
        else ranked.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    // 两套曲库不合并（不去重），按规则交错展示
    val list = remember(local, vm.nmSongs) { vm.mix(local, vm.nmSongs) }
    LaunchedEffect(q, vm.nmReady) { vm.searchNetease(q) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        // 顶部：播放历史（本站 + 网易云）
        if (q.isEmpty() && vm.historySongs.isNotEmpty()) {
            item { HistoryRow(vm) }
        }
        if (vm.nmSearching) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = LocalGo.current.primary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    HS(8)
                    Txt("正在搜索网易云曲库", 12, color = LocalGo.current.muted)
                }
            }
        }
        itemsIndexed(list, key = { _, s -> s.ssid }, contentType = { _, _ -> "song" }) { i, s ->
            SongRow(
                s, s.ssid == vm.curSsid, vm.isPlaying,
                downloaded = vm.isDownloaded(s.ssid),
                downloading = s.ssid in vm.downloading,
                onClick = { vm.playSongs(list, i) },
                onAdd = { vm.pick(listOf(s.ssid), s.title, s.coverUrl) },
                onDownload = { vm.download(listOf(s)) },
            )
        }
    }
}

/** 歌曲栏顶部：最近播放（本站与网易云都记录） */
@Composable
fun HistoryRow(vm: MainViewModel) {
    val go = LocalGo.current
    val list = vm.historySongs
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("历史", 15, Modifier.weight(1f), FontWeight.SemiBold)
            Txt("清空", 12, Modifier.clickable { vm.clearHistory() }, color = go.primary)
            HS(12)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.take(20), key = { it.ssid }) { s ->
                Column(
                    Modifier.width(84.dp).clickable { vm.playSongs(list, list.indexOf(s)) },
                ) {
                    CoverWithSource(s.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, source = s.source)
                    VS(6)
                    Txt(s.title, 12, maxLines = 1)
                    Txt(s.artist, 11, color = go.muted, maxLines = 1)
                }
            }
        }
        VS(10)
    }
}

@Composable
fun AlbumsTab(vm: MainViewModel, state: LazyListState) {
    val q = vm.query.trim()
    val local = remember(vm.albumGroupList, q) {
        vm.albumGroupList.filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    val groups = remember(local, vm.nmAlbums) { vm.mix(local, vm.nmAlbums) }
    LaunchedEffect(q, vm.nmReady) { vm.searchNetease(q) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(
            groups,
            key = { _, g -> if (g.source == SRC_NETEASE) "nm${'$'}{g.nmId}" else g.artist to g.album },
            contentType = { _, _ -> "album" },
        ) { _, g ->
            GroupRow(
                g.title, g.subtitle, g.cover,
                source = g.source,
                onClick = {
                    if (g.source == SRC_NETEASE) vm.openNmGroup(g)
                    else vm.stack.add(Screen.Album(g.artist, g.album))
                },
                onAdd = if (g.songs.isEmpty()) null else {
                    { vm.pick(g.songs.map { it.ssid }, g.title, g.cover) }
                },
            )
        }
    }
}

@Composable
fun ArtistsTab(vm: MainViewModel) {
    val q = vm.query.trim()
    val local = remember(vm.artistGroupList, q) {
        vm.artistGroupList.filter { q.isEmpty() || it.title.contains(q, true) }
    }
    val groups = remember(local, vm.nmArtists) { vm.mix(local, vm.nmArtists) }
    LaunchedEffect(q, vm.nmReady) { vm.searchNetease(q) }
    val cols = if (LocalConfiguration.current.screenWidthDp >= 768) 4 else 2
    LazyVerticalGrid(
        GridCells.Fixed(cols),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            groups,
            key = { if (it.source == SRC_NETEASE) "nm${'$'}{it.nmId}" else it.title },
            contentType = { "artist" },
        ) { g ->
            GroupCard(
                g.title, g.subtitle, g.cover,
                source = g.source,
                onClick = {
                    if (g.source == SRC_NETEASE) vm.openNmGroup(g)
                    else vm.stack.add(Screen.Artist(g.artist))
                },
                onAdd = { vm.pick(g.songs.map { it.ssid }, g.title, g.cover) },
            )
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val go = LocalGo.current
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = go.surface,
        title = { Txt(title, 16, weight = FontWeight.SemiBold) },
        text = { SearchField(t, { t = it }, "歌单名称", showIcon = false) },
        confirmButton = {
            TextButton(onClick = { if (t.isNotBlank()) onOk(t.trim()) }) { Text("确定", color = go.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = go.muted) } },
    )
}

@Composable
fun PlaylistsTab(vm: MainViewModel, state: LazyListState) {
    val go = LocalGo.current
    val q = vm.query.trim()
    val list = vm.playlists.filter { q.isEmpty() || it.name.contains(q, true) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }

    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                GoButton("新建歌单", icon = Icons.Outlined.Add) { creating = true }
            }
        }
        itemsIndexed(list, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, p ->
            val songs = remember(p.songs, vm.songs) { vm.songsByIds(p.songs) }
            var menu by remember { mutableStateOf(false) }
            GroupRow(
                p.name, "${songs.size} 首", p.cover ?: songs.firstNotNullOfOrNull { it.coverUrl },
                onClick = { vm.stack.add(Screen.PlaylistPage(p.id)) },
                onAdd = null,
                trailing = {
                    Box {
                        IconBtn(Icons.Outlined.MoreVert, "更多操作") { menu = true }
                        DropdownMenu(menu, { menu = false }, Modifier.background(go.surface)) {
                            DropdownMenuItem(text = { Text("重命名", color = go.on) }, onClick = { menu = false; renaming = p })
                            DropdownMenuItem(text = { Text("删除", color = go.on) }, onClick = { menu = false; deleting = p })
                        }
                    }
                },
            )
        }
    }
    if (creating) NameDialog("新建歌单", "", { creating = false }) { vm.createPlaylist(it); creating = false }
    renaming?.let { p ->
        NameDialog("重命名歌单", p.name, { renaming = null }) { vm.renamePlaylist(p.id, it); renaming = null }
    }
    deleting?.let { p ->
        ConfirmDialog("删除歌单“${p.name}”？", { deleting = null }) { vm.deletePlaylist(p.id); deleting = null }
    }
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val go = LocalGo.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = go.surface,
        title = { Txt(text, 16, weight = FontWeight.SemiBold) },
        confirmButton = { TextButton(onClick = onOk) { Text("删除", color = go.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = go.muted) } },
    )
}

// =====================================================================
// 二级页面
// =====================================================================

@Composable
fun TopBar(vm: MainViewModel, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "返回", size = 44.dp) {
            if (vm.stack.isNotEmpty()) vm.stack.removeAt(vm.stack.lastIndex)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun ActionRow(vm: MainViewModel, songs: List<Song>, name: String, cover: String?, link: String) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GoButton("下载", icon = Icons.Outlined.Download) { vm.download(songs) }
        GoButton("分享", icon = Icons.Outlined.Share) { shareText(ctx, "$name $link") }
        GoButton("加入歌单", icon = Icons.AutoMirrored.Outlined.PlaylistAdd) { vm.pick(songs.map { it.ssid }, name, cover) }
        GoButton("新建歌单", icon = Icons.Outlined.Add) {
            vm.createPlaylist(name, songs.map { it.ssid }, cover)
            vm.toast("已新建歌单")
        }
    }
}

@Composable
fun AlbumPage(vm: MainViewModel, s: Screen.Album) {
    val go = LocalGo.current
    val list = remember(vm.songs, s) { vm.songs.filter { it.artist == s.artist && it.album == s.album } }
    val cover = list.firstNotNullOfOrNull { it.coverUrl }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val w = minOf(LocalConfiguration.current.screenWidthDp * 0.72f, 320f).dp
                    Cover(cover, Modifier.size(w), 24.dp)
                    VS(16)
                    Txt(s.album.ifBlank { "未知专辑" }, 20, weight = FontWeight.Bold, align = TextAlign.Center)
                    VS(4)
                    Txt(
                        s.artist, 14, color = go.muted,
                        modifier = Modifier.clickable { vm.stack.add(Screen.Artist(s.artist)) }.padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                    VS(4)
                    ActionRow(vm, list, s.album, cover, vm.albumLink(s.artist, s.album))
                }
            }
            itemsIndexed(list, key = { _, s -> s.ssid }, contentType = { _, _ -> "song" }) { i, song ->
                SongRow(
                    song, song.ssid == vm.curSsid, vm.isPlaying,
                    downloaded = vm.isDownloaded(song.ssid),
                    downloading = song.ssid in vm.downloading,
                    onClick = { vm.playSongs(list, i) },
                    onAdd = { vm.pick(listOf(song.ssid), song.title, song.coverUrl) },
                    onDownload = { vm.download(listOf(song)) },
                )
            }
        }
    }
}

@Composable
fun ArtistPage(vm: MainViewModel, s: Screen.Artist) {
    val go = LocalGo.current
    val list = remember(vm.songs, s) { vm.songs.filter { it.artist == s.name } }
    val albums = remember(list) { vm.albums(list) }
    val info = vm.artistInfo[s.name]
    val img = info?.image?.takeIf { it.isNotBlank() } ?: list.firstNotNullOfOrNull { it.coverUrl }
    var expanded by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(img, Modifier.size(160.dp), circle = true)
                    VS(12)
                    Txt(s.name, 20, weight = FontWeight.Bold, align = TextAlign.Center)
                    val bio = info?.bio
                    if (!bio.isNullOrBlank()) {
                        VS(8)
                        Text(
                            bio,
                            Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            color = go.muted, fontSize = 13.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    VS(12)
                    ActionRow(vm, list, s.name, img, vm.artistLink(s.name))
                    VS(8)
                    Row(Modifier.fillMaxWidth()) {
                        listOf("专辑", "歌曲").forEachIndexed { i, t ->
                            Box(
                                Modifier.weight(1f).height(44.dp).clickable { sub = i },
                                contentAlignment = Alignment.Center,
                            ) {
                                Txt(
                                    t, 16, weight = FontWeight.SemiBold,
                                    color = if (sub == i) go.primary else go.muted,
                                )
                            }
                        }
                    }
                }
            }
            if (sub == 0) {
                itemsIndexed(albums, key = { _, g -> g.artist to g.album }, contentType = { _, _ -> "album" }) { _, g ->
                    GroupRow(
                        g.title, g.subtitle, g.cover,
                        onClick = { vm.stack.add(Screen.Album(g.artist, g.album)) },
                        onAdd = { vm.pick(g.songs.map { it.ssid }, g.title, g.cover) },
                    )
                }
            } else {
                itemsIndexed(list, key = { _, s -> s.ssid }, contentType = { _, _ -> "song" }) { i, song ->
                    SongRow(
                        song, song.ssid == vm.curSsid, vm.isPlaying,
                        downloaded = vm.isDownloaded(song.ssid),
                        downloading = song.ssid in vm.downloading,
                        onClick = { vm.playSongs(list, i) },
                        onAdd = { vm.pick(listOf(song.ssid), song.title, song.coverUrl) },
                        onDownload = { vm.download(listOf(song)) },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistPage(vm: MainViewModel, s: Screen.PlaylistPage) {
    val go = LocalGo.current
    val p = vm.playlists.firstOrNull { it.id == s.id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (p == null) {
        LaunchedEffect(Unit) { if (vm.stack.isNotEmpty()) vm.stack.removeAt(vm.stack.lastIndex) }
        return
    }
    val songs = remember(p.songs, vm.songs) { vm.songsByIds(p.songs) }
    val rs = rememberReorderState()
    Column(Modifier.fillMaxSize()) {
        TopBar(vm) {
            IconBtn(Icons.Outlined.Edit, "重命名歌单") { renaming = true }
            IconBtn(Icons.Outlined.Delete, "删除歌单") { deleting = true }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Txt(p.name, 20, weight = FontWeight.Bold)
                    Txt("${songs.size} 首", 12, color = go.muted)
                    VS(12)
                    GoButton("播放全部", icon = Icons.Outlined.PlayArrow, primary = true, enabled = songs.isNotEmpty()) {
                        vm.playSongs(songs, 0)
                    }
                }
            }
            reorderItems(
                rs, songs, 64.dp,
                onMove = { f, t -> vm.movePl(p.id, f, t) },
                key = { _, song -> song.ssid },
            ) { i, song, handle ->
                Row(
                    Modifier.fillMaxSize().clickable { vm.playSongs(songs, i) }.padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DragHandle, "拖动排序", tint = go.muted)
                    }
                    Cover(song.coverUrl, Modifier.size(48.dp), 8.dp)
                    HS(12)
                    Column(Modifier.weight(1f)) {
                        Txt(
                            song.title, 15, weight = FontWeight.Medium,
                            color = if (song.ssid == vm.curSsid) go.primary else go.on, maxLines = 1,
                        )
                        Txt("${song.artist} · ${song.album}", 12, color = go.muted, maxLines = 1)
                    }
                    IconBtn(Icons.Outlined.Close, "从歌单移除") { vm.removeFromPl(p.id, song.ssid) }
                }
            }
        }
    }
    if (renaming) NameDialog("重命名歌单", p.name, { renaming = false }) { vm.renamePlaylist(p.id, it); renaming = false }
    if (deleting) ConfirmDialog("删除歌单“${p.name}”？", { deleting = false }) {
        deleting = false
        vm.deletePlaylist(p.id)
        if (vm.stack.isNotEmpty()) vm.stack.removeAt(vm.stack.lastIndex)
    }
}

// =====================================================================
// 全屏播放页
// =====================================================================

@Composable
fun FullPlayer(vm: MainViewModel) {
    val go = LocalGo.current
    val s = vm.currentSong
    val wide = LocalConfiguration.current.screenWidthDp >= 768
    val bgModel = remember(s?.coverUrl) { imageModel(s?.coverUrl) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        if (bgModel != null) {
            AsyncImage(bgModel, null, Modifier.fillMaxSize().blur(40.dp), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row { IconBtn(Icons.Outlined.KeyboardArrowDown, "关闭", size = 44.dp) { vm.showPlayer = false } }
            if (s != null) {
                if (wide) {
                    Row(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { PlayerBody(vm, s, false) }
                        Box(Modifier.weight(1f).fillMaxHeight()) { LyricsList(vm) }
                    }
                } else {
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { PlayerBody(vm, s, true) }
                }
            }
        }
    }
}

@Composable
fun PlayerBody(vm: MainViewModel, s: Song, snippet: Boolean) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverSize = minOf(maxWidth * 0.86f, 360.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(
                s.coverUrl,
                Modifier.size(coverSize).clickable {
                    vm.showPlayer = false
                    vm.stack.add(Screen.Album(s.artist, s.album))
                },
                20.dp,
            )
            VS(20)
            MarqueeText(s.title, 22, FontWeight.Bold, Modifier.fillMaxWidth(), align = TextAlign.Center)
            VS(4)
            Txt(
                s.artist, 14, color = go.muted, maxLines = 1,
                modifier = Modifier.clickable {
                    vm.showPlayer = false
                    vm.stack.add(Screen.Artist(s.artist))
                }.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            if (snippet) {
                VS(8)
                val idx = vm.lyricIndex
                val lines = vm.lyric
                Column(
                    Modifier.fillMaxWidth().height(84.dp).clickable { vm.showLyrics = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (lines.isEmpty()) {
                        Txt("暂无歌词", 14, color = go.muted)
                    } else {
                        val i = idx.coerceAtLeast(0)
                        listOf(i - 1, i, i + 1).forEach { k ->
                            val t = lines.getOrNull(k)?.text ?: ""
                            Txt(
                                t, if (k == i) 17 else 14,
                                weight = if (k == i) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (k == i && idx >= 0) go.primary else go.muted,
                                maxLines = 1, align = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            VS(8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconBtn(Icons.Outlined.Share, "分享") { shareText(ctx, "${s.title} - ${s.artist} ${vm.songLink(s.ssid)}") }
                IconBtn(Icons.Outlined.Download, "下载") { vm.download(listOf(s)) }
                IconBtn(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单") { vm.pick(listOf(s.ssid), s.title, s.coverUrl) }
                QualityMenu(vm)
                SpeedMenu(vm)
            }
            VS(8)
            SeekBar(vm.positionMs, vm.durationMs) { vm.seekTo(it) }
            VS(8)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(
                    when (vm.playMode) { 0 -> Icons.Outlined.Repeat; 1 -> Icons.Outlined.Shuffle; else -> Icons.Outlined.RepeatOne },
                    listOf("列表循环", "随机播放", "单曲循环")[vm.playMode], size = 44.dp,
                ) { vm.cycleMode() }
                IconBtn(Icons.Outlined.SkipPrevious, "上一首", size = 44.dp) { vm.prev() }
                IconBtn(
                    if (vm.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (vm.isPlaying) "暂停" else "播放", size = 56.dp, tint = OnPrimary, bg = go.primary,
                ) { vm.togglePlay() }
                IconBtn(Icons.Outlined.SkipNext, "下一首", size = 44.dp) { vm.next() }
                IconBtn(Icons.AutoMirrored.Outlined.QueueMusic, "播放列表", size = 44.dp) { vm.showQueue = true }
            }
            VS(16)
        }
    }
}

@Composable
fun QualityMenu(vm: MainViewModel) {
    val go = LocalGo.current
    var open by remember { mutableStateOf(false) }
    val opts = vm.qualities.ifEmpty { listOf(vm.quality) }
    Box {
        IconBtn(Icons.Outlined.HighQuality, "音质") { open = true }
        DropdownMenu(open, { open = false }, Modifier.background(go.surface)) {
            opts.forEach { k ->
                DropdownMenuItem(
                    text = { Text(qualityLabel(k), color = if (k == vm.quality) go.primary else go.on) },
                    onClick = { vm.updateQuality(k); open = false },
                )
            }
        }
    }
}

@Composable
fun SpeedMenu(vm: MainViewModel) {
    val go = LocalGo.current
    var open by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    Box {
        IconBtn(Icons.Outlined.Speed, "倍速") { open = true }
        DropdownMenu(open, { open = false }, Modifier.background(go.surface)) {
            speeds.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v}×", color = if (v == vm.speed) go.primary else go.on) },
                    onClick = { vm.updateSpeed(v); open = false },
                )
            }
        }
    }
}

// =====================================================================
// 歌词
// =====================================================================

@Composable
fun LyricsList(vm: MainViewModel) {
    val go = LocalGo.current
    val lines = vm.lyric
    val idx = vm.lyricIndex
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("暂无歌词", 14, color = go.muted) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        val st = rememberLazyListState()
        var lastManual by remember { mutableLongStateOf(0L) }
        var auto by remember { mutableStateOf(false) }
        LaunchedEffect(st) {
            snapshotFlow { st.isScrollInProgress }.collect { if (it && !auto) lastManual = System.currentTimeMillis() }
        }
        // 手动滑动后 3 秒内不自动居中
        LaunchedEffect(idx, lines) {
            if (idx >= 0 && System.currentTimeMillis() - lastManual > 3000) {
                auto = true
                try { st.animateScrollToItem(idx) } finally { auto = false }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = st,
            contentPadding = PaddingValues(vertical = half),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lines) { i, l ->
                val cur = i == idx
                val size = if (cur) 19 else 17
                Text(
                    l.text,
                    Modifier.fillMaxWidth()
                        .clickable(enabled = l.timeMs >= 0) { vm.seekTo(l.timeMs) }
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    color = if (cur) go.primary else go.muted,
                    fontSize = size.sp,
                    lineHeight = (size * 1.8f).sp,
                    fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun LyricsPage(vm: MainViewModel) {
    val go = LocalGo.current
    val s = vm.currentSong
    val bgModel = remember(s?.coverUrl) { imageModel(s?.coverUrl) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        if (bgModel != null) {
            AsyncImage(bgModel, null, Modifier.fillMaxSize().blur(40.dp), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Outlined.KeyboardArrowDown, "关闭歌词", size = 44.dp) { vm.showLyrics = false }
                Column(Modifier.weight(1f)) {
                    Txt(s?.title ?: "", 16, weight = FontWeight.SemiBold, maxLines = 1)
                    Txt(s?.artist ?: "", 12, color = go.muted, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f)) { LyricsList(vm) }
        }
    }
}

// =====================================================================
// 弹层：播放列表 / 加入歌单 / 主题色
// =====================================================================

@Composable
fun QueueSheet(vm: MainViewModel) {
    val go = LocalGo.current
    GoSheet({ vm.showQueue = false }) {
        val st = rememberLazyListState()
        val rs = rememberReorderState()
        // 打开播放列表时自动定位到当前播放的歌曲
        LaunchedEffect(Unit) {
            val i = vm.curIndex
            if (i > 0) st.scrollToItem((i - 1).coerceAtLeast(0))
        }
        Txt("播放列表（${vm.queue.size}）", 16, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), FontWeight.SemiBold)
        LazyColumn(state = st, contentPadding = PaddingValues(bottom = 8.dp)) {
            reorderItems(
                rs, vm.queue, 64.dp,
                onMove = { f, t -> vm.moveQueue(f, t) },
                key = { _, e -> e.ssid },
            ) { i, e, handle ->
                val cur = i == vm.curIndex
                Row(
                    Modifier.fillMaxSize().clickable { vm.playIndex(i) }.padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DragHandle, "拖动排序", tint = go.muted)
                    }
                    Cover(e.cover, Modifier.size(48.dp), 8.dp)
                    HS(12)
                    Column(Modifier.weight(1f)) {
                        Txt(e.title, 15, weight = FontWeight.Medium, color = if (cur) go.primary else go.on, maxLines = 1)
                        Txt(e.artist, 12, color = go.muted, maxLines = 1)
                    }
                    IconBtn(Icons.Outlined.Close, "从队列移除") { vm.removeQueue(i) }
                }
            }
        }
    }
}

@Composable
fun PickSheet(vm: MainViewModel, req: PickRequest) {
    val go = LocalGo.current
    var creating by remember { mutableStateOf(false) }
    val close = { vm.pickReq = null }
    GoSheet(close) {
        Txt("加入歌单", 16, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), FontWeight.SemiBold)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { creating = true }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = go.primary, modifier = Modifier.size(22.dp))
                HS(12)
                Txt("新建歌单", 15, color = go.primary, weight = FontWeight.Medium)
            }
            vm.playlists.forEach { p ->
                val songs = remember(p.songs, vm.songs) { vm.songsByIds(p.songs) }
                GroupRow(
                    p.name, "${songs.size} 首", p.cover ?: songs.firstNotNullOfOrNull { it.coverUrl },
                    onClick = { vm.addToPlaylist(p.id, req.ssids); close() },
                    onAdd = null,
                )
            }
        }
    }
    if (creating) {
        NameDialog("新建歌单", req.name, { creating = false }) {
            vm.createPlaylist(it, req.ssids, req.cover)
            vm.toast("已新建并加入歌单")
            creating = false
            close()
        }
    }
}

/** 悬浮歌词用的取色行 */
@Composable
fun FloatColorRow(current: Color, onPick: (Color) -> Unit) {
    val presets = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFD0BCFF, 0xFF64B5F6,
        0xFF81C784, 0xFFFFF176, 0xFFFF8A80, 0xFFF48FB1,
    ).map { Color(it) } + listOf(LocalGo.current.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.forEach { c ->
            val on = c == current
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (on) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (c.luminance() > 0.5f) Color.Black else Color.White),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSheet(vm: MainViewModel) {
    val go = LocalGo.current
    var hex by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val presets = listOf(
        0xFFD0BCFF, 0xFFFF8A80, 0xFFFFB74D, 0xFFFFF176,
        0xFF81C784, 0xFF4DD0E1, 0xFF64B5F6, 0xFFF48FB1,
    ).map { Color(it) }
    GoSheet({ vm.showSettings = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ---------- 连接 ----------
            Txt("连接", 15, weight = FontWeight.SemiBold)
            VS(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (vm.offline) Icons.Outlined.CloudOff else Icons.Outlined.CloudQueue,
                    null, tint = if (vm.offline) go.muted else go.primary, modifier = Modifier.size(20.dp),
                )
                HS(8)
                Txt(
                    if (vm.offline) "离线模式：连接服务器失败" else "已连接服务器",
                    13, Modifier.weight(1f), color = go.muted,
                )
                GoButton("重试", primary = vm.offline) { vm.reconnect() }
            }
            VS(16)

            // ---------- 网易云曲库 ----------
            Txt("网易云曲库", 15, weight = FontWeight.SemiBold)
            VS(8)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(go.surfaceHi)
                    .clickable { vm.setNmEnabled(!vm.nmOn) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("接入网易云曲库", 14, weight = FontWeight.Medium)
                    Txt(
                        if (!vm.nmOn) "关闭：仅使用本站曲库"
                        else if (vm.nmUser.isNullOrBlank()) "已开启：需登录网易云账号后才会搜索网易云"
                        else "已开启：${vm.nmUser}",
                        12, color = go.muted,
                    )
                }
                Switch(
                    checked = vm.nmOn,
                    onCheckedChange = { vm.setNmEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimary,
                        checkedTrackColor = go.primary,
                        uncheckedThumbColor = go.muted,
                        uncheckedTrackColor = go.surfaceHi,
                    ),
                )
            }
            if (vm.nmOn) {
                VS(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        if (vm.nmUser.isNullOrBlank()) "未登录，网易云搜索不会启用" else "已登录：${vm.nmUser}",
                        12, Modifier.weight(1f), color = go.muted,
                    )
                    if (vm.nmUser.isNullOrBlank()) {
                        GoButton("登录 / 注册", primary = true) { vm.setNmLogin(true) }
                    } else {
                        GoButton("退出") { vm.nmLogout() }
                    }
                }
                VS(6)
                Txt("开启并登录后，搜索会同时展示两套曲库的结果（不合并），网易云结果带角标", 12, color = go.muted)
            }
            VS(16)

            // ---------- 后台播放 ----------
            Txt("后台播放", 15, weight = FontWeight.SemiBold)
            VS(8)
            Txt("其他应用播放声音或来电时，本应用是否暂停", 12, color = go.muted)
            VS(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    FOCUS_NORMAL to "允许被打断",
                    FOCUS_CALL_ONLY to "仅通话暂停",
                    FOCUS_ALWAYS to "始终播放",
                ).forEach { (m, label) ->
                    GoButton(label, primary = vm.playFocus == m) { vm.updateFocus(m) }
                }
            }
            VS(8)
            Txt(
                when (vm.playFocus) {
                    FOCUS_CALL_ONLY -> "其他应用播放声音不会打断，仅来电/通话时暂停，挂断后自动继续"
                    FOCUS_ALWAYS -> "不被任何应用打断，来电仍会继续播放"
                    else -> "其他应用播放声音或来电时自动暂停（系统默认行为）"
                },
                12, color = go.muted,
            )
            VS(16)

            // ---------- 主题色 ----------
            Txt("主题色", 15, weight = FontWeight.SemiBold)
            VS(12)
            GoButton("跟随封面", primary = vm.fixedTheme == null) { vm.setTheme(null) }
            VS(12)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                presets.forEach { c ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(c)
                            .clickable { vm.setTheme(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (vm.fixedTheme == c) {
                            Icon(Icons.Outlined.MusicNote, "当前主题色", tint = OnPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            VS(16)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SearchField(hex, { hex = it }, "自定义颜色，如 #64B5F6", showIcon = false) }
                HS(8)
                GoButton("应用") {
                    val t = if (hex.startsWith("#")) hex else "#$hex"
                    runCatching { Color(android.graphics.Color.parseColor(t)) }
                        .onSuccess { vm.setTheme(it) }
                        .onFailure { vm.toast("颜色格式不正确") }
                }
            }
            VS(16)

            // ---------- 悬浮歌词 ----------
            Txt("悬浮歌词", 15, weight = FontWeight.SemiBold)
            VS(8)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(go.surfaceHi)
                    .clickable { vm.chooseFloatOn(!vm.floatOn) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("显示悬浮歌词", 14, weight = FontWeight.Medium)
                    Txt(
                        if (vm.floatOn) "已开启，可拖动调整位置" else "关闭",
                        12, color = go.muted,
                    )
                }
                Switch(
                    checked = vm.floatOn,
                    onCheckedChange = { vm.chooseFloatOn(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimary,
                        checkedTrackColor = go.primary,
                        uncheckedThumbColor = go.muted,
                        uncheckedTrackColor = go.surfaceHi,
                    ),
                )
            }
            if (vm.floatOn) {
                VS(12)
                Txt("文字颜色", 13, color = go.muted)
                VS(6)
                FloatColorRow(vm.floatColor) { vm.updateFloat(color = it) }
                VS(10)
                Txt("描边颜色", 13, color = go.muted)
                VS(6)
                FloatColorRow(vm.floatStrokeColor) { vm.updateFloat(strokeColor = it) }
                VS(10)
                Txt("字号 ${vm.floatSize.toInt()} sp", 13, color = go.muted)
                Slider(
                    value = vm.floatSize,
                    onValueChange = { vm.updateFloat(size = it) },
                    valueRange = 12f..32f,
                    modifier = Modifier.fillMaxWidth(),
                )
                VS(6)
                Txt("描边 ${vm.floatStroke.toInt()} dp", 13, color = go.muted)
                Slider(
                    value = vm.floatStroke,
                    onValueChange = { vm.updateFloat(stroke = it) },
                    valueRange = 0f..8f,
                    modifier = Modifier.fillMaxWidth(),
                )
                VS(6)
                Txt("字体", 13, color = go.muted)
                VS(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FONT_NAMES.forEachIndexed { i, n ->
                        GoButton(n, primary = vm.floatFont == i) { vm.updateFloat(font = i) }
                    }
                }
            }
            VS(16)

            // ---------- 下载 ----------
            Txt("下载", 15, weight = FontWeight.SemiBold)
            VS(8)
            Txt("保存位置：${LocalLib.dirText()}（音乐 / 封面 / 歌词 / 歌曲信息）", 12, color = go.muted)
            VS(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("已下载 ${vm.downloads.size} 首", icon = Icons.Outlined.Download) { vm.showDownloads = true }
                GoButton("清空下载", icon = Icons.Outlined.Delete) { confirmClear = true }
            }
            VS(8)
            Txt("GoMusic Android ${BuildConfig.VERSION_NAME}", 12, color = go.muted)
            VS(8)
        }
    }
    if (confirmClear) {
        ConfirmDialog("清空全部已下载歌曲？", { confirmClear = false }) {
            confirmClear = false
            vm.clearDownloads()
        }
    }
}

@Composable
fun DownloadsSheet(vm: MainViewModel) {
    val go = LocalGo.current
    GoSheet({ vm.showDownloads = false }) {
        Txt("已下载（${vm.downloads.size}）", 16, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), FontWeight.SemiBold)
        if (vm.downloads.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) { Txt("还没有下载歌曲", 14, color = go.muted) }
        } else {
            val list = remember(vm.downloads) { vm.downloads.sortedByDescending { it.time } }
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                items(list, key = { it.ssid }, contentType = { "dl" }) { rec ->
                    val song = Song(rec.ssid, rec.title, rec.artist, rec.album, rec.cover.ifBlank { null })
                    Row(
                        Modifier.fillMaxWidth().height(64.dp)
                            .clickable { vm.playSongs(listOf(song), 0) }
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(song.coverUrl, Modifier.size(48.dp), 8.dp)
                        HS(12)
                        Column(Modifier.weight(1f)) {
                            Txt(rec.title, 15, weight = FontWeight.Medium, maxLines = 1)
                            Txt(
                                "${rec.artist} · ${rec.album} · ${qualityLabel(rec.quality)}",
                                12, color = go.muted, maxLines = 1,
                            )
                        }
                        IconBtn(Icons.Outlined.Refresh, "重新下载") { vm.redownload(rec.ssid) }
                        IconBtn(Icons.Outlined.Delete, "删除下载") { vm.deleteDownload(rec.ssid) }
                    }
                }
            }
        }
    }
}
