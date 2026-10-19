const WEB_DIST: &str = "compose/webApp/build/dist/wasmJs/productionExecutable";

fn main() {
    // rust-embed (webui feature) needs the frontend output directory (the
    // Compose web build, see compose/webApp) to exist even when it has not
    // been built yet.
    if std::env::var_os("CARGO_FEATURE_WEBUI").is_some() {
        let dir = std::path::Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap()).join(WEB_DIST);
        if !dir.exists() {
            std::fs::create_dir_all(&dir).unwrap();
        }
    }
    println!("cargo:rerun-if-changed=build.rs");
}
