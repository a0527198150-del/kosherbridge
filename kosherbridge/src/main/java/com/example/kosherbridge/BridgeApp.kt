package com.example.kosherbridge

import android.app.Application
import com.example.kosherbridge.data.ServiceLocator

class BridgeApp : Application() {

  companion object {
    // Needed by HfpUserService: Shizuku loads this APK into a second
    // process (running as `shell`) and Application.onCreate() runs first in
    // that process too, so this reference is always set before anything
    // else in that process needs it.
    lateinit var instance: Application
      private set
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    ServiceLocator.init(this)
  }
}
