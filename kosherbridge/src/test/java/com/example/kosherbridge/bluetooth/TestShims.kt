package com.example.kosherbridge.bluetooth

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.mockito.Mockito

/** Test-only coroutine scopes for the JVM SLC tests. */
object TestScopes {
  /** A detached scope for the client's background work in tests. */
  fun service(): CoroutineScope = CoroutineScope(SupervisorJob())

  /** A scope for the handshake coroutine under test (blocking stream reads). */
  fun io(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * A [Context] stand-in for JVM tests.
 *
 * Uses [Mockito.mock] to create a mock instance — the abstract class
 * cannot be instantiated via [sun.misc.Unsafe.allocateInstance] on JDK 17+.
 * The SLC tests never invoke any Context method (no sockets, SDP or wake
 * locks are exercised), so the mock is safe.
 */
val NoopContext: Context = Mockito.mock(Context::class.java)
