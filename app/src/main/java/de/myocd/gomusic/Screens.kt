@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package de.myocd.gomusic

import kotlin.math.abs
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.asin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.QrCode
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path

private const val PI_F = 3.14159265f

/**
 * 相邻两行的纵向间距（dp）——这就是「行距」。
 * 与可见行数此消彼长：间距越大，一屏能放的歌词越少。
 */
private const val ARC_ROW_SPACING_DP = 63f

/**
 * 显示译文时行距的放大倍数。
 * 一行变成「原句 + 译文」两块，比原来高一截，行距不放大的话相邻两行会叠住。
 */
private const val ARC_ROW_SPACING_TRANS_MUL = 1.6f

/**
 * 最外侧那行的最大倾斜角（度）。
 *
 * 圆心半径不再拍脑袋给倍数，而是由这个角度反推：
 *   r = 可见行数 × 行距 ÷ 最大倾斜角 × [ARC_RADIUS_BOOST]
 * 这样不管行距、屏幕怎么变，弧的弯曲程度始终稳定在能看清的一档，
 * 也不会因为半径过大把弧压成一条直线。
 */
private const val ARC_MAX_TILT_DEG = 20f

/**
 * 圆心远近的倍数：1.0 = 按倾角刚好算出的半径（最外侧行倾角 = [ARC_MAX_TILT_DEG]）。
 *
 * 注意方向：**越大圆心越远，弧越平**。之前这里是 1.3，弧被压得接近直线，
 * 看着就像圆心跑到了很远的地方。取 0.8 把圆心拉回来，
 * 最外侧行倾角约 25°，弧看得出来。
 */
private const val ARC_RADIUS_BOOST = 0.8f

/**
 * 横屏专用：行距比竖屏小，一屏能看到的歌词行数更多。
 * 控制区移到顶部后歌词区变宽了，行距缩小也不会显得挤。
 */
private const val ARC_ROW_SPACING_DP_WIDE = 78f

/** 横屏歌词字号：当前句 / 其他行，比竖屏大一号 */
private const val ARC_CUR_SP_WIDE = 23f
private const val ARC_OTHER_SP_WIDE = 18f

/**
 * 横屏专用圆心倍数。
 *
 * 之前是 3.2 —— 圆心越远弧越平，3.2 倍把弧压成了几乎一条直线，
 * 这就是「圆心过远」的由来。取 0.7 把圆心拉回来，弧的弯曲看得出来。
 */
private const val ARC_RADIUS_BOOST_WIDE = 0.7f

/** 横屏至少显示的行数（当前句上下各这么多），保证一屏能看到足够歌词 */
private const val ARC_MIN_SPAN_WIDE = 4

/**
 * 横屏歌词整体上移的距离（dp）。
 *
 * 歌词容器是垂直居中的，但内容区带 statusBars / navigationBars 留白，
 * 容器中心会比屏幕中线偏下，当前句看着就不在中线上。
 * 这里把差值补回来，让当前句对齐屏幕中线；觉得偏高偏低就调这个数。
 */
private const val ARC_LYRIC_LIFT_WIDE = 14f

/**
 * 拖动灵敏度。行距越大，滑过一整行需要的手指距离就越长，
 * 这里放大一点让滑动跟手（1.0 = 手指移动多少、歌词就移动多少）。
 */
private const val ARC_DRAG_SENSITIVITY = 2.5f

/**
 * 完全透明的点击拦截：吃掉点击但不做任何事、也不显示水波纹。
 * 用来隔开上下两层，避免点上层时穿透到下层（比如点操作栏跳到歌词）。
 */
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

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
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState()
    )
    val top = vm.stack.lastOrNull()

    BackHandler(enabled = vm.showLyrics) { vm.showLyrics = false }
    BackHandler(enabled = vm.showPlayer && !vm.showLyrics) { vm.showPlayer = false }
    BackHandler(enabled = !vm.showPlayer && vm.stack.isNotEmpty()) { vm.stack.removeAt(vm.stack.lastIndex) }
    // 后注册的先响应：MV 在栈顶时返回键关 MV，而不是关下面的播放页
    BackHandler(enabled = vm.stack.lastOrNull() is Screen.Video) {
        vm.stack.removeAt(vm.stack.lastIndex)
    }

    // 横屏（含平板）：底栏挪到左侧竖排、迷你播放器挪到右侧竖排，
    // 中间留给内容 —— 竖屏那两条横栏在横屏下会把内容压得很扁
    val landscape = isLandscape()

    Box(Modifier.fillMaxSize().background(go.bg)) {
        // weight 必须在 Row/Column 作用域里用，所以把内容块做成带 Modifier 的 lambda
        val contentBox: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
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
                                    // MV 不在这里画：它是全屏覆盖层，由最外层单独组合，
                                    // 否则会被后面组合的 FullPlayer 盖住
                                    is Screen.Video -> Spacer(Modifier.size(0.dp))
                                }
                            }
                        } else {
                            Spacer(Modifier.size(0.dp))
                        }
                    }
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                NavRail(vm)
                contentBox(Modifier.weight(1f))
                MiniPlayerRail(vm)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                contentBox(Modifier.weight(1f))
                MiniPlayer(vm)
                NavBar(vm)
            }
        }
    }
}

/**
 * 横屏左侧导航栏：竖屏底栏的竖排版本，逻辑完全一样（切标签、清栈、清搜索词）。
 */
/** 横屏（含平板）判定 */
@Composable
fun isLandscape(): Boolean {
    val c = LocalConfiguration.current
    return c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
}

@Composable
fun NavRail(vm: MainViewModel) {
    val go = LocalGo.current
    // 顺序：歌曲 / 专辑 / 首页 / 歌手 / 歌单 —— 首页在正中间
    val tabs = listOf(
        TAB_SONGS to ("歌曲" to Icons.Outlined.MusicNote),
        TAB_ALBUMS to ("专辑" to Icons.Outlined.Album),
        TAB_HOME to ("首页" to Icons.Outlined.Home),
        TAB_ARTISTS to ("歌手" to Icons.Outlined.Person),
        TAB_PLAYLISTS to ("歌单" to Icons.Outlined.LibraryMusic),
    )
    val w = 76.dp
    Column(
        Modifier.fillMaxHeight().width(w).background(go.surface)
            .navigationBarsPadding().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabs.forEach { (t, pair) ->
            val (label, icon) = pair
            val sel = vm.tab == t && vm.stack.isEmpty()
            Column(
                Modifier.fillMaxWidth().height(64.dp).clickable {
                    vm.stack.clear()
                    vm.chooseTab(t)
                    vm.query = ""
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    icon, label, tint = if (sel) go.primary else go.muted,
                    modifier = Modifier.size(22.dp),
                )
                VS(2)
                Txt(
                    label, 11,
                    weight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) go.primary else go.muted,
                )
            }
        }
    }
}

/**
 * 横屏右侧播放栏：竖屏底部迷你播放器的竖排版本。
 * 封面在上，歌名/歌手竖着排，下面是播放与播放列表，操作逻辑不变。
 */
@Composable
fun MiniPlayerRail(vm: MainViewModel) {
    val s = vm.currentSong
    val go = LocalGo.current
    val w = 150.dp
    Column(
        Modifier.fillMaxHeight().width(w).background(go.surface)
            .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Txt("未在播放", 12, color = go.muted, align = TextAlign.Center)
            }
            return@Column
        }
        Cover(s.coverUrl, Modifier.size(96.dp), 12.dp)
        VS(10)
        // 竖排：歌名在上、歌手在下，都居中，窄栏里不够宽就换行而不是截断
        Txt(s.title, 14, weight = FontWeight.Medium, align = TextAlign.Center, maxLines = 2)
        VS(4)
        Txt(s.artist, 11, color = go.muted, align = TextAlign.Center, maxLines = 2)
        VS(10)
        IconBtn(
            if (vm.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (vm.isPlaying) "暂停" else "播放", size = 44.dp,
        ) { vm.togglePlay() }
        IconBtn(Icons.AutoMirrored.Outlined.QueueMusic, "播放列表") { vm.showQueue = true }
    }
}

@Composable
fun NavBar(vm: MainViewModel) {
    val go = LocalGo.current
    // 顺序：歌曲 / 专辑 / 首页 / 歌手 / 歌单 —— 首页在正中间
    val tabs = listOf(
        TAB_SONGS to ("歌曲" to Icons.Outlined.MusicNote),
        TAB_ALBUMS to ("专辑" to Icons.Outlined.Album),
        TAB_HOME to ("首页" to Icons.Outlined.Home),
        TAB_ARTISTS to ("歌手" to Icons.Outlined.Person),
        TAB_PLAYLISTS to ("歌单" to Icons.Outlined.LibraryMusic),
    )
    Column(Modifier.background(go.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            tabs.forEach { (t, pair) ->
                val (label, icon) = pair
                val sel = vm.tab == t && vm.stack.isEmpty()
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        vm.stack.clear()
                        vm.chooseTab(t)
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
    val titles = listOf("歌曲", "专辑", "首页", "歌手", "歌单")
    val hints = listOf("搜索歌名或歌手", "搜索专辑名或歌手", "搜索歌名或歌手", "搜索歌手", "搜索歌单")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            // 搜索栏放在标题和设置中间（同一行），省掉它单独占的一整行
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 首页不显示搜索栏：首页内容都是推荐位，不需要搜索
                if (vm.tab == TAB_HOME) {
                    Txt(titles[vm.tab], 20, Modifier.weight(1f), FontWeight.Bold)
                } else {
                    // weight 必须写成具名参数：Txt 的第 3 个位置是 modifier，
                    // 直接传 FontWeight 会被当成 modifier 而报类型不符
                    Txt(titles[vm.tab], 20, weight = FontWeight.Bold)
                    HS(12)
                    SearchField(
                        vm.query, { vm.query = it }, hints[vm.tab],
                        Modifier.weight(1f),
                        onSearch = { vm.searchNetease(vm.query.trim()) },
                    )
                }
                HS(4)
                IconBtn(Icons.Outlined.Settings, "设置", size = 44.dp) { vm.showSettings = true }
            }
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
                TAB_SONGS -> SongsTab(vm, listStates[0])
                TAB_ALBUMS -> AlbumsTab(vm, listStates[1])
                TAB_HOME -> HomeTab(vm)
                TAB_ARTISTS -> ArtistsTab(vm)
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

/**
 * 首页。
 *
 * 顺序：历史记录置顶，其后依次是每日推荐、新歌速递、热门歌手、最新专辑、热搜歌曲。
 * 离线模式：只展示已下载的歌曲。
 */
@Composable
fun HomeTab(vm: MainViewModel) {
    val go = LocalGo.current
    // 只在第一次进入首页时加载，换回首页直接用缓存
    LaunchedEffect(vm.nmReady) { vm.loadHome() }
    val rec = vm.homeRecommend
    val news = vm.homeNewSongs
    val hots = vm.homeHotWords
    val artists = vm.homeTopArtists
    val albums = vm.homeNewAlbums
    val tops = vm.homeTops
    val his = vm.historySongs
    // 离线：没有推荐位，直接列出已下载
    if (vm.offline) {
        val dl = vm.homeDownloaded
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { HomeHeader("已下载（${dl.size}）") }
            if (dl.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Txt("还没有下载歌曲", 14, color = go.muted)
                    }
                }
            } else {
                items(dl, key = { it.ssid }) { s ->
                    SongRow(
                        s, s.ssid == vm.curSsid, vm.isPlaying,
                        downloaded = true,
                        downloading = false,
                        onClick = { vm.playSongs(dl, dl.indexOf(s)) },
                        onAdd = { vm.pick(listOf(s.ssid), s.title, s.coverUrl) },
                        onDownload = null,
                    )
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        // 历史记录始终置顶
        if (his.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("历史记录", "全部") { vm.showHistory = true }
                    HomeSongRow(his.take(20), vm)
                }
            }
        }
        if (!vm.nmReady) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Txt("接入网易云可显示每日推荐等栏目", 14, weight = FontWeight.SemiBold)
                    VS(4)
                    Txt(
                        "在「设置」里开启「接入网易云曲库」并登录后，这里会显示每日推荐、新歌速递、榜单、热门歌手、最新专辑与热搜",
                        12, color = go.muted,
                    )
                    VS(10)
                    GoButton("去设置", primary = true) { vm.showSettings = true }
                }
            }
        }
        if (vm.homeLoading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = go.primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                    )
                    HS(8)
                    Txt("正在加载首页", 12, color = go.muted)
                }
            }
        }
        if (rec.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("每日推荐", "播放全部") { vm.playSongs(rec, 0) }
                    HomeSongRow(rec.take(20), vm)
                }
            }
        }
        if (news.isNotEmpty()) {
            item { NewSongSection(vm, news) }
        }
        if (tops.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("榜单")
                    HomeTopRow(vm, tops.take(12))
                }
            }
        }
        if (artists.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("热门歌手")
                    HomeArtistRow(vm, artists.take(20))
                }
            }
        }
        if (albums.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("最新专辑")
                    HomeAlbumRow(vm, albums.take(20))
                }
            }
        }
        if (hots.isNotEmpty()) {
            item { HotSection(vm, hots) }
        }
    }
}

