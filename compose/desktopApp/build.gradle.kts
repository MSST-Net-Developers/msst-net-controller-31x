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
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "msst-net-controller"
            packageVersion = providers.gradleProperty("msstVersionName").get().substringBefore('-')
            description = "MSST-Net Controller"
            vendor = "MSST-Net 开发组"
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
            // Beyond what jlink derives: sun.misc.Unsafe (jdk.unsupported) and
            // java.lang.instrument, as `suggestRuntimeModules` reports.
            modules("java.instrument", "jdk.unsupported")

            windows {
                // Fixed for every release: an MSI only upgrades the installed
                // version in place when the upgrade codes match.  Never change it.
                upgradeUuid = "3896a5fb-1a4b-4ef5-b34e-8409ea570382"
                menu = true
                shortcut = true
            }
            macOS {
                bundleID = "com.msst.net.controller"
            }
        }

        // createReleaseDistributable / packageRelease*: shrunk by ProGuard.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}

// Development runs (`:desktopApp:run`) load the library from cargo's output,
// not from appResources/ (a copy made by build-desktop.sh that may be older).
// Only the run task gets this: packaged builds must not point at the build tree.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("msst.native.dir", nativeDir.asFile.absolutePath)
}
