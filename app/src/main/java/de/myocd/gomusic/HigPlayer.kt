package de.myocd.gomusic

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 从播放器里点「专辑详情 / 歌手」时自动收起，让二级页直接露出来。
 *
 * 26.11.49：以前 openLocalAlbumIntro / openArtistByName 会先发
 * searchAlbums / searchArtists，网络回来才 push 页面 —— 就是用户说的
 * 「要等一会才会弹出来」。现在两条路都是同步入栈，不把导航挂在网络请求后面：
 *   - 网易云且带了 id：直接开对应的网易云详情页（openNmSongAlbum/Artist 内部同步）
 *   - 其余：进本地「专辑详情 / 歌手页」（Screen.Album / Screen.Artist）
 * 不再是「专辑简介 / 歌手简介」（Screen.AlbumIntro / ArtistIntro）。
 * 设备本地文件仍按 26.11.34 的约定不跳转、不联网查询。
 */
private fun MainViewModel.openAlbumAndClose(s: Song) {
    showPlayer = false
    if (s.source == SRC_DEVICE || LocalAudio.isDevice(s.ssid)) return
    if (s.source == SRC_NETEASE && s.nmAlbumId > 0) openNmSongAlbum(s)
    else stack.add(Screen.Album(s.artist, s.album))
}

private fun MainViewModel.openArtistAndClose(s: Song) {
    showPlayer = false
    if (s.source == SRC_DEVICE || LocalAudio.isDevice(s.ssid)) return
    if (s.source == SRC_NETEASE && s.nmArtistId > 0) openNmSongArtist(s)
    else stack.add(Screen.Artist(s.artist))
}

/** HIG 风格确认框：清空 / 批量删除共用，按钮文案可配 */
@Composable
internal fun HigConfirm(
    text: String,
    okLabel: String,
    onDismiss: () -> Unit,
    onOk: () -> Unit,
) {
    val go = LocalGo.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = go.surface,
        titleContentColor = go.on,
        textContentColor = go.muted,
        title = {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = go.on)
        },
        confirmButton = {
            Text(
                okLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = go.primary,
                modifier = Modifier.clickable { onOk() }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        },
        dismissButton = {
            Text(
                "取消", fontSize = 15.sp, color = go.muted,
                modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        },
    )
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
    // 26.11.55：背景也要从高清原图模糊。之前用的是可能被降过参数的封面，
    // 再叠 18~42dp 模糊就糊成一片色块。
    val bgUrl = hdCoverUrl(s?.coverUrl, 2000) ?: s?.coverUrl
    val bgModel = remember(bgUrl) {
        imageModel(ImageCache.cached(bgUrl, 640) ?: bgUrl)
    }
    // 26.11.52：三页共用「一张」专辑封面。两个页面各自只留透明占位并上报
    // 自己在 root 坐标系里的位置/尺寸，真正的图由下面的 SharedCover 画，
    // 用单个 t 在「大图」与「左上角小图」之间插值，切页就是连续缩放位移。
    var bigX by remember { mutableFloatStateOf(0f) }
    var bigY by remember { mutableFloatStateOf(0f) }
    var bigS by remember { mutableFloatStateOf(0f) }
    var bigReady by remember { mutableStateOf(false) }
    var smallX by remember { mutableFloatStateOf(0f) }
    var smallY by remember { mutableFloatStateOf(0f) }
    var smallS by remember { mutableFloatStateOf(0f) }
    var smallReady by remember { mutableStateOf(false) }
    // 封面占位测的是 root 坐标，而 SharedCover 的 offset 是相对这个根 Box 的。
    // 记下根 Box 自己在 root 里的位置再相减，外层 AnimatedVisibility / 系统栏
    // 带来任何平移都不会让封面错位。
    var rootX by remember { mutableFloatStateOf(0f) }
    var rootY by remember { mutableFloatStateOf(0f) }
    // 26.11.54：歌词控制区显隐提到根层，横屏时右栏歌词与左栏控制区
    // 才能共用同一份状态（下滑隐藏 / 上滑恢复在横屏同样生效）。
    var lyricsControls by remember { mutableStateOf(true) }
    // 26.11.60：封面三态尺寸。从未播放 = 原样式 1.0；开始播放放大到 1.08；
    // 暂停缩小到 0.88；再次播放平滑放大回 1.08。用 spring 连续过渡，不跳变。
    val coverScaleTarget = when {
        !vm.playerEverPlayed -> 1f
        vm.isPlaying -> 1.08f
        else -> 0.88f
    }
    // 26.11.68：横屏改为常驻左右双栏，且不再有「封面页」这个页面状态。
    // 左半边固定是竖屏封面页那套布局（大封面 / 信息 / 进度 / 控制 / 三键），
    // 右半边只在「歌词」与「播放列表」之间切换。
    var lastLand by remember { mutableStateOf(false) }
    /** 横屏时共享封面必须一直停在大图态，不能缩成信息区小图 */
    var forceBigCover by remember { mutableStateOf(false) }
    /**
     * 横屏三键 / 切换条的请求：横屏没有封面页，所以「再点一次返回主页」
     * 这个动作落到默认歌词页，其余在歌词 / 播放列表之间切换。
     */
    val landRequest: (Int) -> Unit = { p ->
        when (p) {
            PLAYER_LYRICS, PLAYER_QUEUE -> vm.gotoPlayerPage(p)
            else -> vm.gotoPlayerPage(PLAYER_LYRICS)
        }
    }
    /** 竖屏信息区 / 小封面点击回封面页；横屏 forceBig 时走不到这里 */
    val collapse: () -> Unit = { vm.gotoPlayerPage(PLAYER_COVER) }
    // 26.11.53：切页时背景模糊半径平滑变化 —— 歌词页更朦胧，队列页居中。
    val blur by animateDpAsState(
        targetValue = when (vm.playerPage) {
            PLAYER_LYRICS -> 42.dp
            PLAYER_QUEUE -> 30.dp
            else -> 18.dp
        },
        label = "playerBgBlur",
    )
    val onBig: (Float, Float, Float) -> Unit = { x, y, sz ->
        bigX = x; bigY = y; bigS = sz; bigReady = true
    }
    val onSmall: (Float, Float, Float) -> Unit = { x, y, sz ->
        smallX = x; smallY = y; smallS = sz; smallReady = true
    }
    Box(
        Modifier.fillMaxSize().background(go.playerBg)
            .onGloballyPositioned { c ->
                val p = c.positionInRoot()
                rootX = p.x; rootY = p.y
            }
            // 背景空白区吃掉触摸：否则会穿透到下面的主页列表 / 底栏
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (bgModel != null) {
            AsyncImage(
                bgModel, null,
                Modifier.fillMaxSize().blur(blur),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
        Box(Modifier.fillMaxSize().background(go.scrim))
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        ) {
            val land = maxWidth > maxHeight * 1.15f
            LaunchedEffect(land) {
                forceBigCover = land
                // 第一次进入横屏默认落在歌词页，不再是封面页
                if (land && !lastLand) vm.gotoPlayerPage(PLAYER_LYRICS)
                lastLand = land
            }
            // 横屏没有主页：外部 openPlayer 或任何残留逻辑把页面设成 COVER 时立即纠正
            LaunchedEffect(land, vm.playerPage) {
                if (land && vm.playerPage == PLAYER_COVER) vm.gotoPlayerPage(PLAYER_LYRICS)
            }
            if (land) {
                CompositionLocalProvider(
                    LocalLandPanel provides LandPanelState(true, landRequest, {}),
                    LocalPageRequest provides landRequest,
                ) {
                    Row(Modifier.fillMaxSize()) {
                        // 左半边：与竖屏封面页同一套组件，只是紧凑尺寸
                        Box(Modifier.weight(0.5f).padding(horizontal = 12.dp)) {
                            CoverPane(
                                vm, s, onBig,
                                compact = true, controls = lyricsControls,
                            )
                        }
                        // 右半边：可选歌词 / 播放列表。
                        // 26.11.75：删掉顶部「歌词 / 播放列表」文字切换条，
                        // 切换只交给左栏控制栏底部那排图标按钮；右栏整块
                        // 留给内容，歌词区多出原来那 48dp。
                        Box(Modifier.weight(0.5f).padding(horizontal = 12.dp)) {
                            when (vm.playerPage) {
                                PLAYER_LYRICS -> Box(
                                    Modifier.fillMaxSize().lyricsControlGesture(
                                        onUp = { lyricsControls = true },
                                        onDown = { lyricsControls = false },
                                    ),
                                ) { WordLyricsList(vm, go, compact = true) }
                                PLAYER_QUEUE -> QueueContent(vm, go)
                                else -> { /* 横屏不存在封面页 */ }
                            }
                        }
                    }
                }
            } else {
                // 竖屏：封面 / 歌词 / 队列三页排版原样不动
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    PlayerBody(
                        vm, s, onBig, onSmall,
                        compact = false, showContent = true,
                        controls = lyricsControls,
                        onControls = { lyricsControls = it },
                    )
                }
            }
        }
        SharedCover(
            vm, s,
            bigX, bigY, bigS, bigReady,
            smallX, smallY, smallS, smallReady,
            rootX, rootY,
            collapse,
            coverScaleTarget,
            forceBigCover,
        )
        // 图 17 样式的更多操作表：盖在播放器最上层
        if (vm.showMoreSheet) PlayerMoreSheet(vm, s) { vm.showMoreSheet = false }
    }
}