/** 横向歌手卡片行 */
@Composable
private fun HomeArtistRow(vm: MainViewModel, list: List<Group>) {
    val go = LocalGo.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { "nm${it.nmId}" }) { g ->
            Column(Modifier.width(84.dp).clickable { vm.openNmGroup(g) }) {
                Cover(g.cover, Modifier.fillMaxWidth().aspectRatio(1f), 8.dp, circle = true)
                VS(6)
                Txt(titleWithSource(g.title, g.source), 12, maxLines = 1)
                Txt(g.subtitle, 11, color = go.muted, maxLines = 1)
            }
        }
    }
    VS(14)
}

/** 横向专辑卡片行 */
@Composable
private fun HomeAlbumRow(vm: MainViewModel, list: List<Group>) {
    val go = LocalGo.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { "nm${it.nmId}" }) { g ->
            Column(Modifier.width(84.dp).clickable { vm.openNmGroup(g) }) {
                Cover(g.cover, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
                VS(6)
                Txt(titleWithSource(g.title, g.source), 12, maxLines = 1)
                Txt(g.subtitle, 11, color = go.muted, maxLines = 1)
            }
        }
    }
    VS(14)
}

/** 横向榜单卡片行 */
@Composable
private fun HomeTopRow(vm: MainViewModel, list: List<Group>) {
    val go = LocalGo.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { "top${it.nmId}" }) { g ->
            Column(Modifier.width(84.dp).clickable { vm.openNmGroup(g) }) {
                Cover(g.cover, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
                VS(6)
                Txt(titleWithSource(g.title, g.source), 12, maxLines = 1)
                Txt(g.subtitle, 11, color = go.muted, maxLines = 1)
            }
        }
    }
    VS(14)
}

/** 首页区块标题：左边标题，右边可选的操作文字 */
@Composable
private fun HomeHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(title, 15, Modifier.weight(1f), FontWeight.SemiBold)
        if (action != null) {
            Txt(action, 12, Modifier.clickable(onClick = onAction), color = go.primary)
            HS(12)
        }
    }
}

/** 横向歌曲卡片行，与历史栏排版一致 */
@Composable
private fun HomeSongRow(list: List<Song>, vm: MainViewModel) {
    val go = LocalGo.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.ssid }) { s ->
            Column(Modifier.width(84.dp).clickable { vm.playSongs(list, list.indexOf(s)) }) {
                CoverWithSource(s.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, source = s.source)
                VS(6)
                Txt(titleWithSource(s.title, s.source), 12, maxLines = 1)
                Txt(s.artist, 11, color = go.muted, maxLines = 1)
            }
        }
    }
    VS(14)
}

/** 新歌速递：只展示「全部」，不提供地区切换 */
@Composable
private fun NewSongSection(vm: MainViewModel, list: List<Song>) {
    Column(Modifier.fillMaxWidth()) {
        HomeHeader("新歌速递")
        HomeSongRow(list.take(20), vm)
    }
}

/** 热搜歌曲：点一下填入搜索框并回到歌曲页 */
@Composable
private fun HotSection(vm: MainViewModel, words: List<String>) {
    val go = LocalGo.current
    Column(Modifier.fillMaxWidth()) {
        HomeHeader("热搜歌曲")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.take(20).forEach { w ->
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(go.surfaceHi)
                        .clickable { vm.searchHotWord(w) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Txt(w, 12, color = go.on) }
            }
        }
        VS(14)
    }
}


/**
 * 首次搜索的大加载动画：盖住整个列表区，直到第一页结果回来才显示内容。
 * 只在没有结果时显示，避免翻页时又盖一次。
 */
@Composable
private fun FirstSearchLoading(vm: MainViewModel, text: String = "正在搜索网易云曲库") {
    val go = LocalGo.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = go.primary,
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
            )
            VS(16)
            Txt(text, 14, color = go.muted)
        }
    }
}

/**
 * 触底加载下一页。
 *
 * 之前把监听放在列表里的 item 中，那个 item 一滚出屏幕就会被回收、协程被取消，
 * 导致「滑到底不加载下一页」。现在放到列表外面，常驻监听可见范围。
 */
@Composable
private fun ReachEndEffect(
    hasMore: Boolean,
    lastVisibleIndex: () -> Int?,
    totalCount: () -> Int,
    onReachEnd: () -> Unit,
) {
    if (!hasMore) return
    LaunchedEffect(hasMore) {
        snapshotFlow {
            val last = lastVisibleIndex()
            val total = totalCount()
            if (last != null && total > 0) last >= total - 3 else false
        }.collect { atEnd -> if (atEnd) onReachEnd() }
    }
}

/** 列表底部「正在加载更多」占位 */
@Composable
private fun LoadingMoreRow(text: String = "正在加载更多") {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = go.primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        HS(8)
        Txt(text, 12, color = go.muted)
    }
}

@Composable
fun SongsTab(vm: MainViewModel, state: LazyListState) {
    val q = vm.query.trim()
    val ranked = vm.rankedSongs
    val zhMode = LocalZhMode.current
    val local = remember(ranked, q, zhMode) {
        if (q.isEmpty()) ranked
        else ranked.filter {
            localMatch(it.title, q, zhMode) || localMatch(it.artist, q, zhMode) ||
                localMatch(it.album, q, zhMode)
        }
    }
    // 两套曲库不合并（不去重），按规则交错展示
    val list = remember(local, vm.nmSongs) { vm.mix(local, vm.nmSongs) }
    // tab 也作为 key：切标签进页面时必定重新发起搜索
    LaunchedEffect(vm.tab, q, vm.nmReady) {
        NmLog.note("界面触发搜索 tab=${vm.tab} q=$q")
        vm.searchNetease(q)
    }
    // 第一次搜索完成前，用大加载动画盖住，不先显示结果
    if (vm.nmSearching && vm.nmSongs.isEmpty()) {
        FirstSearchLoading(vm)
        return
    }
    // 触底监听放在列表外，常驻生效
    ReachEndEffect(
        hasMore = vm.nmHasMore && !vm.nmLoadingMore,
        lastVisibleIndex = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
        totalCount = { state.layoutInfo.totalItemsCount },
        onReachEnd = { vm.loadMoreNetease(0) },
    )
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        // 历史只在首页展示，歌曲页不再显示历史栏
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
        itemsIndexed(list, key = { i, s -> "${s.ssid}#$i" }, contentType = { _, _ -> "song" }) { i, s ->
            SongRow(
                s, s.ssid == vm.curSsid, vm.isPlaying,
                downloaded = vm.isDownloaded(s.ssid),
                downloading = s.ssid in vm.downloading,
                onClick = { vm.playSongs(list, i) },
                onAdd = { vm.pick(listOf(s.ssid), s.title, s.coverUrl) },
                onDownload = { vm.download(listOf(s)) },
                        onQueue = { vm.addToQueue(s) },
            )
        }
        // 加载中占位放最后，跟着列表一起滚到底部
        if (vm.nmHasMore) item { LoadingMoreRow() }
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
            Txt("全部", 12, Modifier.clickable { vm.showHistory = true }, color = go.primary)
            HS(12)
            Txt("清空", 12, Modifier.clickable { vm.clearHistory() }, color = go.muted)
            HS(12)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.take(20), key = { it.ssid }) { s ->
                Column(
                    Modifier.width(84.dp).clickable { vm.playSongs(list, list.indexOf(s)) },
                ) {
                    CoverWithSource(s.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, source = s.source)
                    VS(6)
                    Txt(titleWithSource(s.title, s.source), 12, maxLines = 1)
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
    val zhMode = LocalZhMode.current
    val local = remember(vm.albumGroupList, q, zhMode) {
        vm.albumGroupList.filter {
            q.isEmpty() || localMatch(it.title, q, zhMode) || localMatch(it.artist, q, zhMode)
        }
    }
    // 去重：同名专辑可能属于不同歌手，key 必须唯一，否则 Lazy 会因重复 key 崩溃
    val groups = remember(local, vm.nmAlbums) {
        vm.mix(local, vm.nmAlbums)
            .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title + "|" + it.artist }
    }
    // tab 也作为 key：切标签进页面时必定重新发起搜索
    LaunchedEffect(vm.tab, q, vm.nmReady) {
        NmLog.note("界面触发搜索 tab=" + vm.tab + " q=$q")
        vm.searchNetease(q)
    }
    // 第一次搜索完成前用大加载动画，不先显示结果
    if (vm.nmSearching && vm.nmAlbums.isEmpty()) {
        FirstSearchLoading(vm, "正在搜索网易云专辑")
        return
    }
    ReachEndEffect(
        hasMore = vm.nmAlbumsHasMore && !vm.nmLoadingMore,
        lastVisibleIndex = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
        totalCount = { state.layoutInfo.totalItemsCount },
        onReachEnd = { vm.loadMoreNetease(1) },
    )
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(
            groups,
            key = { i, g -> if (g.nmId > 0) "nm" + g.nmId + "#" + i else "loc" + g.title + "|" + g.artist + "#" + i },
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
        if (vm.nmAlbumsHasMore) item { LoadingMoreRow("正在加载更多专辑") }
    }
}


