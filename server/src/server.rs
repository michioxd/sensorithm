use slint::{ModelRc, VecModel};
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::collections::HashMap;
use tokio::io::{AsyncReadExt, AsyncBufReadExt, AsyncWriteExt};
use uuid::Uuid;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use serde::Deserialize;
#[derive(Deserialize, Debug)]
struct ClientInfo {
    name: String,
    os: String,
    app: String,
}

#[cfg(windows)]
use windows::{
    core::PCSTR,
    Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE},
    Win32::System::Memory::{
        CreateFileMappingA, MapViewOfFile, OpenFileMappingA, UnmapViewOfFile,
        FILE_MAP_ALL_ACCESS, PAGE_READWRITE,
    },
};


#[cfg(windows)]
const BROKENITHM_MAPPING_NAME: &str = r"Local\BROKENITHM_SHARED_BUFFER";
#[cfg(windows)]
const BROKENITHM_BUFFER_SIZE: usize = 1024;
#[cfg(windows)]
const BROKENITHM_AIR_INDEX: [usize; 6] = [4, 5, 2, 3, 0, 1];

#[cfg(windows)]
struct BrokenithmSharedBuffer {
    mapping_name: String,
    handle: Option<HANDLE>,
    view: Option<*mut u8>,
    last_open_attempt: Option<Instant>,
}

#[cfg(windows)]
unsafe impl Send for BrokenithmSharedBuffer {}
#[cfg(windows)]
unsafe impl Sync for BrokenithmSharedBuffer {}

#[cfg(windows)]
impl BrokenithmSharedBuffer {
    fn new(mapping_name: &str) -> Self {
        Self {
            mapping_name: mapping_name.to_owned(),
            handle: None,
            view: None,
            last_open_attempt: None,
        }
    }

    fn ensure_open(&mut self) -> bool {
        if self.view.is_some() {
            return true;
        }

        let now = Instant::now();
        if let Some(last) = self.last_open_attempt {
            if now.duration_since(last) < Duration::from_secs(1) {
                return false;
            }
        }
        self.last_open_attempt = Some(now);

        let name_cstr = match std::ffi::CString::new(self.mapping_name.as_str()) {
            Ok(name) => name,
            Err(e) => {
                eprintln!("Invalid shared buffer name: {e}");
                return false;
            }
        };
        let handle = unsafe {
            OpenFileMappingA(
                FILE_MAP_ALL_ACCESS.0,
                false,
                PCSTR(name_cstr.as_ptr() as *const u8),
            )
        }
        .or_else(|_| unsafe {
            CreateFileMappingA(
                INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                0,
                BROKENITHM_BUFFER_SIZE as u32,
                PCSTR(name_cstr.as_ptr() as *const u8),
            )
        });

        let handle = match handle {
            Ok(h) if !h.is_invalid() => h,
            Err(e) => {
                eprintln!("Cannot open/create shared buffer {}: {e}", self.mapping_name);
                return false;
            }
            _ => return false,
        };

        let view = unsafe {
            MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0)
        };

        if view.Value.is_null() {
            eprintln!("Cannot map shared buffer {}: {}", self.mapping_name, std::io::Error::last_os_error());
            unsafe { let _ = CloseHandle(handle); }
            return false;
        }

        self.handle = Some(handle);
        self.view = Some(view.Value as *mut u8);
        println!("Connected to Brokenithm shared buffer: {}", self.mapping_name);
        true
    }

    fn write_mask(&mut self, mask: u8) -> bool {
        if !self.ensure_open() {
            return false;
        }

        let view = self.view.unwrap();
        for (sensor_index, &shared_index) in BROKENITHM_AIR_INDEX.iter().enumerate() {
            let value = u8::from((mask & (1 << sensor_index)) != 0);
            unsafe { std::ptr::write_volatile(view.add(shared_index), value); }
        }
        true
    }

    fn close(&mut self) {
        if let Some(view) = self.view.take() {
            for i in 0..6usize {
                unsafe { std::ptr::write_volatile(view.add(i), 0); }
            }
            unsafe { let _ = UnmapViewOfFile(windows::Win32::System::Memory::MEMORY_MAPPED_VIEW_ADDRESS { Value: view as *mut _ }); }
        }
        if let Some(handle) = self.handle.take() {
            unsafe { let _ = CloseHandle(handle); }
        }
        println!("Disconnected from Brokenithm shared buffer");
    }

    fn change_mapping_name(&mut self, new_name: &str) {
        if self.mapping_name != new_name {
            self.close();
            self.mapping_name = new_name.to_owned();
            self.last_open_attempt = None;
        }
    }
}

#[cfg(windows)]
impl Drop for BrokenithmSharedBuffer {
    fn drop(&mut self) {
        self.close();
    }
}


#[cfg(windows)]
static BROKENITHM: std::sync::OnceLock<Arc<Mutex<BrokenithmSharedBuffer>>> = std::sync::OnceLock::new();

