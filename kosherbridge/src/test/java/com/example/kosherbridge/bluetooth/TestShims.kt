package com.example.kosherbridge.bluetooth

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Test-only coroutine scopes for the JVM SLC tests. */
object TestScopes {
  /** A detached scope for the client's background work in tests. */
  fun service(): CoroutineScope = CoroutineScope(SupervisorJob())

  /** A scope for the handshake coroutine under test (blocking stream reads). */
  fun io(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * A concrete [Context] stand-in for JVM tests.
 *
 * The SLC tests never invoke any Context method (no sockets, SDP or wake
 * locks are exercised), so an empty subclass of the android.jar stub is
 * enough — no Robolectric needed.
 *
 * Previous versions used ReflectionFactory to skip the constructor, but
 * JDK 17+ rejects instantiation of abstract classes that way.
 */
private class StubContext : Context()

/** Singleton [Context] instance shared by all SLC tests. */
val NoopContext: Context = StubContext()