@Composable
fun ArtistsTab(vm: MainViewModel) {
    val q = vm.query.trim()
    val zhMode = LocalZhMode.current
    val local = remember(vm.artistGroupList, q, zhMode) {
        vm.artistGroupList.filter { q.isEmpty() || localMatch(it.title, q, zhMode) }
    }
    // 去重：key 必须唯一，否则 Lazy 会因重复 key 崩溃
    val groups = remember(local, vm.nmArtists) {
        vm.mix(local, vm.nmArtists).distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
    }
    // tab 也作为 key：切标签进页面时必定重新发起搜索
    LaunchedEffect(vm.tab, q, vm.nmReady) {
        NmLog.note("界面触发搜索 tab=" + vm.tab + " q=$q")
        vm.searchNetease(q)
    }
    // 第一次搜索完成前用大加载动画，不先显示结果
    if (vm.nmSearching && vm.nmArtists.isEmpty()) {
        FirstSearchLoading(vm, "正在搜索网易云歌手")
        return
    }
    val gridState = rememberLazyGridState()
    ReachEndEffect(
        hasMore = vm.nmArtistsHasMore && !vm.nmLoadingMore,
        lastVisibleIndex = { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
        totalCount = { gridState.layoutInfo.totalItemsCount },
        onReachEnd = { vm.loadMoreNetease(2) },
    )
    val cols = if (LocalConfiguration.current.screenWidthDp >= 768) 4 else 2
    LazyVerticalGrid(
        GridCells.Fixed(cols),
        Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            groups,
            key = { g -> if (g.nmId > 0) "nm" + g.nmId else "loc" + g.title },
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
    // 歌单导入 / 导出放在歌单总览页，合并成两个按钮
    var importing by remember { mutableStateOf(false) }
    // 导出支持一次选多个歌单；null = 弹层未打开
    var exporting by remember { mutableStateOf(false) }
    var exportPicked by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            // 横向可滚动：按钮多的时候宁可滚动，也不要把「同步网易云」挤没了
            // （之前固定排布，宽度不够时按钮被压缩，同步按钮直接看不见）
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GoButton("新建歌单", icon = Icons.Outlined.Add) { creating = true }
                Spacer(Modifier.width(8.dp))
                GoButton("导入", icon = Icons.AutoMirrored.Outlined.PlaylistAdd) { importing = true }
                Spacer(Modifier.width(8.dp))
                GoButton("导出", icon = Icons.Outlined.Share) {
                    exportPicked = emptySet()
                    exporting = true
                }
                Spacer(Modifier.width(8.dp))
                GoButton("同步网易云", icon = Icons.Outlined.Refresh) { vm.syncNmPlaylists() }
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
    // 导入：粘贴还原字符
    if (importing) ImportPlaylistDialog(vm) { importing = false }
    // 导出：选歌单（可多选）后复制还原字符
    if (exporting) {
        ExportPlaylistSheet(vm, exportPicked, { exporting = false }) { exportPicked = it }
    }
}

/**
 * 导入歌单：粘贴还原字符。
 * 与已有歌单重名时会自动改名，不会覆盖原有歌单。
 */
@Composable
fun ImportPlaylistDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val go = LocalGo.current
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = go.surface,
        title = { Txt("导入歌单", 16, weight = FontWeight.SemiBold) },
        text = {
            Column {
                Txt("粘贴还原字符；多个歌单可以一次粘贴多行", 12, color = go.muted)
                VS(8)
                SearchField(t, { t = it }, "还原字符", showIcon = false)
                VS(6)
                Txt("重名会自动改名，不会覆盖已有歌单", 11, color = go.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (t.isNotBlank()) vm.importPlaylist(t)
                onDismiss()
            }) { Text("导入", color = go.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = go.muted) } },
    )
}

/**
 * 导出歌单：选一个歌单后生成还原字符，可复制。
 * [picked] 为空表示还没选，先列出歌单。
 */
@Composable
fun ExportPlaylistSheet(
    vm: MainViewModel,
    picked: Set<String>,
    onDismiss: () -> Unit,
    onPick: (Set<String>) -> Unit,
) {
    val go = LocalGo.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 已确认生成时才算还原字符；未确认前只显示选了几个
    var confirmed by remember { mutableStateOf(false) }
    val text = remember(confirmed, picked) {
        if (!confirmed || picked.isEmpty()) null else vm.exportPlaylists(picked.toList())
    }
    GoSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Txt("导出歌单", 16, weight = FontWeight.SemiBold)
            VS(4)
            Txt("可以勾选多个，一次导出；复制后发给别人，对方用「导入」粘贴", 12, color = go.muted)
            VS(12)
            if (vm.playlists.isEmpty()) {
                Txt("还没有歌单", 13, color = go.muted)
            } else {
                vm.playlists.forEach { p ->
                    val sel = p.id in picked
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .clickable {
                                confirmed = false
                                onPick(if (sel) picked - p.id else picked + p.id)
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 勾选框
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (sel) go.primary else go.surfaceHi),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) {
                                // 勾选标记：用小方块，不依赖额外图标
                                Box(
                                    Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFF1A1A1A)),
                                )
                            }
                        }
                        HS(12)
                        Column(Modifier.weight(1f)) {
                            Txt(p.name, 15, weight = FontWeight.Medium, maxLines = 1)
                            Txt("${p.songs.size} 首", 12, color = go.muted, maxLines = 1)
                        }
                    }
                }
            }
            VS(12)
            if (picked.isEmpty()) {
                Txt("先勾选要导出的歌单", 12, color = go.muted)
            } else if (text == null) {
                GoButton("生成还原字符（已选 ${picked.size} 个）", primary = true) { confirmed = true }
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(12.dp)).background(go.surfaceHi).padding(10.dp),
                ) { Txt(text, 11, color = go.on) }
                VS(12)
                GoButton("复制还原字符", primary = true) {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("歌单还原字符", text))
                    vm.toast("已复制 ${picked.size} 个歌单的还原字符")
                }
            }
            VS(16)
        }
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

/**
 * 音质选择：批量下载前先选档位。
 *
 * 选项由调用方按歌单内容拼好（网易云档位 / 本站音质键），
 * 选中后回传音质键；取消或关闭回传 null。
 */
@Composable
fun QualityPickDialog(
    title: String,
    options: List<Pair<String, String>>,
    onPick: (String?) -> Unit,
) {
    val go = LocalGo.current
    AlertDialog(
        onDismissRequest = { onPick(null) },
        containerColor = go.surface,
        title = { Txt(title, 16, weight = FontWeight.SemiBold) },
        text = {
            Column {
                if (options.isEmpty()) {
                    Txt("没有可选音质", 13, color = go.muted)
                } else {
                    options.forEach { (key, label) ->
                        TextButton(onClick = { onPick(key) }) {
                            Text(label, color = go.on)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("取消", color = go.muted) } },
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
                        onQueue = { vm.addToQueue(song) },
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
                        onQueue = { vm.addToQueue(song) },
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
    // 下载全部：先选音质。歌单里可能混着本站与网易云歌曲，两套音质键不同
    var pickingQuality by remember { mutableStateOf(false) }
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
                    Row(Modifier.fillMaxWidth()) {
                        GoButton("播放全部", icon = Icons.Outlined.PlayArrow, primary = true, enabled = songs.isNotEmpty()) {
                            vm.playSongs(songs, 0)
                        }
                        Spacer(Modifier.width(8.dp))
                        GoButton(
                            "下载全部", icon = Icons.Outlined.Download,
                            enabled = songs.isNotEmpty() && !vm.nmSyncing,
                        ) { pickingQuality = true }
                    }
                    Txt(
                        "下载会用当前选中的音质；网易云歌曲若没有该档位会自动降到可用的最高档，" +
                            "下载记录里记的是实际拿到的档位",
                        11, color = go.muted,
                    )

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
    if (pickingQuality) {
        // 选项按歌单内容拼：网易云歌曲用网易云档位，本站歌曲用本站音质键
        val hasNm = songs.any { it.source == SRC_NETEASE }
        val hasLocal = songs.any { it.source != SRC_NETEASE }
        val opts = buildList {
            if (hasNm) NM_LEVELS.take(5).forEach { add(it to nmLevelLabel(it)) }
            if (hasLocal) {
                add("lossless" to "无损")
                add("high" to "高品质")
                add("normal" to "标准")
            }
        }.distinctBy { it.first }
        QualityPickDialog("下载全部（${songs.size} 首）", opts) { q ->
            pickingQuality = false
            if (q != null) vm.downloadAllWithQuality(songs, q)
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
    val cfg = LocalConfiguration.current
    // 横屏（宽 > 高）或屏幕足够宽时，用「左播放器 / 右旋转歌词」的双栏布局
    val wide = cfg.screenWidthDp > cfg.screenHeightDp || cfg.screenWidthDp >= 720
    val bgModel = remember(s?.coverUrl) { imageModel(s?.coverUrl) }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        // 背景：模糊放大的专辑封面
        if (bgModel != null) {
            AsyncImage(bgModel, null, Modifier.fillMaxSize().blur(40.dp), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        // 控制条默认收起，点最右侧箭头展开 / 收起
        var ctrlOpen by remember { mutableStateOf(false) }
        // ---------- 内容区：始终占满整屏 ----------
        // 操作栏不再参与这一层的布局（见下面的浮层），所以展开 / 收起
        // 都不会改变专辑与歌词可用的高度 —— 之前操作栏和拦截层都在 Column 里，
        // 展开时把内容区压掉 100 多 dp，专辑被缩小、歌词中心也被推到偏下。
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            if (s != null) {
                if (wide) {
                    WidePlayerPane(vm, s, Modifier.fillMaxSize())
                } else {
                    // 竖屏顶部留出一个操作栏的高度，避免内容被浮层挡住
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 48.dp)
                            .verticalScroll(rememberScrollState()),
                    ) { PlayerBody(vm, s, true) }
                }
            }
        }
        // ---------- 操作栏：独立浮层，浮在内容之上、不占布局空间 ----------
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 横屏左上角用「×」，竖屏保持向下箭头；都是关闭播放器
                    IconBtn(
                        if (wide) Icons.Outlined.Close else Icons.Outlined.KeyboardArrowDown,
                        "关闭", size = 44.dp,
                    ) { vm.showPlayer = false }
                    if (wide && s != null) {
                        // 折叠区：向左展开、向右收起。
                        // 不用 AnimatedVisibility：Row 作用域里的那个重载已废弃，
                        // 直接按状态显示 / 隐藏，行为一样且不受影响。
                        // 容器自己也吃掉点击：收起时这块是空的，点上去会穿透到歌词
                        Box(
                            Modifier
                                .weight(1f)
                                .noRippleClickable { /* 只拦截穿透，不做任何事 */ },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ctrlOpen) WideTopControls(vm, s)
                        }
                        // 箭头：收起时朝下（v），展开时朝左（<）
                        IconBtn(
                            if (ctrlOpen) Icons.AutoMirrored.Outlined.KeyboardArrowLeft
                            else Icons.Outlined.KeyboardArrowDown,
                            if (ctrlOpen) "收起控制条" else "展开控制条",
                            size = 34.dp,
                        ) { ctrlOpen = !ctrlOpen }
                    }
                }
                // 操作栏展开时，在「操作栏下方 / 歌词上方」插一层完全透明的拦截层。
                // 点操作栏的按钮或按钮之间的空隙时，事件会被这一层吃掉，
                // 不再穿透到下面的歌词（之前点操作栏会跳歌词就是这么来的）。
                // 同样是浮层的一部分，不占内容区的高度
                if (wide && s != null && ctrlOpen) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .noRippleClickable { /* 只拦截穿透，不做任何事 */ },
                    )
                }
            }
        }
    }
}

