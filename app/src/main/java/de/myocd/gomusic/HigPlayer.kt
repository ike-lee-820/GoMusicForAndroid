package de.myocd.gomusic

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 从播放器里点「查看专辑 / 歌手」时自动收起，让二级页直接露出来 */
private fun MainViewModel.openAlbumAndClose(s: Song) {
    openLocalAlbumIntro(s.artist, s.album, s.coverUrl, listOf(s))
    showPlayer = false
}

private fun MainViewModel.openArtistAndClose(s: Song) {
    openArtistByName(s.artist)
    showPlayer = false
}

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
    Box(
        Modifier.fillMaxSize().background(go.bg)
            // 背景空白区吃掉触摸：否则会穿透到下面的主页列表 / 底栏，
            // 点一下背景就误触切歌或跳页（用户说的「点击背景会错乱」）。
            // 子组件优先命中，这里只处理没被任何控件接住的事件。
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
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
        // 图 17 样式的更多操作表：盖在播放器最上层
        if (vm.showMoreSheet) PlayerMoreSheet(vm, s) { vm.showMoreSheet = false }
    }
}

// =====================================================================
// 图 1：封面页
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverPane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
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
                        if (s != null) vm.openAlbumAndClose(s)
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
                                vm.openAlbumAndClose(s)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("查看歌手", fontSize = 15.sp) },
                            onClick = { infoMenu = false; vm.openArtistAndClose(s) },
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
                FavoriteStar(vm, s?.ssid, go, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            HigRoundBtn(go) {
                Icon(
                    Icons.Outlined.MoreVert, "更多", tint = go.on,
                    modifier = Modifier.size(22.dp).clickable { vm.showMoreSheet = true },
                )
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

/**
 * 星标：**未收藏一定是空心**（Outlined），收藏后才实心主色。
 * 封面页、歌词页、队列页、更多列表共用这一处，状态不会再各画各的。
 */
@Composable
private fun FavoriteStar(
    vm: MainViewModel,
    ssid: String?,
    go: GoColors,
    modifier: Modifier = Modifier,
) {
    val fav = vm.isFavorite(ssid)
    Icon(
        if (fav) Icons.Filled.Star else Icons.Outlined.Star,
        if (fav) "移出我的喜欢" else "加入我的喜欢",
        tint = if (fav) go.primary else go.on,
        modifier = modifier.clickable { vm.toggleFavorite(ssid) },
    )
}

private data class MoreRow(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val action: () -> Unit,
)

/**
 * 26.11.45：更多列表改成图 17 的样式——
 * 半透明遮罩 + 底部圆角卡片 + 歌曲信息头（封面 / 歌名 / 歌手主色 / 专辑）+ 主色图标行。
 * 「不看功能」：功能仍是原来的全部能力，只是排版按图重做。
 */
@Composable
private fun PlayerMoreSheet(vm: MainViewModel, s: Song?, onClose: () -> Unit) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    BackHandler(enabled = true) { onClose() }

    val lyrics = vm.wordLyric?.lines?.joinToString("\n") { it.text }.orEmpty()
    val credits = vm.wordLyric?.lines?.filter { it.meta }
        ?.joinToString("\n") { it.text }.orEmpty()
    val fav = vm.isFavorite(s?.ssid)

    val rows = buildList<MoreRow> {
        add(
            MoreRow(
                if (fav) "取消喜爱" else "喜爱",
                if (fav) Icons.Filled.Star else Icons.Outlined.Star,
            ) { vm.toggleFavorite(s?.ssid) }
        )
        if (s != null) {
            add(MoreRow("分享歌曲", Icons.Outlined.Share) {
                shareText(ctx, "${s.title} - ${s.artist} ${vm.songLink(s.ssid)}")
            })
            add(MoreRow("分享歌词…", Icons.Outlined.ChatBubbleOutline) {
                shareText(ctx, lyrics.ifBlank { "暂无歌词" })
            })
            add(MoreRow("查看制作人员", Icons.Outlined.Info) {
                if (credits.isBlank()) vm.toast("暂无制作人员信息") else shareText(ctx, credits)
            })
            add(MoreRow("下载", Icons.Outlined.Download) { vm.download(listOf(s)) })
            add(MoreRow("加入歌单", Icons.AutoMirrored.Outlined.PlaylistAdd) {
                vm.pick(listOf(s.ssid), s.title, s.coverUrl)
            })
            add(MoreRow("播放 MV", Icons.Outlined.PlayArrow) { vm.playMv() })
            add(MoreRow("翻译歌词", Icons.Outlined.Translate) { vm.translateLyric() })
            add(MoreRow("查看专辑", Icons.Outlined.Album) { vm.openAlbumAndClose(s) })
            add(MoreRow("查看歌手", Icons.Outlined.Person) { vm.openArtistAndClose(s) })
        }
        add(MoreRow("均衡器", Icons.Outlined.Equalizer) { vm.showEq = true })
        add(MoreRow("3D 环绕", Icons.Outlined.MusicNote) { vm.showSurround = true })
        add(MoreRow("录制视频", Icons.Outlined.Videocam) { vm.showRecorder = true })
        add(MoreRow("已下载", Icons.Outlined.CloudQueue) { vm.showDownloads = true })
        add(MoreRow("播放历史", Icons.Outlined.History) { vm.showHistory = true })
        add(MoreRow("检查更新", Icons.Outlined.Refresh) { vm.checkUpdate(false) })
        add(MoreRow("设置", Icons.Outlined.Settings) { vm.showSettings = true })
    }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点空白处关闭
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onClose() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(go.surface)
                .navigationBarsPadding(),
        ) {
            // 信息头（图 17 上半部）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(s?.coverUrl, Modifier.size(84.dp), 8.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s?.title.orEmpty(), fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, color = go.on, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s?.artist.orEmpty(), fontSize = 17.sp,
                        color = go.primary, maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s?.album.orEmpty(), fontSize = 15.sp,
                        color = go.muted, maxLines = 1,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().height(58.dp)
                            .clickable { onClose(); r.action() }
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            r.icon, r.label, tint = go.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(r.label, fontSize = 17.sp, color = go.on)
                    }
                    Box(
                        Modifier.fillMaxWidth().height((0.5f).dp)
                            .padding(start = 20.dp).background(go.separator),
                    )
                }
            }
        }
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
        Box(Modifier.size(44.dp).clickable { vm.gotoPlayerPage(PLAYER_LYRICS) }, contentAlignment = Alignment.Center) {
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
        ) { vm.gotoPlayerPage(PLAYER_QUEUE) }
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
        FavoriteStar(vm, s?.ssid, go, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.MoreVert, "更多", tint = go.on,
            modifier = Modifier.size(22.dp).clickable { vm.showMoreSheet = true },
        )
    }
}

