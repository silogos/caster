fn main() {
    // -ObjC: load ALL Objective-C classes and categories from static
    // libraries, even unreferenced ones. Without this, libwebrtc's ObjC
    // patches (e.g. `+[NSString stringForStdString:]` used by H264
    // profile queries) are dead-stripped by the linker → NSException
    // "unrecognized selector" at runtime (found live in M0-r).
    // The TARGET's OS: `cfg!` here would be the machine running the build
    // script, which differs when cross-compiling.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-arg=-ObjC");
        build_syphon_bridge();
        build_obs_camera_bridge();
        build_h264_decoder();
    }
    tauri_build::build()
}

/// The USB cast's VideoToolbox decoder (ADR-034).
fn build_h264_decoder() {
    println!("cargo:rerun-if-changed=native/h264_decoder.m");
    cc::Build::new().file("native/h264_decoder.m").flag("-fobjc-arc").compile("zf_h264_decoder");
    for framework in ["VideoToolbox", "CoreMedia", "CoreVideo", "Foundation"] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
}

/// The OBS Virtual Camera output (ADR-039): feeds OBS's camera extension
/// through CoreMediaIO. No vendored code: OBS is found on the user's Mac.
fn build_obs_camera_bridge() {
    println!("cargo:rerun-if-changed=native/obs_camera_bridge.m");
    cc::Build::new().file("native/obs_camera_bridge.m").flag("-fobjc-arc").compile("zf_obs_camera_bridge");
    for framework in ["CoreMediaIO", "CoreMedia", "CoreVideo", "AppKit"] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
}

/// The Syphon output (ADR-021): compiles the Objective-C bridge and links
/// the vendored Syphon.framework (`../vendor/syphon`, BSD-3).
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
