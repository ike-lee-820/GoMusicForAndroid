package de.myocd.gomusic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放器统一图标包（与电脑端开发文档 §14 同一套 17 枚 80×80 PNG）。
 *
 * 源图是「纯黑 + alpha」的蒙版：用 ColorFilter.tint 按 SrcIn 着色后只保留形状，
 * 所以横屏白色、竖屏主题色、播放键黑色圆底都能共用同一份资源，
 * 不再出现 Material 线性图标与自绘 Canvas 图标混排、笔触粗细不一的问题。
 */
object PlayerIcons {
    fun res(key: String): Int = when (key) {
        "surround"   -> R.drawable.ic_p_surround
        "mv"         -> R.drawable.ic_p_mv
        "prev"       -> R.drawable.ic_p_prev
        "next"       -> R.drawable.ic_p_next
        "download"   -> R.drawable.ic_p_download
        "share"      -> R.drawable.ic_p_share
        "repeat"     -> R.drawable.ic_p_repeat
        "repeat_one" -> R.drawable.ic_p_repeat_one
        "eq"         -> R.drawable.ic_p_eq
        "record"     -> R.drawable.ic_p_record
        "queue"      -> R.drawable.ic_p_queue
        "pause"      -> R.drawable.ic_p_pause
        "add"        -> R.drawable.ic_p_add
        "play"       -> R.drawable.ic_p_play
        "speed"      -> R.drawable.ic_p_speed
        "shuffle"    -> R.drawable.ic_p_shuffle
        "quality"    -> R.drawable.ic_p_quality
        else         -> R.drawable.ic_p_play
    }
}

@Composable
fun PlayerIcon(
    key: String,
    desc: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Image(
        painter = painterResource(PlayerIcons.res(key)),
        contentDescription = desc,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * 与 [IconBtn] 完全同规格的圆形图标按钮，只是图标换成统一图标包。
 * 触控区、圆底、图标占比（size * 0.55f）都保持一致，整排图标不会参差。
 */
@Composable
fun PIconBtn(
    key: String,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = LocalGo.current.on,
    bg: Color = Color.Transparent,
    minHit: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val hit = if (size < minHit) minHit else size
    Box(
        modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            PlayerIcon(key, desc, modifier = Modifier.size(size * 0.55f), tint = tint)
        }
    }
}
