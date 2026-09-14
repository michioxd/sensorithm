#[cfg(windows)]
use std::time::{Duration, Instant};

#[cfg(windows)]
use windows::{
    Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE},
    Win32::System::Memory::{
        CreateFileMappingA, FILE_MAP_ALL_ACCESS, MapViewOfFile, OpenFileMappingA, PAGE_READWRITE,
        UnmapViewOfFile,
    },
    core::PCSTR,
};

pub const DEFAULT_MAPPING_NAME: &str = r"Local\BROKENITHM_SHARED_BUFFER";

#[cfg(windows)]
const BUFFER_SIZE: usize = 1024;
#[cfg(windows)]
const AIR_INDEX: [usize; 6] = [4, 5, 2, 3, 0, 1];

pub struct SharedMaskOutput {
    #[cfg(windows)]
    mapping_name: String,
    #[cfg(windows)]
    handle: Option<HANDLE>,
    #[cfg(windows)]
    view: Option<*mut u8>,
    #[cfg(windows)]
    last_open_attempt: Option<Instant>,
}

#[cfg(windows)]
unsafe impl Send for SharedMaskOutput {}
#[cfg(windows)]
unsafe impl Sync for SharedMaskOutput {}

impl SharedMaskOutput {
    pub fn new(mapping_name: &str) -> Self {
        Self {
            #[cfg(windows)]
            mapping_name: mapping_name.to_owned(),
            #[cfg(windows)]
            handle: None,
            #[cfg(windows)]
            view: None,
            #[cfg(windows)]
            last_open_attempt: None,
        }
    }

    pub fn change_mapping_name(&mut self, new_name: &str) {
        #[cfg(windows)]
        if self.mapping_name != new_name {
            self.close();
            self.mapping_name = new_name.to_owned();
            self.last_open_attempt = None;
        }
    }

    pub fn write_mask(&mut self, mask: u8) -> bool {
        #[cfg(windows)]
        {
            if !self.ensure_open() {
                return false;
            }

            let view = self.view.expect("view is open after ensure_open");
            for (sensor_index, &shared_index) in AIR_INDEX.iter().enumerate() {
                let value = u8::from((mask & (1 << sensor_index)) != 0);
                unsafe { std::ptr::write_volatile(view.add(shared_index), value) };
            }
        }
        true
    }

    pub fn close(&mut self) {
        #[cfg(windows)]
        {
            if let Some(view) = self.view.take() {
                for index in 0..6usize {
                    unsafe { std::ptr::write_volatile(view.add(index), 0) };
                }
                unsafe {
                    let _ = UnmapViewOfFile(
                        windows::Win32::System::Memory::MEMORY_MAPPED_VIEW_ADDRESS {
                            Value: view as *mut _,
                        },
                    );
                }
            }
            if let Some(handle) = self.handle.take() {
                unsafe {
                    let _ = CloseHandle(handle);
                }
            }
            println!("Disconnected from Brokenithm shared buffer");
        }
    }

    #[cfg(windows)]
    fn ensure_open(&mut self) -> bool {
        if self.view.is_some() {
            return true;
        }

        let now = Instant::now();
        if self
            .last_open_attempt
            .is_some_and(|last| now.duration_since(last) < Duration::from_secs(1))
        {
            return false;
        }
        self.last_open_attempt = Some(now);

        let name = match std::ffi::CString::new(self.mapping_name.as_str()) {
            Ok(name) => name,
            Err(error) => {
                eprintln!("Invalid shared buffer name: {error}");
                return false;
            }
        };
        let handle = unsafe {
            OpenFileMappingA(
                FILE_MAP_ALL_ACCESS.0,
                false,
                PCSTR(name.as_ptr() as *const u8),
            )
        }
        .or_else(|_| unsafe {
            CreateFileMappingA(
                INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                0,
                BUFFER_SIZE as u32,
                PCSTR(name.as_ptr() as *const u8),
            )
        });

        let handle = match handle {
            Ok(handle) if !handle.is_invalid() => handle,
            Err(error) => {
                eprintln!(
                    "Cannot open/create shared buffer {}: {error}",
                    self.mapping_name
                );
                return false;
            }
            _ => return false,
        };
        let view = unsafe { MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0) };
        if view.Value.is_null() {
            eprintln!(
                "Cannot map shared buffer {}: {}",
                self.mapping_name,
                std::io::Error::last_os_error()
            );
            unsafe {
                let _ = CloseHandle(handle);
            }
            return false;
        }

        self.handle = Some(handle);
        self.view = Some(view.Value as *mut u8);
        println!(
            "Connected to Brokenithm shared buffer: {}",
            self.mapping_name
        );
        true
    }
}

impl Drop for SharedMaskOutput {
    fn drop(&mut self) {
        self.close();
    }
}
