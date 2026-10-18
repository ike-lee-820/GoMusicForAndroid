package de.myocd.gomusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
fun GoTheme(primary: Color, content: @Composable () -> Unit) {
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
        )
    ) {
        CompositionLocalProvider(LocalGo provides c) {
            Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.on) { content() }
        }
    }
}
