fn main() {
    // -ObjC: load ALL Objective-C classes and categories from static
    // libraries, even unreferenced ones. Without this, libwebrtc's ObjC
    // patches (e.g. `+[NSString stringForStdString:]` used by H264
    // profile queries) are dead-stripped by the linker → NSException
    // "unrecognized selector" at runtime (found live in M0-r).
    #[cfg(target_os = "macos")]
    {
        println!("cargo:rustc-link-arg=-ObjC");
    }
    tauri_build::build()
}
