@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package de.myocd.gomusic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
import kotlin.math.roundToInt

fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

// ---------- 基础文字与间距 ----------

/** 等宽数字的样式常量：复用同一实例，避免每次组合都新建 TextStyle 影响文本测量缓存 */
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun Txt(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalGo.current.on,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    tnum: Boolean = false,
) {
    Text(
        text,
        modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        // 列表里不用等宽数字：字体特性会让文本排版变慢，滚动时能省下不少开销
        style = if (tnum) Tnum else LocalTextStyle.current,
    )
}

@Composable
fun HS(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun VS(h: Int) = Spacer(Modifier.height(h.dp))

/** 跑马灯：文字超出容器才滚动，两端停留 1.5 秒，约 40px/s */
@Composable
fun MarqueeText(
    text: String,
    size: Int,
    weight: FontWeight,
    modifier: Modifier = Modifier,
    color: Color = LocalGo.current.on,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = 1500,
            initialDelayMillis = 1500,
            velocity = 40.dp,
        ),
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = 1,
        softWrap = false,
        textAlign = align,
    )
}

// ---------- 点击缩放 ----------

fun Modifier.clickScale(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, tween(120), label = "press")
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
}

// ---------- 图片 ----------

/**
 * 带来源角标的封面：网易云曲库的结果在封面右下角加一个「网易云」小标，
 * 用来和本站曲库区分。角标是纯文字，不用图标。
 */
@Composable
fun CoverWithSource(
    cover: String?,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    circle: Boolean = false,
    source: String = SRC_LOCAL,
) {
    val netease = source == SRC_NETEASE
    Box(modifier) {
        Cover(cover, Modifier.fillMaxSize(), radius, circle)
        if (netease) {
            val go = LocalGo.current
            Box(
                Modifier.align(Alignment.BottomEnd).padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(go.surfaceHi.copy(alpha = 0.92f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Txt("网易云", 8, color = go.on, maxLines = 1)
            }
        }
    }
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, radius: Dp = 8.dp, circle: Boolean = false) {
    val go = LocalGo.current
    val shape = if (circle) CircleShape else RoundedCornerShape(radius)
    val model = remember(url) { imageModel(url) }
    Box(modifier.clip(shape).background(go.surfaceHi), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, tint = go.muted.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
        if (model != null) {
            AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

// ---------- 按钮 ----------

/** 圆形图标按钮：视觉尺寸 size，触控区不小于 40dp */
@Composable
fun IconBtn(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = LocalGo.current.on,
    bg: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    val hit = if (size < 40.dp) 40.dp else size
    Box(
        modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun GoButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val go = LocalGo.current
    val bg = if (primary) go.primary else go.surfaceHi
    val fg = if (primary) OnPrimary else go.on
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(40.dp)
            .clickScale(enabled && !loading, onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
            HS(6)
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            HS(6)
        }
        Txt(text, 14, weight = if (primary) FontWeight.SemiBold else FontWeight.Medium, color = fg, maxLines = 1)
    }
}

// ---------- 输入框 ----------

@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
) {
    val go = LocalGo.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = go.on, fontSize = 15.sp),
        cursorBrush = SolidColor(go.primary),
        modifier = modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().clip(shape).background(go.surface)
                    .border(BorderStroke(1.dp, if (focused) go.primary else Color.Transparent), shape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    Icon(Icons.Outlined.Search, null, tint = go.muted, modifier = Modifier.size(20.dp))
                    HS(8)
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = go.muted, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty() && showIcon) {
                    IconBtn(Icons.Outlined.Close, "清除", size = 28.dp) { onChange("") }
                }
            }
        },
    )
}

// ---------- 列表行 / 卡片 ----------

/** 暂停时用静态柱，避免每帧重组 */
@Composable
fun Bars(playing: Boolean, color: Color) {
    if (playing) AnimatedBars(color) else StaticBars(color)
}

