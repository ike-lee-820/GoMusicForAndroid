package de.myocd.gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 网易云登录 / 注册弹层：二维码登录 或 短信验证码登录注册 */
@Composable
fun NmLoginSheet(vm: MainViewModel) {
    val go = LocalGo.current
    var tab by remember { mutableIntStateOf(0) }
    GoSheet({
        vm.setNmLogin(false)
        vm.stopQrLogin()
    }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("网易云账号", 16, Modifier.weight(1f), FontWeight.SemiBold)
                IconBtn(Icons.Outlined.Close, "关闭") {
                    vm.setNmLogin(false)
                    vm.stopQrLogin()
                }
            }
            Txt("登录后才会搜索网易云曲库；未登录时只使用本站曲库", 12, color = go.muted)
            Spacer(Modifier.height(12.dp))
            Row {
                GoButton("扫码登录", primary = tab == 0) { tab = 0 }
                Spacer(Modifier.width(8.dp))
                GoButton("短信登录 / 注册", primary = tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(14.dp))
            if (tab == 0) QrLogin(vm) else PhoneLogin(vm)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QrLogin(vm: MainViewModel) {
    val go = LocalGo.current
    var started by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!started) {
            started = true
            vm.startQrLogin()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.stopQrLogin() }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val img = vm.nmQr
        if (img != null) {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Cover(img, Modifier.fillMaxSize(), 8.dp)
            }
        } else {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(go.surfaceHi),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = go.primary, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Txt(
            vm.nmQrTip, 13,
            Modifier.clickable { vm.startQrLogin() },
            color = if (vm.nmQr == null) go.primary else go.muted,
        )
    }
}

@Composable
private fun PhoneLogin(vm: MainViewModel) {
    val go = LocalGo.current
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    val reg = nick.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        GoField(phone, { phone = it }, "手机号")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GoField(code, { code = it }, "验证码") }
            Spacer(Modifier.width(8.dp))
            GoButton(if (vm.nmBusy) "发送中" else "获取验证码", enabled = !vm.nmBusy) { vm.nmSendCode(phone) }
        }
        Spacer(Modifier.height(8.dp))
        GoField(nick, { nick = it }, "昵称（留空为登录，填写即注册）")
        if (reg) {
            Spacer(Modifier.height(8.dp))
            GoField(pwd, { pwd = it }, "密码（注册时必填）")
        }
        Spacer(Modifier.height(14.dp))
        GoButton(
            if (reg) "注册并登录" else "登录",
            primary = true,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            enabled = !vm.nmBusy,
        ) { vm.nmLoginPhone(phone, code, pwd, nick.trim()) }
        Spacer(Modifier.height(8.dp))
        Txt("验证码由网易云下发，注册即表示同意网易云的用户协议", 12, color = go.muted)
    }
}

@Composable
private fun GoField(value: String, onValue: (String) -> Unit, hint: String) {
    val go = LocalGo.current
    TextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        placeholder = { Txt(hint, 14, color = go.muted) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = go.surfaceHi,
            unfocusedContainerColor = go.surfaceHi,
            disabledContainerColor = go.surfaceHi,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = go.on,
            unfocusedTextColor = go.on,
            cursorColor = go.primary,
        ),
    )
}

/**
 * 触底加载下一页（详情页用）。
 * 监听放在列表外面常驻，避免放在 item 里被回收后协程取消。
 */
@Composable
private fun PageReachEnd(
    hasMore: Boolean,
    lastIndex: () -> Int?,
    total: () -> Int,
    onReachEnd: () -> Unit,
) {
    if (!hasMore) return
    LaunchedEffect(hasMore) {
        snapshotFlow {
            val last = lastIndex()
            val t = total()
            if (last != null && t > 0) last >= t - 3 else false
        }.collect { atEnd -> if (atEnd) onReachEnd() }
    }
}

@Composable
private fun PageLoadingMore(text: String) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = go.primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Txt(text, 12, color = go.muted)
    }
}

