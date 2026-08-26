package com.example.kosherbridge.bluetooth

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Entry point of the privileged **root** process, run by [RootBridge] as
 *
 *     su -c "CLASSPATH=<our apk> app_process /system/bin --nice-name=<app>:root \
 *            com.example.kosherbridge.bluetooth.RootBridgeMain \
 *            --package=<app> --class=...HfpUserService --token=<token>"
 *
 * The process runs under uid 0, so it is exempt from hidden-API enforcement
 * and passes every permission check (including BLUETOOTH_PRIVILEGED) - the
 * two walls that block the normal app process. It boots the app's Application
 * (the same sequence Shizuku's starter uses), instantiates [HfpUserService]
 * there, and delivers the service binder back into the app process through
 * [RootBridgeProvider] using a ContentProvider.call + Bundle.putBinder - the
 * same handoff Shizuku's server performs, without the Shizuku app.
 *
 * Everything Android-specific here is accessed through reflection because the
 * involved classes (ActivityThread, ServiceManager, IActivityManager,
 * IContentProvider) are hidden from the public SDK - fine in this process,
 * since hidden-API enforcement does not apply to app_process children.
 */
object RootBridgeMain {

  private const val TAG = "RootBridgeMain"

  private const val ARG_PACKAGE = "--package="
  private const val ARG_CLASS = "--class="
  private const val ARG_TOKEN = "--token="

  @JvmStatic
  fun main(args: Array<String>) {
    Log.i(TAG, "root bridge starting (uid=${Process.myUid()}, pid=${Process.myPid()})")

    var pkg: String? = null
    var cls: String? = null
    var token: String? = null
    for (a in args) {
      when {
        a.startsWith(ARG_PACKAGE) -> pkg = a.removePrefix(ARG_PACKAGE)
        a.startsWith(ARG_CLASS) -> cls = a.removePrefix(ARG_CLASS)
        a.startsWith(ARG_TOKEN) -> token = a.removePrefix(ARG_TOKEN)
      }
    }
    if (pkg.isNullOrBlank() || cls.isNullOrBlank() || token.isNullOrBlank()) {
      Log.e(TAG, "missing arguments: $args")
      System.exit(1)
    }
    val packageName = pkg ?: return
    val className = cls ?: return
    val tokenValue = token ?: return

    if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

    val service = try {
      createService(packageName, className)
    } catch (t: Throwable) {
      Log.e(TAG, "failed to create user service", t)
      System.exit(1)
      return
    }

    if (!deliverBinder(service, packageName, tokenValue)) {
      Log.e(TAG, "failed to deliver binder to the app")
      System.exit(1)
    }

    Log.i(TAG, "root bridge ready, serving")
    Looper.loop()
    System.exit(0)
  }

  /**
   * Boots the app's Application inside this process (so [HfpUserService] gets
   * a real Context and the app's class loader) and instantiates the service.
   * Mirrors Shizuku's UserService.create().
   */
  private fun createService(pkg: String, cls: String): IHfpBridge {
    val atClass = Class.forName("android.app.ActivityThread")
    val activityThread = atClass.getMethod("systemMain").invoke(null)
    val systemContext = atClass.getMethod("getSystemContext").invoke(activityThread) as Context

    val userHandle = Class.forName("android.os.UserHandle")
      .getMethod("of", Int::class.javaPrimitiveType)
      .invoke(null, 0)
    val appContext = systemContext.javaClass.getMethod(
      "createPackageContextAsUser",
      String::class.java,
      Int::class.javaPrimitiveType,
      Class.forName("android.os.UserHandle"),
    ).invoke(
      systemContext,
      pkg,
      Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
      userHandle,
    ) as Context

    val mPackageInfo = appContext.javaClass.getDeclaredField("mPackageInfo")
    mPackageInfo.isAccessible = true
    val loadedApk = mPackageInfo.get(appContext)

    val makeApplication = loadedApk.javaClass.getDeclaredMethod(
      "makeApplication",
      Boolean::class.javaPrimitiveType,
      android.app.Instrumentation::class.java,
    )
    makeApplication.isAccessible = true
    val application = makeApplication.invoke(loadedApk, true, null) as Application

    val mInitialApplication = atClass.getDeclaredField("mInitialApplication")
    mInitialApplication.isAccessible = true
    mInitialApplication.set(activityThread, application)

    val serviceClass = application.classLoader.loadClass(cls)
    return serviceClass.getConstructor(Context::class.java).newInstance(application) as IHfpBridge
  }

  /**
   * Hands [HfpUserService]'s binder to the app process through its
   * RootBridgeProvider. Mirrors Shizuku's ServiceStarter.sendBinder(): obtain
   * the provider via ActivityManager.getContentProviderExternal, then call it
   * with a Bundle carrying the binder as an extra.
   */
  private fun deliverBinder(binder: IHfpBridge, pkg: String, token: String): Boolean {
    val amClass = Class.forName("android.app.IActivityManager")
    val iam = Class.forName("android.app.IActivityManager\$Stub")
      .getMethod("asInterface", IBinder::class.java)
      .invoke(null, serviceManagerBinder("activity"))
    val icpClass = Class.forName("android.content.IContentProvider")
    var icp: IInterface? = null

    // Mirrors Shizuku's ServiceStarter: a real (non-null) token binder is
    // passed to getContentProviderExternal and the matching remove call. Some
    // AOSP versions do not accept a null token for the external provider.
    val tokenBinder = Binder()
    return try {
      val provider = amClass.getMethod(
        "getContentProviderExternal",
        String::class.java,
        Int::class.javaPrimitiveType,
        IBinder::class.java,
        String::class.java,
      ).invoke(iam, RootBridge.AUTHORITIES, 0, tokenBinder, RootBridge.AUTHORITIES)
      icp = icpClass.cast(provider) as? IInterface
      if (icp == null || !icp.asBinder().pingBinder()) return false

      // Exit when the app process dies: the provider binder lives in the app
      // process, so its death means there is nobody left to serve.
      icp.asBinder().linkToDeath({
        Log.i(TAG, "app process died - exiting")
        System.exit(0)
      }, 0)

      val extras = Bundle().apply {
        putBinder(RootBridge.EXTRA_BINDER, binder.asBinder())
        putString(RootBridge.EXTRA_TOKEN, token)
        putInt(RootBridge.EXTRA_PID, Process.myPid())
      }
      callCompat(icpClass, icp, pkg, RootBridge.AUTHORITIES, RootBridge.METHOD_SEND_BINDER, null, extras) != null
    } catch (t: Throwable) {
      Log.e(TAG, "deliverBinder failed", t)
      false
    } finally {
      runCatching {
        amClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
          .invoke(iam, RootBridge.AUTHORITIES, tokenBinder)
      }
    }
  }

  private fun serviceManagerBinder(name: String): IBinder =
    Class.forName("android.os.ServiceManager")
      .getMethod("getService", String::class.java)
      .invoke(null, name) as IBinder

  /**
   * IContentProvider.call changed signature across Android versions. Mirrors
   * rikka.shizuku.server.api.IContentProviderUtils.callCompat().
   */
  private fun callCompat(
    icpClass: Class<*>,
    icp: IInterface,
    callingPkg: String,
    authority: String,
    method: String,
    arg: String?,
    extras: Bundle,
  ): Bundle? {
    val sdk = Build.VERSION.SDK_INT
    return when {
      sdk >= Build.VERSION_CODES.S -> {
        val m = icpClass.getMethod(
          "call",
          Class.forName("android.content.AttributionSource"),
          String::class.java,
          String::class.java,
          String::class.java,
          Bundle::class.java,
        )
        m.invoke(icp, attributionSource(callingPkg), authority, method, arg, extras) as Bundle?
      }
      sdk >= Build.VERSION_CODES.R -> {
        val m = icpClass.getMethod(
          "call",
          String::class.java,
          String::class.java,
          String::class.java,
          String::class.java,
          String::class.java,
          Bundle::class.java,
        )
        m.invoke(icp, callingPkg, null, authority, method, arg, extras) as Bundle?
      }
      sdk >= Build.VERSION_CODES.Q -> {
        val m = icpClass.getMethod(
          "call",
          String::class.java,
          String::class.java,
          String::class.java,
          String::class.java,
          Bundle::class.java,
        )
        m.invoke(icp, callingPkg, authority, method, arg, extras) as Bundle?
      }
      else -> {
        val m = icpClass.getMethod(
          "call",
          String::class.java,
          String::class.java,
          String::class.java,
          Bundle::class.java,
        )
        m.invoke(icp, callingPkg, method, arg, extras) as Bundle?
      }
    }
  }

  private fun attributionSource(callingPkg: String): Any {
    val builderClass = Class.forName("android.content.AttributionSource\$Builder")
    val builder = builderClass.getConstructor(Int::class.javaPrimitiveType)
      .newInstance(Process.myUid())
    builderClass.getMethod("setPackageName", String::class.java).invoke(builder, callingPkg)
    return builderClass.getMethod("build").invoke(builder)
  }
}
