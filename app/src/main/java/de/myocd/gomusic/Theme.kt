package de.myocd.gomusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** 对应网页端 :root 的 CSS 变量；26.11.42 起按亮/暗两套 HIG 语义色生成 */
data class GoColors(
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val on: Color,
    val muted: Color,
    val primary: Color,
    val separator: Color,
    val scrim: Color,
)

private val DarkColors = GoColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceHi = Color(0xFF2C2C2E),
    on = Color(0xFFFFFFFF),
    muted = Color(0xFF98989F),
    primary = Color(0xFFD0BCFF),
    separator = Color(0xFF545458).copy(alpha = 0.65f),
    scrim = Color.Black.copy(alpha = 0.55f),
)

private val LightColors = GoColors(
    bg = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceHi = Color(0xFFE5E5EA),
    on = Color(0xFF000000),
    muted = Color(0xFF6E6E73),
    primary = Color(0xFFD0BCFF),
    separator = Color(0xFF3C3C43).copy(alpha = 0.29f),
    scrim = Color.White.copy(alpha = 0.72f),
)

val LocalGo = compositionLocalOf { DarkColors }

val OnPrimary = Color(0xFF1A1423)

/** 0 亮色 / 1 暗色 / 2 自动 */
fun resolveDark(mode: Int, systemDark: Boolean): Boolean = when (mode) {
    0 -> false
    1 -> true
    else -> systemDark
}

@Composable
fun GoTheme(
    primary: Color,
    font: FontFamily? = null,
    mode: Int = 1,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(mode, isSystemInDarkTheme())
    val p by animateColorAsState(primary, tween(400), label = "primary")
    val base = if (dark) DarkColors else LightColors
    // 三档只换语义色与材质叠加，图层结构（模糊封面一张）保持不变
    val c = base.copy(primary = p)
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = p,
                onPrimary = OnPrimary,
                background = c.bg,
                onBackground = c.on,
                surface = c.surface,
                onSurface = c.on,
                surfaceVariant = c.surfaceHi,
                onSurfaceVariant = c.muted,
                surfaceContainer = c.surface,
                surfaceContainerHigh = c.surface,
                surfaceContainerHighest = c.surfaceHi,
            )
        } else {
            lightColorScheme(
                primary = p,
                onPrimary = OnPrimary,
                background = c.bg,
                onBackground = c.on,
                surface = c.surface,
                onSurface = c.on,
                surfaceVariant = c.surfaceHi,
                onSurfaceVariant = c.muted,
                surfaceContainer = c.surface,
                surfaceContainerHigh = c.surface,
                surfaceContainerHighest = c.surfaceHi,
            )
        },
        typography = appTypography(font),
    ) {
        CompositionLocalProvider(
            LocalGo provides c,
            LocalAppFont provides font,
        ) {
            Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.on) { content() }
        }
    }
}

/**
 * 把全局字体套到整套排版上。
 * [font] 为 null 时用系统字体，保持原本的样子。
 */
fun appTypography(font: FontFamily?): Typography {
    if (font == null) return Typography()
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = font),
        displayMedium = b.displayMedium.copy(fontFamily = font),
        displaySmall = b.displaySmall.copy(fontFamily = font),
        headlineLarge = b.headlineLarge.copy(fontFamily = font),
        headlineMedium = b.headlineMedium.copy(fontFamily = font),
        headlineSmall = b.headlineSmall.copy(fontFamily = font),
        titleLarge = b.titleLarge.copy(fontFamily = font),
        titleMedium = b.titleMedium.copy(fontFamily = font),
        titleSmall = b.titleSmall.copy(fontFamily = font),
        bodyLarge = b.bodyLarge.copy(fontFamily = font),
        bodyMedium = b.bodyMedium.copy(fontFamily = font),
        bodySmall = b.bodySmall.copy(fontFamily = font),
        labelLarge = b.labelLarge.copy(fontFamily = font),
        labelMedium = b.labelMedium.copy(fontFamily = font),
        labelSmall = b.labelSmall.copy(fontFamily = font),
    )
}

/** 当前全局字体；null 表示用系统字体 */
val LocalAppFont = compositionLocalOf<FontFamily?> { null }
