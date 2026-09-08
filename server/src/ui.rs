use crate::MainWindow;
use slint::ComponentHandle;
use tokio::sync::watch;

pub fn setup_event_handlers(
    ui: &MainWindow,
    port_tx: watch::Sender<Option<(String, u16, String)>>,
    cmd_tx: tokio::sync::mpsc::UnboundedSender<(String, String)>,
) {
    ui.on_github_clicked(|| {
        let _ = webbrowser::open("https://github.com/michioxd/sensorithm");
    });

    ui.on_recalibrate_clicked(move |id| {
        let _ = cmd_tx.send((id.to_string(), "RECALIBRATE".to_string()));
    });

    let ui_weak = ui.as_weak();
    ui.on_forward_port_clicked(move || {
        if let Some(ui) = ui_weak.upgrade() {
            let current_port = ui.get_port().to_string().parse::<u16>().unwrap_or(4420);
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
                    let listen_addr = ui.get_listen_address().to_string();
                    let new_port_str = ui.get_port().to_string();
                    let shared_path = ui.get_shared_buffer_path().to_string();
                    let auto_adb = ui.get_auto_adb();
                    let minimize_on_startup = ui.get_minimize_on_startup();
                    let adb_found = ui.get_adb_found();
                    
                    let new_port = new_port_str.parse::<u16>().unwrap_or(4420);
                    let final_addr = if listen_addr.trim().is_empty() {
                        "127.0.0.1".to_string()
                    } else {
                        listen_addr
                    };
                    let final_path = if shared_path.trim().is_empty() {
                        r"Local\BROKENITHM_SHARED_BUFFER".to_string()
                    } else {
                        shared_path
                    };
                    crate::config::save_config(&final_addr, new_port, &final_path, auto_adb, minimize_on_startup);
                    if auto_adb && adb_found {
                        crate::utils::reverse_adb_port(new_port);
                    }
                    let _ = port_tx.send(Some((final_addr, new_port, final_path)));
                }
            }
        }
    });

    ui.on_apply_clicked({
        let port_tx = port_tx.clone();
        let ui_handle = ui.as_weak();
        move |listen_addr, new_port_str, shared_path| {
            if let Some(ui) = ui_handle.upgrade() {
                let new_port = new_port_str.to_string().parse::<u16>().unwrap_or(4420);
                let final_addr = if listen_addr.trim().is_empty() {
                    "127.0.0.1".to_string()
                } else {
                    listen_addr.to_string()
                };
                let final_path = if shared_path.trim().is_empty() {
                    r"Local\BROKENITHM_SHARED_BUFFER".to_string()
                } else {
                    shared_path.to_string()
                };
                let auto_adb = ui.get_auto_adb();
                let minimize_on_startup = ui.get_minimize_on_startup();
                let adb_found = ui.get_adb_found();
                
                println!("Applying new config: {}:{} with path: {}", final_addr, new_port, final_path);
                crate::config::save_config(&final_addr, new_port, &final_path, auto_adb, minimize_on_startup);
                
                if auto_adb && adb_found {
                    crate::utils::reverse_adb_port(new_port);
                }
                
                let _ = port_tx.send(Some((final_addr, new_port, final_path)));
            }
        }
    });
}
