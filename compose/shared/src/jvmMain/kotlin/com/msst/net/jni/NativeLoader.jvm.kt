package com.msst.net.jni

import java.io.File

/**
 * Desktop: development runs use `msst.native.dir` (cargo's output, set only
 * by `:desktopApp:run`) first, so a stale copy in the app resources is never
 * picked up; packaged builds load it from the app resources
 * (`compose.application.resources.dir`, see desktopApp/build.gradle.kts);
 * otherwise `java.library.path`.
 */
internal actual fun loadNativeLibrary() {
    val name = System.mapLibraryName("msst_jni")
    val candidates = listOfNotNull(
        System.getProperty("msst.native.dir"),
        System.getProperty("compose.application.resources.dir"),
    ).map { File(it, name) }
    val file = candidates.firstOrNull { it.isFile }
    if (file != null) System.load(file.absolutePath) else System.loadLibrary("msst_jni")
}