/**
 * 横屏 / 桌面端播放器：
 * 左半边是播放器区（大封面 → 歌手 → 专辑名 → 单色进度条 → 控制按钮），
 * 右半边是中心旋转式歌词。
 */
@Composable
fun WidePlayerPane(vm: MainViewModel, s: Song, modifier: Modifier = Modifier) {
    val go = LocalGo.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 歌词宽度按「屏幕 - 专辑 - 间隔」的剩余算：
        // 控制区移到顶部后，专辑右边剩下的整块都给歌词，单行能更长
        val albumW = maxWidth * 0.34f
        val gap = 16.dp
        val lyricW = (maxWidth - albumW - gap).coerceAtLeast(120.dp)
        Row(Modifier.fillMaxSize()) {
            // ---------- 左：专辑封面 / 歌手 / 歌名 / 专辑名（位置不变） ----------
            BoxWithConstraints(
                Modifier.width(albumW).fillMaxHeight().padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val coverSize = minOf(maxWidth * 0.66f, maxHeight * 0.56f).coerceAtLeast(80.dp)
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 专辑：封面
                    Cover(
                        s.coverUrl,
                        Modifier.size(coverSize).clickable {
                            vm.showPlayer = false
                            vm.openNmSongAlbum(s)
                        },
                        16.dp,
                    )
                    VS(10)
                    // 歌手
                    Txt(
                        s.artist, 13, color = Color.White.copy(alpha = 0.72f), maxLines = 1,
                        modifier = Modifier.clickable {
                            vm.showPlayer = false
                            vm.openNmSongArtist(s)
                        }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    VS(4)
                    // 歌名
                    MarqueeText(s.title, 20, FontWeight.Bold, Modifier.fillMaxWidth(), align = TextAlign.Center)
                    VS(2)
                    // 专辑名
                    Txt(s.album.ifBlank { "未知专辑" }, 12, color = go.muted, maxLines = 1)
                }
            }
            Spacer(Modifier.width(gap))
            // ---------- 右：歌词区（左移到专辑旁，字号更大、可见行数更多） ----------
            Box(
                Modifier
                    .width(lyricW)
                    .fillMaxHeight()
                    // 整体上移，让当前这一句落在屏幕中线上：
                    // 内容区带 statusBars / navigationBars 留白，容器中心会比
                    // 屏幕中线偏下一点。底部留白的二分之一就是上移量，
                    // 所以这里取 [ARC_LYRIC_LIFT_WIDE] 的两倍。
                    //
                    // 不用 Modifier.offset：Box 作用域里还有 BoxScope.offset，
                    // 两者会构成重载歧义，编译过不去。
                    .padding(bottom = (ARC_LYRIC_LIFT_WIDE * 2).dp),
            ) {
                ArcLyrics(
                    vm.lyricRows, vm.lyricRowIndex,
                    Modifier.fillMaxSize(), vm.lyricLoading,
                    curColor = go.primary,
                    // 译文跟着一起显示；已经有译文后点翻译按钮会关掉它
                    showTrans = vm.showTrans,
                    // 行距缩小 -> 一屏能看到的歌词行数更多
                    rowSpacingDp = ARC_ROW_SPACING_DP_WIDE,
                    curSp = ARC_CUR_SP_WIDE,
                    otherSp = ARC_OTHER_SP_WIDE,
                    radiusBoost = ARC_RADIUS_BOOST_WIDE,
                    minSpan = ARC_MIN_SPAN_WIDE,
                ) { vm.seekTo(it) }
            }
        }
    }
}

/**
 * 横屏顶部控制条：关闭键旁边的一整排，默认收起。
 * [v] 箭头点一次向左展开（箭头转向左），再点一次向右收起（箭头转向下）。
 * 关闭键始终显示，不受折叠影响。
 */
@Composable
fun WideTopControls(vm: MainViewModel, s: Song) {
    val ctx = LocalContext.current
    // 横向可滚动：按钮多的时候宁可滚动，也不要把每个按钮挤成窄条
    // （之前固定排布，宽度不够时按钮被压缩，就变得很难点中）
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // 按钮之间的空隙也吃掉点击：不然点空隙会穿透到下面的歌词
            .noRippleClickable { /* 只拦截穿透，不做任何事 */ },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 进度条占弹性宽度，但保底 80dp，窄屏也不会被挤没
        SimpleSeekBar(
            vm.positionMs, vm.durationMs,
            Modifier.width(160.dp),
        ) { vm.seekTo(it) }
        HS(8)
        Txt(
            "${fmt(vm.positionMs)} / ${fmt(vm.durationMs)}", 11,
            color = Color.White.copy(alpha = 0.65f),
        )
        HS(10)
        // 上一首 / 播放 / 下一首
        IconBtn(Icons.Outlined.SkipPrevious, "上一首", size = 26.dp, minHit = 34.dp, tint = Color.White) { vm.prev() }
        HS(6)
        IconBtn(
            if (vm.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (vm.isPlaying) "暂停" else "播放",
            size = 34.dp, minHit = 34.dp, tint = Color.Black, bg = Color.White,
        ) { vm.togglePlay() }
        HS(6)
        IconBtn(Icons.Outlined.SkipNext, "下一首", size = 26.dp, minHit = 34.dp, tint = Color.White) { vm.next() }
        HS(10)
        // 播放列表 / 播放方式
        IconBtn(
            Icons.AutoMirrored.Outlined.PlaylistPlay, "播放列表",
            size = 24.dp, minHit = 34.dp, tint = Color.White,
        ) { vm.showQueue = true }
        HS(6)
        IconBtn(
            when (vm.playMode) {
                0 -> Icons.Outlined.Repeat
                1 -> Icons.Outlined.Shuffle
                else -> Icons.Outlined.RepeatOne
            },
            listOf("列表循环", "随机播放", "单曲循环")[vm.playMode],
            size = 24.dp, minHit = 34.dp, tint = Color.White,
        ) { vm.cycleMode() }
        HS(10)
        // 分享 / 下载 / 加入歌单 / 音质 / 倍速
        IconBtn(Icons.Outlined.Share, "分享", size = 24.dp, minHit = 34.dp, tint = Color.White) {
            shareText(ctx, "${s.title} - ${s.artist} ${vm.songLink(s.ssid)}")
        }
        HS(6)
        IconBtn(Icons.Outlined.Download, "下载", size = 24.dp, minHit = 34.dp, tint = Color.White) {
            vm.download(listOf(s))
        }
        HS(6)
        IconBtn(
            Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单",
            size = 24.dp, minHit = 34.dp, tint = Color.White,
        ) { vm.pick(listOf(s.ssid), s.title, s.coverUrl) }
        // MV：确认过真能播才显示（mvId>0 只说明接口给了 id，见 vm.mvAvailable）
        LaunchedEffect(s.ssid) { vm.probeMv(s) }
        if (vm.mvAvailable(s)) {
            HS(6)
            MvIconButton({ vm.playMv() }, tint = Color.White, size = 24.dp, hit = 34.dp)
        }
        // 翻译：歌词里有非中文才出现
        if (vm.lyricNeedTrans) {
            HS(6)
            TransIconButton(
                { vm.translateLyric() }, tint = Color.White, size = 24.dp, hit = 34.dp,
                active = vm.lyricHasTrans && vm.showTrans, loading = vm.transLoading,
            )
        }
        HS(6)
        QualityMenu(vm, Color.White)
        HS(6)
        SpeedMenu(vm, Color.White)
    }
}

/**
 * 简洁版单色进度条：细轨道 + 白色进度，可点可拖。
 *
 * 触摸区比轨道本身高不少（32dp），并且加了圆点滑块，
 * 手指容易按准；不拖动时圆点淡出，保持原本的简洁感。
 */
@Composable
fun SimpleSeekBar(
    position: Long,
    duration: Long,
    // modifier 必须放在 onSeek 之前：尾随 lambda 会匹配最后一个参数，
    // 若 onSeek 在前、modifier 在后，SimpleSeekBar(a, b) { ... } 里的
    // lambda 会被当成 modifier，编译报类型不符 + 缺少 onSeek
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var w by remember { mutableIntStateOf(1) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier.fillMaxWidth().height(32.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                // 单击也能跳：不用非得按住拖
                detectTapGestures { o ->
                    if (duration > 0) onSeek(((o.x / w).coerceIn(0f, 1f) * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectDragGestures(
                    onDragStart = { o -> drag = (o.x / w).coerceIn(0f, 1f) },
                    onDrag = { change, _ -> drag = (change.position.x / w).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { f -> if (duration > 0) onSeek((f * duration).toLong()) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f), CircleShape))
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(3.dp).background(Color.White, CircleShape))
        // 拖动时显示圆点，方便对准
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.size(if (drag != null) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (drag != null) 1f else 0.75f)),
            )
        }
    }
}

/**
 * 竖屏播放器的主体：封面 → 歌名 → 歌手 → 歌词预览 → 功能键 → 进度条 → 控制按钮。
 *
 * 这一版按原始排版恢复：之前补回代码时凭记忆重写，把顺序改成了
 * 歌手→歌名→专辑名、播放按钮改成了白底、歌词预览区也丢了，看着和原来不一样。
 * 保留的有意改动：进度条用 SimpleSeekBar（圆点滑块 + 单击跳转），
 * 封面与歌手点击走网易云的 openNmSong* （比原来的 stack.add 更准）。
 */
