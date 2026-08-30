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
 * A Context stand-in for JVM tests, instantiated without running any
 * constructor (Objenesis-style, via ReflectionFactory) so no Android runtime
 * is needed. RawHfpClient only touches the context when opening sockets,
 * discovering SDP, or acquiring wake locks - none of which the SLC tests
 * exercise - so the instance is never actually called.
 */
val NoopContext: Context by lazy {
  val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
  val ctor = rf.newConstructorForSerialization(
    Context::class.java,
    Any::class.java.getDeclaredConstructor(),
  )
  ctor.isAccessible = true
  ctor.newInstance() as Context
}
