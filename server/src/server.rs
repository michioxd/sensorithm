use crate::config::ListenerConfig;
use crate::protocol::{
    ClientFrame, ClientMessage, ServerMessage, read_client_frame, read_client_metadata,
    write_server_hello, write_server_message,
};
use crate::shared_buffer::{DEFAULT_MAPPING_NAME, SharedMaskOutput};
use slint::{ModelRc, VecModel};
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use uuid::Uuid;

type SharedOutput = Arc<Mutex<SharedMaskOutput>>;
const CLIENT_BUSY_MESSAGE: &str =
    "Another client is already connected. You cannot connect right now.";
const PREVIEW_TIMEOUT: Duration = Duration::from_secs(5);
const LOW_BATTERY_THRESHOLDS: [u8; 3] = [20, 10, 5];

fn apply_mask(output: &SharedOutput, mask: u8) {
    if let Ok(mut output) = output.lock() {
        output.write_mask(mask);
    }
}

pub async fn run_server(
    ui_handle: slint::Weak<crate::MainWindow>,
    mut port_rx: watch::Receiver<Option<ListenerConfig>>,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<ServerMessage>,
    battery_low_warning_enabled: Arc<AtomicBool>,
) {
    let shared_output = Arc::new(Mutex::new(SharedMaskOutput::new(DEFAULT_MAPPING_NAME)));
    let active_mask = Arc::new(AtomicU8::new(0));

    loop {
        let config_opt = port_rx.borrow().clone();

        let config = match config_opt {
            Some(c) => c,
            None => {
                let ui = ui_handle.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = ui.upgrade() {
                        ui.set_server_ok(false);
                    }
                });

                if port_rx.changed().await.is_err() {
                    break;
                }
                continue;
            }
        };

        if let Ok(mut output) = shared_output.lock() {
            output.change_mapping_name(&config.shared_buffer_path);
        }

        let bind_addr = format!("{}:{}", config.address, config.port);
        let listener = match TcpListener::bind(&bind_addr).await {
            Ok(l) => {
                println!("Server listening on {}", bind_addr);
                let ui = ui_handle.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = ui.upgrade() {
                        ui.set_server_ok(true);
                    }
                });
                l
            }
            Err(e) => {
                eprintln!("Failed to bind to {}: {}", bind_addr, e);
                let ui = ui_handle.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = ui.upgrade() {
                        ui.set_server_ok(false);
                    }
                });

                if e.kind() == std::io::ErrorKind::AddrInUse {
                    crate::utils::show_error_dialog(
                        "Port Error",
                        &format!(
                            "Failed to bind to {}.\nThe port {} is already in use by another application. Try a different port then restart the server.",
                            bind_addr, config.port
                        ),
                    );
                } else {
                    crate::utils::show_error_dialog(
                        "Bind Error",
                        &format!("Failed to bind to {}: {}", bind_addr, e),
                    );
                }

                if port_rx.changed().await.is_err() {
                    break;
                }
                continue;
            }
        };

        let mut clients = tokio::task::JoinSet::new();
        let mut active_client_tx: Option<tokio::sync::mpsc::UnboundedSender<ServerMessage>> = None;
        let mut refresh = tokio::time::interval(Duration::from_millis(4));
        refresh.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = refresh.tick() => {
                    if active_client_tx.is_some() {
                        apply_mask(&shared_output, active_mask.load(Ordering::Relaxed));
                    }
                }
                Some(_) = clients.join_next(), if !clients.is_empty() => {
                    if active_client_tx.as_ref().is_some_and(|sender| sender.is_closed()) {
                        active_client_tx = None;
                    }
                }
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, addr)) => {
                            if active_client_tx.as_ref().is_some_and(|sender| sender.is_closed()) {
                                active_client_tx = None;
                            }
                            if active_client_tx.is_some() {
                                println!("Rejecting additional connection from {addr}");
                                tokio::spawn(async move {
                                    let fallback = addr.ip().to_string();
                                    let mut reader = tokio::io::BufReader::new(stream);
                                    let _ = read_client_metadata(&mut reader, &fallback).await;
                                    let _ = write_server_hello(
                                        reader.get_mut(),
                                        false,
                                        Some(CLIENT_BUSY_MESSAGE),
                                    )
                                    .await;
                                });
                                continue;
                            }
                            println!("New connection: {}", addr);
                            let ui_clone = ui_handle.clone();
                            let shared_output_clone = shared_output.clone();
                            let active_mask_clone = active_mask.clone();
                            let battery_warning_clone = battery_low_warning_enabled.clone();

                            let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<ServerMessage>();
                            let client_id = Uuid::new_v4().to_string();
                            active_client_tx = Some(tx);

                            clients.spawn(async move {
                                handle_client(
                                    stream,
                                    ui_clone,
                                    shared_output_clone,
                                    active_mask_clone,
                                    client_id,
                                    rx,
                                    battery_warning_clone,
                                ).await;
                            });
                        }
                        Err(e) => {
                            eprintln!("Accept error: {}", e);
                        }
                    }
                }
                _ = port_rx.changed() => {
                    println!("Config changed; restarting/stopping listener");
                    break;
                }
                cmd_opt = cmd_rx.recv() => {
                    if let Some(command) = cmd_opt
                        && let Some(sender) = &active_client_tx
                    {
                        let _ = sender.send(command);
                    }
                }
            }
        }

        clients.abort_all();
        while clients.join_next().await.is_some() {}
        active_mask.store(0, Ordering::Relaxed);
        if let Ok(mut output) = shared_output.lock() {
            output.close();
        }

        let ui = ui_handle.clone();
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(ui) = ui.upgrade() {
                ui.set_server_ok(false);
                ui.set_client_connected(false);
                ui.set_torch_available(false);
                ui.set_torch_enabled(false);
                ui.set_battery_percent(-1);
                ui.set_battery_charging(false);
                ui.set_device_temperature(-1000.0);
                ui.set_device_temperature_label("".into());
                ui.set_preview_loading(false);
                ui.set_preview_status("Client disconnected".into());
                ui.set_preview_width(0);
                ui.set_preview_height(0);
                ui.set_connected_devices(ModelRc::from(Rc::new(VecModel::from(Vec::<
                    crate::ConnectedDevice,
                >::new(
                )))));
                ui.set_sensor_states(ModelRc::from(Rc::new(VecModel::from(vec![false; 6]))));
            }
        });
        if port_rx.has_changed().is_err() {
            break;
        }
    }
}

