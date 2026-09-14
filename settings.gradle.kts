pluginManagement {
  repositories {
    maven { url = uri("https://dl.google.com/dl/android/maven2/") }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // libadb-android and sun-security-android are published on JitPack only.
    // They are used by the "plus" flavour alone (see kosherbridge's
    // build.gradle.kts); the standard flavour resolves nothing from here.
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "KosherBridge"
include(":app")
include(":kosherbridge")
