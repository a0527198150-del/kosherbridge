package com.example.kosherbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.bluetooth.BridgeUiState

/**
 * The local connection journal previously shown under the "יומן חיבור בלוטוס"
 * card on the main settings screen, now its own page inside the connection
 * settings.
 */
@Composable
fun ConnectionLogScreen(
  state: BridgeUiState,
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("יומן חיבור בלוטוס", onBack)

    SettingsCard("יומן חיבור בלוטוס") {
      Text(
        "היומן נשמר מקומית במכשיר. שורות אדומות הן כשלים שהאפליקציה זיהתה.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (state.connectionLog.isEmpty()) {
        Text("עדיין לא נרשמו ניסיונות חיבור.", style = MaterialTheme.typography.bodySmall)
      } else {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          state.connectionLog.takeLast(40).forEach { line ->
            Text(
              line,
              style = MaterialTheme.typography.labelSmall,
              color = if (line.contains("🔴")) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
      SettingRow("נקה יומן חיבור", "מחיקת השורות שנשמרו במכשיר") {
        BridgeHub.service?.clearConnectionLog()
        onSnackbar("יומן החיבור נוקה")
      }
    }
  }
}
