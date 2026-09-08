#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

slint::include_modules!();

use slint::{ModelRc, VecModel};
use std::rc::Rc;
use std::thread;
use tokio::sync::watch;

mod config;
mod server;
mod ui;
mod utils;

fn main() -> Result<(), slint::PlatformError> {
    let ui = MainWindow::new()?;

    let (saved_addr, saved_port, saved_path, saved_auto_adb, saved_minimize, saved_pos) = config::load_config();
    ui.set_listen_address(saved_addr.clone().into());
    ui.set_port(saved_port.to_string().into());
    ui.set_shared_buffer_path(saved_path.clone().into());
    ui.set_auto_adb(saved_auto_adb);
    ui.set_minimize_on_startup(saved_minimize);
    ui.set_config_valid(true);

    ui.set_server_ver(env!("CARGO_PKG_VERSION").into());

    let adb_found = which::which("adb").is_ok();
    ui.set_adb_found(adb_found);

    let initial_states = vec![false, false, false, false, false, false];
    let sensor_model = Rc::new(VecModel::from(initial_states));
    ui.set_sensor_states(ModelRc::from(sensor_model.clone()));

    if let Some((x, y)) = saved_pos {
        #[cfg(windows)]
        {
            use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN};
            let vx = unsafe { GetSystemMetrics(SM_XVIRTUALSCREEN) };
            let vy = unsafe { GetSystemMetrics(SM_YVIRTUALSCREEN) };
            let vw = unsafe { GetSystemMetrics(SM_CXVIRTUALSCREEN) };
            let vh = unsafe { GetSystemMetrics(SM_CYVIRTUALSCREEN) };

            if x >= vx && x < vx + vw && y >= vy && y < vy + vh {
                ui.window().set_position(slint::PhysicalPosition::new(x, y));
            }
        }
        #[cfg(not(windows))]
        {
            ui.window().set_position(slint::PhysicalPosition::new(x, y));
        }
    }

    let ui_weak = ui.as_weak();
    ui.window().on_close_requested(move || {
        if let Some(ui) = ui_weak.upgrade() {
            let pos = ui.window().position();
            config::save_window_position(pos.x, pos.y);
        }
        slint::CloseRequestResponse::HideWindow
    });

    let initial_port = ui.get_port().to_string().parse::<u16>().unwrap_or(4420);
    let initial_path = ui.get_shared_buffer_path().to_string();
    let initial_addr = ui.get_listen_address().to_string();
    let initial_auto_adb = ui.get_auto_adb();
    let initial_minimize = ui.get_minimize_on_startup();
    
    if initial_auto_adb && adb_found {
        crate::utils::reverse_adb_port(initial_port);
    }
    
    if initial_minimize {
        let ui_handle = ui.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_millis(50), move || {
            if let Some(ui) = ui_handle.upgrade() {
                ui.window().set_minimized(true);
            }
        });
    }
    
    let (port_tx, port_rx) = watch::channel(Some((initial_addr.clone(), initial_port, initial_path.clone())));
    
    ui::setup_event_handlers(&ui, port_tx);

    let ui_handle = ui.as_weak();
    thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            server::run_server(ui_handle, port_rx).await;
        });
    });

    ui.run()
}