import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

// The JNI library (compose/rust, built without the embedded Core) is
// packaged per OS under appResources/<os>/ — see rust/build-desktop.sh.
val nativeDir = rootProject.layout.projectDirectory.dir("rust/target/release")

compose.desktop {
    application {
        mainClass = "com.msst.net.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "msst-net-controller"
            packageVersion = providers.gradleProperty("msstVersionName").get().substringBefore('-')
            description = "MSST-Net Controller"
            vendor = "MSST-Net 开发组"
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
        }
    }
}

// Development runs (`:desktopApp:run`) load the library from cargo's output,
// not from appResources/ (a copy made by build-desktop.sh that may be older).
// Only the run task gets this: packaged builds must not point at the build tree.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("msst.native.dir", nativeDir.asFile.absolutePath)
}
