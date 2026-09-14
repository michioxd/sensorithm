use crate::config::ListenerConfig;
use crate::protocol::{SENSOR_MASK, read_client_metadata, write_server_metadata};
use crate::shared_buffer::{DEFAULT_MAPPING_NAME, SharedMaskOutput};
use slint::{ModelRc, VecModel};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use uuid::Uuid;

type SharedOutput = Arc<Mutex<SharedMaskOutput>>;

fn apply_mask(output: &SharedOutput, mask: u8) {
    if let Ok(mut output) = output.lock() {
        output.write_mask(mask);
    }
}

pub async fn run_server(
    ui_handle: slint::Weak<crate::MainWindow>,
    mut port_rx: watch::Receiver<Option<ListenerConfig>>,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<(String, String)>,
) {
    let client_masks: Arc<tokio::sync::Mutex<HashMap<String, u8>>> =
        Arc::new(tokio::sync::Mutex::new(HashMap::new()));
    let client_cmd_txs: Arc<
        tokio::sync::Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<String>>>,
    > = Arc::new(tokio::sync::Mutex::new(HashMap::new()));
    let shared_output = Arc::new(Mutex::new(SharedMaskOutput::new(DEFAULT_MAPPING_NAME)));

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
        let mut refresh = tokio::time::interval(Duration::from_millis(4));
        refresh.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _ = refresh.tick() => {
                    let masks = client_masks.lock().await;
                    if !masks.is_empty() {
                        apply_mask(&shared_output, masks.values().fold(0, |acc, &m| acc | m));
                    }
                }
                Some(_) = clients.join_next(), if !clients.is_empty() => {}
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, addr)) => {
                            println!("New connection: {}", addr);
                            let ui_clone = ui_handle.clone();
                            let client_masks_clone = client_masks.clone();
                            let shared_output_clone = shared_output.clone();

                            let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<String>();
                            let client_id = Uuid::new_v4().to_string();

                            {
                                let mut txs = client_cmd_txs.lock().await;
                                txs.insert(client_id.clone(), tx);
                            }

                            let txs_clone = client_cmd_txs.clone();

                            clients.spawn(async move {
                                handle_client(stream, ui_clone, client_masks_clone, shared_output_clone, client_id.clone(), rx).await;

                                let mut txs = txs_clone.lock().await;
                                txs.remove(&client_id);
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
                    if let Some((cid, cmd)) = cmd_opt {
                        let txs = client_cmd_txs.lock().await;
                        if let Some(tx) = txs.get(&cid) {
                            let _ = tx.send(cmd);
                        }
                    }
                }
            }
        }

        clients.abort_all();
        while clients.join_next().await.is_some() {}
        client_cmd_txs.lock().await.clear();
        client_masks.lock().await.clear();
        if let Ok(mut output) = shared_output.lock() {
            output.close();
        }

        let ui = ui_handle.clone();
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(ui) = ui.upgrade() {
                ui.set_server_ok(false);
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
    client_masks: Arc<tokio::sync::Mutex<HashMap<String, u8>>>,
    shared_output: SharedOutput,
    client_id: String,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<String>,
) {
    let client_ip = stream
        .peer_addr()
        .map(|addr| addr.ip().to_string())
        .unwrap_or_else(|_| "Unknown IP".into());

    let mut reader = tokio::io::BufReader::new(stream);
    let metadata = read_client_metadata(&mut reader, &client_ip).await;
    write_server_metadata(&mut reader).await;

    println!(
        "Client {} ({}) connected, starting sensor loop",
        client_id, client_ip
    );

    let ui_h = ui_handle.clone();
    let cid = client_id.clone();
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
        }
    });

    let mut buf = [0u8; 1];
    loop {
        tokio::select! {
            cmd_opt = cmd_rx.recv() => {
                match cmd_opt {
                    Some(cmd) => {
                        let cmd_str = format!("{}\n", cmd);
                        if let Err(e) = reader.get_mut().write_all(cmd_str.as_bytes()).await {
                            eprintln!("Failed to send command to client {}: {}", client_id, e);
                            break;
                        }
                        let _ = reader.get_mut().flush().await;
                    }
                    None => break, // Channel closed
                }
            }
            read_res = reader.read_exact(&mut buf) => {
                match read_res {
                    Ok(_) => {
                        let mask = buf[0] & SENSOR_MASK;

                        let combined_mask = {
                            let mut masks = client_masks.lock().await;
                            masks.insert(client_id.clone(), mask);
                            let combined = masks.values().fold(0, |acc, &m| acc | m);
                            apply_mask(&shared_output, combined);
                            combined
                        };

                        let ui_h = ui_handle.clone();
                        let _ = slint::invoke_from_event_loop(move || {
                            if let Some(ui) = ui_h.upgrade() {
                                let states: Vec<bool> = (0..6).map(|i| (combined_mask & (1 << i)) != 0).collect();
                                let sensor_model = Rc::new(VecModel::from(states));
                                ui.set_sensor_states(ModelRc::from(sensor_model));
                            }
                        });
                    }
                    Err(e) => {
                        println!("Client {} disconnected: {}", client_id, e);
                        break;
                    }
                }
            }
        }
    }

    let combined_mask = {
        let mut masks = client_masks.lock().await;
        masks.remove(&client_id);
        let combined = masks.values().fold(0, |acc, &m| acc | m);
        apply_mask(&shared_output, combined);
        combined
    };

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

            let states: Vec<bool> = (0..6).map(|i| (combined_mask & (1 << i)) != 0).collect();
            let sensor_model = Rc::new(VecModel::from(states));
            ui.set_sensor_states(ModelRc::from(sensor_model));
        }
    });
}
