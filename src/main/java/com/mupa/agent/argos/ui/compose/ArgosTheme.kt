package com.mupa.agent.argos.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mupa.agent.argos.managers.SettingsManager

object ArgosTokens {
    val PrimaryBlue = Color(0xFF1E6FFF)
    val Navy = Color(0xFF041B4D)
    val Dark = Color(0xFF07173A)
    val White = Color(0xFFFFFFFF)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Offline = Color(0xFF6B7280)
}

enum class ArgosWindowClass {
    Compact,
    Medium,
    Expanded,
}

data class ArgosDimens(
    val padding: Dp,
    val margin: Dp,
    val gap: Dp,
    val headerHeight: Dp,
    val footerHeight: Dp,
    val appIconSize: Dp,
    val minTouchTarget: Dp,
) {
    val appLabelMaxLines: Int = 2
}

val LocalArgosWindowClass = staticCompositionLocalOf { ArgosWindowClass.Medium }
val LocalArgosDimens = staticCompositionLocalOf {
    ArgosDimens(
        padding = 12.dp,
        margin = 12.dp,
        gap = 12.dp,
        headerHeight = 56.dp,
        footerHeight = 32.dp,
        appIconSize = 56.dp,
        minTouchTarget = 48.dp,
    )
}

private val DarkScheme = darkColorScheme(
    primary = ArgosTokens.PrimaryBlue,
    onPrimary = ArgosTokens.White,
    primaryContainer = Color(0xFF0E2A66),
    onPrimaryContainer = Color(0xFFDDE7FF),

    secondary = Color(0xFF8AB4FF),
    onSecondary = ArgosTokens.Dark,
    secondaryContainer = Color(0xFF102A66),
    onSecondaryContainer = Color(0xFFE6EEFF),

    background = ArgosTokens.Dark,
    onBackground = ArgosTokens.White,
    surface = ArgosTokens.Dark,
    onSurface = ArgosTokens.White,
    surfaceVariant = ArgosTokens.Navy,
    onSurfaceVariant = Color(0xFFC9D6FF),

    error = ArgosTokens.Danger,
    onError = ArgosTokens.White,
)

private val LightScheme = lightColorScheme(
    primary = ArgosTokens.PrimaryBlue,
    onPrimary = ArgosTokens.White,
    secondary = ArgosTokens.Navy,
    onSecondary = ArgosTokens.White,
    background = ArgosTokens.White,
    onBackground = ArgosTokens.Dark,
    surface = ArgosTokens.White,
    onSurface = ArgosTokens.Dark,
    surfaceVariant = Color(0xFFF1F5FF),
    onSurfaceVariant = Color(0xFF243B77),
    error = ArgosTokens.Danger,
    onError = ArgosTokens.White,
)

@OptIn(ExperimentalTextApi::class)
private fun argosTypography(
    windowClass: ArgosWindowClass,
    scale: Float,
): androidx.compose.material3.Typography {
    fun t(sizeSp: Int, weight: FontWeight, lineHeightSp: Int): TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = weight,
            fontSize = (sizeSp * scale).sp,
            lineHeight = (lineHeightSp * scale).sp,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )

    val titleBase = when (windowClass) {
        ArgosWindowClass.Compact -> 16
        ArgosWindowClass.Medium -> 18
        ArgosWindowClass.Expanded -> 20
    }
    val textBase = when (windowClass) {
        ArgosWindowClass.Compact -> 12
        ArgosWindowClass.Medium -> 14
        ArgosWindowClass.Expanded -> 16
    }
    val captionBase = when (windowClass) {
        ArgosWindowClass.Compact -> 10
        ArgosWindowClass.Medium -> 12
        ArgosWindowClass.Expanded -> 14
    }

    return androidx.compose.material3.Typography(
        displayLarge = t(titleBase + 30, FontWeight.SemiBold, titleBase + 36),
        displayMedium = t(titleBase + 22, FontWeight.SemiBold, titleBase + 28),
        displaySmall = t(titleBase + 16, FontWeight.SemiBold, titleBase + 22),
        headlineLarge = t(titleBase + 12, FontWeight.SemiBold, titleBase + 18),
        headlineMedium = t(titleBase + 10, FontWeight.SemiBold, titleBase + 16),
        headlineSmall = t(titleBase + 8, FontWeight.SemiBold, titleBase + 14),
        titleLarge = t(titleBase + 4, FontWeight.SemiBold, titleBase + 10),
        titleMedium = t(titleBase + 2, FontWeight.Medium, titleBase + 8),
        titleSmall = t(titleBase, FontWeight.Medium, titleBase + 6),
        bodyLarge = t(textBase + 2, FontWeight.Normal, textBase + 8),
        bodyMedium = t(textBase, FontWeight.Normal, textBase + 6),
        bodySmall = t((textBase - 2).coerceAtLeast(10), FontWeight.Normal, (textBase + 4).coerceAtLeast(14)),
        labelLarge = t(captionBase + 4, FontWeight.Medium, captionBase + 10),
        labelMedium = t(captionBase + 2, FontWeight.Medium, captionBase + 8),
        labelSmall = t(captionBase, FontWeight.Medium, captionBase + 6),
    )
}

@Composable
fun ArgosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme
    val cfg = LocalConfiguration.current
    val context = LocalContext.current
    val settings = remember { SettingsManager(context.applicationContext) }
    val fontMode by settings.uiFontModeFlow().collectAsState(initial = settings.getUiFontModeCached())
    val iconMode by settings.uiIconModeFlow().collectAsState(initial = settings.getUiIconModeCached())

    val baseWindowClass = when {
        cfg.screenWidthDp <= 400 -> ArgosWindowClass.Compact
        cfg.screenWidthDp <= 720 -> ArgosWindowClass.Medium
        else -> ArgosWindowClass.Expanded
    }

    fun forceClass(mode: String, fallback: ArgosWindowClass): ArgosWindowClass {
        return when (mode.trim().lowercase()) {
            "small" -> ArgosWindowClass.Compact
            "medium" -> ArgosWindowClass.Medium
            "large" -> ArgosWindowClass.Expanded
            else -> fallback
        }
    }

    val typographyClass = forceClass(fontMode, baseWindowClass)
    val iconClass = forceClass(iconMode, baseWindowClass)
    val typography = remember(typographyClass) { argosTypography(typographyClass, scale = 1f) }

    val dimens = remember(baseWindowClass, iconClass) {
        val padding = when (baseWindowClass) {
            ArgosWindowClass.Compact -> 8.dp
            ArgosWindowClass.Medium -> 12.dp
            ArgosWindowClass.Expanded -> 16.dp
        }
        val headerH = when (baseWindowClass) {
            ArgosWindowClass.Compact -> 48.dp
            else -> 56.dp
        }
        val iconSize = when (iconClass) {
            ArgosWindowClass.Compact -> 48.dp
            ArgosWindowClass.Medium -> 56.dp
            ArgosWindowClass.Expanded -> 64.dp
        }
        ArgosDimens(
            padding = padding,
            margin = padding,
            gap = padding,
            headerHeight = headerH,
            footerHeight = 32.dp,
            appIconSize = iconSize,
            minTouchTarget = 48.dp,
        )
    }

    CompositionLocalProvider(
        LocalArgosWindowClass provides baseWindowClass,
        LocalArgosDimens provides dimens,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content,
        )
    }
}
