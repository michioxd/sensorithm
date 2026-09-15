#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u32)]
pub enum BackdropEffect {
    Mica = 0,
    Acrylic = 1,
    #[default]
    AcrylicWindows10 = 2,
    Disabled = 3,
}

impl BackdropEffect {
    pub fn from_index(index: i32) -> Self {
        match index {
            1 => Self::Acrylic,
            2 => Self::AcrylicWindows10,
            3 => Self::Disabled,
            _ => Self::Mica,
        }
    }

    pub fn from_registry(value: u32) -> Self {
        Self::from_index(value as i32)
    }

    pub fn index(self) -> i32 {
        self as i32
    }
}

#[cfg(windows)]
pub fn select_backend() -> Result<(), slint::PlatformError> {
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .with_winit_window_attributes_hook(|attributes| attributes.with_transparent(true))
        .select()
}

#[cfg(windows)]
pub fn setup(ui: &crate::MainWindow, initial_effect: BackdropEffect) {
    use slint::ComponentHandle;
    use slint::winit_030::WinitWindowAccessor;
    use slint::winit_030::winit::window::Theme;

    ui.set_backdrop_mode(initial_effect.index());

    ui.on_backdrop_mode_edited({
        let ui_handle = ui.as_weak();
        move |index| {
            let effect = BackdropEffect::from_index(index);
            crate::config::save_backdrop_effect(effect);

            let ui_handle = ui_handle.clone();
            let _ = slint::spawn_local(async move {
                let Some(ui) = ui_handle.upgrade() else {
                    return;
                };
                let window = ui.window();
                let Ok(winit_window) = window.winit_window().await else {
                    return;
                };
                let dark_mode = matches!(winit_window.theme(), Some(Theme::Dark));
                ui.set_backdrop_enabled(apply(window, effect, dark_mode));
            });
        }
    });

    let ui_handle = ui.as_weak();
    let _ = slint::spawn_local(async move {
        use slint::winit_030::winit::event::WindowEvent;
        use slint::winit_030::{EventResult, WinitWindowAccessor};

        let Some(ui) = ui_handle.upgrade() else {
            return;
        };
        let window = ui.window();
        let Ok(winit_window) = window.winit_window().await else {
            return;
        };

        let dark_mode = matches!(winit_window.theme(), Some(Theme::Dark));
        ui.set_backdrop_enabled(apply(window, initial_effect, dark_mode));

        let ui_handle = ui.as_weak();
        window.on_winit_window_event(move |window, event| {
            if let WindowEvent::ThemeChanged(theme) = event
                && let Some(ui) = ui_handle.upgrade()
            {
                let effect = BackdropEffect::from_index(ui.get_backdrop_mode());
                ui.set_backdrop_enabled(apply(window, effect, *theme == Theme::Dark));
            }
            EventResult::Propagate
        });
    });
}

#[cfg(windows)]
fn apply(window: &slint::Window, effect: BackdropEffect, dark_mode: bool) -> bool {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{
        DWMSBT_MAINWINDOW, DWMSBT_NONE, DWMSBT_TRANSIENTWINDOW, DWMWA_USE_IMMERSIVE_DARK_MODE,
        DwmExtendFrameIntoClientArea, DwmSetWindowAttribute,
    };
    use windows::Win32::UI::Controls::MARGINS;

    let handle_provider = window.window_handle();
    let Ok(window_handle) = handle_provider.window_handle() else {
        return false;
    };
    let RawWindowHandle::Win32(win32_handle) = window_handle.as_raw() else {
        return false;
    };
    let hwnd = HWND(win32_handle.hwnd.get() as *mut core::ffi::c_void);

    unsafe {
        let use_dark_mode = i32::from(dark_mode);
        let _ = DwmSetWindowAttribute(
            hwnd,
            DWMWA_USE_IMMERSIVE_DARK_MODE,
            &use_dark_mode as *const _ as *const core::ffi::c_void,
            size_of_val(&use_dark_mode) as u32,
        );

        set_windows_10_acrylic(hwnd, false, dark_mode);
        let _ = set_dwm_backdrop(hwnd, DWMSBT_NONE);

        let full_window = MARGINS {
            cxLeftWidth: -1,
            cxRightWidth: -1,
            cyTopHeight: -1,
            cyBottomHeight: -1,
        };
        let client_frame = MARGINS::default();

        let enabled = match effect {
            BackdropEffect::Mica => set_dwm_backdrop(hwnd, DWMSBT_MAINWINDOW),
            BackdropEffect::Acrylic => set_dwm_backdrop(hwnd, DWMSBT_TRANSIENTWINDOW),
            BackdropEffect::AcrylicWindows10 => set_windows_10_acrylic(hwnd, true, dark_mode),
            BackdropEffect::Disabled => false,
        };

        let margins = if enabled { &full_window } else { &client_frame };
        let _ = DwmExtendFrameIntoClientArea(hwnd, margins);
        enabled
    }
}

