@file:OptIn(ExperimentalTextApi::class)

package de.myocd.gomusic

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** 系统字体（手机自带） */
const val FONT_SYSTEM = 0

/** 思源宋体（可变字体，按需下载） */
const val FONT_SONG = 1

/** 可变字体 wght 轴范围 */
const val WGHT_MIN = 250
const val WGHT_MAX = 900

/** 默认字重 */
const val WGHT_DEFAULT = 450

/** 字体名称，设置界面直接用 */
val FONT_NAMES = listOf("系统字体", "思源宋体")

/**
 * 按当前选择构造全局字体。
 *
 * 用可变字体的 wght 轴直接指定字重，而不是让系统按 FontWeight 猜：
 * 同一份字体文件注册两个实例——常规用基准字重，粗体在基准上再加一档，
 * 这样界面里的 Bold 仍然有层次，不会被统一成一个粗细。
 *
 * 思源宋体不再内置在 APK 里，改由 [SongFont] 按需下载；
 * 没下载过就返回 null（用系统字体），界面不会崩。
 *
 * [ready] 是「字体就绪版本号」：下载完成后它会变，
 * 从而让这里重新构造一次字体族，界面立刻换上宋体。
 */
@Composable
fun rememberAppFont(kind: Int, wght: Int, ready: Int = 0): FontFamily? {
    val ctx = LocalContext.current
    return remember(kind, wght, ready) { buildFontFamily(ctx, kind, wght) }
}

private fun buildFontFamily(ctx: Context, kind: Int, wght: Int): FontFamily? {
    if (kind != FONT_SONG) return null
    val f: File = SongFont.file(ctx)
    // 文件不在就用系统字体：宁可显示默认字体，也不要加载失败崩掉
    if (!SongFont.has(ctx)) return null
    val w = wght.coerceIn(WGHT_MIN, WGHT_MAX)
    val bw = (w + 250).coerceAtMost(WGHT_MAX)
    return FontFamily(
        Font(
            f,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        ),
        Font(
            f,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(bw)),
        ),
    )
}
