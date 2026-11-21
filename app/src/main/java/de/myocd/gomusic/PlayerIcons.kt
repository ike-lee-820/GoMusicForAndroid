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
 * 播放器图标统一规格。
 *
 * 26.11.39 前 [PIconBtn] 的 size 是「外层圆底」，图标还要再乘 0.55f，
 * 于是同一排里传 36 / 30 / 24 会画出 19.8 / 16.5 / 13.2dp 三种大小 ——
 * 截图里前几个大、后几个小就是这么来的。
 * 现在 size 一律表示图标实际绘制尺寸，整排按钮只认这一组常量。
 */
object PlayerIconSpec {
    /** 功能行与次要控制键的图标尺寸 */
    val Func: Dp = 24.dp
    /** 主播放键图标尺寸（圆底由调用方用 box 指定） */
    val Main: Dp = 32.dp
}

@Composable
fun PIconBtn(
    key: String,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = PlayerIconSpec.Func,
    tint: Color = LocalGo.current.on,
    bg: Color = Color.Transparent,
    minHit: Dp = 36.dp,
    box: Dp? = null,
    onClick: () -> Unit,
) {
    // 圆底比图标宽 12dp；主播放键可显式给一个更大的圆底。
    val boxSize = box ?: (size + 12.dp)
    val hit = if (minHit > boxSize) minHit else boxSize
    Box(
        modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(boxSize).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            PlayerIcon(key, desc, modifier = Modifier.size(size), tint = tint)
        }
    }
}