async fn handle_client(
    stream: TcpStream,
    ui_handle: slint::Weak<crate::MainWindow>,
    shared_output: SharedOutput,
    active_mask: Arc<AtomicU8>,
    client_id: String,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<ServerMessage>,
    battery_low_warning_enabled: Arc<AtomicBool>,
) {
    let client_ip = stream
        .peer_addr()
        .map(|addr| addr.ip().to_string())
        .unwrap_or_else(|_| "Unknown IP".into());

    let mut reader = tokio::io::BufReader::new(stream);
    let metadata = read_client_metadata(&mut reader, &client_ip).await;
    if let Err(error) = write_server_hello(reader.get_mut(), true, None).await {
        eprintln!("Failed to accept client {client_id}: {error}");
        return;
    }

    println!(
        "Client {} ({}) connected, starting sensor loop",
        client_id, client_ip
    );

    let ui_h = ui_handle.clone();
    let cid = client_id.clone();
    let ios_thermal_state = metadata.description.starts_with("iOS ");
    let cdesc = metadata.description;
    let cname = metadata.name;
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_h.upgrade() {
            let mut devices: Vec<crate::ConnectedDevice> =
                ui.get_connected_devices().iter().collect();
            devices.push(crate::ConnectedDevice {
                id: cid.into(),
                name: cname.into(),
                description: cdesc.into(),
                ip: client_ip.into(),
            });

            use slint::Model;
            let device_model = std::rc::Rc::new(slint::VecModel::from(devices));
            ui.set_connected_devices(slint::ModelRc::from(device_model));
            ui.set_client_connected(true);
            ui.set_client_error("".into());
            ui.set_battery_percent(-1);
            ui.set_battery_charging(false);
            ui.set_device_temperature(-1000.0);
            ui.set_device_temperature_label("".into());
            ui.set_preview_width(0);
            ui.set_preview_height(0);
            ui.set_preview_loading(false);
            ui.set_preview_status("Click Refresh to request a preview".into());
        }
    });

    // Reading a large preview frame must never be cancelled halfway through by
    // an outgoing command or timeout tick. A dedicated reader owns the stream
    // half and publishes only complete frames to this session loop.
    let stream = reader.into_inner();
    let (read_half, mut write_half) = stream.into_split();
    let (frame_tx, mut frame_rx) = tokio::sync::mpsc::unbounded_channel();
    let frame_reader = tokio::spawn(async move {
        let mut reader = tokio::io::BufReader::new(read_half);
        loop {
            let frame = read_client_frame(&mut reader).await;
            let disconnected = frame.is_err();
            if frame_tx.send(frame).is_err() || disconnected {
                break;
            }
        }
    });

    let mut pending_preview: Option<(String, tokio::time::Instant)> = None;
    let mut previous_battery_percent: Option<u8> = None;
    let mut preview_timeout_check = tokio::time::interval(Duration::from_millis(250));
    preview_timeout_check.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {
            _ = preview_timeout_check.tick() => {
                if pending_preview
                    .as_ref()
                    .is_some_and(|(_, deadline)| tokio::time::Instant::now() >= *deadline)
                {
                    pending_preview = None;
                    set_preview_status(&ui_handle, false, "Preview request timed out");
                }
            }
            cmd_opt = cmd_rx.recv() => {
                match cmd_opt {
                    Some(cmd) => {
                        if let ServerMessage::RequestPreview { request_id } = &cmd {
                            if pending_preview.is_some() {
                                continue;
                            }
                            pending_preview = Some((
                                request_id.clone(),
                                tokio::time::Instant::now() + PREVIEW_TIMEOUT,
                            ));
                        }
                        if let Err(e) = write_server_message(&mut write_half, &cmd).await {
                            eprintln!("Failed to send command to client {}: {}", client_id, e);
                            break;
                        }
                    }
                    None => break, // Channel closed
                }
            }
            read_opt = frame_rx.recv() => {
                let Some(read_res) = read_opt else {
                    break;
                };
                match read_res {
                    Ok(ClientFrame::SensorMask(mask)) => {
                        active_mask.store(mask, Ordering::Relaxed);
                        apply_mask(&shared_output, mask);
                        let ui_h = ui_handle.clone();
                        let _ = slint::invoke_from_event_loop(move || {
                            if let Some(ui) = ui_h.upgrade() {
                                let states: Vec<bool> = (0..6).map(|i| (mask & (1 << i)) != 0).collect();
                                let sensor_model = Rc::new(VecModel::from(states));
                                ui.set_sensor_states(ModelRc::from(sensor_model));
                            }
                        });
                    }
                    Ok(ClientFrame::Control(message)) => {
                        handle_client_message(
                            &ui_handle,
                            message,
                            &mut pending_preview,
                            &mut previous_battery_percent,
                            battery_low_warning_enabled.load(Ordering::Relaxed),
                            ios_thermal_state,
                        );
                    }
                    Err(e) => {
                        println!("Client {} disconnected: {}", client_id, e);
                        break;
                    }
                }
            }
        }
    }

    frame_reader.abort();

    apply_mask(&shared_output, 0);
    active_mask.store(0, Ordering::Relaxed);

    let cid = client_id.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_handle.upgrade() {
            use slint::Model;
            let devices: Vec<crate::ConnectedDevice> = ui
                .get_connected_devices()
                .iter()
                .filter(|d| d.id != cid)
                .collect();

            let device_model = std::rc::Rc::new(slint::VecModel::from(devices));
            ui.set_connected_devices(slint::ModelRc::from(device_model));
            ui.set_client_connected(false);
            ui.set_torch_available(false);
            ui.set_torch_enabled(false);
            ui.set_battery_percent(-1);
            ui.set_battery_charging(false);
            ui.set_device_temperature(-1000.0);
            ui.set_device_temperature_label("".into());
            ui.set_preview_loading(false);
            ui.set_preview_status("Client disconnected".into());
            ui.set_preview_width(0);
            ui.set_preview_height(0);

            let sensor_model = Rc::new(VecModel::from(vec![false; 6]));
            ui.set_sensor_states(ModelRc::from(sensor_model));
        }
    });
}

