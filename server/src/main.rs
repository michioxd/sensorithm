#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

slint::include_modules!();

use slint::{ModelRc, VecModel};
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::thread;
use tokio::sync::watch;

mod config;
mod preview;
mod protocol;
mod server;
mod shared_buffer;
mod theme;
mod ui;
mod utils;

fn main() -> Result<(), slint::PlatformError> {
    let saved_config = config::load_config();

    #[cfg(windows)]
    theme::select_backend()?;

    let ui = MainWindow::new()?;

    #[cfg(windows)]
    theme::setup(&ui, saved_config.backdrop_effect);
    ui.set_listen_address(saved_config.listener.address.clone().into());
    ui.set_port(saved_config.listener.port.to_string().into());
    ui.set_shared_buffer_path(saved_config.listener.shared_buffer_path.clone().into());
    ui.set_auto_adb(saved_config.auto_adb);
    ui.set_minimize_on_startup(saved_config.minimize_on_startup);
    ui.set_auto_refresh_preview(saved_config.auto_refresh_preview);
    ui.set_preview_refresh_seconds(saved_config.preview_refresh_seconds as i32);
    ui.set_disable_battery_low_warning(saved_config.disable_battery_low_warning);
    ui.set_config_valid(true);

    ui.set_server_ver(env!("CARGO_PKG_VERSION").into());

    let adb_found = which::which("adb").is_ok();
    ui.set_adb_found(adb_found);

    let initial_states = vec![false, false, false, false, false, false];
    let sensor_model = Rc::new(VecModel::from(initial_states));
    ui.set_sensor_states(ModelRc::from(sensor_model.clone()));

    if let Some((x, y)) = saved_config.window_position {
        #[cfg(windows)]
        {
            use windows::Win32::UI::WindowsAndMessaging::{
                GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN,
                SM_YVIRTUALSCREEN,
            };
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

    let initial_listener = saved_config.listener;

    if saved_config.auto_adb && adb_found {
        crate::utils::reverse_adb_port(initial_listener.port);
    }

    if saved_config.minimize_on_startup {
        let ui_handle = ui.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_millis(50), move || {
            if let Some(ui) = ui_handle.upgrade() {
                ui.window().set_minimized(true);
            }
        });
    }

    let (port_tx, port_rx) = watch::channel(Some(initial_listener));
    let (cmd_tx, cmd_rx) = tokio::sync::mpsc::unbounded_channel::<protocol::ServerMessage>();
    let battery_low_warning_enabled =
        Arc::new(AtomicBool::new(!saved_config.disable_battery_low_warning));

    ui::setup_event_handlers(&ui, port_tx, cmd_tx, battery_low_warning_enabled.clone());

    let ui_handle = ui.as_weak();
    thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            server::run_server(ui_handle, port_rx, cmd_rx, battery_low_warning_enabled).await;
        });
    });

    ui.run()
}
