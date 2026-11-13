package de.myocd.gomusic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘图标的统一笔触基准。
 *
 * 之前 MV 用 1.7f 线宽、全屏用 1.8f，两个 Canvas 各写各的网格，
 * 一排按钮放一起笔触粗细明显不一样 —— 这就是「MV 图标不统一」的来源。
 * 现在 MV / 全屏 / 均衡器都走 [glyphLineWidth]，与原版按钮保持同一观感。
 */
private const val GLYPH_GRID = 24f

/** 24dp 设计网格下 1.7 格；按实际绘制尺寸等比缩放 */
fun glyphLineWidth(px: Float): Float = 1.7f * (px / GLYPH_GRID)

/** 均衡器入口：三条竖推子 + 滑块点，[active] 时推子错开表示已开启音效 */
@Composable
fun EqIconButton(
    onClick: () -> Unit,
    tint: Color = LocalGo.current.on,
    size: Dp = 24.dp,
    hit: Dp = 36.dp,
    active: Boolean = false,
) {
    Box(
        Modifier.size(hit).clickScale(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val u = this.size.width / GLYPH_GRID
            val w = glyphLineWidth(this.size.width)
            val xs = floatArrayOf(6f, 12f, 18f)
            val knobY = floatArrayOf(9f, 13.5f, 7.5f)
            xs.forEachIndexed { i, x ->
                drawLine(tint, Offset(x * u, 4f * u), Offset(x * u, 20f * u), w)
                val ky = if (active) knobY[i] else 12f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset((x - 2.1f) * u, (ky - 1.5f) * u),
                    size = Size(4.2f * u, 3f * u),
                    cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
                )
            }
        }
    }
}
