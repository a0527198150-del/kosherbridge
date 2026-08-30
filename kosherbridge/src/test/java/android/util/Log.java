package android.util

/** No-op Log stub for JVM unit tests. Android's android.jar stubs throw
 *  RuntimeException("Stub!") on every method — this replacement lets code
 *  that calls Log.w/i/e proceed without crashing. */
object Log {
    @JvmStatic fun v(tag: String, msg: String) = 0
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable?) = 0
    @JvmStatic fun d(tag: String, msg: String) = 0
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable?) = 0
    @JvmStatic fun i(tag: String, msg: String) = 0
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable?) = 0
    @JvmStatic fun w(tag: String, msg: String) = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?) = 0
    @JvmStatic fun w(tag: String, tr: Throwable?) = 0
    @JvmStatic fun e(tag: String, msg: String) = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?) = 0
}
