package de.myocd.gomusic

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘图标的统一笔触基准。
 *
 * 26.11.37 起播放器按钮全部改用统一图标包（PlayerIcons.kt），
 * 目前只剩「全屏」这枚辅助图标仍走 Canvas 自绘 —— 图标包里没有对应图形，
 * 保留 [glyphLineWidth] 让它与整排按钮的观感不至于差太多。
 */
private const val GLYPH_GRID = 24f

/** 24dp 设计网格下 1.7 格；按实际绘制尺寸等比缩放 */
fun glyphLineWidth(px: Float): Float = 1.7f * (px / GLYPH_GRID)

/** 均衡器入口：改用统一图标包的 eq 蒙版，[active] 时按主题主色着色 */
@Composable
fun EqIconButton(
    onClick: () -> Unit,
    tint: Color = LocalGo.current.on,
    size: Dp = 24.dp,
    hit: Dp = 36.dp,
    active: Boolean = false,
) {
    val c = if (active) LocalGo.current.primary else tint
    PIconBtn("eq", "均衡器", size = size, minHit = hit, tint = c, onClick = onClick)
}
