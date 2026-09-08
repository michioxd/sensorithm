use slint::{ModelRc, VecModel};
use std::rc::Rc;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use std::collections::HashMap;
use tokio::io::AsyncReadExt;
use uuid::Uuid;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;

#[cfg(windows)]
use windows::{
    core::PCSTR,
    Win32::Foundation::{CloseHandle, HANDLE},
    Win32::System::Memory::{
        MapViewOfFile, OpenFileMappingA, UnmapViewOfFile, FILE_MAP_READ, FILE_MAP_WRITE,
    },
};


#[cfg(windows)]
const BROKENITHM_MAPPING_NAME: &str = r"Local\BROKENITHM_SHARED_BUFFER";
#[cfg(windows)]
const BROKENITHM_BUFFER_SIZE: usize = 0x88;
#[cfg(windows)]
const IR_MAP: [usize; 6] = [5, 4, 3, 2, 1, 0];

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

        let name_cstr = std::ffi::CString::new(self.mapping_name.as_str()).unwrap();
        let handle = unsafe {
            OpenFileMappingA(
                FILE_MAP_READ.0 | FILE_MAP_WRITE.0,
                false,
                PCSTR(name_cstr.as_ptr() as *const u8),
            )
        };

        let handle = match handle {
            Ok(h) if !h.is_invalid() => h,
            _ => return false,
        };

        let view = unsafe {
            MapViewOfFile(handle, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, BROKENITHM_BUFFER_SIZE)
        };

        if view.Value.is_null() {
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
        for i in 0..6usize {
            unsafe { *view.add(i) = 0; }
        }
        for (zone_index, &beam_index) in IR_MAP.iter().enumerate() {
            if (mask & (1 << zone_index)) != 0 {
                unsafe { *view.add(beam_index ^ 1) = 1; }
            }
        }
        true
    }

    fn close(&mut self) {
        if let Some(view) = self.view.take() {
            for i in 0..6usize {
                unsafe { *view.add(i) = 0; }
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
) {
    let held_mask = Arc::new(AtomicU8::new(0));
    let client_masks: Arc<tokio::sync::Mutex<HashMap<String, u8>>> = Arc::new(tokio::sync::Mutex::new(HashMap::new()));

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

        loop {
            tokio::select! {
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, addr)) => {
                            println!("New connection: {}", addr);
                            let ui_clone = ui_handle.clone();
                            let mask_clone = held_mask.clone();
                            let client_masks_clone = client_masks.clone();
                            tokio::spawn(async move {
                                handle_client(stream, ui_clone, mask_clone, client_masks_clone).await;
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
            }
        }
        
        let ui = ui_handle.clone();
        let _ = slint::invoke_from_event_loop(move || {
            if let Some(ui) = ui.upgrade() {
                ui.set_server_ok(false);
            }
        });
    }
}

async fn handle_client(
    mut stream: TcpStream, 
    ui_handle: slint::Weak<crate::MainWindow>, 
    held_mask: Arc<AtomicU8>,
    client_masks: Arc<tokio::sync::Mutex<HashMap<String, u8>>>,
) {
    let _ = stream.set_nodelay(true);
    
    let client_ip = stream.peer_addr().map(|addr| addr.ip().to_string()).unwrap_or_else(|_| "Unknown IP".into());
    let client_id = Uuid::new_v4().to_string();
    
    println!("Client {} ({}) connected, starting sensor loop", client_id, client_ip);

    let ui_h = ui_handle.clone();
    let cid = client_id.clone();
    let cip = client_ip.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(ui) = ui_h.upgrade() {
            let mut devices: Vec<crate::ConnectedDevice> = ui.get_connected_devices().iter().collect();
            devices.push(crate::ConnectedDevice {
                id: cid.into(),
                name: "Android Device".into(),
                ip: cip.into(),
            });
            
            use slint::Model;
            let device_model = std::rc::Rc::new(slint::VecModel::from(devices));
            ui.set_connected_devices(slint::ModelRc::from(device_model));
        }
    });

    let mut buf = [0u8; 1];
    loop {
        match stream.read_exact(&mut buf).await {
            Ok(_) => {
                let mask = buf[0] & 0x3F;
                
                {
                    let mut masks = client_masks.lock().await;
                    masks.insert(client_id.clone(), mask);
                }
                
                let combined_mask = {
                    let masks = client_masks.lock().await;
                    masks.values().fold(0, |acc, &m| acc | m)
                };
                
                held_mask.store(combined_mask, Ordering::SeqCst);

                apply_mask(combined_mask);

                let ui_h = ui_handle.clone();
                let _ = slint::invoke_from_event_loop(move || {
                    if let Some(ui) = ui_h.upgrade() {
                        let states: Vec<bool> = (0..6).map(|i| (combined_mask & (1 << i)) != 0).collect();
                        let sensor_model = Rc::new(VecModel::from(states));
                        ui.set_sensor_states(ModelRc::from(sensor_model));
                    }
                });
            }
            Err(_) => {
                println!("Client {} disconnected", client_id);
                break;
            }
        }
    }

    {
        let mut masks = client_masks.lock().await;
        masks.remove(&client_id);
    }
    
    let combined_mask = {
        let masks = client_masks.lock().await;
        masks.values().fold(0, |acc, &m| acc | m)
    };
    
    held_mask.store(combined_mask, Ordering::SeqCst);
    apply_mask(combined_mask);

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

