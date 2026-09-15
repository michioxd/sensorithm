use std::net::Ipv4Addr;
use winreg::RegKey;
use winreg::enums::*;

use crate::theme::BackdropEffect;

const REGISTRY_PATH: &str = r"SOFTWARE\sensorithm";
pub const DEFAULT_ADDRESS: &str = "0.0.0.0";
pub const EMPTY_ADDRESS_FALLBACK: &str = "127.0.0.1";
pub const DEFAULT_PORT: u16 = 4420;
pub const DEFAULT_SHARED_BUFFER_PATH: &str = r"Local\BROKENITHM_SHARED_BUFFER";
pub const DEFAULT_PREVIEW_REFRESH_SECONDS: u32 = 1;

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
    pub auto_refresh_preview: bool,
    pub preview_refresh_seconds: u32,
    pub disable_battery_low_warning: bool,
    pub backdrop_effect: BackdropEffect,
    pub window_position: Option<(i32, i32)>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServerPreferences {
    pub auto_adb: bool,
    pub minimize_on_startup: bool,
    pub auto_refresh_preview: bool,
    pub preview_refresh_seconds: u32,
    pub disable_battery_low_warning: bool,
    pub backdrop_effect: BackdropEffect,
}

impl ServerConfig {
    pub fn from_ui(
        address: &str,
        port: &str,
        shared_buffer_path: &str,
        preferences: ServerPreferences,
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
            auto_adb: preferences.auto_adb,
            minimize_on_startup: preferences.minimize_on_startup,
            auto_refresh_preview: preferences.auto_refresh_preview,
            preview_refresh_seconds: preferences.preview_refresh_seconds.clamp(1, 5),
            disable_battery_low_warning: preferences.disable_battery_low_warning,
            backdrop_effect: preferences.backdrop_effect,
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
    let mut auto_refresh_preview = false;
    let mut preview_refresh_seconds = DEFAULT_PREVIEW_REFRESH_SECONDS;
    let mut disable_battery_low_warning = false;
    let mut backdrop_effect = BackdropEffect::default();
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
        if let Ok(val) = key.get_value("auto_refresh_preview") {
            let enabled: u32 = val;
            auto_refresh_preview = enabled != 0;
        }
        if let Ok(val) = key.get_value("preview_refresh_seconds") {
            let seconds: u32 = val;
            preview_refresh_seconds = seconds.clamp(1, 5);
        }
        if let Ok(val) = key.get_value("disable_battery_low_warning") {
            let disabled: u32 = val;
            disable_battery_low_warning = disabled != 0;
        }
        if let Ok(val) = key.get_value("backdrop_effect") {
            backdrop_effect = BackdropEffect::from_registry(val);
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
        auto_refresh_preview,
        preview_refresh_seconds,
        disable_battery_low_warning,
        backdrop_effect,
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
        let _ = key.set_value(
            "auto_refresh_preview",
            &(if config.auto_refresh_preview {
                1u32
            } else {
                0u32
            }),
        );
        let _ = key.set_value("preview_refresh_seconds", &config.preview_refresh_seconds);
        let _ = key.set_value(
            "disable_battery_low_warning",
            &(if config.disable_battery_low_warning {
                1u32
            } else {
                0u32
            }),
        );
        let _ = key.set_value("backdrop_effect", &(config.backdrop_effect as u32));
    }
}

pub fn save_backdrop_effect(effect: BackdropEffect) {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok((key, _)) = hkcu.create_subkey(REGISTRY_PATH) {
        let _ = key.set_value("backdrop_effect", &(effect as u32));
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
        let config = ServerConfig::from_ui(
            " ",
            "invalid",
            "",
            ServerPreferences {
                auto_adb: true,
                minimize_on_startup: false,
                auto_refresh_preview: true,
                preview_refresh_seconds: 9,
                disable_battery_low_warning: true,
                backdrop_effect: BackdropEffect::Acrylic,
            },
        );
        assert_eq!(config.listener.address, EMPTY_ADDRESS_FALLBACK);
        assert_eq!(config.listener.port, DEFAULT_PORT);
        assert_eq!(
            config.listener.shared_buffer_path,
            DEFAULT_SHARED_BUFFER_PATH
        );
        assert!(config.auto_adb);
        assert!(!config.minimize_on_startup);
        assert!(config.auto_refresh_preview);
        assert_eq!(config.preview_refresh_seconds, 5);
        assert!(config.disable_battery_low_warning);
        assert_eq!(config.backdrop_effect, BackdropEffect::Acrylic);
    }

    #[test]
    fn validation_accepts_empty_or_ipv4_addresses_and_u16_ports() {
        assert!(validate_config("", "4420"));
        assert!(validate_config("127.0.0.1", "65535"));
        assert!(!validate_config("localhost", "4420"));
        assert!(!validate_config("127.0.0.1", "65536"));
    }
}