fn handle_client_message(
    ui_handle: &slint::Weak<crate::MainWindow>,
    message: ClientMessage,
    pending_preview: &mut Option<(String, tokio::time::Instant)>,
    previous_battery_percent: &mut Option<u8>,
    battery_low_warning_enabled: bool,
    ios_thermal_state: bool,
) {
    if let ClientMessage::Preview {
        request_id,
        width,
        height,
        image_base64,
    } = &message
    {
        let is_expected = pending_preview
            .as_ref()
            .is_some_and(|(pending_id, _)| pending_id == request_id);
        if !is_expected {
            set_preview_status(ui_handle, false, "Ignoring an unexpected preview response");
            return;
        }
        pending_preview.take();
        match crate::preview::decode_jpeg(image_base64, *width, *height) {
            Ok(preview) => {
                let width = *width;
                let height = *height;
                let ui_handle = ui_handle.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = ui_handle.upgrade() {
                        let buffer =
                            slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(
                                &preview.rgba,
                                preview.width,
                                preview.height,
                            );
                        let image = slint::Image::from_rgba8(buffer);
                        ui.set_preview_image(image);
                        ui.set_preview_width(width as i32);
                        ui.set_preview_height(height as i32);
                        ui.set_preview_loading(false);
                        ui.set_preview_status(format!("Preview: {width} x {height}").into());
                    }
                });
            }
            Err(error) => set_preview_status(ui_handle, false, &error),
        }
        return;
    }

    if let ClientMessage::Error {
        operation,
        request_id,
        message,
    } = &message
        && operation == "preview"
        && request_id.as_ref().is_some_and(|id| {
            pending_preview
                .as_ref()
                .is_some_and(|(pending, _)| pending == id)
        })
    {
        pending_preview.take();
        set_preview_status(ui_handle, false, &format!("Preview failed: {message}"));
    }

    if let ClientMessage::Telemetry {
        battery_percent,
        temperature_celsius,
        ..
    } = &message
        && *battery_percent <= 100
        && temperature_celsius.is_none_or(|temperature| {
            temperature.is_finite() && (-50.0..=150.0).contains(&temperature)
        })
    {
        if battery_low_warning_enabled
            && let Some(threshold) =
                crossed_low_battery_threshold(*previous_battery_percent, *battery_percent)
        {
            crate::utils::show_low_battery_notification(*battery_percent, threshold);
        }
        *previous_battery_percent = Some(*battery_percent);
    }

    let ui_handle = ui_handle.clone();
    let _ = slint::invoke_from_event_loop(move || {
        let Some(ui) = ui_handle.upgrade() else {
            return;
        };
        match message {
            ClientMessage::Settings { settings } if settings.is_valid() => {
                ui.set_sensor_size_x(settings.size_x as f32);
                ui.set_sensor_size_y(settings.size_y as f32);
                ui.set_sensor_spacing(settings.spacing as f32);
                ui.set_sensor_angle(settings.angle as f32);
                ui.set_camera_exposure(settings.exposure as f32);
                ui.set_sensor_threshold(settings.threshold as f32);
                ui.set_zone_offset_x(settings.offset_x);
                ui.set_zone_offset_y(settings.offset_y);
                ui.set_client_error("".into());
            }
            ClientMessage::Settings { .. } => {
                ui.set_client_error("Client sent invalid settings".into());
            }
            ClientMessage::TorchState { available, enabled } => {
                ui.set_torch_available(available);
                ui.set_torch_enabled(available && enabled);
            }
            ClientMessage::Telemetry {
                battery_percent,
                temperature_celsius,
                charging,
            } if battery_percent <= 100
                && temperature_celsius.is_none_or(|temperature| {
                    temperature.is_finite() && (-50.0..=150.0).contains(&temperature)
                }) =>
            {
                ui.set_battery_percent(battery_percent as i32);
                ui.set_battery_charging(charging);
                ui.set_device_temperature(temperature_celsius.unwrap_or(-1000.0));
                ui.set_device_temperature_label(
                    if ios_thermal_state {
                        temperature_celsius.map(ios_thermal_label).unwrap_or_default()
                    } else {
                        ""
                    }
                    .into(),
                );
            }
            ClientMessage::Telemetry { .. } => {
                ui.set_client_error("Client sent invalid telemetry".into());
            }
            ClientMessage::Error {
                operation, message, ..
            } => {
                ui.set_client_error(format!("{operation}: {message}").into());
            }
            ClientMessage::Preview { .. } => unreachable!(),
        }
    });
}

