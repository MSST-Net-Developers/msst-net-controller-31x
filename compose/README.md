# MSST-Net Controller (Kotlin Multiplatform)

One Compose Multiplatform UI for every Controller variant:

- `shared/` — UI, presentation state and model (`commonMain`); the JNI
  backend shared by Android and desktop (`jniMain`).
- `androidApp/` — Android: embedded Core in `MsstVpnService`, notifications.
- `desktopApp/` — desktop (JVM); Core runs as the system service.
- `webApp/` — WebUI frontend (wasm), embedded by `msst-net-webui`.
- `rust/` — JNI crate linking the Controller library (`../src`).

All variants talk to Core over IPC through the Rust Controller library;
see `../docs/CONTROLLER_3.1.md` for the variant matrix and build commands.

```sh
rust/build-desktop.sh && ./gradlew :desktopApp:run          # desktop
rust/build-android.sh && ./gradlew :androidApp:assembleRelease
./gradlew :webApp:wasmJsBrowserDistribution                  # then build msst-net-webui
./gradlew :shared:jvmTest :shared:testAndroidHostTest        # tests
```
