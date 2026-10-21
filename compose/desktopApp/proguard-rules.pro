# JNI native methods are bound by name (compose/rust).
-keepclasseswithmembers class com.msst.net.jni.** {
    native <methods>;
}

# Called from Rust by name: NoticeListener.onNotice(String).  Nothing on the
# Java side calls it, so without this ProGuard removes it and no Core events
# reach the UI.
-keep interface com.msst.net.jni.NoticeListener { *; }
-keep class * implements com.msst.net.jni.NoticeListener { *; }

# Called from Rust by name: the tray icon's click and menu callbacks.
-keep interface com.msst.net.jni.DesktopTray$Listener { *; }
-keep class * implements com.msst.net.jni.DesktopTray$Listener { *; }
