package com.example.kosherbridge.bluetooth

import android.content.Context

/**
 * The standard build has no in-app ADB channel, on purpose.
 *
 * The ADB library is not a dependency of this flavour at all, so
 * android.permission.INTERNET cannot reach the manifest through a merge - which
 * is the whole point of the split. Shell access on this build comes from
 * Shizuku, a separate app that carries its own network permission.
 *
 * Returning null rather than a throwing stub keeps every caller honest: the
 * channel is absent, not broken.
 */
object AdbShellFactory {
  fun create(context: Context): AdbShell? = null
}
