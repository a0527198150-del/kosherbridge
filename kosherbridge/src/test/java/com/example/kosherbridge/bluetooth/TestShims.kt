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
 * A [Context] stand-in for JVM tests.
 *
 * Uses [sun.misc.Unsafe.allocateInstance] to create an instance without
 * calling any constructor — this sidesteps the abstract-method requirement
 * and the JDK 17+ restriction on [sun.reflect.ReflectionFactory] for
 * abstract classes. The SLC tests never invoke any Context method (no
 * sockets, SDP or wake locks are exercised), so the uninitialized instance
 * is safe.
 */
val NoopContext: Context by lazy {
  val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
  f.isAccessible = true
  @Suppress("UNCHECKED_CAST")
  val unsafe = f.get(null) as sun.misc.Unsafe
  unsafe.allocateInstance(Context::class.java) as Context
}
