pub fn show_error_dialog(title: &str, message: &str) {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows::core::PCWSTR;
        use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};

        let title_wide: Vec<u16> = std::ffi::OsStr::new(title)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let message_wide: Vec<u16> = std::ffi::OsStr::new(message)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();

        unsafe {
            let _ = MessageBoxW(
                None,
                PCWSTR(message_wide.as_ptr()),
                PCWSTR(title_wide.as_ptr()),
                MB_OK | MB_ICONERROR,
            );
        }
    }
    #[cfg(not(windows))]
    {
        eprintln!("{}: {}", title, message);
    }
}

pub fn reverse_adb_port(port: u16) {
    std::thread::spawn(move || {
        println!("Forwarding port {} via ADB...", port);
        
        let mut cmd = std::process::Command::new("adb");
        cmd.arg("reverse")
           .arg(format!("tcp:{}", port))
           .arg(format!("tcp:{}", port));
           
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        match cmd.output() {
            Ok(output) => {
                if output.status.success() {
                    println!("Port {} forwarded successfully.", port);
                } else {
                    let err = String::from_utf8_lossy(&output.stderr);
                    eprintln!("ADB reverse failed: {}", err);
                }
            }
            Err(e) => {
                eprintln!("Failed to execute adb: {}", e);
            }
        }
    });
}