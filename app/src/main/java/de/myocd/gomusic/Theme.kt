package de.myocd.gomusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 对应网页端 :root 的 CSS 变量 */
data class GoColors(
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val on: Color,
    val muted: Color,
    val primary: Color,
)

val LocalGo = compositionLocalOf {
    GoColors(
        bg = Color(0xFF121014),
        surface = Color(0xFF1C1A20),
        surfaceHi = Color(0xFF2A2730),
        on = Color(0xFFECE6F0),
        muted = Color(0xFFA8A2B0),
        primary = Color(0xFFD0BCFF),
    )
}

val OnPrimary = Color(0xFF1A1423)

@Composable
fun GoTheme(
    primary: Color,
    font: androidx.compose.ui.text.font.FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val p by animateColorAsState(primary, tween(400), label = "primary")
    val c = GoColors(
        bg = Color(0xFF121014),
        surface = Color(0xFF1C1A20),
        surfaceHi = Color(0xFF2A2730),
        on = Color(0xFFECE6F0),
        muted = Color(0xFFA8A2B0),
        primary = p,
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
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
        ),
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
fun appTypography(font: androidx.compose.ui.text.font.FontFamily?): Typography {
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
