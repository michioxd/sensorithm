use crate::MainWindow;
use crate::config::{DEFAULT_PORT, ListenerConfig, ServerConfig, ServerPreferences};
use crate::protocol::{ServerMessage, SyncedSettings};
use slint::ComponentHandle;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::watch;

type ListenerSender = watch::Sender<Option<ListenerConfig>>;

pub fn setup_event_handlers(
    ui: &MainWindow,
    port_tx: ListenerSender,
    cmd_tx: tokio::sync::mpsc::UnboundedSender<ServerMessage>,
    battery_low_warning_enabled: Arc<AtomicBool>,
) {
    ui.on_github_clicked(|| {
        let _ = webbrowser::open("https://github.com/michioxd/sensorithm");
    });

    ui.on_recalibrate_clicked({
        let cmd_tx = cmd_tx.clone();
        move || {
            let _ = cmd_tx.send(ServerMessage::Recalibrate);
        }
    });

    ui.on_settings_released({
        let cmd_tx = cmd_tx.clone();
        let ui_handle = ui.as_weak();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                let _ = cmd_tx.send(ServerMessage::Settings {
                    settings: settings_from_ui(&ui),
                });
            }
        }
    });

    ui.on_torch_requested({
        let cmd_tx = cmd_tx.clone();
        move |enabled| {
            let _ = cmd_tx.send(ServerMessage::SetTorch { enabled });
        }
    });

    ui.on_restart_camera_clicked({
        let cmd_tx = cmd_tx.clone();
        move || {
            let _ = cmd_tx.send(ServerMessage::RestartCamera);
        }
    });

    ui.on_refresh_preview_clicked({
        let cmd_tx = cmd_tx.clone();
        let ui_handle = ui.as_weak();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                if ui.get_preview_loading() || !ui.get_client_connected() {
                    return;
                }
                ui.set_preview_loading(true);
                ui.set_preview_status("Requesting preview...".into());
            }
            let request_id = uuid::Uuid::new_v4().to_string();
            if cmd_tx
                .send(ServerMessage::RequestPreview { request_id })
                .is_err()
                && let Some(ui) = ui_handle.upgrade()
            {
                ui.set_preview_loading(false);
                ui.set_preview_status("Preview request failed: server is unavailable".into());
            }
        }
    });

    ui.on_preview_options_edited({
        let ui_handle = ui.as_weak();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                crate::config::save_config(&config_from_ui(&ui));
            }
        }
    });

    ui.on_battery_warning_option_edited({
        let ui_handle = ui.as_weak();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                battery_low_warning_enabled
                    .store(!ui.get_disable_battery_low_warning(), Ordering::Relaxed);
                crate::config::save_config(&config_from_ui(&ui));
            }
        }
    });

    ui.on_zone_edit_finished({
        let cmd_tx = cmd_tx.clone();
        let ui_handle = ui.as_weak();
        move |offset_x, offset_y| {
            if let Some(ui) = ui_handle.upgrade() {
                ui.set_zone_offset_x(offset_x);
                ui.set_zone_offset_y(offset_y);
                let _ = cmd_tx.send(ServerMessage::Settings {
                    settings: settings_from_ui(&ui),
                });
            }
        }
    });

    let ui_weak = ui.as_weak();
    ui.on_forward_port_clicked(move || {
        if let Some(ui) = ui_weak.upgrade() {
            let current_port = ui
                .get_port()
                .to_string()
                .parse::<u16>()
                .unwrap_or(DEFAULT_PORT);
            crate::utils::reverse_adb_port(current_port);
        }
    });

    ui.on_config_edited({
        let ui_handle = ui.as_weak();
        move |addr, port, _path| {
            if let Some(ui) = ui_handle.upgrade() {
                ui.set_config_valid(crate::config::validate_config(&addr, &port));
            }
        }
    });

    ui.on_start_stop_clicked({
        let port_tx = port_tx.clone();
        let ui_handle = ui.as_weak();
        move || {
            if let Some(ui) = ui_handle.upgrade() {
                if ui.get_server_ok() {
                    println!("Stopping server...");
                    let _ = port_tx.send(None);
                } else {
                    println!("Starting server...");
                    publish_config(config_from_ui(&ui), ui.get_adb_found(), &port_tx);
                }
            }
        }
    });

    ui.on_apply_clicked({
        let port_tx = port_tx.clone();
        let ui_handle = ui.as_weak();
        move |listen_addr, new_port_str, shared_path| {
            if let Some(ui) = ui_handle.upgrade() {
                let config = ServerConfig::from_ui(
                    &listen_addr,
                    &new_port_str,
                    &shared_path,
                    preferences_from_ui(&ui),
                );
                println!(
                    "Applying new config: {}:{} with path: {}",
                    config.listener.address,
                    config.listener.port,
                    config.listener.shared_buffer_path,
                );
                publish_config(config, ui.get_adb_found(), &port_tx);
            }
        }
    });
}

fn settings_from_ui(ui: &MainWindow) -> SyncedSettings {
    SyncedSettings {
        size_x: ui.get_sensor_size_x().round() as i32,
        size_y: ui.get_sensor_size_y().round() as i32,
        spacing: ui.get_sensor_spacing().round() as i32,
        angle: ui.get_sensor_angle().round() as i32,
        exposure: ui.get_camera_exposure().round() as i32,
        threshold: ui.get_sensor_threshold().round() as i32,
        offset_x: ui.get_zone_offset_x(),
        offset_y: ui.get_zone_offset_y(),
    }
}

fn config_from_ui(ui: &MainWindow) -> ServerConfig {
    ServerConfig::from_ui(
        &ui.get_listen_address(),
        &ui.get_port(),
        &ui.get_shared_buffer_path(),
        preferences_from_ui(ui),
    )
}

fn preferences_from_ui(ui: &MainWindow) -> ServerPreferences {
    ServerPreferences {
        auto_adb: ui.get_auto_adb(),
        minimize_on_startup: ui.get_minimize_on_startup(),
        auto_refresh_preview: ui.get_auto_refresh_preview(),
        preview_refresh_seconds: ui.get_preview_refresh_seconds() as u32,
        disable_battery_low_warning: ui.get_disable_battery_low_warning(),
        backdrop_effect: crate::theme::BackdropEffect::from_index(ui.get_backdrop_mode()),
    }
}

fn publish_config(config: ServerConfig, adb_found: bool, sender: &ListenerSender) {
    crate::config::save_config(&config);
    if config.auto_adb && adb_found {
        crate::utils::reverse_adb_port(config.listener.port);
    }
    let _ = sender.send(Some(config.listener));
}
