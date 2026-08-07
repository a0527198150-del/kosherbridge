package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { LIGHT, DARK, SYSTEM }

private val DarkColorScheme =
  darkColorScheme(
    primary = MinimalistDarkAccent,
    onPrimary = MinimalistDarkBg,
    primaryContainer = MinimalistDarkContainer,
    onPrimaryContainer = MinimalistDarkOnContainer,
    secondary = MinimalistDarkAccent,
    onSecondary = MinimalistDarkBg,
    background = MinimalistDarkBg,
    surface = MinimalistDarkSurface,
    onBackground = MinimalistDarkTextPrimary,
    onSurface = MinimalistDarkTextPrimary,
    surfaceVariant = MinimalistDarkNavBg,
    onSurfaceVariant = MinimalistDarkTextSecondary,
    outline = MinimalistDarkBorder,
    outlineVariant = MinimalistDarkLightBorder,
    error = MinimalistError
  )

private val LightColorScheme =
  lightColorScheme(
    primary = MinimalistAccent,
    onPrimary = Color.White,
    primaryContainer = MinimalistContainer,
    onPrimaryContainer = MinimalistOnContainer,
    secondary = MinimalistAccent,
    onSecondary = Color.White,
    background = MinimalistBg,
    surface = Color.White,
    onBackground = MinimalistTextPrimary,
    onSurface = MinimalistTextPrimary,
    surfaceVariant = MinimalistNavBg,
    onSurfaceVariant = MinimalistTextSecondary,
    outline = MinimalistBorder,
    outlineVariant = MinimalistLightBorder,
    error = MinimalistError
  )

@Composable
fun resolveDarkTheme(mode: ThemeMode): Boolean =
  when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
  }

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Set default to false to showcase the custom Clean Minimalism theme exactly as requested
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
