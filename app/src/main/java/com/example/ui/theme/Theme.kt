package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF02171B),
    primaryContainer = Color(0xFF0A333D),
    onPrimaryContainer = CyanPrimary,
    secondary = BlueSecondary,
    onSecondary = Color(0xFFF8FAFC),
    secondaryContainer = Color(0xFF13294B),
    onSecondaryContainer = Color(0xFF93C5FD),
    tertiary = EmeraldSuccess,
    onTertiary = Color(0xFF022C22),
    background = StudioVoid,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = StudioVoid,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = StudioSurfaceElevated,
    surfaceContainerHigh = StudioSurfaceHighlight,
    outline = StudioBorder,
    outlineVariant = Color(0xFF19273B),
    error = CrimsonError,
    onError = Color(0xFFFFFFFF),
  )

private val StudioShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun CompressXStudioTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = StudioDarkColorScheme,
    typography = CompressXTypography,
    shapes = StudioShapes,
    content = content,
  )
}
