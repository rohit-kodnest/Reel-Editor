package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = StudioPrimary,
    secondary = StudioSecondary,
    tertiary = StudioTeritary,
    background = StudioBackground,
    surface = StudioCard,
    onPrimary = TextWhite,
    onSecondary = TextWhite,
    onTertiary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextWhite,
    surfaceVariant = StudioCardPressed,
    onSurfaceVariant = TextGray
  )

private val LightColorScheme = DarkColorScheme // Enforce our gorgeous premium dark theme always, matching standard high-end video editors ( Premiere, CapCut, DaVinci)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme for cinematic vibe
  dynamicColor: Boolean = false, // Disable dynamic light schemes so our branding remains perfectly solid
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