// =====================================================================
// 图 2 / 3：歌词页
//
// 26.11.44 修正：上下滑交还给「歌词栏」自己滚动浏览，不再拿整页的
// 拖动去切播放栏显隐。之前那个 pointerInput 会吞掉所有指针事件，
// 导致歌词既不能滚也不能点（用户报的「无法点击歌词跳转」）。
// 播放栏改为常驻。
// =====================================================================

@Composable
private fun LyricsPane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
    Column(Modifier.fillMaxSize()) {
        InfoHeader(vm, s, go) { vm.gotoPlayerPage(PLAYER_COVER) }
        Box(Modifier.weight(1f).fillMaxWidth()) { WordLyricsList(vm, go) }
        PlayerBottomBar(vm, go)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WordLyricsList(vm: MainViewModel, go: GoColors) {
    // 所有来源（/lyric/new 的 yrc、退回的老 /lyric、本站、本地、离线）
    // 都已在 ViewModel 里统一成 WordLyric，因此这里只有一套大字渲染，
    // 老歌词不再掉回旧的 LyricsList 小字列表。
    val w = vm.wordLyric
    if (w == null || w.lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", fontSize = 17.sp, color = go.muted)
        }
        return
    }
    val state = rememberLazyListState()
    // 逐帧位置：ViewModel 的 250ms 轮询只够进度条，逐字卡拉 OK 必须每帧一个位置，
    // 否则一次跳 250ms，几个字一起变色，看起来就是「生硬跳动」。
    val framePos = remember { mutableLongStateOf(vm.positionMs) }
    val userScrollUntil = remember { mutableLongStateOf(0L) }
    LaunchedEffect(w) {
        var lastActive = -1
        while (true) {
            if (vm.isPlaying) withFrameNanos { it } else delay(100)
            framePos.longValue = vm.controller?.currentPosition?.coerceAtLeast(0) ?: vm.positionMs
            // 用户手动滑歌词后 4 秒内不自动抢回当前行
            if (state.isScrollInProgress) {
                userScrollUntil.longValue = System.currentTimeMillis() + 4000
            }
            val a = w.activeIndex(framePos.longValue)
            if (a != lastActive) {
                lastActive = a
                if (a >= 0 && System.currentTimeMillis() > userScrollUntil.longValue) {
                    launch { state.animateScrollToItem(a) }
                }
            }
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(w.lines) { i, line ->
            val isActive by remember(w) {
                derivedStateOf { w.activeIndex(framePos.longValue) == i }
            }
            val alpha = if (isActive) 1f else 0.32f
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    // 点击歌词跳到这一行；制作信息行不跳
                    .clickable(enabled = !line.meta) { vm.seekTo(line.startMs) },
            ) {
                if (line.meta) {
                    Text(line.text, fontSize = 12.sp, color = go.muted.copy(alpha = 0.6f))
                } else {
                    WordLineText(line, framePos, go, alpha)
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

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun mixColor(a: Color, b: Color, t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * x,
        green = a.green + (b.green - a.green) * x,
        blue = a.blue + (b.blue - a.blue) * x,
        alpha = a.alpha + (b.alpha - a.alpha) * x,
    )
}

/**
 * 单字连续流动：每个字按自己的播放进度 t 做平滑颜色插值，
 * 而不是 PENDING / ACTIVE / SUNG 三态硬切。相邻字时间错开，
 * 看起来就是一道光从字里扫过去；正在唱的那个字再叠一条
 * 随 progress 从左往右推进的擦除高光带。
 *
 * 字号固定 26sp：字号随进度变会让 Text 每帧重新测量、整行抖动。
 */
@Composable
private fun WordLineText(line: WordLine, posState: LongState, go: GoColors, alpha: Float) {
    val posMs = posState.longValue
    val frames = WordLyricParser.frameAt(line, posMs)

    val dim = go.on.copy(alpha = alpha * 0.34f)
    val lit = go.primary.copy(alpha = alpha)
    val sung = go.on.copy(alpha = alpha)
    val text: AnnotatedString = buildAnnotatedString {
        frames.forEach { f ->
            val t = when (f.phase) {
                CharPhase.PENDING -> 0f
                CharPhase.ACTIVE -> f.progress
                CharPhase.SUNG -> 1f
            }
            val e = smoothstep(t)
            // 暗 → 主色 → 唱完的白，三段都是连续插值（字本身逐个点亮）
            val c = if (e < 0.72f) mixColor(dim, lit, e / 0.72f)
            else mixColor(lit, sung, (e - 0.72f) / 0.28f)
            pushStyle(SpanStyle(color = c, fontWeight = FontWeight.SemiBold, fontSize = 26.sp))
            append(f.char.text)
            pop()
        }
    }

    // 整行进度：光从左往右连续扫，不再按「当前是哪个字」跳。
    val lineProgress = (
        (posMs - line.startMs).toFloat() / line.durMs.coerceAtLeast(1).toFloat()
        ).coerceIn(0f, 1f)

    Text(
        text,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = go.on.copy(alpha = alpha),
        textAlign = TextAlign.Start,
        // 26.11.45：滚动光必须「在字上面」，不是字后面的一根棍子。
        // drawWithContent 先画文字，再用 SrcAtop 叠一道窄亮带：
        // 源只保留在目标已有像素（字形）上，所以光只出现在笔画里，
        // 折行、字距、不同语言都能连续流过，不会画出背景矩形。
        modifier = Modifier.fillMaxWidth().drawWithContent {
            drawContent()
            if (lineProgress <= 0f || lineProgress >= 1f) return@drawWithContent
            val sweep = size.width * lineProgress
            val from = (sweep - 30.dp.toPx()).coerceAtLeast(0f)
            val to = (sweep + 10.dp.toPx()).coerceAtMost(size.width)
            if (to <= from) return@drawWithContent
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        go.primary.copy(alpha = 0f),
                        go.primary.copy(alpha = 0.85f * alpha),
                        go.primary.copy(alpha = 0f),
                    ),
                    startX = from,
                    endX = to,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                blendMode = BlendMode.SrcAtop,
            )
        },
    )
}

// =====================================================================
// 图 4：播放列表页
// =====================================================================

@Composable
private fun QueuePane(vm: MainViewModel, s: Song?) {
    val go = LocalGo.current
    Column(Modifier.fillMaxSize()) {
        InfoHeader(vm, s, go) { vm.gotoPlayerPage(PLAYER_COVER) }
        Spacer(Modifier.height(12.dp))
        // Segmented：随机 / 列表循环 / 单曲循环。
        // 原来 Row 固定 44dp 又给文字上下各 10dp padding，行高被压到 38dp，
        // 三段文字竖着被裁掉一截；改成最小高度自适应 + 内容居中。
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(go.surfaceHi.copy(alpha = 0.45f))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("随机", "列表循环", "单曲循环").forEachIndexed { i, label ->
                // playMode: 0 列表循环 1 随机 2 单曲循环
                val mode = when (i) { 0 -> 1; 1 -> 0; else -> 2 }
                val sel = vm.playMode == mode
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) go.on.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { vm.selectPlayMode(mode) }
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) go.primary else go.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
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
