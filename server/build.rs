fn main() {
    slint_build::compile("src/ui.slint").unwrap();

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        winresource::WindowsResource::new()
            .compile()
            .expect("failed to embed Windows executable metadata");
    }
}
