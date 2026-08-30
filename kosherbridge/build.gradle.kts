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
  buildFeatures {
    compose = true
    aidl = true
    buildConfig = true
  }
}

dependencies {
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
