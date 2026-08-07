package com.example.kosherbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState

object Notifications {
  private const val CH_BRIDGE = "bridge"
  private const val CH_CALLS = "calls"
  private const val NOTIF_BRIDGE = 1
  private const val NOTIF_CALL = 2

  fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CH_BRIDGE, "גשר בלוטוס", NotificationManager.IMPORTANCE_LOW).apply {
        description = "סטטוס החיבור לטלפון הכשר"
        setShowBadge(false)
      },
    )
    nm.createNotificationChannel(
      NotificationChannel(CH_CALLS, "שיחות", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "שיחות נכנסות ויוצאות דרך הטלפון הכשר"
        enableVibration(true)
      },
    )
  }

  fun bridgeNotification(context: Context, text: String): Notification {
    val open = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val disconnect = PendingIntent.getService(
      context,
      1,
      Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_DISCONNECT),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, CH_BRIDGE)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("גשר כשר")
      .setContentText(text)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(open)
      .addAction(0, "ניתוק", disconnect)
      .build()
  }

  fun showIncomingCall(context: Context, title: String, number: String?, fullScreen: Boolean) {
    val full = PendingIntent.getActivity(
      context,
      2,
      IncomingCallActivity.createIntent(context, number, null),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val answer = PendingIntent.getService(
      context,
      3,
      Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_ANSWER),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val reject = PendingIntent.getService(
      context,
      4,
      Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_REJECT),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(context, CH_CALLS)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("שיחה נכנסת")
      .setContentText(title)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      // No DEFAULT_SOUND here: IncomingCallActivity plays the ringtone itself.
      .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
      .setContentIntent(full)
      .addAction(0, "ענה", answer)
      .addAction(0, "דחה", reject)
    if (fullScreen) {
      @Suppress("DEPRECATION")
      builder.setFullScreenIntent(full, true)
    }
    notifySafe(context, NOTIF_CALL, builder.build())
  }

  fun showInCall(context: Context, call: CallInfo) {
    val open = PendingIntent.getActivity(
      context,
      5,
      IncomingCallActivity.createIntent(context, call.number, null),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val hangup = PendingIntent.getService(
      context,
      6,
      Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_HANGUP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val title = if (call.state == CallState.ACTIVE) "בשיחה" else "שיחה פעילה"
    notifySafe(
      context,
      NOTIF_CALL,
      NotificationCompat.Builder(context, CH_CALLS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(call.number ?: "שיחה")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setContentIntent(open)
        .addAction(0, "נתק", hangup)
        .build(),
    )
  }

  fun cancelCall(context: Context) {
    try {
      NotificationManagerCompat.from(context).cancel(NOTIF_CALL)
    } catch (_: Throwable) {
    }
  }

  fun updateBridge(context: Context, text: String) {
    notifySafe(context, NOTIF_BRIDGE, bridgeNotification(context, text))
  }

  private fun notifySafe(context: Context, id: Int, notification: Notification) {
    try {
      if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      NotificationManagerCompat.from(context).notify(id, notification)
    } catch (_: Throwable) {
    }
  }
}