@Composable
private fun BarRow(a: Float, b: Float, c: Float, color: Color) {
    Row(
        Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(a, b, c).forEach { v ->
            Box(
                Modifier.width(3.dp).fillMaxHeight(v)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun StaticBars(color: Color) = BarRow(0.5f, 0.5f, 0.5f, color)

@Composable
private fun AnimatedBars(color: Color) {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    BarRow(a, b, c, color)
}

@Composable
fun SongRow(
    song: Song,
    current: Boolean,
    playing: Boolean,
    downloaded: Boolean = false,
    downloading: Boolean = false,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    val go = LocalGo.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CoverWithSource(song.coverUrl, Modifier.fillMaxSize(), 8.dp, source = song.source)
            if (current) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Bars(playing, go.primary) }
            }
        }
        HS(12)
        Column(Modifier.weight(1f)) {
            Txt(song.title, 15, weight = FontWeight.Medium, color = if (current) go.primary else go.on, maxLines = 1)
            Txt("${song.artist} · ${song.album}", 12, color = go.muted, maxLines = 1)
        }
        if (onDownload != null) {
            IconBtn(
                if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                if (downloaded) "已下载" else "下载歌曲",
                tint = when {
                    downloaded -> go.primary
                    downloading -> go.muted
                    else -> go.on
                },
                onClick = onDownload,
            )
        }
        IconBtn(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单", onClick = onAdd)
    }
}

/** 专辑 / 歌单等「封面 + 标题 + 副标题 + 右侧加入歌单」的通用行 */
@Composable
fun GroupRow(
    title: String,
    subtitle: String,
    cover: String?,
    onClick: () -> Unit,
    onAdd: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null,
    source: String = SRC_LOCAL,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverWithSource(cover, Modifier.size(48.dp), 8.dp, source = source)
        HS(12)
        Column(Modifier.weight(1f)) {
            Txt(title, 15, weight = FontWeight.Medium, maxLines = 1)
            Txt(subtitle, 12, color = LocalGo.current.muted, maxLines = 1)
        }
        if (onAdd != null) IconBtn(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单", onClick = onAdd)
        trailing?.invoke()
    }
}

@Composable
fun GroupCard(
    title: String,
    subtitle: String,
    cover: String?,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    source: String = SRC_LOCAL,
) {
    val go = LocalGo.current
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth()) {
            CoverWithSource(cover, Modifier.fillMaxWidth().aspectRatio(1f), 16.dp, source = source)
            IconBtn(
                Icons.AutoMirrored.Outlined.PlaylistAdd, "加入歌单",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                bg = go.surfaceHi, onClick = onAdd,
            )
        }
        VS(6)
        Txt(title, 15, weight = FontWeight.Medium, maxLines = 1)
        Txt(subtitle, 12, color = go.muted, maxLines = 1)
    }
}

// ---------- 底部弹层 ----------

@Composable
fun GoSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val go = LocalGo.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = go.surface,
        contentColor = go.on,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp).size(36.dp, 4.dp)
                    .background(go.muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            )
        },
    ) {
        Column(Modifier.heightIn(max = maxH).navigationBarsPadding(), content = content)
    }
}

// ---------- 提示 ----------

@Composable
fun GoToast(msg: String?, modifier: Modifier = Modifier) {
    val go = LocalGo.current
    var last by remember { mutableStateOf("") }
    LaunchedEffect(msg) { if (msg != null) last = msg }
    AnimatedVisibility(msg != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(go.surfaceHi)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) { Txt(msg ?: last, 14) }
    }
}

// ---------- 进度条 ----------