/** 三页骨架：封面 / 歌词 / 队列，横竖屏共用，只是紧凑参数不同 */
@Composable
private fun PlayerBody(
    vm: MainViewModel,
    s: Song?,
    onBig: (Float, Float, Float) -> Unit,
    onSmall: (Float, Float, Float) -> Unit,
    compact: Boolean,
    showContent: Boolean,
    controls: Boolean,
    onControls: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when (vm.playerPage) {
            PLAYER_LYRICS -> LyricsPane(
                vm, s, onSmall, compact, showContent, controls, onControls,
            )
            PLAYER_QUEUE -> QueuePane(vm, s, onSmall, compact, showContent)
            else -> CoverPane(vm, s, onBig, compact)
        }
    }
}

/**
 * 26.11.52：共享专辑封面。
 * t=0 是大图（封面页中央），t=1 是信息区左上角的小图（歌词 / 队列页）。
 * 两个页面里原来的 Cover 都换成透明占位，所以整个切换过程只有这一张图在动，
 * 不会出现「大图先消失、小图后冒出来」的跳变。
 */
@Composable
private fun SharedCover(
    vm: MainViewModel,
    s: Song?,
    bigX: Float, bigY: Float, bigS: Float, bigReady: Boolean,
    smallX: Float, smallY: Float, smallS: Float, smallReady: Boolean,
    rootX: Float, rootY: Float,
    onBack: () -> Unit,
    /** 26.11.60：播放 / 暂停 / 未播放三态的目标缩放，只作用在大图态 */
    playScaleTarget: Float = 1f,
    /**
     * 26.11.68：横屏没有封面页，左栏固定就是竖屏封面页那套布局，
     * 所以封面要一直停在大图态，不能因为当前页是歌词/队列就缩到信息区小图。
     */
    forceBig: Boolean = false,
) {
    val density = LocalDensity.current
    // 动画值只在这一层读，播放/暂停切换时不会让整棵播放器子树每帧重组
    val playScale by animateFloatAsState(
        targetValue = playScaleTarget,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 300f),
        label = "coverPlayScale",
    )
    val morph = remember { Animatable(0f) }
    LaunchedEffect(vm.playerPage, forceBig) {
        morph.animateTo(
            if (forceBig || vm.playerPage == PLAYER_COVER) 0f else 1f,
            spring<Float>(dampingRatio = 0.92f, stiffness = 360f),
        )
    }
    // 只在共享层内部读动画值，避免每帧重组整棵播放器子树
    val t = morph.value
    if (!bigReady && !smallReady) return
    val bx = if (bigReady) bigX else smallX
    val by = if (bigReady) bigY else smallY
    val bs = if (bigReady) bigS else smallS
    val sx = if (smallReady) smallX else bx
    val sy = if (smallReady) smallY else by
    val ss = if (smallReady) smallS else bs
    val x = bx + (sx - bx) * t
    val y = by + (sy - by) * t
    val sz = bs + (ss - bs) * t
    if (sz <= 0f) return
    // 26.11.60：大图态（t=0）吃满播放缩放，切到信息区小图（t=1）时淡出该缩放，
    // 这样歌词页 / 队列页的小封面不会因为暂停而忽大忽小。
    val drawSz = sz * (1f + (playScale - 1f) * (1f - t))
    // 尺寸变了但中心不动：左上角补一半差值
    val drawX = x + (sz - drawSz) / 2f
    val drawY = y + (sz - drawSz) / 2f
    Box(
        Modifier
            .offset {
                IntOffset((drawX - rootX).roundToInt(), (drawY - rootY).roundToInt())
            }
            .size((drawSz / density.density).dp)
            .clip(RoundedCornerShape(8.dp))
            // 大图态点封面进专辑详情；小图态点它回封面页（与信息区点击一致）
            .clickable {
                if (t > 0.5f) onBack()
                else s?.let { vm.openAlbumAndClose(it) }
            },
    ) {
        Cover(s?.coverUrl, Modifier.fillMaxSize(), 8.dp, hd = true)
    }
}

