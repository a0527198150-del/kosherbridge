package com.example.kosherbridge

import android.app.Application
import com.example.kosherbridge.data.ServiceLocator

class BridgeApp : Application() {
  override fun onCreate() {
    super.onCreate()
    ServiceLocator.init(this)
  }
}
