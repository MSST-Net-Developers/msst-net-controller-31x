# JNI native 方法不能被混淆
-keepclasseswithmembers class com.msst.net.jni.MsstCore {
    native <methods>;
}

# IPC 回调接口由 JNI 层按名称调用，不能被混淆
-keep interface com.msst.net.jni.IpcEventCallback {
    void onIpcEvent(java.lang.String);
}
-keep class * implements com.msst.net.jni.IpcEventCallback {
    void onIpcEvent(java.lang.String);
}

# VpnService 子类不能被混淆
-keep class com.msst.net.platform.MsstVpnService { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}