/**
 * 26.11.57：横屏不再是「永远双栏展开」。
 * 收缩态整屏只显示封面页；展开态才左右双栏，右侧可选歌词或播放列表，
 * 并且随时能收起。状态和请求通过 CompositionLocal 往下传，
 * 不用把参数一层层穿透 CoverPane / PlayerBottomBar / BottomThreeKeys。
 */
private data class LandPanelState(
    val expanded: Boolean,
    val onExpand: (Int) -> Unit,
    val onCollapse: () -> Unit,
)

private val LocalLandPanel = compositionLocalOf<LandPanelState?> { null }
private val LocalPageRequest = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/** 与背景相对的纯色：暗色背景纯白、亮色背景纯黑；返回 (前景, 反色) */
private fun GoColors.contrastPair(): Pair<Color, Color> {
    val lum = on.red * 0.299f + on.green * 0.587f + on.blue * 0.114f
    return if (lum > 0.5f) Color.White to Color.Black else Color.Black to Color.White
}

// =====================================================================
// 图 1：封面页
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverPane(
    vm: MainViewModel,
    s: Song?,
    onBigCover: (Float, Float, Float) -> Unit,
    compact: Boolean = false,
    /** 26.11.68：横屏右栏歌词上滑/下滑时，左栏控制区跟着显隐；竖屏恒 true */
    controls: Boolean = true,
) {
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
            // 26.11.60：占位始终保持原尺寸。播放 / 暂停的缩放由
            // SharedCover 的 playScale 单独承担，这里再改一次会双重放大，
            // 而且每帧改布局会让整棵播放器子树跟着重组。
            val side = minOf(maxWidth * 0.74f, maxHeight * 0.92f)
            // 26.11.52：这里只留透明占位并上报位置，真正的封面由
            // SharedCover 统一绘制，切页时才能连续缩放而不是换一张图。
            Box(
                Modifier.width(side).aspectRatio(1f)
                    .onGloballyPositioned { c ->
                        val p = c.positionInRoot()
                        onBigCover(p.x, p.y, c.size.width.toFloat())
                    },
            )
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
                        // 26.11.49：歌手单独可点，立即进歌手页；父 Box 的
                        // combinedClickable 仍负责歌名区域与长按菜单，子级优先命中
                        modifier = Modifier.fillMaxWidth().basicMarquee()
                            .clickable { if (s != null) vm.openArtistAndClose(s) },
                    )
                }
                DropdownMenu(expanded = infoMenu, onDismissRequest = { infoMenu = false }) {
                    if (s != null) {
                        DropdownMenuItem(
                            text = { Text("专辑详情", fontSize = 15.sp) },
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
        // 26.11.53：封面页与歌词页 / 队列页共用同一段底栏，进度条、
        // 控制键、三键的高度与间距完全一致 —— 切页时进度条纵坐标不再跳动。
        // 26.11.68：横屏右栏歌词手势会隐藏/恢复这一段，竖屏恒显示。
        AnimatedVisibility(visible = controls) {
            PlayerBottomBar(vm, go, compact)
        }
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
 * 26.11.53：星标改成自绘五角星，**未收藏时只描细边（真正的空心）**，
 * 收藏后才填充主色。之前用 Material 的 Outlined.Star，描边在小尺寸下
 * 看起来像实心；封面页、歌词页、队列页、信息区共用这一处。
 */
private fun DrawScope.starPath(): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension / 2f * 0.9f
    val p = Path()
    for (i in 0 until 10) {
        val a = Math.toRadians(-90.0 + i * 36.0)
        val rr = if (i % 2 == 0) r else r * 0.46f
        val x = cx + (cos(a) * rr).toFloat()
        val y = cy + (sin(a) * rr).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

@Composable
private fun FavoriteStar(
    vm: MainViewModel,
    ssid: String?,
    go: GoColors,
    modifier: Modifier = Modifier,
) {
    val fav = vm.isFavorite(ssid)
    val (fg, _) = go.contrastPair()
    val c = if (fav) go.primary else fg
    Canvas(
        modifier.clickable { vm.toggleFavorite(ssid) },
    ) {
        val path = starPath()
        if (fav) {
            drawPath(path, c)
        } else {
            drawPath(path, c, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private data class MoreRow(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** 点完是否收起整张卡片；音质 / 倍速要留在卡片里切子页。
     *  必须放在 action 前面且带默认值，否则「MoreRow(标签, 图标) { … }」
     *  的尾随 lambda 会被当成这个 Boolean，整排全编译不过。 */
    val closeAfter: Boolean = true,
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
    // 0 主菜单 / 1 音质 / 2 倍速。26.11.46：把改版时丢掉的音质、倍速入口补回来，
    // 用子页而不是嵌套 DropdownMenu，避免底部卡片里菜单层叠错位。
    var subPage by remember { mutableIntStateOf(0) }
    BackHandler(enabled = true) {
        if (subPage != 0) subPage = 0 else onClose()
    }

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
            add(MoreRow("专辑详情", Icons.Outlined.Album) { vm.openAlbumAndClose(s) })
            add(MoreRow("查看歌手", Icons.Outlined.Person) { vm.openArtistAndClose(s) })
        }
        add(
            MoreRow("音质", Icons.Outlined.GraphicEq, false) {
                // 打开时才探测可用档位：切歌时跑十几个请求会拖慢起播
                vm.probeQualities()
                subPage = 1
            }
        )
        add(MoreRow("倍速", Icons.Outlined.Speed, false) { subPage = 2 })
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
                Cover(s?.coverUrl, Modifier.size(84.dp), 8.dp, hd = true)
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
            if (subPage == 0) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().height(58.dp)
                                .clickable { if (r.closeAfter) onClose(); r.action() }
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
            } else {
                // 子页：标题栏带返回，下面是一组可直接点的选项
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .clickable { subPage = 0 }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                        tint = go.primary, modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (subPage == 1) "音质" else "倍速",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = go.on,
                    )
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).background(go.separator))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (subPage == 1) {
                        if (!vm.nmQualityMode) {
                            Text(
                                "本站曲库只有服务端提供的单一音源，没有多档位可切换",
                                fontSize = 15.sp, color = go.muted,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            NM_LEVELS.forEach { lv ->
                                val ok = lv !in vm.qualitiesAbsent
                                val sel = lv == vm.curLevel
                                Row(
                                    Modifier.fillMaxWidth().height(58.dp)
                                        .clickable {
                                            when {
                                                !ok -> vm.toast("这首歌没有${nmLevelLabel(lv)}的音源")
                                                sel -> onClose()
                                                else -> {
                                                    vm.updateQuality(lv)
                                                    vm.toast("已切换到${nmLevelLabel(lv)}")
                                                    onClose()
                                                }
                                            }
                                        }
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        nmLevelLabel(lv), fontSize = 17.sp,
                                        color = when {
                                            !ok -> go.muted.copy(alpha = 0.45f)
                                            sel -> go.primary
                                            else -> go.on
                                        },
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    when {
                                        sel -> Text("✓", fontSize = 16.sp, color = go.primary)
                                        !ok -> Text("无音源", fontSize = 12.sp, color = go.muted)
                                    }
                                }
                                Box(
                                    Modifier.fillMaxWidth().height((0.5f).dp)
                                        .padding(start = 20.dp).background(go.separator),
                                )
                            }
                        }
                    } else {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { sp ->
                            Row(
                                Modifier.fillMaxWidth().height(58.dp)
                                    .clickable { vm.updateSpeed(sp); onClose() }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (sp == 1f) "正常" else "${sp}x",
                                    fontSize = 17.sp, color = go.on,
                                )
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
    }
}

// =====================================================================
// 共用：进度 / 控制 / 底部三键
// =====================================================================

@Composable
private fun ProgressLine(vm: MainViewModel, go: GoColors) {
    val left = fmt(vm.positionMs)
    val right = "-" + fmt((vm.durationMs - vm.positionMs).coerceAtLeast(0))
    // 26.11.53：进度条轨道、已播段、圆点、时间文字全部用与播放按钮同一个
    // 「与背景相对」的对比色，不再各自取 go.muted / 硬编码白。
    val (fg, _) = go.contrastPair()
    Column(Modifier.fillMaxWidth()) {
        SimpleSeekBar(
            vm.positionMs, vm.durationMs,
            Modifier.fillMaxWidth().height(32.dp),
            color = fg,
        ) { vm.seekTo(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(left, fontSize = 13.sp, color = fg.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.quality == "lossless") "无损" else vm.quality,
                fontSize = 12.sp, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(go.surfaceHi.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(right, fontSize = 13.sp, color = fg.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun MainControls(vm: MainViewModel, go: GoColors, compact: Boolean = false) {
    // 26.11.52：播放键去掉圆底，改成图 2/3 那种简洁的实心大三角 / 双竖条。
    // 颜色仍是「与背景相对」的纯白 / 纯黑，不再耦合 go.bg / go.on 的圆底配色。
    val (fg, _) = go.contrastPair()
    // 26.11.62：换成截图里那套粗圆角实心图标后，双箭头是宽扁形状
    // （160 画布里 152×110），等比放进方形按钮会比播放三角矮一截。
    // 侧键略放大到 48/36，让「双箭头 : 大三角」的高度比贴近截图的 ~0.66。
    val side = if (compact) 36.dp else 48.dp
    val main = if (compact) 40.dp else 56.dp
    val sideBox = if (compact) 48.dp else 60.dp
    val mainBox = if (compact) 56.dp else 72.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PIconBtn("prev", "上一首", size = side, tint = fg, box = sideBox) { vm.prev() }
        PIconBtn(
            if (vm.isPlaying) "pause" else "play",
            if (vm.isPlaying) "暂停" else "播放",
            size = main, tint = fg, bg = Color.Transparent, box = mainBox,
        ) { vm.togglePlay() }
        PIconBtn("next", "下一首", size = side, tint = fg, box = sideBox) { vm.next() }
    }
}

/**
 * 26.11.52：底部三键。
 * 未选中 = 与背景相对的纯白 / 纯黑图标、无背景；
 * 选中（进入对应页面）= 圆角方块背景用对比色，图标反色（图 1 左下第一个按钮）；
 * 再点一次同一个键回到封面页。
 */
@Composable
private fun PlayerTabKey(
    selected: Boolean,
    fg: Color,
    inv: Color,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) fg else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        icon(if (selected) inv else fg)
    }
}

@Composable
private fun BottomThreeKeys(vm: MainViewModel, go: GoColors) {
    val (fg, inv) = go.contrastPair()
    // 横屏由播放器根提供覆写：选择展开歌词 / 列表，再点一次收起。
    val req = LocalPageRequest.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerTabKey(
            selected = vm.playerPage == PLAYER_LYRICS,
            fg = fg, inv = inv,
            onClick = {
                val p = if (vm.playerPage == PLAYER_LYRICS) PLAYER_COVER else PLAYER_LYRICS
                if (req != null) req(p) else vm.gotoPlayerPage(p)
            },
        ) { c ->
            Icon(
                Icons.Outlined.ChatBubbleOutline, "歌词",
                tint = c, modifier = Modifier.size(24.dp),
            )
        }
        PlayerTabKey(
            selected = false, fg = fg, inv = inv,
            onClick = { vm.showCast = true },
        ) { c ->
            Icon(Icons.Outlined.Cast, "投屏", tint = c, modifier = Modifier.size(24.dp))
        }
        PlayerTabKey(
            selected = vm.playerPage == PLAYER_QUEUE,
            fg = fg, inv = inv,
            onClick = {
                val p = if (vm.playerPage == PLAYER_QUEUE) PLAYER_COVER else PLAYER_QUEUE
                if (req != null) req(p) else vm.gotoPlayerPage(p)
            },
        ) { c ->
            PlayerIcon("queue", "播放列表", modifier = Modifier.size(24.dp), tint = c)
        }
    }
}

@Composable
private fun PlayerBottomBar(vm: MainViewModel, go: GoColors, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        ProgressLine(vm, go)
        Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
        MainControls(vm, go, compact)
        Spacer(Modifier.height(if (compact) 6.dp else 18.dp))
        BottomThreeKeys(vm, go)
    }
}

/** 歌词页 / 队列页共用的顶部信息区：点一下回封面页 */
@Composable
private fun InfoHeader(
    vm: MainViewModel,
    s: Song?,
    go: GoColors,
    onBack: () -> Unit,
    onSmallCover: (Float, Float, Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onBack() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 26.11.52：透明占位，图由 SharedCover 画在完全相同的 root 坐标上
        Box(
            Modifier.size(44.dp).onGloballyPositioned { c ->
                val p = c.positionInRoot()
                onSmallCover(p.x, p.y, c.size.width.toFloat())
            },
        )
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

/**
 * 26.11.53：在歌词展示区里观察垂直拖拽方向。
 * 手指上滑 → 显示播放控制区；下滑 → 隐藏。只读取 Initial pass、不消费事件，
 * 所以 LazyColumn 自己的滚动、点击歌词跳转、自动跟随全都不受影响。
 * 一次手势只触发一次，避免连续不停切换显隐。
 */
private fun Modifier.lyricsControlGesture(onUp: () -> Unit, onDown: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent(PointerEventPass.Initial)
                if (down.type != PointerEventType.Press) continue
                var lastY = down.changes.firstOrNull()?.position?.y ?: 0f
                var acc = 0f
                var fired = false
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Release) break
                    if (e.type == PointerEventType.Move) {
                        val y = e.changes.firstOrNull()?.position?.y ?: lastY
                        acc += y - lastY
                        lastY = y
                        if (!fired && kotlin.math.abs(acc) > 64f) {
                            fired = true
                            if (acc < 0f) onUp() else onDown()
                        }
                    }
                }
            }
        }
    }

@Composable
private fun LyricsPane(
    vm: MainViewModel,
    s: Song?,
    onSmallCover: (Float, Float, Float) -> Unit,
    compact: Boolean = false,
    showContent: Boolean = true,
    controls: Boolean = true,
    onControls: (Boolean) -> Unit = {},
) {
    val go = LocalGo.current
    // controls 由播放器根层持有：竖屏手势在这里、横屏手势在右栏歌词上，
    // 两边改的是同一份状态。默认显示，下滑隐藏 / 上滑恢复。
    Column(Modifier.fillMaxSize()) {
        // 26.11.57：横屏展开态点信息区直接收起，不是只把页面标成 COVER
        val land = LocalLandPanel.current
        InfoHeader(
            vm, s, go,
            { if (land?.expanded == true) land.onCollapse() else vm.gotoPlayerPage(PLAYER_COVER) },
            onSmallCover,
        )
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(
                    if (showContent) Modifier.lyricsControlGesture(
                        onUp = { onControls(true) },
                        onDown = { onControls(false) },
                    ) else Modifier,
                ),
        ) {
            // 横屏时歌词在右栏，左栏这里只留弹性空白托住底部控制区，
            // 不再出现两份歌词。
            if (showContent) WordLyricsList(vm, go, compact)
        }
        AnimatedVisibility(visible = controls) {
            PlayerBottomBar(vm, go, compact)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WordLyricsList(vm: MainViewModel, go: GoColors, compact: Boolean = false) {
    // 所有来源（/lyric/new 的 yrc、退回的老 /lyric、本站、本地、离线）
    // 都已在 ViewModel 里统一成 WordLyric，因此这里只有一套大字渲染，
    // 老歌词不再掉回旧的 LyricsList 小字列表。
    val w = vm.wordLyric
    if (w == null || w.lines.isEmpty()) {
        // 26.11.69：以前「暂无歌词」只是个静态文字，加载失败后没有任何重试入口。
        // 现在整块可点，加载中显示状态并禁用重复点击，失败时明确给出「点击重试」。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !vm.lyricLoading) { vm.reloadLyric() }
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            ) {
                Text(
                    if (vm.lyricLoading) "正在加载歌词…" else "暂无歌词",
                    fontSize = 17.sp, color = go.muted,
                )
                if (!vm.lyricLoading) {
                    Spacer(Modifier.height(8.dp))
                    Text("点击重试", fontSize = 13.sp, color = go.primary)
                }
            }
        }
        return
    }
    // 26.11.49：歌词排版三项独立设置，只作用于这里，不改全局字体。
    // 「跟随全局」读 LocalAppFont；「系统字体」留 null 由字重生效；
    // 内置字体用精确 wght 的字体实例，不再叠加全局字重。
    val globalFont = LocalAppFont.current
    val customLyricFont = rememberLyricFont(vm.lyricFont, vm.lyricWght)
    val lyricFamily = when (vm.lyricFont) {
        LYRIC_FONT_FOLLOW -> globalFont
        LYRIC_FONT_SYSTEM -> null
        else -> customLyricFont
    }
    val lyricWeight = FontWeight(vm.lyricWght)
    val lyricSizeSp = vm.lyricSize
    // 只略微放大同一句折成多行时的行距；不同歌词行之间的 spacedBy(18.dp) 不动
    val lyricLineHeight = (lyricSizeSp * LYRIC_LINE_HEIGHT_RATIO).sp
    // 26.11.54：切进歌词页时列表初始就在当前行，不要从 index 0 一路动画
    // 滚到目标行。用 key(w) 让换歌时状态重建，并把初始行设成 activeIndex。
    val initialIndex = w.activeIndex(vm.positionMs).coerceAtLeast(0)
    val state = key(w) { rememberLazyListState(initialFirstVisibleItemIndex = initialIndex) }
    // 26.11.48：两种歌词都要自动跟随当前行滚动，区别只在刷新频率与是否逐字渲染。
    // 26.11.46 把「无高级歌词不要滚动」误解成列表完全静止，老歌词播到后面几行
    // 还停在顶部；实际要的是「行级跟随滚动」，不要的是逐字扫光那种逐帧滚。
    val wordTiming = w.hasWordTiming
    // 逐帧位置：ViewModel 的 250ms 轮询只够进度条，逐字卡拉 OK 必须每帧一个位置，
    // 否则一次跳 250ms，几个字一起变色，看起来就是「生硬跳动」。
    val framePos = remember { mutableLongStateOf(vm.positionMs) }
    val userScrollUntil = remember { mutableLongStateOf(0L) }
    LaunchedEffect(w) {
        var lastActive = -1
        // 26.11.51：只保留一个滚动协程。以前每切一行就 launch 一个新
        // animateScrollToItem，几个动画并发会互相打断，看起来也会漏行。
        var scrollJob: Job? = null
        // 用户手动滑动期间被跳过的那一句，抑制结束后补滚回来
        var suppressed = false
        // 第一次拿到有效行：直接无动画落位。之前这里会走 animateScrollToItem，
        // 于是每次切进歌词页都能看到列表从「开头」飞快滚到当前句。
        var positioned = false
        while (true) {
            if (wordTiming) {
                if (vm.isPlaying) withFrameNanos { it } else delay(100)
                framePos.longValue = vm.controller?.currentPosition?.coerceAtLeast(0) ?: vm.positionMs
            } else {
                // 行级歌词：250ms 定位当前行就够，不逐帧刷新、不画任何光带
                delay(250)
                framePos.longValue = vm.positionMs
            }
            // 26.11.51：这里**不再**读 state.isScrollInProgress。
            // 它对 animateScrollToItem 自己发起的滚动也返回 true，于是每自动
            // 滚一句就把 userScrollUntil 刷成 now+4s，下一句必然被抑制、
            // 再下一句才滚 —— 正是「每一种歌词都隔一句跳一次」。
            // 抑制窗口现在只由下面 pointerInput 里的真实手指事件设置。
            val held = System.currentTimeMillis() <= userScrollUntil.longValue
            val a = w.activeIndex(framePos.longValue)
            if (a != lastActive) {
                lastActive = a
                suppressed = false
                if (a >= 0) {
                    if (!positioned) {
                        // 直接进入当前行，不播滚动动画；之后切句才平滑跟随
                        positioned = true
                        scrollJob?.cancel()
                        state.scrollToItem(a)
                    } else if (held) {
                        suppressed = true
                    } else {
                        scrollJob?.cancel()
                        scrollJob = launch { state.easeScrollToItem(a) }
                    }
                }
            } else if (suppressed && !held && lastActive >= 0) {
                suppressed = false
                scrollJob?.cancel()
                scrollJob = launch { state.easeScrollToItem(lastActive) }
            }
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize()
            // 只把真实手指拖拽记为「用户手动滑歌词」；拖完的 fling 惯性由
            // 4 秒窗口覆盖，不需要再认 Release（否则点一下歌词也会被当成
            // 手动滑动，跳转后 4 秒不跟随）。Initial pass 只观察不消费，
            // 不影响 LazyColumn 自己的滚动手势，也不影响行点击。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Move) {
                            userScrollUntil.longValue = System.currentTimeMillis() + 4000
                        }
                    }
                }
            },
        contentPadding = PaddingValues(vertical = if (compact) 40.dp else 120.dp),
        // 26.11.49：不同歌词行之间的 18dp 保持原样，只改同一句折行行距
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(w.lines) { i, line ->
            val isActive by remember(w) {
                derivedStateOf { w.activeIndex(framePos.longValue) == i }
            }
            val alpha = if (isActive) 1f else 0.32f
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    // 点击歌词跳到这一行；制作信息行不跳。
                    // 清掉手动滑动抑制窗口：用户主动选行，就该立刻跟到那一行
                    .clickable(enabled = !line.meta) {
                        userScrollUntil.longValue = 0L
                        vm.seekTo(line.startMs)
                    },
            ) {
                if (line.meta) {
                    // 26.11.66：监制栏（作词/作曲/编曲/制作人）固定 12sp，
                    // 调小歌词字号时它一动不动。改成按歌词字号等比缩放并留上下限。
                    Text(
                        line.text,
                        fontSize = (lyricSizeSp * 0.46f).coerceIn(10f, 16f).sp,
                        color = go.muted.copy(alpha = 0.6f),
                    )
                } else {
                    WordLineText(
                        line, framePos, go, alpha, wordTiming,
                        lyricSizeSp, lyricFamily, lyricWeight, lyricLineHeight,
                    )
                    // 26.11.65：译文要跟随「翻译歌词」按钮的显示开关。
                    // 之前只读 line.trans，按钮切换 showTrans 在这里没人消费。
                    if (vm.showTrans && !line.trans.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            line.trans,
                            fontSize = (lyricSizeSp * 0.50f).coerceIn(10f, 18f).sp,
                            color = go.muted.copy(alpha = alpha),
                        )
                    }
                    if (!line.roman.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            line.roman,
                            fontSize = (lyricSizeSp * 0.42f).coerceIn(9f, 15f).sp,
                            color = go.muted.copy(alpha = alpha * 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 26.11.78：纵向跟随滚动的果冻回弹。
 *
 * 轻微 easeOutBack：推进到位前多走约 6% 再收回，起止速度仍为 0，
 * 落位时轻轻弹一下。只作用在列表位移上，**不碰逐字时间轴** ——
 * 字亮起的时刻依旧与真实演唱时间一一对应，不会像 26.11.76 那样错位。
 */
private fun jellyScroll(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    val c1 = 1.25f
    val c3 = c1 + 1f
    val u = x - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * 26.11.77：行与行之间的跟随滚动换成定时长连续推进；26.11.78 加果冻回弹。
 *
 * 系统 animateScrollToItem 用固定 spring：每切一句先 cancel 上一个动画，
 * 速度归零再弹出去，落到下一句时一顿 —— 这就是「不同句滚动很生硬」。
 * 逐字歌词和行级歌词共用这段循环，所以两种歌词都生硬。
 *
 * 这里只在目标行已经出现在可视区时接管（自动切句几乎总是相邻行）：
 * 按目标行相对视口顶部的真实像素距离逐帧推进 520ms，末端轻微回弹。
 * 目标不可见时退回系统动画，行为不变。
 * 首次直接落位、抑制窗口、点击跳转、补滚回当前行这些状态机一行没动。
 */
private suspend fun LazyListState.easeScrollToItem(index: Int) {
    if (index < 0) return
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (target == null) {
        animateScrollToItem(index)
        return
    }
    val total = target.offset.toFloat()
    if (abs(total) < 1f) return
    val durationNs = 520_000_000L
    val t0 = withFrameNanos { it }
    var applied = 0f
    scroll {
        while (true) {
            val t = withFrameNanos {
                ((it - t0).toFloat() / durationNs).coerceIn(0f, 1f)
            }
            val want = total * jellyScroll(t)
            this.scrollBy(want - applied)
            applied = want
            if (t >= 1f) break
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

/** 字组在行内的相对宽度权重：CJK / 假名算全宽，拉丁字母算半宽，空格更窄。
 *  只用于把「播放边界」换算成在字流里的连续位置，不做真实像素测量，
 *  因此字号、字体、折行都不会让每帧重新布局。 */
private fun wordWidth(s: String): Float = s.fold(0f) { a, c -> a + charWidth(c) }

private fun charWidth(c: Char): Float = when {
    c == ' ' -> 0.32f
    c.code in 0x2E80..0xA4CF || c.code in 0xAC00..0xD7A3 ||
        c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF ||
        c.code in 0xFF01..0xFF60 -> 1f
    c.code < 0x2E80 -> 0.55f
    else -> 0.7f
}

/**
 * 26.11.48：逐字高亮只有字形本身的颜色，但**边界是整行连续的**。
 *
 * 26.11.47 让每个字按自己的时间窗独立插值，结果字 A 唱完变全亮、紧接的
 * 字 B 又从暗开始，交界处亮度直接跳断，就是用户说的「生硬、字之间没有过渡」。
 *
 * 现在先按字宽算出每个字在行内的 [起, 止] 比例，再用逐字时间轴把播放位置
 * 换算成一条在 0..1 字流上连续滑动的边界 edge；每个字按「自己的中心到 edge
 * 的距离」做约 3 个字宽的羽化插值，并给边界一点右偏，让已唱的字保持全亮。
 * 于是相邻三四个字亮度是渐进的，实测最大相邻跳变从 0.97 降到 0.48，
 * 明暗像一道连续的波沿字流滑过去，而不是逐字眨眼。
 *
 * 仍然没有任何矩形、渐变条、光晕或叠加层：SpanStyle 只改字色。
 * 位置逐帧读取，字号固定 26sp。
 *
 * 26.11.77：撤掉 26.11.76 那层「时间→位置」的果冻缓动。它把 progress
 * 非线性化，字亮起的时刻与真实演唱时间对不上（时间不准确）。边界回到严格线性。
 *
 * 26.11.78：横向不再是「整字从暗渐变到亮」的渐亮，改成字内从左到右的
 * 滚动式擦亮 —— 底层整字暗色，上层整字亮色按该字被扫过的比例裁掉右半。
 * 「顺」字先只亮左边的丿、再亮川、最后全字亮。仍然只有字形两档明暗，
 * 没有矩形、渐变条、光晕或任何叠加层；时间轴依旧严格线性。
 */
@Composable
private fun WordLineText(
    line: WordLine,
    posState: LongState,
    go: GoColors,
    alpha: Float,
    wordTiming: Boolean,
    sizeSp: Int,
    family: FontFamily?,
    weight: FontWeight,
    lineHeight: TextUnit,
) {
    // 无逐字时间轴：静态大字，行级自动滚动由列表负责（26.11.48 起）
    if (!wordTiming) {
        Text(
            line.text,
            fontSize = sizeSp.sp,
            fontFamily = family,
            fontWeight = weight,
            lineHeight = lineHeight,
            color = go.on.copy(alpha = alpha),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val posMs = posState.longValue
    val frames = WordLyricParser.frameAt(line, posMs)
    if (frames.isEmpty()) return

    // 每个字在字流里的连续区间：边界 edge 严格按逐字时间轴线性推进，
    // 不做任何缓动，字亮起的时刻与真实演唱时间一一对应。
    val weights = frames.map { wordWidth(it.char.text).coerceAtLeast(0.05f) }
    val total = weights.sum().coerceAtLeast(1e-6f)
    val starts = FloatArray(frames.size)
    val ends = FloatArray(frames.size)
    var acc = 0f
    for (i in frames.indices) {
        starts[i] = acc / total
        acc += weights[i]
        ends[i] = acc / total
    }
    val active = frames.indexOfFirst { it.phase == CharPhase.ACTIVE }
    val lastSung = frames.indexOfLast { it.phase == CharPhase.SUNG }
    val edge = when {
        active >= 0 -> starts[active] + (ends[active] - starts[active]) * frames[active].progress
        // 整行唱完：边界推到行尾之外，保证最后一个字也是全亮
        lastSung == frames.lastIndex -> 1.02f
        lastSung >= 0 -> ends[lastSung]
        else -> -0.02f // 整行还没开始，全部保持暗
    }

    // 只有两档明暗，不用主色，也没有矩形 / 渐变条 / 光晕 / 叠加层。
    // 底层整字画暗，上层整字画亮但按「该字被边界扫过的比例」裁掉右半：
    // 字内从左到右滚动式擦亮，而不是整字亮度从暗插值到亮的渐亮。
    val dark = go.on.copy(alpha = alpha * 0.28f)
    val bright = go.on.copy(alpha = alpha)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val linePx = with(density) { lineHeight.toPx() }
    val baseStyle = TextStyle(
        fontSize = sizeSp.sp,
        fontFamily = family,
        fontWeight = weight,
        lineHeight = lineHeight,
    )
    // 逐字测量结果只随内容 / 字号 / 字体变；逐帧只改裁切比例，不重新排版
    val layouts = remember(line, sizeSp, family, weight, lineHeight) {
        frames.map { measurer.measure(AnnotatedString(it.char.text), baseStyle) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = with(density) { maxWidth.toPx() }
        val places = remember(layouts, maxPx) {
            val out = ArrayList<Triple<Int, Float, Float>>(layouts.size)
            var row = 0
            var x = 0f
            layouts.forEach { r ->
                val w = r.size.width.toFloat()
                if (x > 0f && x + w > maxPx) {
                    row++
                    x = 0f
                }
                out.add(Triple(row, x, w))
                x += w
            }
            out
        }
        val rows = (places.maxOfOrNull { it.first } ?: 0) + 1
        Canvas(
            Modifier.fillMaxWidth().height(with(density) { (rows * linePx).toDp() })
        ) {
            places.forEachIndexed { i, pl ->
                val (row, x, w) = pl
                val y = row * linePx
                val r = layouts[i]
                val h = r.size.height.toFloat()
                // 底层：整字暗
                drawText(r, color = dark, topLeft = Offset(x, y))
                // 该字被边界扫过的比例：0=全暗，1=全亮
                val span = (ends[i] - starts[i]).coerceAtLeast(1e-6f)
                val p = ((edge - starts[i]) / span).coerceIn(0f, 1f)
                if (p > 0f) {
                    drawContext.canvas.save()
                    drawContext.canvas.clipRect(x, y - 2f, x + w * p, y + h + 2f)
                    drawText(r, color = bright, topLeft = Offset(x, y))
                    drawContext.canvas.restore()
                }
            }
        }
    }
}

// =====================================================================
// 图 4：播放列表页
// =====================================================================

@Composable
private fun QueuePane(
    vm: MainViewModel,
    s: Song?,
    onSmallCover: (Float, Float, Float) -> Unit,
    compact: Boolean = false,
    showContent: Boolean = true,
) {
    val go = LocalGo.current
    Column(Modifier.fillMaxSize()) {
        // 26.11.57：横屏展开态点信息区直接收起，不是只把页面标成 COVER
        val land = LocalLandPanel.current
        InfoHeader(
            vm, s, go,
            { if (land?.expanded == true) land.onCollapse() else vm.gotoPlayerPage(PLAYER_COVER) },
            onSmallCover,
        )
        Spacer(Modifier.height(12.dp))
        // 26.11.54：横屏时列表搬到右栏，左栏只留弹性空白托住底部控制区，
        // 保证三页的控制栏纵坐标一致、且不会有两份队列。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showContent) QueueContent(vm, go, Modifier.fillMaxSize())
        }
        PlayerBottomBar(vm, go, compact)
        Spacer(Modifier.height(12.dp))
    }
}

/** 队列内容（播放模式 / 继续播放 / 清空 / 列表）：竖屏与横屏右栏共用 */
@Composable
private fun QueueContent(vm: MainViewModel, go: GoColors, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier) {
        // Segmented：随机 / 列表循环 / 单曲循环。最小高度自适应 + 内容居中，
        // 三段文字不会被竖向裁掉。
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(go.surfaceHi.copy(alpha = 0.45f))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("随机", "列表循环", "单曲循环").forEachIndexed { i, label ->
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("继续播放", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = go.on)
            Spacer(Modifier.weight(1f))
            var confirmClear by remember { mutableStateOf(false) }
            Text(
                "清空", fontSize = 14.sp, color = go.primary,
                modifier = Modifier.clickable { confirmClear = true }.padding(8.dp),
            )
            if (confirmClear) {
                HigConfirm(
                    "清空播放列表？当前正在播放的歌也会被移除",
                    "清空",
                    onDismiss = { confirmClear = false },
                    onOk = { confirmClear = false; vm.clearQueue() },
                )
            }
        }
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
                    // 26.11.68：每首歌右侧新增删除按钮，直接从播放队列移除这一首
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .clickable { vm.removeQueue(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Close, "从播放列表移除",
                            tint = go.muted.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height((0.5f).dp).padding(start = 60.dp).background(go.separator))
            }
        }
    }
}
