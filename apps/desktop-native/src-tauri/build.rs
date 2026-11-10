fn main() {
    // -ObjC: load ALL Objective-C classes and categories from static
    // libraries, even unreferenced ones. Without this, libwebrtc's ObjC
    // patches (e.g. `+[NSString stringForStdString:]` used by H264
    // profile queries) are dead-stripped by the linker → NSException
    // "unrecognized selector" at runtime (found live in M0-r).
    #[cfg(target_os = "macos")]
    {
        println!("cargo:rustc-link-arg=-ObjC");
        build_syphon_bridge();
    }
    tauri_build::build()
}

/// The Syphon output (ADR-021): compiles the Objective-C bridge and links
/// the vendored Syphon.framework (`../vendor/syphon`, BSD-3).
#[cfg(target_os = "macos")]
fn build_syphon_bridge() {
    let manifest = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let frameworks = manifest.join("../vendor/syphon").canonicalize().expect("vendor/syphon is missing");
    println!("cargo:rerun-if-changed=native/syphon_bridge.m");
    cc::Build::new()
        .file("native/syphon_bridge.m")
        .flag("-fobjc-arc")
        .flag("-F")
        .flag(frameworks.to_str().expect("utf-8 path"))
        .compile("zf_syphon_bridge");
    println!("cargo:rustc-link-search=framework={}", frameworks.display());
    for framework in ["Syphon", "Metal", "CoreImage", "CoreVideo", "AppKit"] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
    // Syphon is a dynamic framework: found next to a bundled app, and in
    // the vendored folder when run straight from `target/`.
    println!("cargo:rustc-link-arg=-Wl,-rpath,@executable_path/../Frameworks");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{}", frameworks.display());
}
