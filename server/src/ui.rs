use crate::MainWindow;
use crate::config::{DEFAULT_PORT, ListenerConfig, ServerConfig};
use slint::ComponentHandle;
use tokio::sync::watch;

const RECALIBRATE_COMMAND: &str = "RECALIBRATE";
type ListenerSender = watch::Sender<Option<ListenerConfig>>;

pub fn setup_event_handlers(
    ui: &MainWindow,
    port_tx: ListenerSender,
    cmd_tx: tokio::sync::mpsc::UnboundedSender<(String, String)>,
) {
    ui.on_github_clicked(|| {
        let _ = webbrowser::open("https://github.com/michioxd/sensorithm");
    });

    ui.on_recalibrate_clicked(move |id| {
        let _ = cmd_tx.send((id.to_string(), RECALIBRATE_COMMAND.to_string()));
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
                    ui.get_auto_adb(),
                    ui.get_minimize_on_startup(),
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

fn config_from_ui(ui: &MainWindow) -> ServerConfig {
    ServerConfig::from_ui(
        &ui.get_listen_address(),
        &ui.get_port(),
        &ui.get_shared_buffer_path(),
        ui.get_auto_adb(),
        ui.get_minimize_on_startup(),
    )
}

fn publish_config(config: ServerConfig, adb_found: bool, sender: &ListenerSender) {
    crate::config::save_config(&config);
    if config.auto_adb && adb_found {
        crate::utils::reverse_adb_port(config.listener.port);
    }
    let _ = sender.send(Some(config.listener));
}