@Composable
fun PlayerBody(vm: MainViewModel, s: Song, snippet: Boolean) {
    val go = LocalGo.current
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverSize = minOf(maxWidth * 0.86f, 360.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Cover(
                s.coverUrl,
                Modifier.size(coverSize).clickable {
                    vm.showPlayer = false
                    vm.openNmSongAlbum(s)
                },
                20.dp,
            )
            VS(20)
            // 歌名
            MarqueeText(s.title, 22, FontWeight.Bold, Modifier.fillMaxWidth(), align = TextAlign.Center)
            VS(4)
            // 歌手：点进去看歌手
            Txt(
                s.artist, 14, color = go.muted, maxLines = 1,
                modifier = Modifier.clickable {
                    vm.showPlayer = false
                    vm.openNmSongArtist(s)
                }.padding(horizontal = 12.dp, vertical = 6.dp),
            )

            // 歌词预览：三行，点一下打开整份歌词
            if (snippet) {
                VS(8)
                val idx = vm.lyricIndex
                val lines = vm.lyric
                Column(
                    Modifier.fillMaxWidth().height(84.dp).clickable { vm.showLyrics = true },
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    // Column 用 verticalArrangement，没有 verticalAlignment 这个参数
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (lines.isEmpty()) {
                        Txt("暂无歌词", 14, color = go.muted)
                    } else {
                        val i = idx.coerceAtLeast(0)
                        listOf(i - 1, i, i + 1).forEach { k ->
                            val t = lines.getOrNull(k)?.text ?: ""
                            Txt(
                                zhText(t), if (k == i) 17 else 14,
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
                IconBtn(Icons.Outlined.Share, "分享") {
                    shareText(ctx, "${s.title} - ${s.artist} ${vm.songLink(s.ssid)}")
                }
                IconBtn(Icons.Outlined.Download, "下载") { vm.download(listOf(s)) }
                IconBtn(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单") {
                    vm.pick(listOf(s.ssid), s.title, s.coverUrl)
                }
                // MV：确认过真能播才显示（mvId>0 只说明接口给了 id，见 vm.mvAvailable）
                LaunchedEffect(s.ssid) { vm.probeMv(s) }
                if (vm.mvAvailable(s)) {
                    MvIconButton({ vm.playMv() }, tint = go.on, size = 24.dp, hit = 36.dp)
                }
                // 翻译：歌词里有非中文才出现
                if (vm.lyricNeedTrans) {
                    TransIconButton(
                        { vm.translateLyric() }, tint = go.on, size = 24.dp, hit = 36.dp,
                        active = vm.lyricHasTrans && vm.showTrans, loading = vm.transLoading,
                    )
                }
                QualityMenu(vm)
                SpeedMenu(vm)
            }
            VS(8)
            SimpleSeekBar(vm.positionMs, vm.durationMs) { vm.seekTo(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Txt(fmt(vm.positionMs), 12, color = go.muted, tnum = true)
                Txt(fmt(vm.durationMs), 12, color = go.muted, tnum = true)
            }
            VS(8)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconBtn(
                    when (vm.playMode) {
                        0 -> Icons.Outlined.Repeat
                        1 -> Icons.Outlined.Shuffle
                        else -> Icons.Outlined.RepeatOne
                    },
                    listOf("列表循环", "随机播放", "单曲循环")[vm.playMode], size = 44.dp,
                ) { vm.cycleMode() }
                IconBtn(Icons.Outlined.SkipPrevious, "上一首", size = 44.dp) { vm.prev() }
                IconBtn(
                    if (vm.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (vm.isPlaying) "暂停" else "播放",
                    size = 56.dp, tint = OnPrimary, bg = go.primary,
                ) { vm.togglePlay() }
                IconBtn(Icons.Outlined.SkipNext, "下一首", size = 44.dp) { vm.next() }
                IconBtn(Icons.AutoMirrored.Outlined.QueueMusic, "播放列表", size = 44.dp) { vm.showQueue = true }
            }
            VS(16)
        }
    }
}

// =====================================================================
// 音质 / 倍速菜单
// =====================================================================

/**
 * 翻译按钮：就是一个「译」字。
 *
 * 之前画的是 Material Icons 的 translate（「文」字形），辨识度不高；
 * 直接用汉字反而一眼就懂，也不用再管图标集里有没有对应图形。
 *
 * [active] 已经有译文时换成主题色，表示「这篇已经翻过了」；
 * [loading] 正在整篇翻译时转圈。
 */
@Composable
fun TransIconButton(
    onClick: () -> Unit,
    tint: Color = LocalGo.current.on,
    size: Dp = 24.dp,
    hit: Dp = 36.dp,
    active: Boolean = false,
    loading: Boolean = false,
) {
    val go = LocalGo.current
    Box(
        Modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = tint, modifier = Modifier.size(size * 0.7f), strokeWidth = 2.dp,
            )
        } else {
            Txt(
                "译",
                (size.value * 0.75f).roundToInt(),
                color = if (active) go.primary else tint,
                weight = FontWeight.Bold,
            )
        }
    }
}

/**
 * MV 按钮：图标用 Canvas 自绘，画的是 Material Icons 的 music_video
 * （显示器外框 + 里面的音符）。
 *
 * 之前画的是「摄像机 + 播放三角」，和一排 Material 图标放在一起很跳，
 * 而且桌面端用的是 music_video，两端还不一样。这里照着 music_video 画：
 * 图标扩展里的视频类图标取不到（VideoLibrary / Movie 都报 Unresolved
 * reference），自绘不依赖图标名，两端也能一致。
 */
@Composable
fun MvIconButton(
    onClick: () -> Unit,
    tint: Color = LocalGo.current.on,
    size: Dp = 24.dp,
    hit: Dp = 36.dp,
) {
    Box(
        Modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            // 按 24dp 的设计网格画，u 是「一格」对应的像素
            val u = this.size.width / 24f
            val line = Stroke(width = 1.7f * u)
            // 显示器外框
            drawRoundRect(
                color = tint,
                topLeft = Offset(2f * u, 4f * u),
                size = Size(20f * u, 16f * u),
                cornerRadius = CornerRadius(2f * u, 2f * u),
                style = line,
            )
            // 音符头
            drawOval(
                color = tint,
                topLeft = Offset(5.9f * u, 13.4f * u),
                size = Size(6.2f * u, 5.2f * u),
            )
            // 符干
            drawRect(
                color = tint,
                topLeft = Offset(11.4f * u, 7.2f * u),
                size = Size(1.5f * u, 9.6f * u),
            )
            // 符尾
            drawPath(
                Path().apply {
                    moveTo(12.9f * u, 7.2f * u)
                    lineTo(18.4f * u, 9.1f * u)
                    lineTo(18.4f * u, 11.1f * u)
                    lineTo(12.9f * u, 9.3f * u)
                    close()
                },
                color = tint,
            )
        }
    }
}

/** 音质菜单。[tint] 为 null 时用文字按钮（设置页）；传颜色时用图标按钮（播放器控制区） */
@Composable
fun QualityMenu(vm: MainViewModel, tint: Color = LocalGo.current.on) {
    // 本站曲库的歌只有服务端给的那一份，没有多档位可选，不显示这个菜单
    if (!vm.nmQualityMode) return
    var open by remember { mutableStateOf(false) }
    val go = LocalGo.current
    // 探测出来的真实可用档位（探测未完成前为空，这时不禁用任何一项）
    val avail = vm.qualities
    val cur = vm.curLevel
    Box {
        IconBtn(Icons.Outlined.HighQuality, "音质", size = 30.dp, tint = tint) { open = true }
        DropdownMenu(open, { open = false }) {
            NM_LEVELS.forEach { lv ->
                val ok = avail.isEmpty() || lv in avail
                val sel = lv == cur
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(
                                nmLevelLabel(lv), 14,
                                color = when {
                                    !ok -> go.muted.copy(alpha = 0.45f)
                                    sel -> go.primary
                                    else -> go.on
                                },
                                weight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            HS(8)
                            when {
                                sel -> Txt("✓", 14, color = go.primary)
                                !ok -> Txt("无音源", 11, color = go.muted.copy(alpha = 0.6f))
                            }
                        }
                    },
                    onClick = {
                        open = false
                        when {
                            !ok -> vm.toast("这首歌没有${nmLevelLabel(lv)}的音源")
                            sel -> Unit
                            else -> {
                                vm.updateQuality(lv)
                                vm.toast("已切换到${nmLevelLabel(lv)}")
                            }
                        }
                    },
                    enabled = ok,
                )
            }
        }
    }
}

/** 倍速菜单 */
@Composable
fun SpeedMenu(vm: MainViewModel, tint: Color = LocalGo.current.on) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Icons.Outlined.Speed, "倍速", size = 30.dp, tint = tint) { open = true }
        DropdownMenu(open, { open = false }) {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                DropdownMenuItem(
                    { Txt(if (sp == 1f) "正常" else "${sp}x", 14) },
                    {
                        vm.updateSpeed(sp)
                        open = false
                    },
                )
            }
        }
    }
}

// =====================================================================
// 歌词
// =====================================================================

/**
 * 中心旋转式歌词（滚轮）。
 *
 * 当前行固定在正中，其余行绕一个圆柱面分布在上下两侧：
 * 越远的行越小、越淡、绕 X 轴转得越多，形成滚轮的透视感。
 * 上下滑动可以旋转选行，点某一行直接跳到那一句。
 */