fn ios_thermal_label(temperature: f32) -> &'static str {
    match temperature.round() as i32 {
        30 => "Nominal",
        40 => "Fair",
        50 => "Serious",
        60 => "Critical",
        _ => "Unknown",
    }
}

fn crossed_low_battery_threshold(previous: Option<u8>, current: u8) -> Option<u8> {
    let previous = previous?;
    LOW_BATTERY_THRESHOLDS
        .iter()
        .rev()
        .copied()
        .find(|threshold| previous >= *threshold && current < *threshold)
}

fn set_preview_status(ui_handle: &slint::Weak<crate::MainWindow>, loading: bool, status: &str) {
    let ui_handle = ui_handle.clone();
    let status = status.to_owned();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_handle.upgrade() {
            ui.set_preview_loading(loading);
            ui.set_preview_status(status.into());
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{crossed_low_battery_threshold, ios_thermal_label};

    #[test]
    fn maps_ios_thermal_state_values_to_labels() {
        assert_eq!(ios_thermal_label(30.0), "Nominal");
        assert_eq!(ios_thermal_label(40.0), "Fair");
        assert_eq!(ios_thermal_label(50.0), "Serious");
        assert_eq!(ios_thermal_label(60.0), "Critical");
        assert_eq!(ios_thermal_label(0.0), "Unknown");
    }

    #[test]
    fn low_battery_notification_fires_when_crossing_configured_thresholds() {
        assert_eq!(crossed_low_battery_threshold(Some(20), 19), Some(20));
        assert_eq!(crossed_low_battery_threshold(Some(10), 9), Some(10));
        assert_eq!(crossed_low_battery_threshold(Some(5), 4), Some(5));
        assert_eq!(crossed_low_battery_threshold(Some(11), 4), Some(5));
        assert_eq!(crossed_low_battery_threshold(None, 19), None);
        assert_eq!(crossed_low_battery_threshold(Some(19), 18), None);
        assert_eq!(crossed_low_battery_threshold(Some(20), 20), None);
    }
}
