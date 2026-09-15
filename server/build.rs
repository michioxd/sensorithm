fn main() {
    slint_build::compile("src/ui.slint").unwrap();

    let profile = std::env::var("PROFILE").unwrap_or_default();
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();

    if profile == "release" {
        if target_os != "windows" || target_arch != "x86_64" {
            panic!("Release build is only supported on Windows 64-bit.");
        }
        
        println!("cargo:rustc-link-arg=/OSVERSION:10.0");
        println!("cargo:rustc-link-arg=/SUBSYSTEM:WINDOWS,10.0");
    }

    if target_os == "windows" {
        winresource::WindowsResource::new()
            .compile()
            .expect("failed to embed Windows executable metadata");
    }
}
