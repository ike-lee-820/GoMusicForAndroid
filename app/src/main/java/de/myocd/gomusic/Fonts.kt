@file:OptIn(ExperimentalTextApi::class)

package de.myocd.gomusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight

/** 系统字体（手机自带） */
const val FONT_SYSTEM = 0

/** 思源宋体（可变字体，内置） */
const val FONT_SONG = 1

/** 思源黑体（可变字体，内置） */
const val FONT_HEI = 2

/** 可变字体 wght 轴范围 */
const val WGHT_MIN = 250
const val WGHT_MAX = 900

/** 默认字重 */
const val WGHT_DEFAULT = 450

/** 字体名称，设置界面直接用 */
val FONT_NAMES = listOf("系统字体", "思源宋体", "思源黑体")

/**
 * 按当前选择构造全局字体。
 *
 * 用可变字体的 wght 轴直接指定字重，而不是让系统按 FontWeight 猜：
 * 同一份字体文件注册两个实例——常规用基准字重，粗体在基准上再加一档，
 * 这样界面里的 Bold 仍然有层次，不会被统一成一个粗细。
 */
@Composable
fun rememberAppFont(kind: Int, wght: Int): FontFamily? =
    remember(kind, wght) { buildFontFamily(kind, wght) }

private fun buildFontFamily(kind: Int, wght: Int): FontFamily? {
    if (kind == FONT_SYSTEM) return null
    val res = if (kind == FONT_SONG) R.font.song else R.font.hei
    val w = wght.coerceIn(WGHT_MIN, WGHT_MAX)
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
