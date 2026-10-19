package com.msst.net.jni

import java.io.File

/**
 * Desktop: the library is packaged per OS in the app resources
 * (`compose.application.resources.dir`, see desktopApp/build.gradle.kts);
 * development runs use `msst.native.dir` (cargo's output); otherwise
 * `java.library.path`.
 */
internal actual fun loadNativeLibrary() {
    val name = System.mapLibraryName("msst_jni")
    val candidates = listOfNotNull(
        System.getProperty("compose.application.resources.dir"),
        System.getProperty("msst.native.dir"),
    ).map { File(it, name) }
    val file = candidates.firstOrNull { it.isFile }
    if (file != null) System.load(file.absolutePath) else System.loadLibrary("msst_jni")
}
