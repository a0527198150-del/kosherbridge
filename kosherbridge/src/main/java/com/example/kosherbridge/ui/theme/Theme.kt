package com.example.kosherbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** App-wide appearance setting: follow the system, or force light / dark. */
enum class ThemeMode(val label: String) {
  SYSTEM("אוטומטי"),
  LIGHT("בהיר"),
  DARK("כהה"),
}

private val LightColors = lightColorScheme(
  primary = Color(0xFF00696D),
  onPrimary = Color.White,
  primaryContainer = Color(0xFF9CF1F5),
  onPrimaryContainer = Color(0xFF002021),
  secondary = Color(0xFF4A6365),
  secondaryContainer = Color(0xFFCCE8EA),
  onSecondaryContainer = Color(0xFF051F21),
  tertiary = Color(0xFF4A5D92),
  background = Color(0xFFF7FBFA),
  surface = Color(0xFFF7FBFA),
  error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFF80D4D9),
  onPrimary = Color(0xFF003739),
  primaryContainer = Color(0xFF005053),
  onPrimaryContainer = Color(0xFF9CF1F5),
  secondary = Color(0xFFB1CCCE),
  onSecondary = Color(0xFF1B3436),
  secondaryContainer = Color(0xFF324B4D),
  onSecondaryContainer = Color(0xFFCCE8EA),
  tertiary = Color(0xFFB6C4FF),
  background = Color(0xFF0F1515),
  surface = Color(0xFF0F1515),
  error = Color(0xFFFFB4AB),
)

@Composable
fun KosherBridgeTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val darkTheme = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