#[cfg(windows)]
fn get_brokenithm() -> Arc<Mutex<BrokenithmSharedBuffer>> {
    BROKENITHM
        .get_or_init(|| Arc::new(Mutex::new(BrokenithmSharedBuffer::new(BROKENITHM_MAPPING_NAME))))
        .clone()
}

pub fn set_brokenithm_mapping_name(new_name: &str) {
    #[cfg(windows)]
    {
        if let Ok(mut buf) = get_brokenithm().lock() {
            buf.change_mapping_name(new_name);
        }
    }
}

fn apply_mask(mask: u8) {
    #[cfg(windows)]
    {
        if let Ok(mut buf) = get_brokenithm().lock() {
            buf.write_mask(mask);
        }
    }
}


pub async fn run_server(
    ui_handle: slint::Weak<crate::MainWindow>,
    mut port_rx: watch::Receiver<Option<(String, u16, String)>>,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<(String, String)>,
) {
    let client_masks: Arc<tokio::sync::Mutex<HashMap<String, u8>>> = Arc::new(tokio::sync::Mutex::new(HashMap::new()));
    let client_cmd_txs: Arc<tokio::sync::Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<String>>>> = Arc::new(tokio::sync::Mutex::new(HashMap::new()));

    loop {
        let config_opt = port_rx.borrow().clone();
        
        let (address, port, shared_path) = match config_opt {
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

        set_brokenithm_mapping_name(&shared_path);

        let bind_addr = format!("{}:{}", address, port);
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
                        &format!("Failed to bind to {}.\nThe port {} is already in use by another application. Try a different port then restart the server.", bind_addr, port)
                    );
                } else {
                    crate::utils::show_error_dialog(
                        "Bind Error",
                        &format!("Failed to bind to {}: {}", bind_addr, e)
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
                        apply_mask(masks.values().fold(0, |acc, &m| acc | m));
                    }
                }
                Some(_) = clients.join_next(), if !clients.is_empty() => {}
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, addr)) => {
                            println!("New connection: {}", addr);
                            let ui_clone = ui_handle.clone();
                            let client_masks_clone = client_masks.clone();
                            
                            let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<String>();
                            let client_id = Uuid::new_v4().to_string();
                            
                            {
                                let mut txs = client_cmd_txs.lock().await;
                                txs.insert(client_id.clone(), tx);
                            }
                            
                            let txs_clone = client_cmd_txs.clone();
                            
                            clients.spawn(async move {
                                handle_client(stream, ui_clone, client_masks_clone, client_id.clone(), rx).await;
                                
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
        #[cfg(windows)]
        if let Ok(mut buf) = get_brokenithm().lock() {
            buf.close();
        }
        
        let ui = ui_handle.clone();
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(ui) = ui.upgrade() {
                ui.set_server_ok(false);
                ui.set_connected_devices(ModelRc::from(Rc::new(VecModel::from(Vec::<crate::ConnectedDevice>::new()))));
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
    client_id: String,
    mut cmd_rx: tokio::sync::mpsc::UnboundedReceiver<String>,
) {
    let client_ip = stream.peer_addr().map(|addr| addr.ip().to_string()).unwrap_or_else(|_| "Unknown IP".into());
    
    let mut reader = tokio::io::BufReader::new(stream);
    let mut init_line = String::new();
    let mut client_name = "Android Device".to_string();
    let mut client_desc = client_ip.clone();
    
    if let Ok(Ok(n)) = tokio::time::timeout(std::time::Duration::from_secs(3), reader.read_line(&mut init_line)).await {
        if n > 0 {
            if let Ok(info) = serde_json::from_str::<ClientInfo>(&init_line) {
                client_name = info.name;
                client_desc = format!("{} - v{}", info.os, info.app);
            }
        }
    }
    
    let server_version = env!("CARGO_PKG_VERSION");
    let server_os = std::env::consts::OS;
    let server_info = format!(r#"{{"version":"{}","os":"{}"}}"#, server_version, server_os);
    let _ = reader.get_mut().write_all(format!("{}\n", server_info).as_bytes()).await;
    let _ = reader.get_mut().flush().await;
    
    println!("Client {} ({}) connected, starting sensor loop", client_id, client_ip);

    let ui_h = ui_handle.clone();
    let cid = client_id.clone();
    let cdesc = client_desc.clone();
    let cname = client_name.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_h.upgrade() {
            let mut devices: Vec<crate::ConnectedDevice> = ui.get_connected_devices().iter().collect();
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
                        let mask = buf[0] & 0x3F;
                        
                        let combined_mask = {
                            let mut masks = client_masks.lock().await;
                            masks.insert(client_id.clone(), mask);
                            let combined = masks.values().fold(0, |acc, &m| acc | m);
                            apply_mask(combined);
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
        apply_mask(combined);
        combined
    };

    let cid = client_id.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_handle.upgrade() {
            use slint::Model;
            let devices: Vec<crate::ConnectedDevice> = ui.get_connected_devices()
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

