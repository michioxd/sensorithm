use std::net::Ipv4Addr;
use winreg::RegKey;
use winreg::enums::*;

const REGISTRY_PATH: &str = r"SOFTWARE\sensorithm";
pub const DEFAULT_ADDRESS: &str = "0.0.0.0";
pub const EMPTY_ADDRESS_FALLBACK: &str = "127.0.0.1";
pub const DEFAULT_PORT: u16 = 4420;
pub const DEFAULT_SHARED_BUFFER_PATH: &str = r"Local\BROKENITHM_SHARED_BUFFER";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ListenerConfig {
    pub address: String,
    pub port: u16,
    pub shared_buffer_path: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServerConfig {
    pub listener: ListenerConfig,
    pub auto_adb: bool,
    pub minimize_on_startup: bool,
    pub window_position: Option<(i32, i32)>,
}

impl ServerConfig {
    pub fn from_ui(
        address: &str,
        port: &str,
        shared_buffer_path: &str,
        auto_adb: bool,
        minimize_on_startup: bool,
    ) -> Self {
        Self {
            listener: ListenerConfig {
                address: if address.trim().is_empty() {
                    EMPTY_ADDRESS_FALLBACK.to_owned()
                } else {
                    address.to_owned()
                },
                port: port.parse().unwrap_or(DEFAULT_PORT),
                shared_buffer_path: if shared_buffer_path.trim().is_empty() {
                    DEFAULT_SHARED_BUFFER_PATH.to_owned()
                } else {
                    shared_buffer_path.to_owned()
                },
            },
            auto_adb,
            minimize_on_startup,
            window_position: None,
        }
    }
}

pub fn load_config() -> ServerConfig {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let mut address = DEFAULT_ADDRESS.to_string();
    let mut port = DEFAULT_PORT;
    let mut path = DEFAULT_SHARED_BUFFER_PATH.to_string();
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
        if let (Ok(x), Ok(y)) = (
            key.get_value::<u32, _>("window_x"),
            key.get_value::<u32, _>("window_y"),
        ) {
            window_pos = Some((x as i32, y as i32));
        }
    }
    ServerConfig {
        listener: ListenerConfig {
            address,
            port,
            shared_buffer_path: path,
        },
        auto_adb,
        minimize_on_startup,
        window_position: window_pos,
    }
}

pub fn save_config(config: &ServerConfig) {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok((key, _)) = hkcu.create_subkey(REGISTRY_PATH) {
        let _ = key.set_value("listen_address", &config.listener.address);
        let _ = key.set_value("port", &(config.listener.port as u32));
        let _ = key.set_value("shared_buffer_path", &config.listener.shared_buffer_path);
        let _ = key.set_value("auto_adb", &(if config.auto_adb { 1u32 } else { 0u32 }));
        let _ = key.set_value(
            "minimize_on_startup",
            &(if config.minimize_on_startup {
                1u32
            } else {
                0u32
            }),
        );
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_ui_values_use_existing_runtime_fallbacks() {
        let config = ServerConfig::from_ui(" ", "invalid", "", true, false);
        assert_eq!(config.listener.address, EMPTY_ADDRESS_FALLBACK);
        assert_eq!(config.listener.port, DEFAULT_PORT);
        assert_eq!(
            config.listener.shared_buffer_path,
            DEFAULT_SHARED_BUFFER_PATH
        );
        assert!(config.auto_adb);
        assert!(!config.minimize_on_startup);
    }

    #[test]
    fn validation_accepts_empty_or_ipv4_addresses_and_u16_ports() {
        assert!(validate_config("", "4420"));
        assert!(validate_config("127.0.0.1", "65535"));
        assert!(!validate_config("localhost", "4420"));
        assert!(!validate_config("127.0.0.1", "65536"));
    }
}
