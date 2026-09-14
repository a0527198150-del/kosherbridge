package com.example.kosherbridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(4.dp))
      content()
    }
  }
}

@Composable
internal fun SettingRow(title: String, subtitle: String?, error: Boolean = false, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      subtitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Icon(
      Icons.Filled.ChevronRight,
      contentDescription = null,
      tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onChecked)
  }
}

@Composable
internal fun DiagRow(label: String, value: String, ok: Boolean) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
internal fun SubPageHeader(title: String, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
    }
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}

/**
 * Opens the wireless-debugging page where it is reachable, falling back to the
 * documented developer-options action.
 *
 * Shared by both shell-channel screens, which open the same page for the same
 * reason: wireless debugging is the gate on every route to a shell identity,
 * whether the app gets there through Shizuku or through its own ADB client.
 *
 * The wireless-debugging activity is not a public action, so it is tried by
 * component first and the documented action is the fallback. A false return
 * lets the caller say "open it yourself" instead of leaving a button that
 * silently does nothing - which is what happens on a build that hides it, and
 * on Android 11+ for any app that has not declared the settings package in its
 * <queries> (this one has).
 */
internal fun openDeveloperOptionsScreen(context: Context): Boolean {
  val candidates = listOf(
    Intent().setComponent(
      ComponentName(
        "com.android.settings",
        "com.android.settings.Settings\$WirelessDebuggingActivity",
      ),
    ),
    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
  )
  for (intent in candidates) {
    val ok = runCatching {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    }.getOrDefault(false)
    if (ok) return true
  }
  return false
}
