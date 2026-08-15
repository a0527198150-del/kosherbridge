package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test. The applicationId is set in
    // app/build.gradle.kts (not the namespace), so this must match that value.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.aistudio.kosherbudget.zrwpxm", appContext.packageName)
  }
}