/** 网易云专辑 / 歌手页。歌手有「歌曲」「专辑」两栏，各自滑到底加载下一页 */
@Composable
fun NmSongsPage(vm: MainViewModel, s: Screen.NmSongs) {
    val go = LocalGo.current
    val isArtist = s.kind == "artist"
    val cover = vm.nmPageCover ?: s.cover
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 显示时确保数据与栈顶页面对应：从歌手页点进专辑 / 返回上层时都要校正，
    // 否则会出现「请求成功但不显示，要再进一次才有」
    LaunchedEffect(s.kind, s.id) { vm.ensureNmPage(s) }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverWithSource(cover, Modifier.size(88.dp), 16.dp, source = SRC_NETEASE)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(titleWithSource(s.title, SRC_NETEASE), 18, weight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    // 专辑显示歌手名（拿到前显示「加载中」），歌手显示「网易云歌手」
                    Txt(
                        if (isArtist) "网易云歌手" else vm.nmPageSubtitle.ifBlank { "网易云专辑" },
                        12, color = go.muted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                GoButton("播放全部", primary = true, enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.playSongs(vm.nmPageSongs, 0)
                }
                Spacer(Modifier.width(8.dp))
                GoButton("下载全部", enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.download(vm.nmPageSongs)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 歌单操作：整页新建歌单，或选已有歌单加入
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("新建歌单并添加", enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.createFromNmPage(s.title, cover)
                }
                GoButton("添加至歌单", enabled = vm.nmPageSongs.isNotEmpty()) {
                    vm.pick(vm.nmPageSongs.map { it.ssid }, s.title, cover)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (isArtist) {
                // 歌手：歌曲 / 专辑 两栏
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoButton("歌曲", primary = vm.nmPageTab == 0) { vm.chooseNmPageTab(0) }
                    GoButton("专辑", primary = vm.nmPageTab == 1) { vm.chooseNmPageTab(1) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (vm.nmPageLoading && vm.nmPageTab == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = go.primary)
            }
        } else if (isArtist && vm.nmPageTab == 1) {
            // ---------- 歌手：专辑栏 ----------
            if (vm.nmPageAlbumsLoading && vm.nmPageAlbums.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = go.primary)
                }
            } else {
                val albums = vm.nmPageAlbums
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (albums.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Txt("没有拿到专辑", 14, color = go.muted)
                            }
                        }
                    }
                    itemsIndexed(
                        albums,
                        key = { i, g -> "nm" + g.nmId + "#" + i },
                    ) { _, g ->
                        GroupRow(
                            titleWithSource(g.title, g.source), g.subtitle, g.cover,
                            source = g.source,
                            onClick = { vm.openNmGroup(g) },
                            onAdd = null,
                        )
                    }
                    if (vm.nmPageAlbumsHasMore) item { PageLoadingMore("正在加载更多专辑") }
                }
            }
        } else {
            // ---------- 歌曲栏 ----------
            val list = vm.nmPageSongs
            PageReachEnd(
                hasMore = vm.nmPageSongsHasMore && !vm.nmPageLoadingMore,
                lastIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
                total = { listState.layoutInfo.totalItemsCount },
                onReachEnd = { vm.loadMoreNmPage(0) },
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(list, key = { i, it -> "${it.ssid}#$i" }) { i, song ->
                    SongRow(
                        song, song.ssid == vm.curSsid, vm.isPlaying,
                        downloaded = vm.isDownloaded(song.ssid),
                        downloading = song.ssid in vm.downloading,
                        onClick = { vm.playSongs(list, i) },
                        onAdd = { vm.pick(listOf(song.ssid), song.title, song.coverUrl) },
                        onDownload = { vm.download(listOf(song)) },
                        onQueue = { vm.addToQueue(song) },
                    )
                }
                if (vm.nmPageSongsHasMore) item { PageLoadingMore("正在加载更多歌曲") }
            }
        }
    }
}
