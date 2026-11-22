@file:OptIn(ExperimentalTextApi::class)

package de.myocd.gomusic

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/** 系统字体（手机自带） */
const val FONT_SYSTEM = 0

/** 思源宋体（可变字体，内置） */
const val FONT_SONG = 1

/** 思源黑体（可变字体，内置） */
const val FONT_HEI = 2

/** 可变字体 wght 轴范围（设置页共用滑杆） */
const val WGHT_MIN = 250
const val WGHT_MAX = 900

/** 默认字重 */
const val WGHT_DEFAULT = 450

/** 字体名称，设置界面直接用 */
val FONT_NAMES = listOf("系统字体", "思源宋体", "思源黑体")

// -------------------------------------------------------------------
// 歌词排版：与全局字体完全独立的三项设置
// -------------------------------------------------------------------
/** 歌词字体来源 */
const val LYRIC_FONT_FOLLOW = 0   // 跟随全局字体（默认，保持升级前观感）
const val LYRIC_FONT_SYSTEM = 1   // 系统字体
const val LYRIC_FONT_SONG = 2     // 思源宋体
const val LYRIC_FONT_HEI = 3      // 思源黑体
val LYRIC_FONT_NAMES = listOf("跟随全局", "系统字体", "思源宋体", "思源黑体")

/** 歌词字号范围；默认值就是大字歌词登场时的 26sp */
const val LYRIC_SIZE_MIN = 14
const val LYRIC_SIZE_MAX = 64
const val LYRIC_SIZE_DEFAULT = 26

/** 歌词字重范围与默认值 */
const val LYRIC_WGHT_MIN = 100
const val LYRIC_WGHT_MAX = 900
const val LYRIC_WGHT_DEFAULT = 700

/**
 * 同一句歌词折成多行时的行距倍数。
 * 只放大折行行距，不动 LazyColumn 里不同歌词行之间的 spacedBy(18.dp)。
 */
const val LYRIC_LINE_HEIGHT_RATIO = 1.30f

/**
 * 构造歌词专用字体：与 [rememberAppFont] 不同，这里按**精确 wght** 注册单个实例，
 * 不再额外 +250 做 Bold 层次——歌词的粗细完全由设置里的字重决定。
 * 返回 null 表示交给调用方用系统字体 / 全局字体。
 */
@Composable
fun rememberLyricFont(kind: Int, wght: Int): FontFamily? {
    val ctx = LocalContext.current
    return remember(kind, wght) {
        if (kind == LYRIC_FONT_FOLLOW || kind == LYRIC_FONT_SYSTEM) return@remember null
        val res = if (kind == LYRIC_FONT_SONG) R.font.song else R.font.hei
        // 黑体是 CFF2 可变字体，少数机型解析失败时回退系统字体，不带崩页面
        if (kind == LYRIC_FONT_HEI && runCatching {
                ResourcesCompat.getFont(ctx, res)
            }.isFailure) {
            Log.w("GoFont", "歌词思源黑体资源加载失败，回退系统字体")
            return@remember null
        }
        val w = wght.coerceIn(if (kind == LYRIC_FONT_HEI) 100 else WGHT_MIN, WGHT_MAX)
        FontFamily(
            Font(
                res,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            ),
        )
    }
}

/**
 * 按当前选择构造全局字体。
 *
 * 用可变字体的 wght 轴直接指定字重，而不是让系统按 FontWeight 猜：
 * 同一份字体文件注册两个实例——常规用基准字重，粗体在基准上再加一档，
 * 这样界面里的 Bold 仍然有层次，不会被统一成一个粗细。
 */
@Composable
fun rememberAppFont(kind: Int, wght: Int): FontFamily? {
    val ctx = LocalContext.current
    return remember(kind, wght) {
        // 黑体是 CFF2 可变字体，少数机型解析失败时直接回退系统字体，
        // 避免把整个 GoTheme 带崩；宋体走原有路径不变。
        if (kind == FONT_HEI && runCatching {
                ResourcesCompat.getFont(ctx, R.font.hei)
            }.isFailure) {
            Log.w("GoFont", "思源黑体资源加载失败，回退系统字体")
            return@remember null
        }
        buildFontFamily(kind, wght)
    }
}

private fun buildFontFamily(kind: Int, wght: Int): FontFamily? {
    val res = when (kind) {
        FONT_SONG -> R.font.song
        FONT_HEI -> R.font.hei
        else -> return null
    }
    // 思源宋体 wght 250~900，思源黑体 100~900
    val w = wght.coerceIn(if (kind == FONT_HEI) 100 else WGHT_MIN, WGHT_MAX)
    val bw = (w + 250).coerceAtMost(WGHT_MAX)
    return FontFamily(
        Font(
            res,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        ),
        Font(
            res,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(bw)),
        ),
    )
}