@Composable
fun WheelLyrics(
    lines: List<LrcLine>,
    idx: Int,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) {
        // 还在请求时显示「正在加载」，只有确认拿不到才说「暂无歌词」
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    HS(8)
                    Txt("正在加载歌词…", 14, color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                Txt("暂无歌词", 14, color = Color.White.copy(alpha = 0.55f))
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowH = with(density) { 52.dp.toPx() }
    // 每行占的圆心角：越小排列越平，越大透视越明显
    val angleDeg = 11f
    val angleRad = angleDeg * 0.017453292f
    val radius = rowH / sin(angleRad)
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    // 手动操作后的冷却时间：这段时间内不自动跟随播放进度
    var manualUntil by remember { mutableLongStateOf(0L) }
    val last = lines.lastIndex.coerceAtLeast(0).toFloat()

    // 跟播：当前行变化时滚到正中
    LaunchedEffect(idx, lines.size) {
        if (idx >= 0 && !dragging && System.currentTimeMillis() > manualUntil) {
            offset.animateTo(idx.toFloat().coerceIn(0f, last), spring(stiffness = Spring.StiffnessLow))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(lines) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            manualUntil = Long.MAX_VALUE
                            scope.launch { offset.stop() }
                        },
                        onDragEnd = {
                            dragging = false
                            // 松手后对齐到最近的一行，冷却 4 秒再恢复跟播
                            manualUntil = System.currentTimeMillis() + 4000
                            scope.launch {
                                offset.animateTo(
                                    offset.value.roundToInt().toFloat().coerceIn(0f, last),
                                    spring(stiffness = Spring.StiffnessLow),
                                )
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            manualUntil = System.currentTimeMillis() + 4000
                        },
                        onVerticalDrag = { _, dragAmount ->
                            // 手指向上（dragAmount 为负）时看后面的行
                            val d = -dragAmount / rowH
                            scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, last)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val cur = offset.value
            val span = 7
            val from = (cur - span).toInt().coerceAtLeast(0)
            val to = (cur + span).toInt().coerceAtMost(lines.lastIndex)
            for (i in from..to) {
                val d = i - cur
                val theta = d * angleRad
                val y = radius * sin(theta)
                val depth = cos(theta).coerceAtLeast(0f)
                val fade = (1f - abs(d) / (span + 1f)).coerceIn(0f, 1f)
                val isCur = abs(d) < 0.5f
            Text(
                zhText(lines[i].text),
                Modifier
                    .graphicsLayer {
                        translationY = y
                        rotationX = d * angleDeg
                        scaleX = 0.88f + 0.12f * depth
                        scaleY = 0.88f + 0.12f * depth
                        alpha = 0.16f + 0.84f * fade
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clickable(enabled = lines[i].timeMs >= 0) {
                        manualUntil = System.currentTimeMillis() + 6000
                        onSeek(lines[i].timeMs)
                    }
                    .padding(horizontal = 28.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = if (isCur) 19.sp else 16.sp,
                lineHeight = if (isCur) 30.sp else 26.sp,
                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                // 左侧对齐
                textAlign = TextAlign.Start,
                // 不裁剪：超过宽度就换行
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
}

/**
 * 以中心为圆心的圆形滚动歌词。
 *
 * 歌词沿以封面中心为圆心、[radius] 为半径的圆周排列：
 * 当前行固定在正上方（12 点方向），其余行按角度分布在两侧并绕圆心旋转，
 * 字身沿切线方向对齐，越远越小越淡。
 * 上下滑动可以手动旋转圆盘，点某一行直接跳到那一句。
 */
@Composable
fun CircleLyrics(
lines: List<LrcLine>,
idx: Int,
radius: Dp,
modifier: Modifier = Modifier,
loading: Boolean = false,
onSeek: (Long) -> Unit,
) {
if (lines.isEmpty()) {
    // 还在请求时显示「正在加载」，只有确认拿不到才说「暂无歌词」
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                )
                HS(8)
                Txt("正在加载歌词…", 13, color = Color.White.copy(alpha = 0.7f))
            }
        } else {
            Txt("暂无歌词", 13, color = Color.White.copy(alpha = 0.55f))
        }
    }
    return
}
val scope = rememberCoroutineScope()
val density = LocalDensity.current
val rPx = with(density) { radius.toPx() }
// 相邻两行之间夹的角度；弧长用来把手指拖动距离换算成行偏移
val stepDeg = 13f
val stepRad = stepDeg * 0.017453292f
val arcPx = (rPx * stepRad).coerceAtLeast(1f)
val offset = remember { Animatable(0f) }
var dragging by remember { mutableStateOf(false) }
// 手动操作后的冷却时间：这段时间内不自动跟随播放进度
var manualUntil by remember { mutableLongStateOf(0L) }
val last = lines.lastIndex.coerceAtLeast(0).toFloat()

LaunchedEffect(idx, lines.size) {
    if (idx >= 0 && !dragging && System.currentTimeMillis() > manualUntil) {
        offset.animateTo(idx.toFloat().coerceIn(0f, last), spring(stiffness = Spring.StiffnessLow))
    }
}

BoxWithConstraints(modifier.fillMaxSize()) {
    val lineW = maxWidth * 0.62f
    Box(
        Modifier.fillMaxSize()
            .pointerInput(lines) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        manualUntil = Long.MAX_VALUE
                        scope.launch { offset.stop() }
                    },
                    onDragEnd = {
                        dragging = false
                        // 松手后对齐到最近的一行，冷却 4 秒再恢复跟播
                        manualUntil = System.currentTimeMillis() + 4000
                        scope.launch {
                            offset.animateTo(
                                offset.value.roundToInt().toFloat().coerceIn(0f, last),
                                spring(stiffness = Spring.StiffnessLow),
                            )
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        manualUntil = System.currentTimeMillis() + 4000
                    },
                    onVerticalDrag = { _, dragAmount ->
                        // 手指向上（dragAmount 为负）时看后面的行
                        val d = -dragAmount / arcPx
                        scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, last)) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val cur = offset.value
        val span = 8
        val from = (cur - span).toInt().coerceAtLeast(0)
        val to = (cur + span).toInt().coerceAtMost(lines.lastIndex)
        for (i in from..to) {
            val d = i - cur
            val theta = d * stepDeg
            val rad = theta * 0.017453292f
            // 圆周坐标：当前行在正上方（角度 0 -> x=0, y=-r）
            val x = rPx * sin(rad)
            val y = -rPx * cos(rad)
            val fade = (1f - abs(d) / (span + 1f)).coerceIn(0f, 1f)
            val isCur = abs(d) < 0.5f
            Text(
                zhText(lines[i].text),
                Modifier
                    .width(lineW)
                    .graphicsLayer {
                        translationX = x
                        translationY = y
                        rotationZ = theta
                        alpha = 0.18f + 0.82f * fade
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clickable(enabled = lines[i].timeMs >= 0) {
                        manualUntil = System.currentTimeMillis() + 6000
                        onSeek(lines[i].timeMs)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = if (isCur) 16.sp else 13.sp,
                lineHeight = if (isCur) 22.sp else 18.sp,
                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                // 左侧对齐
                textAlign = TextAlign.Start,
                // 不裁剪：超过宽度就换行
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
}

/**
 * 圆弧歌词：歌词排在一个真正的圆上。
 *
 * 圆心在左侧，歌词沿圆右侧的弧排列，
 * 每行的位置（沿弧的横纵偏移）与倾斜角（沿半径方向）都由同一个圆心角算出，
 * 所以行与行之间的相对关系是真实的圆周几何，不是各自硬歪一个角度。
 *
 * 定位按「句」走：一句唱完立刻滚到下一句，不做跟播放进度的匀速旋转。
 * 可以上下滑动手动翻看；停手 3 秒后自动回到当前句，中途再滑会重新计时。
 * 当前句用主题色高亮。
 */
@Composable
fun ArcLyrics(
rows: List<LyricRow>,
idx: Int,
modifier: Modifier = Modifier,
loading: Boolean = false,
curColor: Color = Color.White,
showTrans: Boolean = true,
rowSpacingDp: Float = ARC_ROW_SPACING_DP,
curSp: Float = 19f,
otherSp: Float = 15f,
radiusBoost: Float = ARC_RADIUS_BOOST,
minSpan: Int = 1,
onSeek: (Long) -> Unit,
) {
if (rows.isEmpty()) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = curColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                )
                HS(8)
                Txt("正在加载歌词…", 14, color = Color.White.copy(alpha = 0.7f))
            }
        } else {
            Txt("暂无歌词", 14, color = Color.White.copy(alpha = 0.55f))
        }
    }
    return
}
ArcContent(
    rows = rows, idx = idx, modifier = modifier,
    curColor = curColor, showTrans = showTrans, rowSpacingDp = rowSpacingDp,
    curSp = curSp, otherSp = otherSp,
    radiusBoost = radiusBoost, minSpan = minSpan, onSeek = onSeek,
)
}

@Composable
private fun ArcContent(
rows: List<LyricRow>,
idx: Int,
modifier: Modifier,
curColor: Color,
showTrans: Boolean = true,
rowSpacingDp: Float = ARC_ROW_SPACING_DP,
curSp: Float = 19f,
otherSp: Float = 15f,
radiusBoost: Float = ARC_RADIUS_BOOST,
minSpan: Int = 1,
onSeek: (Long) -> Unit,
) {
val scope = rememberCoroutineScope()
val density = LocalDensity.current
val rowPxDrag = with(density) { (ARC_ROW_SPACING_DP / ARC_DRAG_SENSITIVITY).dp.toPx() }.coerceAtLeast(1f)
// 按句定位：当前句索引变了多少，圆盘就转多少，不做匀速旋转
val base by animateFloatAsState(
    targetValue = idx.coerceAtLeast(0).toFloat(),
    animationSpec = tween(400),
    label = "arcBase",
)
// 手动滑动偏移：直接改状态（不用协程 snapTo），每个拖动事件立即生效，滑动才跟手
var dragOff by remember { mutableFloatStateOf(0f) }
// 停手后回位用的动画偏移
val animOff = remember { Animatable(0f) }
var manualAt by remember { mutableLongStateOf(0L) }
// 位置在这里统一裁剪，边界只依赖歌词行数，不会在拖动中跳变
val cur = (base + animOff.value + dragOff)
    .coerceIn(-0.6f, rows.lastIndex.toFloat() + 0.6f)
// 每滑一次就更新 manualAt，这个协程会重启并重新计时 3 秒
LaunchedEffect(manualAt) {
    if (manualAt == 0L) return@LaunchedEffect
    delay(3000)
    // 把手动偏移交给动画，再平滑回到跟播位置
    animOff.snapTo(animOff.value + dragOff)
    dragOff = 0f
    animOff.animateTo(0f, tween(500))
}

BoxWithConstraints(
    modifier.fillMaxSize()
        .pointerInput(rows) {
            detectVerticalDragGestures(
                onDragStart = { manualAt = System.currentTimeMillis() },
                onDragEnd = { manualAt = System.currentTimeMillis() },
                onDragCancel = { manualAt = System.currentTimeMillis() },
                onVerticalDrag = { _, dragAmount ->
                    // 拖动过程只累加，不做裁剪：
                    // 之前按 base 算上下限，而播放时 base 一直在动，
                    // 边界随之跳变会把 dragOff 反复夹回去，看着就不跟手。
                    // 裁剪改到渲染时统一做
                    dragOff -= dragAmount / rowPxDrag
                    manualAt = System.currentTimeMillis()
                },
            )
        },
    contentAlignment = Alignment.Center,
) {
    // 行距固定，可见行数由屏幕高度决定（至少 3 行，最多 15 行）。
    // 显示译文时一行是「原句 + 译文」两块，比原来高，行距要跟着放大，
    // 否则相邻两行会叠在一起。
    val hasTrans = showTrans && rows.any { !it.trans.isNullOrBlank() }
    val rowSpan = rowSpacingDp * (if (hasTrans) ARC_ROW_SPACING_TRANS_MUL else 1f)
    val visibleRows = (maxHeight.value / rowSpan).toInt().coerceIn(minSpan * 2 + 1, 25)
    // 上下各显示 span 行；保底 minSpan，行距加大后也不会只剩一两行
    val span = (visibleRows / 2).coerceAtLeast(minSpan)
    // 圆心半径由「最外侧行的倾斜角」反推，弧的弯曲程度保持稳定
    val tiltRad = ARC_MAX_TILT_DEG * PI_F / 180f
    // 圆心再按倍数拉远：越远弧越平缓，歌词横向铺得更开
    val rDp = (span * rowSpan / tiltRad * radiusBoost).coerceAtLeast(maxHeight.value * 0.5f)
    // 圆心角由间距 / 半径推出（弧长 s = r·θ），几何自洽
    val stepRad = rowSpan / rDp
    // 半径换算成像素：offset 用的是像素，之前直接拿 dp 数值当像素用，
    // 在高密度屏上实际半径只有设计的 1/density，弧会比预期弯得多
    val rPx = with(density) { rDp.dp.toPx() }
    val maxWPx = with(density) { maxWidth.toPx() }
    // 行宽固定：宽度随文字长短变化的话，各行左端就会参差，
    // 圆盘看着不圆。定宽之后所有行的左端都在同一条弧上。
    val lineWPx = maxWPx * 0.78f
    // 弧的横向位移上限。旋转是绕左端转的，横向不会向外扩张，
    // 所以只要「行宽的一半 + 最大位移」不超过容器的一半，就不会出屏：
    // 以前位移没有上限，外侧行被推到屏幕外，看着就是「歌词超出屏幕」。
    val maxShiftPx = ((maxWPx - lineWPx) / 2f).coerceAtLeast(0f)
    val shiftPx = ((rPx * (1 - cos(span * stepRad))) / 2f).coerceAtMost(maxShiftPx)
    val lineW = with(density) { lineWPx.toDp() }
    // 多渲染一行作缓冲：让最外侧的行在透明度已经衰减到 0 之后
    // 才被移出渲染范围，进出就不会「啪」地出现或消失
    val edge = span + 1
    val from = (cur - edge).toInt().coerceAtLeast(0)
    val to = (cur + edge).toInt().coerceAtMost(rows.lastIndex)
    for (i in from..to) {
        val d = i - cur
        val th = d * stepRad
        // 沿弧排列：横向与纵向位移都由半径 r 决定（弧长 s = r·θ 对应的弦向偏移），
        // 每行真正落在半径 r 的圆弧上。
        //
        // 上一版为了「左端对齐」把横向位移改成 0，结果行只沿竖直方向排开、
        // 文字却在转 —— 排列是直线、字是斜的，两者对不上，所以看着很假。
        val x = -rPx * (1 - cos(th)) + shiftPx
        val y = rPx * sin(th)
        // 边缘平滑淡出：用 smoothstep，最外侧处的值与斜率都是 0，
        // 行在边缘进出时不会有突跳（原来是线性再加 0.25 下限，
        // 一行从「不渲染」直接跳到 0.25，所以看着很生硬）
        val t = (abs(d) / span.toFloat()).coerceIn(0f, 1f)
        val fade = 1f - (t * t * (3f - 2f * t))
        val isCur = i == idx
        // 单层结构：承载框和文字是同一个 Box，共用同一个 rotationZ，
        // 所以外框必然跟着文字一起转、两者完全重合（不再是「框正字斜」）。
        //
        // 宽高都不设上限（unbounded）：一旦给了固定高度，这个约束会传给文字，
        // 折行时文字被「量」成一行多高、超出部分不画 —— 这才是歌词被切割的原因。
        //
        // 这一层不挂 clickable：旋转后的矩形纵向跨度远大于行距，
        // 行与行命中框会重叠、点一行跳到另一行。点击改由整块歌词区
        // 统一做几何命中（按纵坐标反解出行号，见下面的点击层）。
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                // 定宽：左端对齐，各行左端才落在同一条弧上
                .width(lineW)
                .wrapContentHeight(unbounded = true)
                .graphicsLayer {
                    // 框与文字同一个角度：外框跟着一起转
                    rotationZ = th * 180f / PI_F
                    // 绕左端转，左端落在圆弧上
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = fade
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 一行 = 原句 +（可选）译文。整块共用上面的 rotationZ，
            // 所以译文和原句一样贴合圆弧，不会被斜切。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    zhText(rows[i].text),
                    // 当前句用主题色
                    color = if (isCur) curColor else Color.White.copy(alpha = 0.5f),
                    fontSize = if (isCur) curSp.sp else otherSp.sp,
                    lineHeight = if (isCur) (curSp * 1.5f).sp else (otherSp * 1.5f).sp,
                    // 稍粗：当前句用 ExtraBold，其余行也比常规粗一档
                    fontWeight = if (isCur) FontWeight.ExtraBold else FontWeight.Medium,
                    // 左侧对齐
                    textAlign = TextAlign.Start,
                    // 完整换行显示，不截断、不加省略号（省略号看着也像被切了）
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
                // 译文：永远不跟着高亮（高亮只属于原句），字号更小、更暗。
                // 点它和点原句一样跳到这一行的时间，也就是「跳到对应原句处」。
                if (showTrans && !rows[i].trans.isNullOrBlank()) {
                    Text(
                        zhText(rows[i].trans ?: ""),
                        color = Color.White.copy(alpha = if (isCur) 0.62f else 0.34f),
                        fontSize = (otherSp * 0.92f).sp,
                        lineHeight = (otherSp * 1.45f).sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Start,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        }

        // ---------- 点击层 ----------
        // 歌词行本身不带 clickable：旋转后的矩形纵向跨度远大于行距，
        // 行与行的命中框会重叠、点一行会跳到另一行。
        // 改由整块歌词区统一接管点击：把点击的纵坐标反解回行号。
        //   y = r·sin(θ) → θ = asin(y / r) → 行号 = 当前行 + θ / 每行弧度
        // 这样点哪一行就是哪一行，既不会跳错行，也不会有点不到的空隙。
        // pointerInput 只在 rows 变化时重启，lambda 里直接捕获 cur / rPx 会拿到
        // 旧值（播放中 cur 一直在变）。用 rememberUpdatedState 取最新值。
        val curNow by rememberUpdatedState(cur)
        val rPxNow by rememberUpdatedState(rPx)
        val stepNow by rememberUpdatedState(stepRad)
        val spanNow by rememberUpdatedState(span)
        val maxHNow by rememberUpdatedState(maxHeight)
        Box(
            Modifier.fillMaxSize().pointerInput(rows) {
                detectTapGestures { off ->
                    if (rows.isEmpty()) return@detectTapGestures
                    val hPx = with(density) { maxHNow.toPx() }
                    val rel = off.y - hPx / 2
                    val th = asin((rel / rPxNow).coerceIn(-1f, 1f))
                    val d = th / stepNow
                    val hitK = (curNow + d).roundToInt().coerceIn(0, rows.lastIndex)
                    // 淡出到几乎看不见的行不接受点击
                    val tt = (abs(hitK - curNow) / spanNow.toFloat()).coerceIn(0f, 1f)
                    val f = 1f - (tt * tt * (3f - 2f * tt))
                    if (f <= 0.12f) return@detectTapGestures
                    val line = rows[hitK]
                    if (line.timeMs < 0) return@detectTapGestures
                    // 点了就立刻回到跟播：清掉手动偏移，
                    // 位置交给播放进度重新算，避免停在手动位置上跳错行
                    dragOff = 0f
                    scope.launch { animOff.snapTo(0f) }
                    manualAt = 0L
                    onSeek(line.timeMs)
                }
            },
        )
    }
}

/** 从 Context 往上找到 Activity：设置屏幕方向必须拿 Activity，Context 本身没有这个能力 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 全屏按钮：图标用 Canvas 自绘四个角的直角箭头。
 *
 * 图标集里取不到 fullscreen（和 VideoLibrary / Movie 一样会报
 * Unresolved reference），所以照着官方图形自己画。
 * [active] = 当前是全屏，画成向内收的角（表示退出全屏）。
 */
@Composable
fun FullscreenIconButton(
    onClick: () -> Unit,
    active: Boolean = false,
    tint: Color = Color.White,
    size: Dp = 24.dp,
    hit: Dp = 36.dp,
) {
    Box(Modifier.size(hit).clickScale(true, onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val u = this.size.width / 24f
            val st = Stroke(width = 1.8f * u)
            // 四个角的直角：向外张开 = 进入全屏，向内收 = 退出全屏
            val o = if (active) 2.6f else 0f
            val l = 6.5f
            val n = 3f + o          // 近端（靠近角）
            val f = 3f + l + o      // 远端
            // 左上
            drawLine(tint, Offset(n * u, f * u), Offset(n * u, n * u), st.width)
            drawLine(tint, Offset(n * u, n * u), Offset(f * u, n * u), st.width)
            // 右上
            drawLine(tint, Offset((24 - f) * u, n * u), Offset((24 - n) * u, n * u), st.width)
            drawLine(tint, Offset((24 - n) * u, n * u), Offset((24 - n) * u, f * u), st.width)
            // 右下
            drawLine(tint, Offset((24 - n) * u, (24 - f) * u), Offset((24 - n) * u, (24 - n) * u), st.width)
            drawLine(tint, Offset((24 - n) * u, (24 - n) * u), Offset((24 - f) * u, (24 - n) * u), st.width)
            // 左下
            drawLine(tint, Offset(f * u, (24 - n) * u), Offset(n * u, (24 - n) * u), st.width)
            drawLine(tint, Offset(n * u, (24 - n) * u), Offset(n * u, (24 - f) * u), st.width)
        }
    }
}

/**
 * MV 整屏播放页。
 *
 * 比例：按视频真实宽高比居中显示，宽高比从 player.videoSize 拿，
 * 拿到之前先用 16:9 占位。
 *
 * 尺寸交给 Compose 算——用 BoxWithConstraints 拿到容器大小后按
 * 「谁先顶到边界就按谁算」求出显示框，再用 Modifier.size 给 AndroidView。
 * 之前是在 update 里改 SurfaceView 的 layoutParams，和 Compose 自己的
 * 布局互相覆盖，所以比例时好时坏；SurfaceView 会把画面铺满整个 surface，
 * surface 的框不对，画面就一定是变形的。
 *
 * 底部有播放 / 暂停 + 进度条 + 倍速（0.5–2），顶部有下载按钮。
 * 倍速只作用于这个视频，和音频播放的倍速分开（两者是不同的 player）。
 */
@Composable
fun VideoScreen(vm: MainViewModel, v: Screen.Video) {
    val ctx = LocalContext.current
    val player = remember(v.url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(v.url))
            prepare()
            playWhenReady = true
        }
    }
    // 视频真实比例：宽高都为 0 时（还没解析出来）用 16:9
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedOpen by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    // 全屏时按视频比例决定横屏还是竖屏：
    // 宽 > 高（ratio > 1）用横屏，竖屏视频（多见于手机直拍 MV）用竖屏，
    // 否则横屏全屏会把竖屏视频压成中间一小条。
    // 退出全屏或离开本页都要恢复成「跟随系统」，不然回主界面方向是锁死的。
    val act = LocalContext.current.findActivity()
    LaunchedEffect(full, ratio) {
        act?.requestedOrientation = when {
            !full -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ratio >= 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        onDispose { act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                if (vs.width > 0 && vs.height > 0) ratio = vs.width.toFloat() / vs.height
            }
            override fun onPlayWhenReadyChanged(pwr: Boolean, reason: Int) { playing = pwr }
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // 进度：定时读，比逐帧监听省事，300ms 足够顺滑
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }
    // 整个画面放进一个 Box：下面的顶栏/底栏要用 Modifier.align 贴边，
    // 而 align 只在 BoxScope 里才有，函数体顶层直接用会解析不到
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = maxWidth
        val ch = maxHeight
        // 容器比视频更宽 -> 上下顶满、左右留黑边；反之左右顶满、上下留黑边
        val wide = cw / ch > ratio
        val boxW = if (wide) ch * ratio else cw
        val boxH = if (wide) ch else cw / ratio
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c -> SurfaceView(c).also { sv -> player.setVideoSurfaceView(sv) } },
                modifier = Modifier.size(boxW, boxH),
            )
        }
    }
    // 点画面切换播放 / 暂停
    Box(Modifier.fillMaxSize().noRippleClickable {
        playing = !playing
        player.playWhenReady = playing
    })
    // 顶部：关闭 + 标题 + 下载
    Row(
        Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .height(48.dp)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Icons.Outlined.Close, "关闭", size = 44.dp, tint = Color.White) {
            if (vm.stack.isNotEmpty()) vm.stack.removeAt(vm.stack.lastIndex)
        }
        HS(8)
        Txt(
            v.title, 15, Modifier.weight(1f),
            color = Color.White, maxLines = 1, weight = FontWeight.Medium,
        )
        if (vm.mvDownloading) {
            Txt(
                if (vm.mvDownloadProgress >= 0f) "下载中 ${(vm.mvDownloadProgress * 100).toInt()}%"
                else "下载中…",
                12, color = Color.White,
            )
        }
        HS(4)
        IconBtn(Icons.Outlined.Download, "下载 MV", size = 30.dp, tint = Color.White) {
            vm.downloadMv(v)
        }
        HS(4)
        FullscreenIconButton(
            { full = !full }, active = full, tint = Color.White, size = 24.dp, hit = 34.dp,
        )
    }
    // 底部：播放 / 暂停 + 进度条 + 倍速
    Column(
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                if (playing) "暂停" else "播放",
                size = 34.dp, tint = Color.White,
            ) {
                playing = !playing
                player.playWhenReady = playing
            }
            HS(8)
            Txt(fmt(pos), 12, color = Color.White, tnum = true)
            HS(4)
            Box(Modifier.weight(1f)) {
                SimpleSeekBar(pos, dur) { player.seekTo(it) }
            }
            HS(4)
            Txt(fmt(dur), 12, color = Color.White, tnum = true)
            HS(4)
            // 倍速：0.5–2
            Box {
                IconBtn(Icons.Outlined.Speed, "倍速", size = 30.dp, tint = Color.White) { speedOpen = true }
                DropdownMenu(speedOpen, { speedOpen = false }) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                        DropdownMenuItem(
                            { Txt(if (sp == 1f) "正常" else "${sp}x", 14) },
                            {
                                speed = sp
                                speedOpen = false
                            },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun LyricsList(vm: MainViewModel) {
    val go = LocalGo.current
    // 用逻辑行：自带翻译的歌词原文与译文并成一行，译文跟着原句走
    val lines = vm.lyricRows
    val idx = vm.lyricRowIndex
    if (lines.isEmpty()) {
        // 还在请求时显示「正在加载」，只有确认拿不到才说「暂无歌词」
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (vm.lyricLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = go.primary,
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    HS(8)
                    Txt("正在加载歌词…", 14, color = go.muted)
                }
            } else {
                Txt("暂无歌词", 14, color = go.muted)
            }
        }
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
                Column(
                    Modifier.fillMaxWidth()
                        // 点整块（含译文）都跳到这一行的时间，也就是「跳到对应原句处」
                        .clickable(enabled = l.timeMs >= 0) { vm.seekTo(l.timeMs) }
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text(
                        zhText(l.text),
                        color = if (cur) go.primary else go.muted,
                        fontSize = size.sp,
                        lineHeight = (size * 1.8f).sp,
                        fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                    // 译文：不跟着高亮，字号更小、更暗
                    if (vm.showTrans && !l.trans.isNullOrBlank()) {
                        Text(
                            zhText(l.trans ?: ""),
                            color = go.muted.copy(alpha = if (cur) 0.95f else 0.6f),
                            fontSize = (size - 3).sp,
                            lineHeight = (size * 1.5f).sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
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
    var confirmClear by remember { mutableStateOf(false) }
    GoSheet({ vm.showQueue = false }) {
        val st = rememberLazyListState()
        val rs = rememberReorderState()
        // 打开播放列表时自动定位到当前播放的歌曲
        LaunchedEffect(Unit) {
            val i = vm.curIndex
            if (i > 0) st.scrollToItem((i - 1).coerceAtLeast(0))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("播放列表（${vm.queue.size}）", 16, Modifier.weight(1f), FontWeight.SemiBold)
            // 一键清空：保留正在播放的那一首，其余全部移除
            Txt(
                "清空", 13,
                Modifier.clip(RoundedCornerShape(50)).background(go.surfaceHi)
                    .clickable {
                        if (vm.queue.size > 1) confirmClear = true else vm.clearQueue()
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                color = go.primary,
            )
        }
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

    if (confirmClear) {
        ConfirmDialog("清空播放列表？（保留正在播放的这一首）", { confirmClear = false }) {
            confirmClear = false
            vm.clearQueue()
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

/** 底部标签编号：首页放正中间 */
const val TAB_SONGS = 0
const val TAB_ALBUMS = 1
const val TAB_HOME = 2
const val TAB_ARTISTS = 3
const val TAB_PLAYLISTS = 4

/** 网易云请求日志的存放路径，设置里显示，方便用户取日志 */
fun nmLogPath(): String = NmLog.path()

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
                        else if (vm.nmUser.isNullOrBlank()) "已开启：搜索与播放可直接用，登录后才能同步我的歌单"
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
                        if (vm.nmUser.isNullOrBlank()) "未登录：搜索与播放照常用，仅同步歌单需要登录"
                        else "已登录：${vm.nmUser}",
                        12, Modifier.weight(1f), color = go.muted,
                    )
                    if (vm.nmUser.isNullOrBlank()) {
                        GoButton("登录 / 注册", primary = true) { vm.setNmLogin(true) }
                    } else {
                        GoButton("退出") { vm.nmLogout() }
                    }
                }
                VS(6)
                Txt(
                    "开启并登录后，搜索会同时展示两套曲库的结果（不合并），网易云结果在名字前带「(网)」",
                    12, color = go.muted,
                )
                VS(6)
                Txt("请求日志：${nmLogPath()}", 11, color = go.muted)
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

            // ---------- 下载 ----------
            Txt("下载", 15, weight = FontWeight.SemiBold)
            VS(8)
            Txt("保存位置：${LocalLib.dirText()}（音乐 / 封面 / 歌词 / 歌曲信息）", 12, color = go.muted)
            VS(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("已下载 ${vm.downloads.size} 首", icon = Icons.Outlined.Download) { vm.showDownloads = true }
                GoButton("清空下载", icon = Icons.Outlined.Delete) { confirmClear = true }
            }
            VS(16)

            // ---------- 检查更新 ----------
            Txt("检查更新", 15, weight = FontWeight.SemiBold)
            VS(8)
            val up = vm.updateInfo
            Txt(
                when {
                    up?.hasUpdate == true -> "发现新版本 ${up.latest}（当前 ${vm.appVersion}）"
                    up != null -> "当前已是最新版本 ${vm.appVersion}"
                    else -> "当前版本 ${vm.appVersion}"
                },
                12, color = if (up?.hasUpdate == true) go.primary else go.muted,
            )
            VS(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton(
                    if (vm.updateChecking) "检查中…" else "检查更新",
                    icon = Icons.Outlined.Refresh,
                    enabled = !vm.updateChecking,
                ) { vm.checkUpdate() }
                if (up?.hasUpdate == true) {
                    // 与启动时一致：打开同一个全屏更新弹窗
                    GoButton("更新", primary = true, icon = Icons.Outlined.Download) { vm.showUpdate = true }
                }
            }
            VS(8)
            Txt("更新检查经 gh-proxy.com 加速", 11, color = go.muted)
            VS(16)

            // ---------- 字体 ----------
            Txt("字体", 15, weight = FontWeight.SemiBold)
            VS(8)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FONT_NAMES.forEachIndexed { i, n ->
                    val sel = vm.fontKind == i
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) go.primary else go.surfaceHi)
                            .clickable { vm.chooseFontKind(i) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Txt(n, 13, color = if (sel) Color(0xFF1A1A1A) else go.muted) }
                }
            }
            VS(10)
            // 字重：可变字体的 wght 轴，拖动即时生效并保存
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("字重", 13, color = go.muted)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(go.surfaceHi)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Txt(vm.fontWght.toString(), 13, color = go.primary) }
            }
            Slider(
                value = vm.fontWght.toFloat(),
                // 拖动过程中就写入，松手或退出都不会丢
                onValueChange = { vm.chooseFontWght(it.roundToInt()) },
                valueRange = WGHT_MIN.toFloat()..WGHT_MAX.toFloat(),
                steps = (WGHT_MAX - WGHT_MIN) / 10 - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Txt(
                if (vm.fontKind == FONT_SYSTEM) "系统字体不支持调字重，切换内置字体后可用"
                else "范围 $WGHT_MIN–$WGHT_MAX，默认 $WGHT_DEFAULT；改动立即保存",
                11, color = go.muted,
            )
            VS(16)

            // ---------- 简繁转换 ----------
            Txt("简繁转换", 15, weight = FontWeight.SemiBold)
            VS(8)
            val zhOpts = listOf(
                ZhConvert.TO_SIMPLE to "转换成简体",
                ZhConvert.TO_TRAD to "转换成繁体",
                ZhConvert.SEARCH_ONLY to "仅搜索统一",
                ZhConvert.OFF to "不转换",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                zhOpts.forEach { (m, n) ->
                    val sel = vm.zhConvert == m
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) go.primary else go.surfaceHi)
                            .clickable { vm.chooseZhConvert(m) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Txt(n, 13, color = if (sel) Color(0xFF1A1A1A) else go.muted) }
                }
            }
            VS(8)
            Txt(
                when (vm.zhConvert) {
                    ZhConvert.TO_TRAD -> "歌名、歌词、歌手与界面文字统一显示繁体"
                    ZhConvert.SEARCH_ONLY -> "界面不转换；搜索本站时简体与繁体的结果一起返回"
                    ZhConvert.OFF -> "保持原样，不做任何转换"
                    else -> "歌名、歌词、歌手与界面文字统一显示简体"
                },
                11, color = go.muted,
            )
            VS(8)

            // ---------- 存储 ----------
            Txt("存储", 15, weight = FontWeight.SemiBold)
            VS(8)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(go.surfaceHi)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("清除缓存", 14, weight = FontWeight.Medium)
                    Txt("封面、歌词翻译、播放缓存与更新安装包，不影响歌单与已下载歌曲", 12, color = go.muted)
                }
                HS(8)
                GoButton("清除") { vm.clearCache() }
            }
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
            // 同一首歌可能下载了多个音质，key 要带上音质才唯一
            val list = remember(vm.downloads) { vm.downloads.sortedByDescending { it.time } }
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                items(list, key = { "${it.ssid}|${it.quality}" }, contentType = { "dl" }) { rec ->
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
                        IconBtn(Icons.Outlined.Refresh, "重新下载") { vm.redownloadQuality(rec) }
                        IconBtn(Icons.Outlined.Delete, "删除这个音质") { vm.deleteDownloadQuality(rec) }
                    }
                }
            }
        }
    }
}
