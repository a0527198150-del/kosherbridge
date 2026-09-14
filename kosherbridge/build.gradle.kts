import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

// Configuration-cache-safe way to derive the versionCode from the git history.
// A ValueSource is the supported mechanism for running external processes at
// configuration time under the configuration cache - a bare ProcessBuilder is
// rejected by Gradle 9's configuration cache.
abstract class GitCommitCountValueSource : ValueSource<Int, ValueSourceParameters.None> {
  override fun obtain(): Int = runCatching {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
      .redirectErrorStream(true)
      .start()
      .let { it.inputStream.readBytes().toString(Charsets.UTF_8).trim().toInt() }
  }.getOrDefault(1).coerceAtLeast(1)
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// Every commit gets a higher versionCode (count of commits on the branch), so
// each new APK built by GitHub Actions can be installed OVER the previous one
// without uninstalling - as long as it is signed with the same stable key
// (debug.keystore restored from the DEBUG_KEYSTORE_BASE64 secret, see README).
val releaseVersionCode: Int = providers.of(GitCommitCountValueSource::class.java) { }.get()

android {
  namespace = "com.example.kosherbridge"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.example.kosherbridge"
    minSdk = 24
    targetSdk = 36
    versionCode = releaseVersionCode
    versionName = "1.0.$releaseVersionCode"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Stable signing key for BOTH build types: the same debug.keystore is
  // restored on every CI run from the DEBUG_KEYSTORE_BASE64 secret, so every
  // released APK shares one signature and updates install over the previous
  // version without uninstalling. (Without this, each CI run generated its
  // own throwaway debug key and Android refused to update the app.)
  signingConfigs {
    create("stable") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  /**
   * Two products, one difference: whether the app may open a TCP socket.
   *
   * The bridge's remaining no-root lever is a shell identity, and the only way
   * to get one without a second app is to speak ADB to the player's own adbd
   * over 127.0.0.1. Android gates EVERY TCP socket behind
   * android.permission.INTERNET - there is no narrower permission, and none
   * that says "loopback only". For an app whose users choose a kosher phone
   * precisely to have no internet, a network permission in the manifest is not
   * a detail: a kashrut reviewer reads the manifest.
   *
   * So the choice is not made for them:
   *
   *  - standard: no INTERNET, and the ADB library is not even a dependency, so
   *    nothing can merge the permission back in. Shell access, if wanted, comes
   *    from Shizuku - a separate app, with its own permission.
   *  - plus: INTERNET, and the in-app ADB channel. No second app, no PC.
   *
   * Same applicationId and the same signing key, so one installs over the other
   * without losing any data.
   */
  flavorDimensions += "network"
  productFlavors {
    create("standard") {
      dimension = "network"
      buildConfigField("boolean", "HAS_ADB_CHANNEL", "false")
    }
    create("plus") {
      dimension = "network"
      versionNameSuffix = "-plus"
      buildConfigField("boolean", "HAS_ADB_CHANNEL", "true")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("stable")
    }
    debug {
      signingConfig = signingConfigs.getByName("stable")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  // Lint REPORTS, it does not gate the build - which is what CI always intended
  // ("Lint reports but does not block"). Expressing that here rather than with
  // the workflow's continue-on-error matters: continue-on-error still marks the
  // step failed, so every green run carried a red step and an "exit code 1" in
  // the log, and a reader could not tell that apart from a real break.
  // The HTML report is still produced and uploaded on every run.
  lint {
    abortOnError = false
    // The release build is assembled from the same sources as debug; linting it
    // a second time doubles the step for no new findings.
    checkReleaseBuilds = false
  }
  // JVM unit tests touch Android framework stubs (android.util.Log etc.).
  // Without isReturnDefaultValues every stub method throws "not mocked" on
  // ANY thread - the static Log mock in the test only covers the JUnit thread,
  // so the handshake coroutine on Dispatchers.IO crashed and the tests timed
  // out with a misleading 5-second AssertionError.
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
  buildFeatures {
    compose = true
    aidl = true
    buildConfig = true
  }
}

dependencies {
  // "plus" only - see the productFlavors block above. Keeping these off the
  // standard variant is what keeps android.permission.INTERNET out of it:
  // libadb-android declares the permission in its own manifest, and a manifest
  // merge would put it back however carefully the app's own manifest is
  // written.
  "plusImplementation"(libs.libadb.android)
  "plusImplementation"(libs.conscrypt.android)
  "plusImplementation"(libs.sun.security.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockito.core)
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.coil.compose)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  ksp(libs.androidx.room.compiler)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
