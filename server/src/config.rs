use std::net::Ipv4Addr;
use winreg::enums::*;
use winreg::RegKey;

const REGISTRY_PATH: &str = r"SOFTWARE\sensorithm";

pub fn load_config() -> (String, u16, String, bool, bool, Option<(i32, i32)>) {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let mut address = "0.0.0.0".to_string();
    let mut port = 4420u16;
    let mut path = r"Local\BROKENITHM_SHARED_BUFFER".to_string();
    let mut auto_adb = false;
    let mut minimize_on_startup = false;
    let mut window_pos = None;

    if let Ok(key) = hkcu.open_subkey(REGISTRY_PATH) {
        if let Ok(val) = key.get_value("listen_address") {
            address = val;
        }
        if let Ok(val) = key.get_value("port") {
            let port_u32: u32 = val;
            port = port_u32 as u16;
        }
        if let Ok(val) = key.get_value("shared_buffer_path") {
            path = val;
        }
        if let Ok(val) = key.get_value("auto_adb") {
            let adb_u32: u32 = val;
            auto_adb = adb_u32 != 0;
        }
        if let Ok(val) = key.get_value("minimize_on_startup") {
            let min_u32: u32 = val;
            minimize_on_startup = min_u32 != 0;
        }
        if let (Ok(x), Ok(y)) = (key.get_value::<u32, _>("window_x"), key.get_value::<u32, _>("window_y")) {
            window_pos = Some((x as i32, y as i32));
        }
    }
    (address, port, path, auto_adb, minimize_on_startup, window_pos)
}

pub fn save_config(address: &str, port: u16, path: &str, auto_adb: bool, minimize_on_startup: bool) {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok((key, _)) = hkcu.create_subkey(REGISTRY_PATH) {
        let _ = key.set_value("listen_address", &address);
        let _ = key.set_value("port", &(port as u32));
        let _ = key.set_value("shared_buffer_path", &path);
        let _ = key.set_value("auto_adb", &(if auto_adb { 1u32 } else { 0u32 }));
        let _ = key.set_value("minimize_on_startup", &(if minimize_on_startup { 1u32 } else { 0u32 }));
    }
}

pub fn validate_config(address: &str, port: &str) -> bool {
    let valid_ip = address.trim().is_empty() || address.parse::<Ipv4Addr>().is_ok();
    let valid_port = port.parse::<u16>().is_ok();
    valid_ip && valid_port
}

pub fn save_window_position(x: i32, y: i32) {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok((key, _)) = hkcu.create_subkey(REGISTRY_PATH) {
        let _ = key.set_value("window_x", &(x as u32));
        let _ = key.set_value("window_y", &(y as u32));
    }
}
