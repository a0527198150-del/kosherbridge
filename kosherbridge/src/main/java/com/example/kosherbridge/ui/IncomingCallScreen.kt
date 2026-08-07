package com.example.kosherbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState

@Composable
fun IncomingCallScreen(
  number: String?,
  name: String?,
  state: CallInfo?,
  onAnswer: () -> Unit,
  onReject: () -> Unit,
  onHangup: () -> Unit,
  onToggleAudio: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val ringing = state?.state == CallState.INCOMING || state?.state == CallState.WAITING
  val active = state?.state == CallState.ACTIVE
  val outgoing = state?.state == CallState.DIALING || state?.state == CallState.ALERTING
  val title = when {
    ringing -> "שיחה נכנסת"
    active -> "בשיחה"
    outgoing -> "מחייג..."
    else -> "שיחה"
  }
  val displayName = name ?: number

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(Color(0xFF005053), Color(0xFF001F20)))),
  ) {
    Column(
      modifier = Modifier.align(Alignment.Center).padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        title,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
      )
      Spacer(Modifier.height(28.dp))
      Box(
        modifier = Modifier
          .size(96.dp)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          displayName?.let { initials(it) } ?: "?",
          color = Color.White,
          style = MaterialTheme.typography.headlineLarge,
          fontWeight = FontWeight.Bold,
        )
      }
      Spacer(Modifier.height(20.dp))
      Text(
        displayName ?: "לא ידוע",
        color = Color.White,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      if (number != null && number != name) {
        Spacer(Modifier.height(4.dp))
        Text(
          number,
          color = Color.White.copy(alpha = 0.7f),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Spacer(Modifier.height(48.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        when {
          ringing -> {
            CallActionButton("דחה", Icons.Filled.CallEnd, Color(0xFFD32F2F), onReject)
            CallActionButton("ענה", Icons.Filled.Call, Color(0xFF00875A), onAnswer)
          }
          active -> {
            CallActionButton("נתק", Icons.Filled.CallEnd, Color(0xFFD32F2F), onHangup)
            CallActionButton("שמע", Icons.Filled.VolumeUp, Color(0xFF1E88E5), onToggleAudio)
          }
          else -> {
            Text(
              "השיחה מסתיימת...",
              color = Color.White.copy(alpha = 0.8f),
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CallActionButton(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    FilledIconButton(
      onClick = onClick,
      modifier = Modifier.size(72.dp),
      colors = IconButtonDefaults.filledIconButtonColors(
        containerColor = color,
        contentColor = Color.White,
      ),
    ) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
  }
}
