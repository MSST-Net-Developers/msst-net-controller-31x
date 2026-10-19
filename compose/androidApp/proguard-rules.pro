# JNI native methods are bound by name (compose/rust).
-keepclasseswithmembers class com.msst.net.jni.** {
    native <methods>;
}

# Called from Rust by name: NoticeListener.onNotice(String) and
# VpnService.protect(int) on the running service.
-keep interface com.msst.net.jni.NoticeListener { *; }
-keep class * implements com.msst.net.jni.NoticeListener { *; }
-keep class com.msst.net.platform.MsstVpnService { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
