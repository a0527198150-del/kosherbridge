package com.example.kosherbridge.bluetooth

import android.content.Context
import android.os.Build

/**
 * The "plus" build ships the in-app ADB channel.
 *
 * Wireless debugging - and therefore this whole route - exists from Android 11
 * (API 30). Below that the channel is absent rather than broken, exactly as in
 * the standard build.
 */
object AdbShellFactory {
  fun create(context: Context): AdbShell? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return LoopbackAdbShell(context.applicationContext)
  }
}