#[cfg(windows)]
unsafe fn set_dwm_backdrop(
    hwnd: windows::Win32::Foundation::HWND,
    backdrop: windows::Win32::Graphics::Dwm::DWM_SYSTEMBACKDROP_TYPE,
) -> bool {
    use windows::Win32::Graphics::Dwm::{DWMWA_SYSTEMBACKDROP_TYPE, DwmSetWindowAttribute};

    unsafe {
        DwmSetWindowAttribute(
            hwnd,
            DWMWA_SYSTEMBACKDROP_TYPE,
            &backdrop as *const _ as *const core::ffi::c_void,
            size_of_val(&backdrop) as u32,
        )
        .is_ok()
    }
}

#[cfg(windows)]
unsafe fn set_windows_10_acrylic(
    hwnd: windows::Win32::Foundation::HWND,
    enabled: bool,
    dark_mode: bool,
) -> bool {
    const WCA_ACCENT_POLICY: u32 = 19;
    const ACCENT_DISABLED: u32 = 0;
    const ACCENT_ENABLE_ACRYLICBLURBEHIND: u32 = 4;

    #[repr(C)]
    struct AccentPolicy {
        state: u32,
        flags: u32,
        gradient_color: u32,
        animation_id: u32,
    }

    #[repr(C)]
    struct WindowCompositionAttributeData {
        attribute: u32,
        data: *mut core::ffi::c_void,
        size: usize,
    }

    type SetWindowCompositionAttributeFn = unsafe extern "system" fn(
        windows::Win32::Foundation::HWND,
        *mut WindowCompositionAttributeData,
    ) -> i32;

    let Ok(user32) = (unsafe {
        windows::Win32::System::LibraryLoader::GetModuleHandleW(windows::core::w!("user32.dll"))
    }) else {
        return false;
    };
    let Some(raw_function) = (unsafe {
        windows::Win32::System::LibraryLoader::GetProcAddress(
            user32,
            windows::core::s!("SetWindowCompositionAttribute"),
        )
    }) else {
        return false;
    };
    let set_window_composition_attribute: SetWindowCompositionAttributeFn =
        unsafe { core::mem::transmute(raw_function) };

    let tint = if dark_mode { 0xCC20_2020 } else { 0xCCF7_F7F7 };
    let mut policy = AccentPolicy {
        state: if enabled {
            ACCENT_ENABLE_ACRYLICBLURBEHIND
        } else {
            ACCENT_DISABLED
        },
        flags: 0,
        gradient_color: tint,
        animation_id: 0,
    };
    let mut data = WindowCompositionAttributeData {
        attribute: WCA_ACCENT_POLICY,
        data: &mut policy as *mut _ as *mut core::ffi::c_void,
        size: size_of::<AccentPolicy>(),
    };

    unsafe { set_window_composition_attribute(hwnd, &mut data) != 0 }
}