@Composable
fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val go = LocalGo.current
    var drag by remember { mutableStateOf<Float?>(null) }
    var w by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .onSizeChanged { w = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    detectTapGestures { o -> onSeek((o.x / w * duration).toLong().coerceIn(0, duration)) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / w).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / w).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val track = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(track).background(Color.White.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(track).background(go.primary))
            Box(
                Modifier.offset { IntOffset((frac * w - with(density) { 6.dp.toPx() }).roundToInt(), 0) }
                    .size(12.dp).background(go.primary, CircleShape)
            )
            if (drag != null) {
                Box(
                    Modifier.offset {
                        IntOffset(
                            (frac * w - with(density) { 24.dp.toPx() }).roundToInt(),
                            -with(density) { 28.dp.toPx() }.roundToInt(),
                        )
                    }.clip(RoundedCornerShape(8.dp)).background(go.surfaceHi).padding(horizontal = 8.dp, vertical = 4.dp)
                ) { Txt(fmt((frac * duration).toLong()), 12, tnum = true) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(fmt(if (drag != null) (frac * duration).toLong() else position), 12, color = go.muted, tnum = true)
            Txt(fmt(duration), 12, color = go.muted, tnum = true)
        }
    }
}

// ---------- 拖动排序列表 ----------

/** 每行固定高度；handle 修饰符加在把手图标上即可拖动 */
@Composable
fun <T> ReorderList(
    items: List<T>,
    rowHeight: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    val go = LocalGo.current
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    Column {
        items.forEachIndexed { i, item ->
            val handle = Modifier.pointerInput(i, item) {
                detectDragGestures(
                    onDragStart = { dragIdx = i; dy = 0f },
                    onDragEnd = {
                        if (dragIdx >= 0) {
                            val to = (dragIdx + (dy / rowPx).roundToInt()).coerceIn(0, items.lastIndex)
                            if (to != dragIdx) onMove(dragIdx, to)
                        }
                        dragIdx = -1
                        dy = 0f
                    },
                    onDragCancel = { dragIdx = -1; dy = 0f },
                ) { change, amount ->
                    change.consume()
                    dy += amount.y
                }
            }
            Box(
                Modifier.fillMaxWidth().height(rowHeight)
                    .zIndex(if (dragIdx == i) 1f else 0f)
                    .graphicsLayer { translationY = if (dragIdx == i) dy else 0f }
                    .background(if (dragIdx == i) go.surfaceHi else Color.Transparent)
            ) { row(i, item, handle) }
        }
    }
}

/** 拖动排序状态：跨重组保留当前拖动项 */
class ReorderState {
    var dragIdx by mutableIntStateOf(-1)
    var dy by mutableFloatStateOf(0f)
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/**
 * 惰性版拖动排序：只组合可见行，用于歌单页 / 播放队列等可能很长的列表。
 * 用法与 [ReorderList] 一致，但要在 LazyColumn 的 content 里调用。
 */
fun <T> LazyListScope.reorderItems(
    state: ReorderState,
    items: List<T>,
    rowHeight: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    key: ((index: Int, item: T) -> Any)? = null,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    itemsIndexed(items, key = key, contentType = { _, _ -> "reorder" }) { i, item ->
        val go = LocalGo.current
        val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
        val handle = Modifier.pointerInput(i) {
            detectDragGestures(
                onDragStart = { state.dragIdx = i; state.dy = 0f },
                onDragEnd = {
                    if (state.dragIdx >= 0) {
                        val to = (state.dragIdx + (state.dy / rowPx).roundToInt()).coerceIn(0, items.lastIndex)
                        if (to != state.dragIdx) onMove(state.dragIdx, to)
                    }
                    state.dragIdx = -1
                    state.dy = 0f
                },
                onDragCancel = { state.dragIdx = -1; state.dy = 0f },
            ) { change, amount ->
                change.consume()
                state.dy += amount.y
            }
        }
        Box(
            Modifier.fillMaxWidth().height(rowHeight)
                .zIndex(if (state.dragIdx == i) 1f else 0f)
                .graphicsLayer { translationY = if (state.dragIdx == i) state.dy else 0f }
                .background(if (state.dragIdx == i) go.surfaceHi else Color.Transparent)
        ) { row(i, item, handle) }
    }
}
