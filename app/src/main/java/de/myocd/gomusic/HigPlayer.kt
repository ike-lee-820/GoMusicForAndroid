package de.myocd.gomusic

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 26.11.44：按用户 4 张截图重做的播放器。
 *
 * - 图 1 封面页：1:1 大封面、跑马灯歌名、星标/更多、负剩余时间、三大控制键、底部三键
 * - 图 2 歌词页：顶部信息区点击回封面、逐字卡拉 OK、下滑隐藏播放栏 / 上滑恢复（1A）
 * - 图 4 队列页：Segmented 三模式 +「继续播放」+ 队列
 * - 5A：信息区长按 = Context Menu
 * - 背景只有一张模糊专辑封面，不拉伸、不叠第二张
 */
@Composable
fun HigPlayerRoot(vm: MainViewModel) {
    val s = vm.currentSong
    val go = LocalGo.current
    val bgModel = remember(s?.coverUrl) {
        imageModel(ImageCache.cached(s?.coverUrl) ?: s?.coverUrl)
    }
    Box(Modifier.fillMaxSize().background(go.bg)) {
        if (bgModel != null) {
            AsyncImage(
                bgModel, null,
                Modifier.fillMaxSize().blur(40.dp),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
        // 亮/暗只换叠加色，图层结构不变
        Box(Modifier.fillMaxSize().background(go.scrim))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            when (vm.playerPage) {
                PLAYER_LYRICS -> LyricsPane(vm, s)
                PLAYER_QUEUE -> QueuePane(vm, s)
                else -> CoverPane(vm, s)
            }
        }
    }
}

// =====================================================================
// 图 1：封面页
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverPane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        // 收起
        Icon(
            Icons.Outlined.KeyboardArrowDown, "收起播放页", tint = go.on,
            modifier = Modifier.size(28.dp).clickable { vm.showPlayer = false }.padding(4.dp),
        )
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth * 0.74f, maxHeight * 0.92f)
            if (s != null) {
                Cover(
                    s.coverUrl,
                    Modifier.width(side).aspectRatio(1f),
                    8.dp,
                )
            }
        }
        // 信息行：跑马灯 + 星标 + 更多。点击进专辑页，长按出 5A Context Menu
        var infoMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).combinedClickable(
                    onClick = {
                        if (s != null) vm.openLocalAlbumIntro(s.artist, s.album, s.coverUrl, listOf(s))
                    },
                    onLongClick = { infoMenu = true },
                ),
            ) {
                Column {
                    Text(
                        s?.title.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = go.on, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s?.artist.orEmpty(), fontSize = 17.sp, color = go.muted, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().basicMarquee(),
                    )
                }
                DropdownMenu(expanded = infoMenu, onDismissRequest = { infoMenu = false }) {
                    if (s != null) {
                        DropdownMenuItem(
                            text = { Text("查看专辑", fontSize = 15.sp) },
                            onClick = {
                                infoMenu = false
                                vm.openLocalAlbumIntro(s.artist, s.album, s.coverUrl, listOf(s))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("查看歌手", fontSize = 15.sp) },
                            onClick = { infoMenu = false; vm.openArtistByName(s.artist) },
                        )
                        DropdownMenuItem(
                            text = { Text("加入歌单", fontSize = 15.sp) },
                            onClick = {
                                infoMenu = false
                                vm.pick(listOf(s.ssid), s.title, s.coverUrl)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (vm.isFavorite(s.ssid)) "移出我的喜欢" else "加入我的喜欢",
                                    fontSize = 15.sp,
                                )
                            },
                            onClick = { infoMenu = false; vm.toggleFavorite(s.ssid) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            HigRoundBtn(go) {
                Icon(
                    if (vm.isFavorite(s?.ssid)) Icons.Filled.Star else Icons.Outlined.Star,
                    "加入我的喜欢",
                    tint = if (vm.isFavorite(s?.ssid)) go.primary else go.on,
                    modifier = Modifier.size(22.dp).clickable { vm.toggleFavorite(s?.ssid) },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box {
                HigRoundBtn(go) {
                    Icon(
                        Icons.Outlined.MoreVert, "更多", tint = go.on,
                        modifier = Modifier.size(22.dp).clickable { more = true },
                    )
                }
                PlayerMoreMenu(vm, s, more) { more = false }
            }
        }
        Spacer(Modifier.height(22.dp))
        ProgressLine(vm, go)
        Spacer(Modifier.height(18.dp))
        MainControls(vm, go)
        Spacer(Modifier.height(26.dp))
        BottomThreeKeys(vm, go)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HigRoundBtn(go: GoColors, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(go.surfaceHi.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 所有原来散在控制栏第一行的功能，一项不丢 */
@Composable
private fun PlayerMoreMenu(vm: MainViewModel, s: Song?, expanded: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
        val item: @Composable (String, () -> Unit) -> Unit = { label, action ->
            DropdownMenuItem(
                text = { Text(label, fontSize = 15.sp) },
                onClick = { onClose(); action() },
            )
        }
        if (s != null) {
            item("分享") { shareText(ctx, "${s.title} - ${s.artist} ${vm.songLink(s.ssid)}") }
            item("下载") { vm.download(listOf(s)) }
            item("加入歌单") { vm.pick(listOf(s.ssid), s.title, s.coverUrl) }
            item("播放 MV") { vm.playMv() }
            item("翻译歌词") { vm.translateLyric() }
            item("查看专辑") { vm.openLocalAlbumIntro(s.artist, s.album, s.coverUrl, listOf(s)) }
            item("查看歌手") { vm.openArtistByName(s.artist) }
        }
        item("均衡器") { vm.showEq = true }
        item("3D 环绕") { vm.showSurround = true }
        item("录制视频") { vm.showRecorder = true }
        item("已下载") { vm.showDownloads = true }
        item("播放历史") { vm.showHistory = true }
        item("检查更新") { vm.checkUpdate(false) }
        item("设置") { vm.showSettings = true }
    }
}

// =====================================================================
// 共用：进度 / 控制 / 底部三键
// =====================================================================

@Composable
private fun ProgressLine(vm: MainViewModel, go: GoColors) {
    val left = fmt(vm.positionMs)
    val right = "-" + fmt((vm.durationMs - vm.positionMs).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth()) {
        SimpleSeekBar(
            vm.positionMs, vm.durationMs,
            Modifier.fillMaxWidth().height(32.dp),
        ) { vm.seekTo(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(left, fontSize = 13.sp, color = go.muted)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.quality == "lossless") "无损" else vm.quality,
                fontSize = 12.sp, color = go.on,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(go.surfaceHi.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(right, fontSize = 13.sp, color = go.muted)
        }
    }
}

@Composable
private fun MainControls(vm: MainViewModel, go: GoColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PIconBtn("prev", "上一首", size = 44.dp, tint = go.on, box = 56.dp) { vm.prev() }
        PIconBtn(
            if (vm.isPlaying) "pause" else "play",
            if (vm.isPlaying) "暂停" else "播放",
            size = 40.dp, tint = go.bg, bg = go.on, box = 72.dp,
        ) { vm.togglePlay() }
        PIconBtn("next", "下一首", size = 44.dp, tint = go.on, box = 56.dp) { vm.next() }
    }
}

@Composable
private fun BottomThreeKeys(vm: MainViewModel, go: GoColors) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clickable { vm.setPlayerPage(PLAYER_LYRICS) }, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.ChatBubbleOutline, "歌词",
                tint = if (vm.playerPage == PLAYER_LYRICS) go.primary else go.on,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(Modifier.size(44.dp).clickable { vm.showCast = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Cast, "投屏", tint = go.on, modifier = Modifier.size(24.dp))
        }
        PIconBtn(
            "queue", "播放列表", size = PlayerIconSpec.Func,
            tint = if (vm.playerPage == PLAYER_QUEUE) go.primary else go.on,
        ) { vm.setPlayerPage(PLAYER_QUEUE) }
    }
}

@Composable
private fun PlayerBottomBar(vm: MainViewModel, go: GoColors) {
    Column(Modifier.fillMaxWidth()) {
        ProgressLine(vm, go)
        Spacer(Modifier.height(12.dp))
        MainControls(vm, go)
        Spacer(Modifier.height(18.dp))
        BottomThreeKeys(vm, go)
    }
}

/** 歌词页 / 队列页共用的顶部信息区：点一下回封面页 */
@Composable
private fun InfoHeader(vm: MainViewModel, s: Song?, go: GoColors, onBack: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onBack() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(s?.coverUrl, Modifier.size(44.dp), 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s?.title.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = go.on, maxLines = 1)
            Text(s?.artist.orEmpty(), fontSize = 14.sp, color = go.muted, maxLines = 1)
        }
        Icon(
            if (vm.isFavorite(s?.ssid)) Icons.Filled.Star else Icons.Outlined.Star,
            "我的喜欢",
            tint = if (vm.isFavorite(s?.ssid)) go.primary else go.on,
            modifier = Modifier.size(22.dp).clickable { vm.toggleFavorite(s?.ssid) },
        )
        Spacer(Modifier.width(8.dp))
        Box {
            Icon(
                Icons.Outlined.MoreVert, "更多", tint = go.on,
                modifier = Modifier.size(22.dp).clickable { more = true },
            )
            PlayerMoreMenu(vm, s, more) { more = false }
        }
    }
}

// =====================================================================
// 图 2 / 3：歌词页（逐字 + 1A 手势）
// =====================================================================

@Composable
private fun LyricsPane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectVerticalDragGestures { _, dy ->
                if (dy > 14) vm.setLyricBarHidden(true)
                else if (dy < -14) vm.setLyricBarHidden(false)
            }
        },
    ) {
        InfoHeader(vm, s, go) { vm.setPlayerPage(PLAYER_COVER); vm.setLyricBarHidden(false) }
        Box(Modifier.weight(1f).fillMaxWidth()) { WordLyricsList(vm, go) }
        if (!vm.lyricBarHidden) {
            PlayerBottomBar(vm, go)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WordLyricsList(vm: MainViewModel, go: GoColors) {
    val w = vm.wordLyric
    val pos = vm.positionMs
    if (w == null || !w.hasWordTiming) {
        // 老歌词 / 纯文本：沿用原来的行级列表，保证任何回退路径都有词显示
        Box(Modifier.fillMaxSize()) { LyricsList(vm) }
        return
    }
    val active = w.activeIndex(pos)
    val state = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) state.animateScrollToItem(active.coerceAtLeast(0))
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(w.lines) { i, line ->
            val isActive = i == active
            val alpha = if (isActive) 1f else 0.32f
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (line.meta) {
                    Text(line.text, fontSize = 12.sp, color = go.muted.copy(alpha = 0.6f))
                } else {
                    WordLineText(line, pos, go, alpha)
                    if (!line.trans.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            line.trans, fontSize = 13.sp,
                            color = go.muted.copy(alpha = alpha),
                        )
                    }
                    if (!line.roman.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            line.roman, fontSize = 12.sp,
                            color = go.muted.copy(alpha = alpha * 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/** 每字 PENDING / ACTIVE(progress) / SUNG 三段着色，日文+拉丁混排不会错位 */
@Composable
private fun WordLineText(line: WordLine, posMs: Long, go: GoColors, alpha: Float) {
    val frames = WordLyricParser.frameAt(line, posMs)
    val text: AnnotatedString = buildAnnotatedString {
        frames.forEach { f ->
            val c = when (f.phase) {
                CharPhase.SUNG -> go.on.copy(alpha = alpha)
                CharPhase.ACTIVE -> go.primary.copy(alpha = alpha)
                CharPhase.PENDING -> go.on.copy(alpha = alpha * 0.42f)
            }
            pushStyle(
                SpanStyle(
                    color = c,
                    fontWeight = if (f.phase == CharPhase.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = if (f.phase == CharPhase.ACTIVE) 27.sp else 26.sp,
                )
            )
            append(f.char.text)
            pop()
        }
    }
    Text(
        text,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = go.on.copy(alpha = alpha),
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

// =====================================================================
// 图 4：播放列表页
// =====================================================================

@Composable
private fun QueuePane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
    Column(Modifier.fillMaxSize()) {
        InfoHeader(vm, s, go) { vm.setPlayerPage(PLAYER_COVER) }
        Spacer(Modifier.height(12.dp))
        // Segmented：随机 / 列表循环 / 单曲循环
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(go.surfaceHi.copy(alpha = 0.45f))
                .padding(3.dp),
        ) {
            listOf("随机", "列表循环", "单曲循环").forEachIndexed { i, label ->
                // playMode: 0 列表循环 1 随机 2 单曲循环
                val mode = when (i) { 0 -> 1; 1 -> 0; else -> 2 }
                val sel = vm.playMode == mode
                Text(
                    label, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) go.primary else go.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) go.on.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { vm.setPlayMode(mode) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("继续播放", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = go.on)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(vm.queue) { i, e ->
                val cur = i == vm.curIndex
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .clickable { vm.playIndex(i) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(e.cover, Modifier.size(48.dp), 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title, fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (cur) go.primary else go.on, maxLines = 1,
                        )
                        Text(e.artist, fontSize = 13.sp, color = go.muted, maxLines = 1)
                    }
                    Icon(
                        Icons.Outlined.MoreVert, "拖动排序", tint = go.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).padding(start = 60.dp).background(go.separator))
            }
        }
        PlayerBottomBar(vm, go)
        Spacer(Modifier.height(12.dp))
    }
}
